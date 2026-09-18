package com.linksi.app.enhanced.download

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaBackend
import com.linksi.app.enhanced.media.MediaFormat
import com.linksi.app.enhanced.media.MediaSource
import com.linksi.app.enhanced.media.direct.DirectFileExtractor
import com.linksi.app.enhanced.media.ytdlp.YtDlpDownloadRequest
import com.linksi.app.enhanced.media.ytdlp.YtDlpDownloader
import com.linksi.app.enhanced.resolver.MediaResolver
import com.linksi.app.enhanced.resolver.rejectUnsafePrivateServerFormats
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Downloads exactly one [DownloadRequest] (specification sections 22 and 23).
 *
 * There are two paths, and the direct one is still the common one:
 *  - a URL that *is* a file, and a format that is already complete, go through
 *    [DirectFileDownloader] over OkHttp exactly as before;
 *  - a format whose video and audio arrive separately, and a URL that is a page rather than a file,
 *    go through [YtDlpDownloader]. Both paths publish through the same [DownloadSink], so the
 *    finished file reaches the user's Downloads collection identically either way.
 *
 * Design notes, all of them consequences of the specification:
 *  - One work item per download, so a failure is isolated to that download and nothing can throw
 *    into the UI (section 26). `doWork` has no path that returns an exception.
 *  - The runtime `POST_NOTIFICATIONS` permission is *not* requested here, and must not be: a worker
 *    has no UI to ask from. It is requested where the download is enqueued from the UI
 *    ([DownloadNotificationPermission]), and this worker simply tolerates the permission being
 *    absent - it posts best effort and never fails a download for a notification (section 33).
 *  - It runs as a **long-running** worker: `setForeground` with
 *    `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, never `shortService` and never expedited work, because a
 *    media download routinely exceeds the three-minute `shortService` deadline and the documented
 *    failure mode there is an ANR (research brief sections 1.1 and 1.6).
 *  - Transient network failures come back as `Result.retry()` and rely on WorkManager's
 *    exponential backoff, with a hard attempt cap so a permanently broken URL still settles.
 *  - The request is echoed into the failure *output* data. WorkManager does not expose a worker's
 *    input data through `WorkInfo`, so this is what lets the engine rebuild a request and offer
 *    "retry" after the process has been killed.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val extractor: DirectFileExtractor,
    private val downloader: DirectFileDownloader,
    private val ytDlp: YtDlpDownloader,
    private val resolver: MediaResolver,
    private val sinks: DownloadSinkFactory,
    private val notifications: DownloadNotifications
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Reading garbage must not throw: a corrupt work record is a failed download, not a crash.
        val request = runCatching { DownloadWorkKeys.readRequest(inputData) }.getOrNull()
        if (request == null) {
            // Nothing to download and no way to tell anyone which download it was.
            return Result.failure(
                DownloadWorkKeys.failure(MediaError.ENGINE_UNAVAILABLE, "no download request in the work input")
            )
        }

        val title = request.suggestedFileName?.takeIf { it.isNotBlank() } ?: request.url

        return try {
            // Everything, including the foreground notice, is inside the same guard: this method
            // has no path that throws.
            notifications.ensureChannels()
            startForegroundQuietly(request.id, title)
            run(request, title)
        } catch (cancelled: CancellationException) {
            // The user cancelled, or the system stopped the work. Whatever the downloader had
            // fetched stays in its scratch directory rather than being deleted: a cancellation is
            // often followed by a retry, and yt-dlp resumes from those bytes. The downloader's stale
            // sweep reclaims a scratch directory nobody comes back to.
            withContext(NonCancellable) { notifications.cancel(request.id) }
            throw cancelled
        } catch (error: Exception) {
            // Failure isolation: an unexpected throwable becomes a normal failed download.
            Log.w(TAG, "download ${request.id} failed unexpectedly", error)
            notifications.notifyFailed(request.id, title, MediaError.EXTRACTOR_FAILED)
            Result.failure(
                DownloadWorkKeys.failure(MediaError.EXTRACTOR_FAILED, error.message, request)
            )
        }
    }

    private suspend fun run(request: DownloadRequest, title: String): Result {
        // The panel marks a request when its local-first analysis had to use the private server.
        // Resolve again here so WorkManager retries read current settings and get a fresh signed
        // media URL; importantly, a server format id is never handed to yt-dlp.
        if (request.backend == MediaBackend.PRIVATE_SERVER) {
            return downloadWithPrivateServer(request, title)
        }

        // A request that names a site-specific format, or that needs two streams merged, cannot be
        // served by an HTTP GET at all, so it never pays for the direct probe first.
        if (request.needsSiteEngine) {
            return downloadWithYtDlp(request, title)
        }

        val info = when (val probe = extractor.analyze(request.url, request.source)) {
            is MediaExtractionResult.Success -> probe.info

            is MediaExtractionResult.Unsupported ->
                // The URL is a page, not a file - which is exactly what the site engine is for.
                // Handing it over turns a certain failure into a possible download.
                return downloadWithYtDlp(request, title)

            is MediaExtractionResult.Skipped ->
                return fail(request, title, MediaError.ENGINE_UNAVAILABLE, probe.reason)

            is MediaExtractionResult.Failure ->
                return retryOrFail(request, title, probe.error, probe.cause?.message)
        }

        return downloadResolvedDirect(
            request = request,
            title = title,
            info = info,
            requireDirectUrl = false,
            allowSiteEngineFallback = true
        )
    }

    /** Resolves the same link through the opted-in server and downloads its selected direct URL. */
    private suspend fun downloadWithPrivateServer(request: DownloadRequest, title: String): Result {
        val resolved = try {
            rejectUnsafePrivateServerFormats(resolver.resolve(request.url, request.source))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            MediaExtractionResult.Failure(MediaError.SERVER_UNAVAILABLE, request.url, error)
        }

        return when (resolved) {
            is MediaExtractionResult.Success -> downloadResolvedDirect(
                request = request,
                title = title,
                info = resolved.info,
                requireDirectUrl = true,
                // A server response is already the fallback; a document response must not send the
                // original page to yt-dlp and accidentally bypass the user's server choice.
                allowSiteEngineFallback = false
            )

            is MediaExtractionResult.Unsupported ->
                fail(request, title, resolved.error, null)

            is MediaExtractionResult.Skipped ->
                fail(request, title, resolved.error, resolved.reason)

            is MediaExtractionResult.Failure ->
                retryOrFail(request, title, resolved.error, resolved.cause?.message)
        }
    }

    /** Downloads a resolved direct format, shared by the native direct and server paths. */
    private suspend fun downloadResolvedDirect(
        request: DownloadRequest,
        title: String,
        info: com.linksi.app.enhanced.media.MediaInfo,
        requireDirectUrl: Boolean,
        allowSiteEngineFallback: Boolean
    ): Result {
        val format = selectDownloadFormat(info, request.formatId)
            ?: return fail(request, title, MediaError.NO_FORMATS, "requested format is unavailable")

        if (!canDownloadPrivateServerFormatDirectly(request, format)) {
            return fail(
                request,
                title,
                MediaError.UNSUPPORTED_SITE,
                "private resolver must return a complete artifact"
            )
        }

        // The user's name wins over the server's; the extension comes from the chosen format.
        val base = request.suggestedFileName?.takeIf { it.isNotBlank() } ?: info.title
        val displayName = FilenameSanitizer.sanitize(base, format.extension)

        val sink = sinks.forDestination(request.destination)
            ?: return fail(
                request,
                title,
                MediaError.NO_STORAGE,
                sinks.unavailableDetail(request.destination)
            )

        publish(request.id, displayName, DownloadState.Downloading(0, format.fileSizeBytes, null))

        val directUrl = format.directUrl?.takeIf { it.isNotBlank() }
            ?: if (requireDirectUrl) {
                return fail(request, title, MediaError.EXTRACTOR_FAILED, "resolver returned no media URL")
            } else {
                request.url
            }

        val result = downloader.download(
            request = DirectDownloadRequest(
                url = directUrl,
                displayName = displayName,
                expectedBytes = format.fileSizeBytes
            ),
            sink = sink
        ) { state ->
            publish(request.id, displayName, state)
        }

        return when (result) {
            is DirectDownloadResult.Completed -> completed(request, result)
            is DirectDownloadResult.Failed -> {
                // The bytes behind this URL are a document, not media: the probe was fooled by a
                // content type and the transfer found the truth. This is exactly the case the site
                // engine exists for, and the user's real download is still reachable - pressing
                // Download on a Facebook Reel took this path, failed, and reported a failure while
                // yt-dlp could read the very same URL perfectly.
                //
                // Only this one detail triggers the hand-over. A stalled or refused transfer goes
                // through the ordinary retry policy instead, because re-running it through a whole
                // Python interpreter would be slower and no more likely to succeed.
                if (allowSiteEngineFallback && result.detail == DirectFileDownloader.NOT_A_FILE_DETAIL) {
                    Log.i(TAG, "download ${request.id} was not a file after all; trying the site engine")
                    return downloadWithYtDlp(request, title)
                }
                retryOrFail(
                    request = request,
                    title = title,
                    error = result.error,
                    detail = result.detail,
                    transient = result.transient
                )
            }
        }
    }

    /**
     * Downloads through the bundled site engine (specification sections 19 and 23).
     *
     * Only two things differ from the direct path: the bytes come from yt-dlp, which can fetch a
     * separate audio stream and merge it with FFmpeg, and the file's container is not known until
     * yt-dlp has chosen it, so the name and MIME type are settled during the copy into the sink
     * rather than before the download starts. Publishing, progress, the notification and the work
     * output are identical, because [YtDlpDownloader] commits through the very same [DownloadSink].
     */
    private suspend fun downloadWithYtDlp(request: DownloadRequest, title: String): Result {
        val sink = sinks.forDestination(request.destination)
            ?: return fail(
                request,
                title,
                MediaError.NO_STORAGE,
                sinks.unavailableDetail(request.destination)
            )

        // The size is unknown until the engine reports one, so the notification starts as an
        // indeterminate spinner rather than as a fake percentage.
        publish(request.id, title, DownloadState.Downloading(0L, null, null))

        val result = ytDlp.download(
            request = YtDlpDownloadRequest(
                url = request.url,
                formatId = request.formatId,
                requiresMuxing = request.requiresMuxing,
                preferredName = request.suggestedFileName?.takeIf { it.isNotBlank() }
            ),
            sink = sink,
            // Named, not trailing: `download` also takes the watchdog policy, so the trailing-lambda
            // position is no longer unique.
            onProgress = { state ->
                publish(request.id, title, state)
            }
        )

        return when (result) {
            is DirectDownloadResult.Completed -> completed(request, result)
            is DirectDownloadResult.Failed -> retryOrFail(
                request = request,
                title = title,
                error = result.error,
                detail = result.detail,
                transient = result.transient
            )
        }
    }

    /** The shared tail of both paths: a finished file becomes a succeeded work item. */
    private suspend fun completed(
        request: DownloadRequest,
        result: DirectDownloadResult.Completed
    ): Result {
        notifications.notifyComplete(request.id, result.displayName, result.location)
        return Result.success(
            DownloadWorkKeys.success(
                location = result.location,
                bytes = result.bytes,
                mimeType = result.mimeType,
                displayName = result.displayName,
                request = request
            )
        )
    }

    /** Publishes progress to WorkManager *and* to the notification, in that order. */
    private suspend fun publish(downloadId: String, title: String, state: DownloadState.Downloading) {
        setProgress(DownloadWorkKeys.progress(state))
        notifications.notifyProgress(downloadId, title, state)
    }

    private suspend fun fail(
        request: DownloadRequest,
        title: String,
        error: MediaError,
        detail: String?
    ): Result {
        Log.w(TAG, "download ${request.id} cannot start: $error ($detail)")
        notifications.notifyFailed(request.id, title, error)
        return Result.failure(DownloadWorkKeys.failure(error, detail, request))
    }

    private suspend fun retryOrFail(
        request: DownloadRequest,
        title: String,
        error: MediaError,
        detail: String?,
        transient: Boolean = DownloadErrorClassifier.isTransient(error)
    ): Result {
        if (transient && runAttemptCount < MAX_RETRIES) {
            // WorkManager re-runs this worker with exponential backoff; the same notification is
            // reused, so the download does not appear to vanish and come back.
            Log.i(TAG, "download ${request.id} will retry (attempt ${runAttemptCount + 1}): $detail")
            return Result.retry()
        }

        Log.w(TAG, "download ${request.id} failed: $error ($detail)")
        notifications.notifyFailed(request.id, title, error)
        return Result.failure(DownloadWorkKeys.failure(error, detail, request))
    }

    /**
     * Enters the foreground, tolerating refusal.
     *
     * `ForegroundServiceStartNotAllowedException` is an `IllegalStateException`, and the `dataSync`
     * daily budget can also refuse the service. Both mean "no foreground guarantee", not "cannot
     * download": the attempt continues, and if the system stops it WorkManager reschedules it.
     */
    private suspend fun startForegroundQuietly(downloadId: String, title: String) {
        val notice = notifications.foregroundInfo(
            downloadId = downloadId,
            title = title,
            state = DownloadState.Downloading(0, null, null)
        )
        try {
            setForeground(notice)
        } catch (error: Exception) {
            Log.w(TAG, "could not enter the foreground; continuing in the background", error)
        }
    }

    companion object {
        private const val TAG = "DownloadWorker"

        /**
         * Retries after a transient failure: enough for a flaky connection, not enough to loop
         * forever on a URL that is simply broken. `runAttemptCount` is 0 on the first run.
         */
        const val MAX_RETRIES = 3
    }
}

/**
 * True when only the site-specific engine can serve this request.
 *
 * Two cases, and both are ones a plain HTTP GET is guaranteed to get wrong:
 *  - [DownloadRequest.requiresMuxing]: the format's video and audio are separate streams, and only
 *    FFmpeg can join them;
 *  - [DownloadRequest.formatId] is set to something other than
 *    [DirectFileExtractor.FORMAT_ID]: the direct-file probe's only format id is its own marker, so
 *    any other id was produced by a site-specific extraction and means nothing to OkHttp.
 */
internal fun canDownloadPrivateServerFormatDirectly(
    request: DownloadRequest,
    format: MediaFormat
): Boolean = request.backend != MediaBackend.PRIVATE_SERVER || !format.requiresMuxing

private val DownloadRequest.needsSiteEngine: Boolean
    get() = requiresMuxing ||
        (formatId != null && formatId != DirectFileExtractor.FORMAT_ID)

/**
 * Naming and tagging rules for download work, deliberately free of WorkManager types so they are
 * unit tested on the plain JVM.
 */
object DownloadWorkNaming {

    /** Every download work item carries this tag, so persisted work can be found after a restart. */
    const val TAG_DOWNLOAD = "linksi.download"

    private const val REQUEST_TAG_PREFIX = "linksi.download.request."

    private const val RETRY_MARKER = "#retry"

    private val RETRY_SUFFIX = Regex(Regex.escape(RETRY_MARKER) + "(\\d+)$")

    private const val MAX_RETRY_SUFFIX = 1_000_000

    /** The tag carrying [id]; the request payload itself lives in the worker's input data. */
    fun requestTag(id: String): String = REQUEST_TAG_PREFIX + id

    /** Reads the request id back out of a work item's tags, or null for unrelated work. */
    fun requestIdFromTags(tags: Collection<String>): String? = tags
        .firstOrNull { it.startsWith(REQUEST_TAG_PREFIX) }
        ?.removePrefix(REQUEST_TAG_PREFIX)
        ?.takeIf { it.isNotEmpty() }

    /**
     * A fresh id for a retry, derived from the original: `clip` -> `clip#retry1` ->
     * `clip#retry2`. Deriving rather than randomising keeps the retry recognisable in logs and in
     * the WorkManager database, while still being a *new* unique work name so
     * `ExistingWorkPolicy.KEEP` does not swallow it.
     */
    fun retryId(id: String): String {
        val match = RETRY_SUFFIX.find(id)
        // Long arithmetic: an absurd suffix must clamp, not wrap around into a collision.
        val next = match
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.plus(1L)
            ?.coerceIn(1L, MAX_RETRY_SUFFIX.toLong())
            ?: 1L
        val base = if (match == null) id else id.substring(0, match.range.first)
        return "$base$RETRY_MARKER$next"
    }
}

