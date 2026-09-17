package com.linksi.app.enhanced.download

import com.linksi.app.enhanced.media.MediaError
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** One direct-file download: what to fetch and what to call it. */
data class DirectDownloadRequest(
    val url: String,
    val displayName: String,
    /** Size the extractor already learned from its probe, when it had one. */
    val expectedBytes: Long? = null
)

/**
 * The outcome of a direct download. Cancellation is *not* modelled here: a cancelled coroutine
 * stays cancelled (the partial file is cleaned up on the way out and the
 * [CancellationException] is rethrown), so callers see cancellation exactly once, through the
 * coroutine machinery they already use.
 */
sealed interface DirectDownloadResult {

    data class Completed(
        val location: String,
        val bytes: Long,
        val displayName: String,
        val mimeType: String?
    ) : DirectDownloadResult

    data class Failed(
        val error: MediaError,
        val detail: String?,
        val httpCode: Int? = null,
        /** True when retrying later could plausibly succeed. */
        val transient: Boolean = false
    ) : DirectDownloadResult
}

/**
 * Streams one file into a [DownloadSink] (specification sections 22 and 23).
 *
 * Behaviour worth stating explicitly:
 *  - **Progress** is reported through a throttled [DownloadProgressMeter], so the UI gets at most
 *    one update per [DownloadProgressMeter.DEFAULT_INTERVAL_MILLIS] with a speed computed from the
 *    whole window rather than from a single buffer.
 *  - **Cancellation** works through both mechanisms: the coroutine's `Job` cancels the OkHttp
 *    [Call] (so a blocked read returns immediately) and every buffer boundary re-checks the
 *    coroutine, so a cancel is never missed.
 *  - **Cleanup**: a cancelled or failed download deletes its partial file, so the user never finds
 *    a half-written "0 byte" entry (research brief section 5.2). The one case cleanup cannot cover
 *    is an abrupt process kill or reboot - which is exactly the case [download] resumes from.
 *  - **Resume** is attempted only when the sink can name the exact byte offset of an existing
 *    partial file ([DownloadSink.resumableBytes]) *and* the server answers `206` starting at that
 *    same offset. Anything else restarts from zero. See [AppStorageSink] and [MediaStoreSink] for
 *    which sinks can do it.
 *  - `Accept-Encoding: identity` is requested so `Content-Length` describes the bytes actually
 *    written; without it a gzipped response would make the progress bar exceed 100 %.
 */
