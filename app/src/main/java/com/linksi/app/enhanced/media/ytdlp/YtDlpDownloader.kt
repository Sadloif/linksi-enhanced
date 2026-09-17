package com.linksi.app.enhanced.media.ytdlp

import android.content.Context
import android.util.Log
import com.linksi.app.enhanced.download.DirectDownloadResult
import com.linksi.app.enhanced.download.DownloadErrorClassifier
import com.linksi.app.enhanced.download.DownloadProgressMeter
import com.linksi.app.enhanced.download.DownloadSink
import com.linksi.app.enhanced.download.DownloadSinkException
import com.linksi.app.enhanced.download.DownloadState
import com.linksi.app.enhanced.download.FilenameSanitizer
import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.direct.DirectFileExtractor
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One download the site engine has to perform, rather than a plain HTTP GET. */
data class YtDlpDownloadRequest(
    val url: String,
    /** The yt-dlp format id the user chose, or null for "whatever is best". */
    val formatId: String? = null,
    /** True when this format is video without sound and has to be merged with an audio stream. */
    val requiresMuxing: Boolean = false,
    /** The name the user asked for, or null to use the title yt-dlp reports. */
    val preferredName: String? = null,
    /** Size the extractor already learned, used only until yt-dlp reports its own. */
    val expectedBytes: Long? = null
)

/**
 * Downloads through the bundled yt-dlp, including the formats OkHttp cannot serve.
 *
 * ### Why this exists
 *
 * A YouTube-style link offers high resolutions only as **separate** video and audio streams, and a
 * single HTTP GET cannot produce one playable file from two. Only yt-dlp, driving the bundled
 * FFmpeg, can fetch both and merge them. Before this class the download worker answered
 * `ENGINE_UNAVAILABLE` for exactly those formats.
 *
 * ### How a file reaches the user
 *
 * yt-dlp writes to a *path*; [DownloadSink] writes to a *stream*, and on API 29+ that stream is a
 * `MediaStore` row with no path at all - which is the only way a download becomes visible in the
 * user's Downloads collection without a storage permission. So the engine downloads into a private
 * scratch directory under `cacheDir`, and the finished file is then streamed through the same sink
 * machinery the direct downloader uses and committed the same way. The result is published, not
 * merely written: `IS_PENDING` is cleared, or the app-private file is named, exactly as before.
 *
 * The scratch directory is deleted on every exit path, so a cancelled or failed download leaves
 * nothing behind. The trade is one extra copy of the file's bytes through `cacheDir`, which is
 * documented here rather than hidden.
 *
 * ### Progress and cancellation
 *
 * yt-dlp's progress callback arrives on its own reader thread and cannot call a suspend function, so
 * updates are handed to a conflated channel that a coroutine inside this scope consumes and forwards
 * to the caller's `onProgress`. [DownloadProgressMeter] - the same pure throttle the direct
 * downloader uses - keeps that to a few updates a second and computes the speed over the window.
 *
 * Cancellation destroys the child process by id. The upstream implementation does that with
 * `/system/bin/sh` and needs both `pstree` and GNU-style `grep -oP`; on a ROM missing either, the
 * process is orphaned and the download simply fails when yt-dlp's exit code arrives. Nothing here
 * depends on the kill succeeding.
 */