/**
 * The vocabulary of a download work item's input, progress and output data.
 *
 * The request is written to the input data *and* echoed into the output data of a finished work
 * item, because `WorkInfo` exposes only output data - which is what makes retry-after-restart work.
 */
object DownloadWorkKeys {

    private const val KEY_ID = "download.id"
    private const val KEY_URL = "download.url"
    private const val KEY_FILE_NAME = "download.file_name"
    private const val KEY_DESTINATION = "download.destination"
    private const val KEY_SOURCE = "download.source"
    private const val KEY_FORMAT_ID = "download.format_id"
    private const val KEY_REQUIRES_MUXING = "download.requires_muxing"
    private const val KEY_BACKEND = "download.backend"

    const val KEY_BYTES = "download.bytes"
    const val KEY_TOTAL = "download.total"
    const val KEY_SPEED = "download.speed"
    const val KEY_FILE_PATH = "download.file_path"
    const val KEY_MIME = "download.mime"
    const val KEY_DISPLAY_NAME = "download.display_name"
    const val KEY_ERROR = "download.error"
    const val KEY_ERROR_DETAIL = "download.error_detail"

    /** Marker for "unknown" in a progress bundle, which cannot hold nulls and needs a sentinel. */
    const val UNKNOWN = -1L

    fun input(request: DownloadRequest): Data = Data.Builder()
        .putString(KEY_ID, request.id)
        .putString(KEY_URL, request.url)
        .putString(KEY_FILE_NAME, request.suggestedFileName)
        .putString(KEY_DESTINATION, request.destination.name)
        .putString(KEY_SOURCE, request.source.name)
        .putString(KEY_FORMAT_ID, request.formatId)
        .putBoolean(KEY_REQUIRES_MUXING, request.requiresMuxing)
        .putString(KEY_BACKEND, request.backend.name)
        .build()