@Singleton
class DirectFileDownloader @Inject constructor(
    private val client: OkHttpClient
) {

    /** Property rather than a constructor parameter: Dagger injects every parameter. */
    private val progressIntervalMillis: Long = DownloadProgressMeter.DEFAULT_INTERVAL_MILLIS

    suspend fun download(
        request: DirectDownloadRequest,
        sink: DownloadSink,
        onProgress: suspend (DownloadState.Downloading) -> Unit = {}
    ): DirectDownloadResult {
        val resumable = runCatching { sink.resumableBytes(request.displayName) }
            .getOrDefault(0L)
            .coerceAtLeast(0L)

        var call: Call = try {
            newCall(request.url, resumable)
        } catch (illegal: IllegalArgumentException) {
            return DirectDownloadResult.Failed(MediaError.EXTRACTOR_FAILED, illegal.message)
        }

        // Cancelling the coroutine must unblock the socket, not wait for a timeout.
        val registration = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        try {
            var response = withContext(Dispatchers.IO) { call.execute() }

            // 416 means the stored file is already at least as large as the resource: restart
            // rather than fail, which also recovers from a completed file being picked up as a
            // partial one.
            if (response.code == HTTP_RANGE_NOT_SATISFIABLE && resumable > 0) {
                response.close()
                call = newCall(request.url, 0L)
                response = withContext(Dispatchers.IO) { call.execute() }
            }

            val result = response.use { open ->
                stream(request, sink, open, resumable, onProgress)
            }
            return result
        } catch (io: IOException) {
            if (!currentCoroutineContext().isActive) {
                // The call was cancelled through the Job; report it as cancellation, not as a
                // network failure.
                throw CancellationException("download cancelled: ${io.message}")
            }
            // A storage failure is an IOException too, so the retry decision comes from the
            // classified error rather than from the exception type.
            val error = DownloadErrorClassifier.forThrowable(io)
            return DirectDownloadResult.Failed(
                error = error,
                detail = io.message,
                transient = DownloadErrorClassifier.isTransient(error)
            )
        } finally {
            registration?.dispose()
        }
    }

    private fun newCall(url: String, resumeFrom: Long): Call = client.newCall(
        Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
            .build()
    )

    private suspend fun stream(
        request: DirectDownloadRequest,
        sink: DownloadSink,
        response: Response,
        resumable: Long,
        onProgress: suspend (DownloadState.Downloading) -> Unit
    ): DirectDownloadResult {
        val contentRange = ContentRange.parse(response.header("Content-Range"))
        val partial = response.code == HTTP_PARTIAL_CONTENT

        if (partial && contentRange?.start != resumable) {
            // The server ignored the offset we asked for. Appending would silently corrupt the
            // file, so this is a failure rather than a guess.
            return DirectDownloadResult.Failed(
                MediaError.NETWORK,
                "server answered with an unexpected byte range",
                response.code,
                transient = true
            )
        }

        if (!response.isSuccessful) {
            return DirectDownloadResult.Failed(
                DownloadErrorClassifier.forHttpCode(response.code),
                "HTTP ${response.code}",
                response.code,
                DownloadErrorClassifier.isTransientHttpCode(response.code)
            )
        }

        val body = response.body
            ?: return DirectDownloadResult.Failed(MediaError.NETWORK, "empty response body", response.code, true)

        val append = partial && resumable > 0
        val declaredTotal = when {
            partial -> contentRange?.total
            else -> body.contentLength().takeIf { it >= 0 }
        }
        val totalBytes = declaredTotal ?: request.expectedBytes?.takeIf { it > 0 }
        val mimeType = response.header("Content-Type")
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: body.contentType()?.let { "${it.type}/${it.subtype}" }

        val handle = try {
            sink.open(request.displayName, mimeType, append)
        } catch (error: Exception) {
            return DirectDownloadResult.Failed(
                DownloadErrorClassifier.forThrowable(error),
                error.message,
                response.code
            )
        }

        var committed = false
        try {
            val meter = DownloadProgressMeter(minIntervalMillis = progressIntervalMillis)
            val startOffset = if (append) resumable else 0L
            meter.reset(startOffset)
            var written = startOffset

            withContext(Dispatchers.IO) {
                handle.output.use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            // Belt and braces: the OkHttp call is cancelled too, but this makes
                            // the loop itself cancellation aware between reads.
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            meter.sample(written, totalBytes)?.let { onProgress(it) }
                        }
                        output.flush()
                    }
                }
            }

            if (declaredTotal != null && declaredTotal > 0 && written != declaredTotal) {
                // The connection died mid-body without an exception, or the server lied about the
                // length. Either way the file is short and must not be published.
                return DirectDownloadResult.Failed(
                    MediaError.NETWORK,
                    "stopped at $written of $declaredTotal bytes",
                    response.code,
                    transient = true
                )
            }

            onProgress(
                meter.sample(written, totalBytes, force = true)
                    ?: DownloadState.Downloading(written, totalBytes, null)
            )

            val location = handle.commit(written)
            committed = true
            return DirectDownloadResult.Completed(location, written, request.displayName, mimeType)
        } finally {
            if (!committed) {
                // Must run even though the coroutine is already cancelled.
                withContext(NonCancellable) { handle.abort() }
            }
        }
    }

    companion object {
        /** 64 KiB: large enough that the per-read overhead disappears, small enough to cancel fast. */
        private const val BUFFER_BYTES = 64 * 1024

        private const val HTTP_PARTIAL_CONTENT = 206

        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}

/**
 * Throttled byte/speed accounting for a download (specification section 20).
 *
 * Pure JVM with an injectable clock, so the arithmetic is unit tested rather than eyeballed.
 *
 * The speed is computed from the bytes and the time **since the last emitted sample**, not since
 * the last buffer: averaging over the whole window is what stops the number flickering between
 * 0 B/s and 40 MB/s when a read happens to be fast or slow.
 */