@Singleton
class YtDlpDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: YtDlpRuntime
) {

    suspend fun download(
        request: YtDlpDownloadRequest,
        sink: DownloadSink,
        /**
         * The watchdog policy, overridable **for tests only**.
         *
         * A device test cannot wait out the production stall limit (a minute) plus a genuine
         * network failure in one instrumented run, and a test that asserted the watchdog without
         * ever triggering it would prove nothing. Nothing in the app passes this argument.
         *
         * Declared before [onProgress] on purpose: a suspend function with a callback keeps its
         * callback in the trailing-lambda position, and every existing caller passes it that way.
         */
        watchdogPolicy: DownloadWatchdogPolicy = DownloadWatchdogPolicy(),
        onProgress: suspend (DownloadState.Downloading) -> Unit = {}
    ): DirectDownloadResult {
        when (val init = runtime.ensureReady()) {
            is YtDlpInitStatus.Failed -> return DirectDownloadResult.Failed(
                MediaError.ENGINE_UNAVAILABLE,
                "the bundled yt-dlp engine could not be started: ${init.reason}"
            )

            YtDlpInitStatus.NotStarted -> return DirectDownloadResult.Failed(
                MediaError.ENGINE_UNAVAILABLE,
                "the bundled yt-dlp engine has not been started"
            )

            YtDlpInitStatus.Ready -> Unit
        }

        val workDirectory = workDirectoryFor(request)
            ?: return DirectDownloadResult.Failed(
                MediaError.NO_STORAGE,
                "the app cache directory is not writable"
            )

        // Opportunistic, and deliberately not a background job: a scratch directory is only worth
        // reclaiming when something is asking for scratch space.
        runCatching { discardStaleWorkDirectories(workDirectory) }

        val result = runDownload(request, sink, workDirectory, watchdogPolicy, onProgress)

        // What happens to the scratch directory is what decides whether a retry resumes or starts
        // over. yt-dlp keeps its own progress in a `.part` file and a `.ytdl` marker beside it and
        // skips what it already fetched, but only while those files exist.
        if (result is DirectDownloadResult.Failed && result.transient) {
            // A network failure: keep the partial download so the retry continues instead of
            // re-fetching everything. It is scratch space, and the stale sweep removes it if the
            // user never comes back.
            Log.i(TAG, "keeping ${workDirectory.name} so a retry can resume: ${result.detail}")
        } else {
            runCatching { workDirectory.deleteRecursively() }
        }

        return result
    }

    private suspend fun runDownload(
        request: YtDlpDownloadRequest,
        sink: DownloadSink,
        workDirectory: File,
        watchdogPolicy: DownloadWatchdogPolicy,
        onProgress: suspend (DownloadState.Downloading) -> Unit
    ): DirectDownloadResult = supervisorScope {
        val processId = "$PROCESS_ID_PREFIX${UUID.randomUUID()}"

        val updates = Channel<DownloadState.Downloading>(Channel.CONFLATED)
        // The caller's own progress callback must never be able to fail a download. It is UI work -
        // a notification update, a Compose state write - and if it throws, the exception would come
        // back through the publisher and abort a transfer that was working perfectly.
        val publisher = launch {
            for (update in updates) {
                runCatching { onProgress(update) }
            }
        }
        val meter = DownloadProgressMeter()
        meter.reset()

        val command = downloadRequest(request, workDirectory)
        val policy = watchdogPolicy.scaleFor(request.expectedBytes)
        Log.i(
            TAG,
            "yt-dlp request: format=${formatSelector(request.formatId, request.requiresMuxing)} " +
                "work=${workDirectory.name} expectedBytes=${request.expectedBytes} " +
                "stallLimit=${policy.stallLimitMillis}ms hardLimit=${policy.hardLimitMillis}ms"
        )

        val stallDetector = DownloadStallDetector(policy.stallLimitMillis)
        // Read by the watchdog on every tick; written once by whichever of the two finishes first.
        val settled = AtomicBoolean(false)

        /**
         * Why the download stopped, when the *app* stopped it, or null when it has not been stopped.
         *
         * A cancellation reaching `download.join()` is the mirror image of the one raised a moment
         * earlier: the job was cancelled by this scope's watchdog rather than by the caller.
         * Re-throwing it would propagate a cancellation out of `download()` - which is what a user
         * tapping Cancel looks like - and on Android an uncaught coroutine cancellation in the
         * worker's scope can take the process down. So a stop this class caused becomes an ordinary
         * failure value, and only the caller's own cancellation is re-thrown.
         *
         * Declared before the watchdog because the watchdog records the reason before cancelling the
         * child; awaiting that child then observes the recorded reason.
         */
        var stoppingFor: String? = null

        /**
         * The child that runs yt-dlp, as an [async] rather than a [launch].
         *
         * This matters for the app's survival, not for style. A `launch` reports its failure as an
         * **uncaught** exception, and an uncaught exception in a coroutine on Android goes to the
         * default handler, which kills the process — verified on the emulator, where a plain 404 from
         * the source crashed the instrumented run with `YoutubeDLException` even though the failure
         * was being collected. `async` hands the same exception to whoever awaits it instead, so an
         * ordinary download error stays an ordinary download error.
         */
        val download = async {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(
                    command,
                    processId
                ) { percent, _, line ->
                    // This runs on the library's stdout-reader thread. Anything that escapes it stops
                    // that thread and yt-dlp then blocks on a full pipe, so every statement here is
                    // guarded: logging, parsing, the stall bookkeeping and the channel send.
                    runCatching {
                        Log.i(TAG, "yt-dlp: ${redactUrls(line)}")
                        progressOf(line, percent, request.expectedBytes)?.let { (downloaded, total) ->
                            // The watchdog is what acts on a stall; this only records the bytes and
                            // the reported total, so that "no progress" is measured from the last byte
                            // and silence after a stream reaches 100% is not mistaken for a dead
                            // socket.
                            stallDetector.onProgress(downloaded, total)
                            meter.sample(downloaded, total)?.let { updates.trySend(it) }
                        }
                    }
                }
            }
        }

        /**
         * The watchdog. It cannot make `execute` return - only the child process exiting does that
         * - so it cancels the download coroutine instead, which is what runs the library's kill and,
         * failing that, interrupts the thread blocked in `waitFor`.
         */
        val watchdog = launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                delay(WATCHDOG_TICK_MILLIS)
                if (settled.get()) break

                val stalled = stallDetector.isStalled()
                val overran = System.currentTimeMillis() - startedAt >= policy.hardLimitMillis
                if (!stalled && !overran) continue

                val reason = if (stalled) STALLED_DETAIL else TIMED_OUT_DETAIL
                stoppingFor = reason
                Log.w(
                    TAG,
                    "stopping the yt-dlp process: $reason " +
                        "(bytes=${stallDetector.bytesSeen()}, elapsed=${System.currentTimeMillis() - startedAt}ms)"
                )
                runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
                download.cancel(CancellationException(reason))
                break
            }
        }

        var failure: DirectDownloadResult.Failed? = null

        try {
            download.await()
        } catch (canceled: YoutubeDL.CanceledException) {
            // The library's own cancellation, raised because the process was destroyed.
            val reason = stoppingFor
                ?: throw CancellationException("the yt-dlp process was stopped")
            failure = DirectDownloadResult.Failed(MediaError.NETWORK, reason, transient = true)
        } catch (interrupted: InterruptedException) {
            // Only reached when the process was destroyed, so this is the same situation.
            val reason = stoppingFor
                ?: throw CancellationException("the yt-dlp process was interrupted")
            failure = DirectDownloadResult.Failed(MediaError.NETWORK, reason, transient = true)
        } catch (canceled: CancellationException) {
            val reason = stoppingFor
            if (reason != null) {
                // The watchdog cancelled only the child after recording its reason. Return a normal,
                // retryable failure so WorkManager can schedule the retry.
                failure = DirectDownloadResult.Failed(MediaError.NETWORK, reason, transient = true)
            } else {
                // The caller cancelled the parent job. `await` must remain cancellable so this path
                // runs immediately; waiting in NonCancellable here used to delay the Cancel button
                // until the entire transfer completed. Stop the native child before propagating the
                // caller's cancellation.
                runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
                throw canceled
            }
        } catch (error: Exception) {
            // YoutubeDLException, or anything the bridge throws, becomes an ordinary failure.
            failure = failed(error, error.message)
        } finally {
            settled.set(true)
            watchdog.cancel()
            updates.close()
            // Progress is best effort: draining must not turn a cancellation into something else.
            withContext(NonCancellable) { runCatching { publisher.join() } }
        }

        failure?.let { return@supervisorScope it }

        val produced = producedFile(workDirectory)
            ?: return@supervisorScope DirectDownloadResult.Failed(
                MediaError.EXTRACTOR_FAILED,
                "yt-dlp finished without producing a file"
            )

        // The last progress sample was for yt-dlp's own work; the copy into the sink has its own.
        publish(request, produced, sink, onProgress)
    }

    /**
     * Streams the finished scratch file into [sink] and commits it, so it lands exactly where a
     * direct download would have.
     */
    private suspend fun publish(
        request: YtDlpDownloadRequest,
        produced: File,
        sink: DownloadSink,
        onProgress: suspend (DownloadState.Downloading) -> Unit
    ): DirectDownloadResult {
        // The container is only known once yt-dlp has chosen it, so the extension comes from the
        // file it actually wrote and never from a guess made before the download started.
        val extension = produced.extension.takeIf { it.isNotBlank() } ?: FALLBACK_EXTENSION
        val base = request.preferredName?.takeIf { it.isNotBlank() }
            ?: produced.nameWithoutExtension
        val displayName = FilenameSanitizer.sanitize(baseNameFor(base), extension)
        val mimeType = mimeTypeFor(extension)
        val totalBytes = produced.length()

        val handle = try {
            sink.open(displayName, mimeType, append = false)
        } catch (error: Exception) {
            return failed(error, error.message)
        }

        var committed = false
        try {
            onProgress(DownloadState.Downloading(0L, totalBytes, null))
            withContext(Dispatchers.IO) {
                handle.output.use { output ->
                    produced.inputStream().use { input -> input.copyTo(output, COPY_BUFFER_BYTES) }
                }
            }
            onProgress(DownloadState.Downloading(totalBytes, totalBytes, null))

            val location = handle.commit(totalBytes)
            committed = true
            return DirectDownloadResult.Completed(location, totalBytes, displayName, mimeType)
        } catch (error: Exception) {
            return failed(error, error.message)
        } finally {
            if (!committed) {
                withContext(NonCancellable) { runCatching { handle.abort() } }
            }
        }
    }

    /**
     * The bytes and total for one progress line, or null when the line carries neither.
     *
     * The parser is preferred because it reads the size yt-dlp itself measured. The library's
     * percentage is the fallback for a line the parser does not recognise, and in that case the
     * only total available is the extractor's estimate.
     */
    private fun progressOf(line: String, percent: Float, fallbackTotal: Long?): Pair<Long, Long?>? {
        YtDlpProgressParser.parse(line)?.let { parsed ->
            return (parsed.downloadedBytes ?: 0L) to (parsed.totalBytes ?: fallbackTotal)
        }

        if (!percent.isFinite() || percent <= 0f) return null
        val total = fallbackTotal?.takeIf { it > 0L }
        val downloaded = total?.let { (it * (percent / 100.0)).toLong() } ?: 0L
        return downloaded to total
    }

    private fun failed(
        cause: Throwable?,
        detail: String?
    ): DirectDownloadResult.Failed {
        // A storage failure already carries the reason the user should see. Anything else is
        // classified from yt-dlp's own wording, which is the only description available, rather
        // than shown verbatim (specification section 68).
        val error = when {
            cause is DownloadSinkException -> cause.error
            detail != null -> MediaError.classify(detail)
            cause != null -> DownloadErrorClassifier.forThrowable(cause)
            else -> MediaError.EXTRACTOR_FAILED
        }
        return DirectDownloadResult.Failed(
            error = error,
            detail = detail ?: cause?.javaClass?.simpleName,
            transient = DownloadErrorClassifier.isTransient(error)
        )
    }

    private fun downloadRequest(request: YtDlpDownloadRequest, workDirectory: File): YoutubeDLRequest =
        YoutubeDLRequest(request.url)
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            // One line per progress update rather than a carriage-returned status line, so the
            // callback really does fire once per update and the parser sees whole lines.
            .addOption("--newline")
            .addOption("--no-cache-dir")
            // Do not stamp the remote's timestamp on the user's copy: it only makes a fresh
            // download look like an old file in the Downloads list.
            .addOption("--no-mtime")
            .addOption("--format", formatSelector(request.formatId, request.requiresMuxing))
            // The container is deliberately NOT forced. Only the extension yt-dlp chose is known
            // to hold the codecs it chose; forcing mp4 onto a VP9/Opus pair would fail or remux.
            .addOption("--output", File(workDirectory, OUTPUT_TEMPLATE).absolutePath)
            // Resume rather than restart after a dropped connection. Measured on the test emulator,
            // whose virtual radio drops a transfer every few megabytes: without this, each retry began
            // again from the beginning - the log showed a fresh "Destination:" line and a jump back to
            // a low percentage - so a 6.46 MiB file that had reached 97.6% still failed after all three
            // retries. With it, the `.part` file continues from where it stopped, which is the only way
            // a large download finishes on a link that cannot hold one connection open for its length.
            .addOption("--continue")
            .addOption("--socket-timeout", SOCKET_TIMEOUT_SECONDS)
            .addOption("--retries", RETRIES)
            // Fragment-level retries, separate from transfer-level ones: a DASH download is hundreds
            // of requests, and without this one refused fragment abandons the whole stream.
            .addOption("--fragment-retries", FRAGMENT_RETRIES)
            // Maintainer-endorsed workaround for stalled CDN connections, several of which are
            // reachable over IPv6 but not over IPv4 (or the reverse) on a mobile network.
            .addOption("--force-ipv4")

    /**
     * The scratch directory for one download, or null when it could not be created.
     *
     * **Stable for a given request**, which is what makes a retry a resume: yt-dlp keeps its
     * progress in a `.part` file and a `.ytdl` marker inside this directory, and it skips the
     * fragments those files say it already has. A directory named after the attempt, as this
     * originally was, throws that away on every failure, so a flaky network could re-download the
     * same bytes forever without ever finishing. The name still has to separate two *different*
     * downloads of the same video - two resolutions, or a renamed copy - hence the format selector
     * and the requested name are part of it.
     */
    private fun workDirectoryFor(request: YtDlpDownloadRequest): File? {
        val base = File(context.cacheDir, CACHE_DIRECTORY)
        val directory = File(base, workDirectoryName(request))
        return directory.takeIf { it.mkdirs() || it.isDirectory }
    }

    /**
     * Removes scratch directories belonging to downloads nobody is waiting for any more, apart from
     * the one about to be used.
     *
     * Kept, not deleted, after a retryable failure, a scratch directory would otherwise accumulate
     * for the life of the install - partial videos are exactly the kind of file that fills a user's
     * storage without ever appearing in their Downloads list. An hour is comfortably longer than any
     * retry this app schedules (WorkManager's backoff tops out well below it) and long enough that a
     * user who returns to a failed download still gets the resume.
     */
    private fun discardStaleWorkDirectories(current: File, now: Long = System.currentTimeMillis()) {
        val base = File(context.cacheDir, CACHE_DIRECTORY)
        val cutoff = now - STALE_WORK_DIRECTORY_MILLIS
        base.listFiles().orEmpty().forEach { candidate ->
            if (candidate.isDirectory && candidate != current && candidate.lastModified() < cutoff) {
                Log.i(TAG, "discarding stale scratch directory ${candidate.name}")
                runCatching { candidate.deleteRecursively() }
            }
        }
    }

    private companion object {
        /** logcat tag for the child process's own output. */
        const val TAG = "YtDlpDownloader"

        const val CACHE_DIRECTORY = "ytdlp"

        const val PROCESS_ID_PREFIX = "linksi-ytdlp-"

        /** yt-dlp writes the video's own title, which is the only sensible name when none was given. */
        const val OUTPUT_TEMPLATE = "%(title)s.%(ext)s"

        const val FALLBACK_EXTENSION = "mp4"

        const val COPY_BUFFER_BYTES = 64 * 1024

        const val SOCKET_TIMEOUT_SECONDS = 20

        /**
         * How many times yt-dlp may re-open a dropped connection within one download.
         *
         * Measured, not guessed. Against a link that drops the connection every few megabytes - the
         * test emulator's virtual radio, which is a fair stand-in for a mobile network - the same
         * 6.46 MiB file behaved like this:
         *
         * | `--retries` | outcome | errors survived | elapsed |
         * |---|---|---|---|
         * | 3 | **incomplete**, `.part` at 6,688,788 of 6,777,555 bytes | 4 | 9 s |
         * | 10 | **complete**, 6,777,555 bytes | 8 | 9 s |
         *
         * Three, the yt-dlp default, is simply too few for a link that needs eight. Raising it is
         * nearly free because every retry **resumes** (`--continue`) rather than starting over, so
         * the extra attempts cost a few hundred kilobytes each rather than the whole file.
         *
         * The bound on a genuinely dead network is not this number: it is the stall watchdog, which
         * stops an attempt that transfers nothing, and [SOCKET_TIMEOUT_SECONDS] on each connection.
         */
        const val RETRIES = 10

        /** One per DASH fragment, so a single refused fragment does not abandon the stream. */
        const val FRAGMENT_RETRIES = 10

        /**
         * How long an unfinished scratch directory may sit in the cache before the next download
         * reclaims it. See [discardStaleWorkDirectories].
         */
        const val STALE_WORK_DIRECTORY_MILLIS = 60L * 60L * 1000L

        /**
         * How often the watchdog looks at the transfer. Well below the stall limit, so a stall is
         * noticed within a few seconds of becoming one rather than a tick late.
         */
        const val WATCHDOG_TICK_MILLIS = 5_000L
    }
}