    fun progress(state: DownloadState.Downloading): Data = workDataOf(
        KEY_BYTES to state.bytesDownloaded,
        KEY_TOTAL to (state.totalBytes ?: UNKNOWN),
        KEY_SPEED to (state.bytesPerSecond ?: UNKNOWN)
    )

    fun success(
        location: String,
        bytes: Long,
        mimeType: String?,
        displayName: String,
        request: DownloadRequest? = null
    ): Data = echo(
        Data.Builder()
            .putString(KEY_FILE_PATH, location)
            .putLong(KEY_BYTES, bytes)
            .putString(KEY_MIME, mimeType)
            .putString(KEY_DISPLAY_NAME, displayName),
        request
    ).build()

    fun failure(error: MediaError, detail: String?, request: DownloadRequest? = null): Data = echo(
        Data.Builder()
            .putString(KEY_ERROR, error.name)
            .putString(KEY_ERROR_DETAIL, detail),
        request
    ).build()

    /** Null when the data does not describe a usable request. Never throws. */
    fun readRequest(data: Data): DownloadRequest? {
        val id = data.getString(KEY_ID)?.takeIf { it.isNotBlank() } ?: return null
        val url = data.getString(KEY_URL)?.takeIf { it.isNotBlank() } ?: return null

        return DownloadRequest(
            id = id,
            url = url,
            source = data.getString(KEY_SOURCE)
                ?.let { name -> MediaSource.values().firstOrNull { it.name == name } }
                ?: MediaSource.UNKNOWN,
            formatId = data.getString(KEY_FORMAT_ID),
            suggestedFileName = data.getString(KEY_FILE_NAME),
            destination = data.getString(KEY_DESTINATION)
                ?.let { name -> DownloadDestination.values().firstOrNull { it.name == name } }
                ?: DownloadDestination.PUBLIC_DOWNLOADS,
            requiresMuxing = data.getBoolean(KEY_REQUIRES_MUXING, false),
            backend = data.getString(KEY_BACKEND)
                ?.let { name -> MediaBackend.values().firstOrNull { it.name == name } }
                ?: MediaBackend.LOCAL
        )
    }