class DownloadProgressMeter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS
) {

    private var lastBytes = 0L
    private var lastTimestamp = 0L
    private var lastSpeed: Long? = null
    private var started = false

    /** Starts a new measurement window at [startBytes] (the resume offset when there is one). */
    fun reset(startBytes: Long = 0L, now: Long = clock()) {
        lastBytes = startBytes.coerceAtLeast(0L)
        lastTimestamp = now
        lastSpeed = null
        started = true
    }

    /**
     * Returns a sample, or null when this update is inside the throttle window and [force] is
     * false. [force] is for the final update, which must always be published.
     */
    fun sample(
        bytesDownloaded: Long,
        totalBytes: Long?,
        force: Boolean = false
    ): DownloadState.Downloading? {
        if (!started) reset(bytesDownloaded)

        val now = clock()
        val elapsed = now - lastTimestamp
        if (!force && elapsed < minIntervalMillis) return null

        val delta = (bytesDownloaded - lastBytes).coerceAtLeast(0L)
        val speed = if (elapsed > 0) {
            ((delta * MILLIS_PER_SECOND) / elapsed)
        } else {
            // Zero-width window (two samples in the same millisecond): keep the previous figure
            // rather than inventing an infinite one.
            lastSpeed
        }

        lastBytes = bytesDownloaded
        lastTimestamp = now
        lastSpeed = speed

        return DownloadState.Downloading(bytesDownloaded, totalBytes, speed)
    }

    companion object {
        /** Four progress updates a second: smooth enough to look live, cheap enough to ignore. */
        const val DEFAULT_INTERVAL_MILLIS = 250L

        private const val MILLIS_PER_SECOND = 1000L
    }
}

/** A parsed `Content-Range` response header. */
data class ByteRange(
    val start: Long,
    val end: Long?,
    /** Total resource size, or null when the server wrote `*`. */
    val total: Long?
)

/**
 * Parses `Content-Range` (RFC 7233), pure JVM.
 *
 * Only two things are ever needed from it: does the body start at the byte we asked for, and how
 * big is the whole resource.
 */
object ContentRange {

    fun parse(header: String?): ByteRange? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null

        val body = value.substringAfter(' ', "").trim()
        if (body.isEmpty()) return null

        val rangePart = body.substringBefore('/').trim()
        val total = body.substringAfter('/', "").trim().toLongOrNull()?.takeIf { it >= 0 }

        // "bytes */1000" is what a 416 sends: there is no usable start offset.
        if (rangePart.isEmpty() || rangePart == "*") return null

        val start = rangePart.substringBefore('-').trim().toLongOrNull() ?: return null
        val end = rangePart.substringAfter('-', "").trim().toLongOrNull()
        if (start < 0) return null

        return ByteRange(start = start, end = end, total = total)
    }
}

/**
 * Maps transports and HTTP status codes onto the user-facing [MediaError] vocabulary
 * (specification section 68), pure JVM.
 *
 * It is shared by the extractor and the downloader so a 404 means the same thing in both, and it is
 * the single place that decides whether a failure is worth retrying.
 */
object DownloadErrorClassifier {

    fun forHttpCode(code: Int): MediaError = when (code) {
        401, 407 -> MediaError.LOGIN_REQUIRED
        403 -> MediaError.PRIVATE_CONTENT
        404, 410 -> MediaError.MEDIA_GONE
        408, 429 -> MediaError.NETWORK
        in 500..599 -> MediaError.SERVER_UNAVAILABLE
        else -> MediaError.EXTRACTOR_FAILED
    }

    fun forThrowable(error: Throwable): MediaError = when (error) {
        is DownloadSinkException -> error.error
        is SecurityException -> MediaError.NO_STORAGE
        is UnknownHostException, is SocketTimeoutException -> MediaError.NETWORK
        is IOException -> MediaError.NETWORK
        else -> MediaError.EXTRACTOR_FAILED
    }

    /** Depletion of storage is permanent until the user frees space; a bad URL never works. */
    fun isTransient(error: MediaError): Boolean =
        error == MediaError.NETWORK || error == MediaError.SERVER_UNAVAILABLE

    fun isTransientHttpCode(code: Int): Boolean = isTransient(forHttpCode(code))
}
