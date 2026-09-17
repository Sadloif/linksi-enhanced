package com.linksi.app.enhanced.media.ytdlp

import android.content.Context
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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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

        val workDirectory = newWorkDirectory()
            ?: return DirectDownloadResult.Failed(
                MediaError.NO_STORAGE,
                "the app cache directory is not writable"
            )

        return try {
            runDownload(request, sink, workDirectory, onProgress)
        } finally {
            // Scratch space, never the user's file: removed whichever way this ended.
            runCatching { workDirectory.deleteRecursively() }
        }
    }

    private suspend fun runDownload(
        request: YtDlpDownloadRequest,
        sink: DownloadSink,
        workDirectory: File,
        onProgress: suspend (DownloadState.Downloading) -> Unit
    ): DirectDownloadResult = coroutineScope {
        val processId = "$PROCESS_ID_PREFIX${UUID.randomUUID()}"

        // Cancelling the coroutine has to stop the child process, not merely stop waiting for it.
        val registration = currentCoroutineContext()[Job]?.invokeOnCompletion {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        }

        val updates = Channel<DownloadState.Downloading>(Channel.CONFLATED)
        val publisher = launch { for (update in updates) onProgress(update) }
        val meter = DownloadProgressMeter()
        meter.reset()

        var failure: DirectDownloadResult.Failed? = null
        try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(
                    downloadRequest(request, workDirectory),
                    processId
                ) { percent, _, line ->
                    progressOf(line, percent, request.expectedBytes)?.let { (downloaded, total) ->
                        meter.sample(downloaded, total)?.let { updates.trySend(it) }
                    }
                }
            }
        } catch (canceled: YoutubeDL.CanceledException) {
            // Only reachable when this process was destroyed, which only happens on cancellation.
            throw CancellationException("the yt-dlp process was stopped")
        } catch (interrupted: InterruptedException) {
            throw CancellationException("the yt-dlp process was interrupted")
        } catch (error: Exception) {
            // YoutubeDLException, or anything the bridge throws, becomes an ordinary failure.
            failure = failed(error, error.message)
        } finally {
            updates.close()
            // Progress is best effort: draining must not turn a cancellation into something else.
            withContext(NonCancellable) { runCatching { publisher.join() } }
            registration?.dispose()
        }

        failure?.let { return@coroutineScope it }

        val produced = producedFile(workDirectory)
            ?: return@coroutineScope DirectDownloadResult.Failed(
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
            .addOption("--socket-timeout", SOCKET_TIMEOUT_SECONDS)
            .addOption("--retries", RETRIES)

    /** The scratch directory for one download, or null when it could not be created. */
    private fun newWorkDirectory(): File? {
        val base = File(context.cacheDir, CACHE_DIRECTORY)
        // Per-download, so two downloads can never see each other's output template.
        val directory = File(base, "work-${UUID.randomUUID().toString().take(8)}")
        return directory.takeIf { it.mkdirs() || it.isDirectory }
    }

    private companion object {
        const val CACHE_DIRECTORY = "ytdlp"

        const val PROCESS_ID_PREFIX = "linksi-ytdlp-"

        /** yt-dlp writes the video's own title, which is the only sensible name when none was given. */
        const val OUTPUT_TEMPLATE = "%(title)s.%(ext)s"

        const val FALLBACK_EXTENSION = "mp4"

        const val COPY_BUFFER_BYTES = 64 * 1024

        const val SOCKET_TIMEOUT_SECONDS = 20

        const val RETRIES = 3
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