    /** The failure reason in [data], defaulting to a generic failure. */
    fun readError(data: Data): MediaError = data.getString(KEY_ERROR)
        ?.let { name -> MediaError.values().firstOrNull { it.name == name } }
        ?: MediaError.EXTRACTOR_FAILED

    fun readErrorDetail(data: Data): String? = data.getString(KEY_ERROR_DETAIL)

    fun readLocation(data: Data): String? = data.getString(KEY_FILE_PATH)

    fun readDisplayName(data: Data): String? = data.getString(KEY_DISPLAY_NAME)

    fun readMimeType(data: Data): String? = data.getString(KEY_MIME)

    fun readBytes(data: Data): Long = data.getLong(KEY_BYTES, 0L)

    fun readTotalBytes(data: Data): Long? = data.getLong(KEY_TOTAL, UNKNOWN).takeIf { it > 0 }

    fun readSpeed(data: Data): Long? = data.getLong(KEY_SPEED, UNKNOWN).takeIf { it > 0 }

    private fun echo(builder: Data.Builder, request: DownloadRequest?): Data.Builder {
        if (request == null) return builder
        return builder
            .putString(KEY_ID, request.id)
            .putString(KEY_URL, request.url)
            .putString(KEY_FILE_NAME, request.suggestedFileName)
            .putString(KEY_DESTINATION, request.destination.name)
            .putString(KEY_SOURCE, request.source.name)
            .putString(KEY_FORMAT_ID, request.formatId)
            .putBoolean(KEY_REQUIRES_MUXING, request.requiresMuxing)
            .putString(KEY_BACKEND, request.backend.name)
    }
}