/**
 * The yt-dlp `--format` selector for a request (pure, unit tested).
 *
 * A video-only format has to be paired with an audio stream, so the chosen id is joined with
 * `bestaudio`; the trailing alternatives mean a site that offers no separate audio track still
 * yields a file instead of an error. [DirectFileExtractor.FORMAT_ID] is not a yt-dlp format id - it
 * is the direct extractor's marker - so it is treated as "no choice made" rather than passed
 * through, which would make yt-dlp fail on an unknown format.
 */
internal fun formatSelector(formatId: String?, requiresMuxing: Boolean): String {
    val chosen = formatId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != DirectFileExtractor.FORMAT_ID }

    return when {
        chosen == null && requiresMuxing -> "bestvideo+bestaudio/best"
        chosen == null -> "best"
        requiresMuxing -> "$chosen+bestaudio/$chosen/best"
        else -> chosen
    }
}

/**
 * The file yt-dlp produced, or null when the directory holds nothing publishable.
 *
 * A partial file is not a result, and the newest complete one is the merged output: yt-dlp deletes
 * the per-stream parts after a successful merge, so on the normal path this is unambiguous.
 */
internal fun producedFile(directory: File): File? = directory.listFiles()
    ?.filter { it.isFile && it.length() > 0L && !it.name.contains(PART_SUFFIX) }
    ?.maxByOrNull { it.lastModified() }

