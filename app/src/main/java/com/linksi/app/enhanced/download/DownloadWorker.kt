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
import com.linksi.app.enhanced.media.MediaSource
import com.linksi.app.enhanced.media.direct.DirectFileExtractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Downloads exactly one [DownloadRequest] (specification sections 22 and 23).
 *
 * Design notes, all of them consequences of the specification:
 *  - One work item per download, so a failure is isolated to that download and nothing can throw
 *    into the UI (section 26). `doWork` has no path that returns an exception.
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
            // The user cancelled, or the system stopped the work. The partial file has already been
            // deleted by the downloader; all that is left is to drop the notification.
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
        if (request.requiresMuxing) {
            // Merging separate video and audio streams needs the optional media engine, which is
            // not bundled. Saying so is better than downloading half a file.
            return fail(
                request,
                title,
                MediaError.ENGINE_UNAVAILABLE,
                "separate video and audio streams need the optional muxing engine"
            )
        }

        val info = when (val probe = extractor.analyze(request.url, request.source)) {
            is MediaExtractionResult.Success -> probe.info

            is MediaExtractionResult.Unsupported ->
                return fail(request, title, MediaError.UNSUPPORTED_SITE, probe.url)

            is MediaExtractionResult.Skipped ->
                return fail(request, title, MediaError.ENGINE_UNAVAILABLE, probe.reason)

            is MediaExtractionResult.Failure ->
                return retryOrFail(request, title, probe.error, probe.cause?.message)
        }

        val format = info.formats.firstOrNull()
            ?: return fail(request, title, MediaError.NO_FORMATS, null)

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

        val result = downloader.download(
            request = DirectDownloadRequest(
                url = format.directUrl ?: request.url,
                displayName = displayName,
                expectedBytes = format.fileSizeBytes
            ),
            sink = sink
        ) { state ->
            publish(request.id, displayName, state)
        }

        return when (result) {
            is DirectDownloadResult.Completed -> {
                notifications.notifyComplete(request.id, result.displayName, result.location)
                Result.success(
                    DownloadWorkKeys.success(
                        location = result.location,
                        bytes = result.bytes,
                        mimeType = result.mimeType,
                        displayName = result.displayName,
                        request = request
                    )
                )
            }

            is DirectDownloadResult.Failed -> retryOrFail(
                request = request,
                title = title,
                error = result.error,
                detail = result.detail,
                transient = result.transient
            )
        }
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
            requiresMuxing = data.getBoolean(KEY_REQUIRES_MUXING, false)
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
    }
}