/** yt-dlp's own suffix for an in-progress download. */
private const val PART_SUFFIX = ".part"

/**
 * The scratch directory name for one download (pure, unit tested).
 *
 * The name is a digest rather than the URL or the title, for three reasons: a URL contains
 * characters no filesystem accepts and can be thousands of characters long, a title is
 * attacker-controlled and could collide or escape the directory, and the *only* property that
 * matters is that the same download always maps to the same directory while two different ones
 * never do.
 *
 * "The same download" is deliberately a narrow idea: the same URL **and** the same format selector
 * **and** the same requested name. Changing the quality the user asked for is a different download
 * whose partial file would be the wrong video, so it must not inherit the old one's progress.
 */
internal fun workDirectoryName(
    request: YtDlpDownloadRequest,
    selector: String = formatSelector(request.formatId, request.requiresMuxing)
): String {
    val identity = "${request.url}\n$selector\n${request.preferredName.orEmpty()}"
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    val hex = digest.take(6).joinToString("") { "%02x".format(it) }
    return "work-$hex"
}

/**
 * One line of yt-dlp's own output, with every URL stripped of its query.
 *
 * This app has a standing rule against writing the URLs a user handles to logcat, and yt-dlp quotes
 * media URLs verbatim - a CDN URL carries a signature and an expiry, which is exactly the sort of
 * thing that must not land in a world-readable log. The scheme, host and path are kept because they
 * are what makes a support log diagnosable ("which host refused, which file it was writing"), and
 * the query is what carries the credential.
 */
internal fun redactUrls(line: String): String = URL_PATTERN.replace(line) { match ->
    val url = match.value
    val cut = url.indexOfFirst { it == '?' || it == '#' }
    // No query and no fragment means nothing in this URL is a credential.
    if (cut < 0) url else "${url.take(cut)}?<redacted>"
}

private val URL_PATTERN = Regex("""https?://\S+""")

/**
 * [raw] without a trailing media extension, so the real one can be appended (pure, unit tested).
 *
 * A user who typed "holiday.mp4" must not end up with "holiday.mp4.webm" just because the site only
 * offers WebM. Only extensions this app recognises are stripped, so a name that merely contains a
 * dot - "Mr. Beast interview" - is left alone.
 */
internal fun baseNameFor(raw: String): String {
    val name = raw.trim()
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
    val looksLikeMedia = extension.isNotEmpty() &&
        extension.length <= MAX_EXTENSION_LENGTH &&
        extension.lowercase() in KNOWN_MEDIA_EXTENSIONS
    return if (looksLikeMedia) name.substringBeforeLast('.') else name
}

/** Extensions this app can expect a site engine to produce. */
private val KNOWN_MEDIA_EXTENSIONS = setOf(
    "mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "3gp", "ts",
    "mp3", "m4a", "aac", "opus", "ogg", "oga", "wav", "flac", "weba"
)

private const val MAX_EXTENSION_LENGTH = 5

/**
 * The MIME type for a downloaded container, or null when it is not one this app knows.
 *
 * MediaStore is happier with an explicit type, and a null is honest: it lets the platform infer from
 * the file name rather than claiming something wrong.
 */
internal fun mimeTypeFor(extension: String): String? = when (extension.lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "mov" -> "video/quicktime"
    "avi" -> "video/x-msvideo"
    "flv" -> "video/x-flv"
    "3gp" -> "video/3gpp"
    "ts" -> "video/mp2t"
    "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "aac" -> "audio/aac"
    "opus" -> "audio/opus"
    "ogg", "oga" -> "audio/ogg"
    "wav" -> "audio/wav"
    "flac" -> "audio/flac"
    "weba" -> "audio/webm"
    else -> null
}
