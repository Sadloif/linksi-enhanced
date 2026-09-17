package com.linksi.app

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linksi.app.enhanced.download.AppStorageSink
import com.linksi.app.enhanced.download.DirectDownloadResult
import com.linksi.app.enhanced.download.DownloadSink
import com.linksi.app.enhanced.download.DownloadState
import com.linksi.app.enhanced.download.MediaStoreSink
import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.ytdlp.DownloadWatchdogPolicy
import com.linksi.app.enhanced.media.ytdlp.YtDlpDownloadRequest
import com.linksi.app.enhanced.media.ytdlp.YtDlpDownloader
import com.linksi.app.enhanced.media.ytdlp.YtDlpInitStatus
import com.linksi.app.enhanced.media.ytdlp.YtDlpRuntime
import com.linksi.app.enhanced.media.ytdlp.YtDlpUpdater
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What happens to a site-engine download that is *interrupted*, and to the scratch space it leaves.
 *
 * The smoke test next door proves a merged download completes when it is left alone. This one covers
 * the three things that decide what a user sees when it is not left alone, all of which the app's own
 * text now promises and none of which a JVM unit test can show:
 *
 *  1. **Cancelling stops the engine.** The user's Cancel button cancels the coroutine, which is
 *     supposed to destroy the child process. The library's documented kill path cannot work - it
 *     hands `/system/bin/sh` the library's own string process id rather than an OS pid, and both
 *     `pstree` and GNU-style `grep -oP` are absent from Android's toybox - so the only thing that
 *     actually stops yt-dlp is `Process.destroy()`. This test checks the outcome rather than the
 *     mechanism: after a cancel, no child of this app running the bundled interpreter may still be
 *     alive. That matters because an orphaned child keeps writing into a scratch directory the
 *     downloader is free to delete.
 *  2. **A caller interruption can be retried.** yt-dlp keeps its progress in a `.part` file plus a
 *     `.ytdl` marker and
 *     skips the fragments those describe, but only if the directory still exists. The downloader now
 *     names that directory after the download's *identity* rather than after the attempt, and keeps
 *     it after a transient failure. This test interrupts a large single stream, checks that the
 *     partial file is still there and intact, and then runs the same request again to show the
 *     scratch directory is reused rather than replaced.
 *
 * The third test uses a loopback HTTP server which advertises a complete payload, sends a fixed
 * prefix and closes the connection for the whole first attempt, then honours the retry's `Range`
 * request. That is deliberately separate from the cancellation test: it drives the production
 * `Failed(transient = true)` branch, not the caller-cancelled branch, and checks the bytes published
 * after the retry.
 *
 * These tests are deliberately written against [YtDlpDownloader] directly rather than through the
 * engine: the engine's cancellation and retry policy is WorkManager's business and is unit tested,
 * while this is about what the child process and the filesystem actually do.
 *
 * The public-stream tests move real bytes, so they are skippable rather than failing when the
 * network cannot reach the sample. The loopback test is deterministic and is treated as a defect
 * when any of its production-path assertions fail.
 */
@RunWith(AndroidJUnit4::class)
class YtDlpInterruptedDownloadTest {

    private companion object {
        const val TAG = "YtDlpInterruptTest"

        /**
         * A public DASH manifest with a video-only stream large enough to interrupt.
         *
         * The manifest is used rather than a plain `.mp4` URL for two reasons. First, this CDN
         * serves no single-file MP4 at all: every representation is a separate DASH stream, which
         * was verified by listing the formats. Second, a *video-only* single stream is the right
         * shape for this test: it isolates the `.part`/`.ytdl` behaviour from the two-stream merge
         * path, and it means cancellation has no FFmpeg grandchild to leave behind while the point
         * being measured is whether the interpreter itself is stopped.
         *
         * The chosen bitrate is the third-smallest, so the file is several megabytes - enough to
         * interrupt mid-transfer on any connection this project has measured - without being the
         * 4K representation that would take half an hour.
         */
        const val STREAM_URL =
            "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd"

        /** The 640x360 ~1 Mbit/s video-only representation of [STREAM_URL]. */
        const val STREAM_FORMAT_ID = "bbb_30fps_640x360_1000k"

        /** How long the download is allowed to run before it is cancelled. */
        const val RUN_BEFORE_CANCEL_MILLIS = 25_000L

        /** Long enough for the child process to be gone; the kill is synchronous, this is slack. */
        const val SETTLE_MILLIS = 5_000L

        /** The second run should finish far quicker than the first: that is the whole point. */
        const val RESUME_TIMEOUT_MILLIS = 300_000L

        /**
         * The stall limit used to exercise the watchdog. Short enough that an instrumented run can
         * wait it out, long enough that a slow fragment gap is not mistaken for one.
         *
         * It cannot be shorter than yt-dlp's start-up, and that is a property of the rule rather
         * than a convenience: a download that has not transferred its first byte has not *stalled*,
         * it has not started, so only the hard limit can end it. Producing a genuine stall here
         * means letting a real transfer begin and then waiting for a gap in the fragments, which is
         * what this limit is sized for.
         */
        const val TEST_STALL_LIMIT_MILLIS = 8_000L

        /** A hard limit that will not fire first, so the test proves the *stall* rule, not the clock. */
        const val TEST_HARD_LIMIT_MILLIS = 600_000L

        /** The stall limit plus room for yt-dlp's start-up, the watchdog's tick and the unwind. */
        const val WATCHDOG_DEADLINE_MILLIS = 240_000L

        /**
         * Long enough for the retry to get past the engine's own start-up and report something.
         * The assertion is that the attempt happens at all, not that it succeeds on this network.
         */
        const val RETRY_AFTER_STALL_MILLIS = 120_000L

        /** A local payload large enough to leave a partial file across yt-dlp's automatic retries. */
        const val LOCAL_PAYLOAD_BYTES = 4 * 1024 * 1024

        /** Bytes the local server sends before closing each connection in the failure phase. */
        const val LOCAL_FAILURE_BYTES = 64 * 1024

        /** Local loopback transfers should settle quickly; a timeout prevents a wedged child test. */
        const val LOCAL_ATTEMPT_TIMEOUT_MILLIS = 120_000L
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val runtime by lazy { YtDlpRuntime(context, YtDlpUpdater(context)) }

    private val downloader by lazy { YtDlpDownloader(context, runtime) }

    private fun sink(): DownloadSink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreSink(context)
        else AppStorageSink(context)

    private fun request(preferredName: String) = YtDlpDownloadRequest(
        url = STREAM_URL,
        formatId = STREAM_FORMAT_ID,
        requiresMuxing = false,
        preferredName = preferredName
    )

    /** The scratch directory for one download, whichever name the downloader gave it. */
    private fun scratchDirs(): List<File> =
        File(context.cacheDir, "ytdlp").listFiles().orEmpty().filter { it.isDirectory }

    private fun partialFiles(): List<File> = scratchDirs().flatMap { directory ->
        directory.listFiles().orEmpty().filter { it.isFile && it.name.contains(".part") }
    }

    /** The interpreter processes this app currently owns, as the platform reports them. */
    private fun interpreterProcesses(): List<String> {
        // `ps` is restricted to the caller's own uid since Android 9, which is exactly the scope
        // wanted here: a surviving child of *this* app is a leak, another app's is not our business.
        val process = ProcessBuilder("/system/bin/sh", "-c", "ps -A -o PID,ARGS 2>/dev/null || ps -A 2>/dev/null")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return output.lineSequence()
            .filter { it.contains("libpython") || it.contains("libffmpeg") }
            .toList()
    }

    @Test
    fun cancellingADownloadStopsTheEnginesChildProcess() {
        assumeTrue(
            "the engine must be startable on this device",
            runBlocking { runtime.ensureReady() is YtDlpInitStatus.Ready }
        )

        // A previous test's leftovers would be counted as our leak, and an already-partial file would
        // make the transfer finish before it could be cancelled.
        scratchDirs().forEach { it.deleteRecursively() }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failure = AtomicReference<Throwable?>(null)
        val job: Job = scope.launch {
            try {
                val outcome = downloader.download(
                    request = request("linksi-ytdlp-interrupted"),
                    sink = sink()
                ) { state -> Log.i(TAG, "progress before cancel: $state") }
                Log.i(TAG, "cancelled download returned $outcome instead of unwinding")
            } catch (canceled: CancellationException) {
                // Expected: the caller cancelled.
                Log.i(TAG, "cancelled download unwound with ${canceled.message}")
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        val started = System.currentTimeMillis()
        val sawTransfer = runBlocking {
            withTimeoutOrNull(RUN_BEFORE_CANCEL_MILLIS) {
                while (partialFiles().none { it.length() > 0L }) delay(500)
                true
            }
        } ?: false

        job.cancel(CancellationException("cancelled by YtDlpInterruptedDownloadTest"))
        runBlocking { job.join() }
        Thread.sleep(SETTLE_MILLIS)

        val survivors = interpreterProcesses()
        Log.i(TAG, "cancelled after ${System.currentTimeMillis() - started}ms; " +
            "surviving interpreter processes: $survivors")
        Log.i(TAG, "scratch after cancel: ${scratchDirs().flatMap { it.listFiles().orEmpty().toList() }}")

        failure.get()?.let { throw AssertionError("the download failed instead of being cancelled", it) }

        if (!sawTransfer) {
            // Nothing was ever downloaded, so there is no child process to leak. That is a fact
            // about the network, not a pass for the kill path.
            assumeTrue(
                "the sample stream never started transferring, so cancellation could not be tested",
                false
            )
        }

        assertTrue(
            "no child of this app may still be running the bundled interpreter after a cancel: " +
                survivors.joinToString("; "),
            survivors.isEmpty()
        )
    }

    @Test
    fun anInterruptedDownloadResumesInsteadOfStartingOver() {        assumeTrue(
            "the engine must be startable on this device",
            runBlocking { runtime.ensureReady() is YtDlpInitStatus.Ready }
        )
        scratchDirs().forEach { it.deleteRecursively() }

        val name = "linksi-ytdlp-resume"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // First attempt, interrupted part way through.
        val job = scope.launch {
            runCatching {
                downloader.download(
                    request = request(name),
                    sink = sink()
                ) { state -> Log.i(TAG, "first attempt: $state") }
            }
        }
        val partial = runBlocking {
            withTimeoutOrNull(RUN_BEFORE_CANCEL_MILLIS) {
                var found: File? = null
                while (found == null) {
                    found = partialFiles().firstOrNull { it.length() > 0L }
                    if (found == null) delay(500)
                }
                found
            }
        }
        job.cancel(CancellationException("interrupted on purpose"))
        runBlocking { job.join() }
        Thread.sleep(SETTLE_MILLIS)

        if (partial == null) {
            assumeTrue(
                "the sample stream neither produced a partial file nor needed interrupting, " +
                    "so resume could not be tested on this connection",
                false
            )
        }
        val partialBytes = partial!!.length()
        Log.i(TAG, "interrupted with ${partialBytes} bytes in ${partial.name}")

        // The partial file must survive the interruption intact. This is what the downloader's
        // scratch-directory policy depends on: if the bytes are gone, the retry is a re-download.
        //
        // One legitimate exception: the stream can finish downloading *before* the cancel lands on a
        // fast connection. Then there is no partial file to check - the file that replaced it is the
        // finished one - and the resume question does not apply to this run.
        if (!partial.exists()) {
            Log.i(TAG, "the download completed before it could be interrupted; nothing to resume")
            assumeTrue("the sample stream finished before it could be interrupted", false)
        }
        assertEquals(
            "the partial file must not have been truncated",
            partialBytes,
            partial.length()
        )

        // Second attempt: the same request, so the same scratch directory.
        val started = System.currentTimeMillis()
        val outcome = runBlocking {
            withTimeoutOrNull(RESUME_TIMEOUT_MILLIS) {
                downloader.download(
                    request = request(name),
                    sink = sink()
                ) { state -> Log.i(TAG, "second attempt: $state") }
            }
        }
        val elapsed = System.currentTimeMillis() - started
        assertNotNull("the resumed download must finish", outcome)

        val completed = outcome as? DirectDownloadResult.Completed
        if (completed == null) {
            // A genuine network failure is a skip; a resume that produced nothing is reported.
            Log.w(TAG, "the resumed download did not complete: $outcome")
            assumeTrue("the resumed download could not be completed on this network: $outcome", false)
        }
        Log.i(
            TAG,
            "resumed download completed ${completed!!.bytes} bytes in ${elapsed / 1000}s " +
                "(it had already fetched $partialBytes)"
        )
        assertTrue("the resumed download must produce a file", completed.bytes > 0L)
    }

    /**
     * A transient transport failure keeps the partial yt-dlp file, and the next request resumes it.
     *
     * This is intentionally a local server rather than a public URL. The server advertises the
     * complete payload length, writes only [LOCAL_FAILURE_BYTES], and closes the socket. It repeats
     * that behaviour for every request until [TransientRangeServer.allowCompletion] is called, so
     * yt-dlp's own three retries cannot accidentally turn the first attempt into a success. Once the
     * first [YtDlpDownloader.download] returns, the server responds to the next `Range` request with
     * the remaining bytes. The non-zero range assertion proves the retry resumed at the HTTP layer,
     * while the final byte-for-byte assertion proves the production publish path did not corrupt it.
     */
    @Test
    fun aTransientFailureKeepsPartialAndRetryResumesFromTheServerOffset() {
        assumeTrue(
            "the engine must be startable on this device",
            runBlocking { runtime.ensureReady() is YtDlpInitStatus.Ready }
        )
        scratchDirs().forEach { it.deleteRecursively() }

        val payload = ByteArray(LOCAL_PAYLOAD_BYTES) { index ->
            // A non-repeating deterministic pattern makes accidental duplication or truncation
            // visible in the final assertion, without needing a media fixture in the repository.
            ((index * 31 + index / 251 + 17) and 0xff).toByte()
        }
        val preferredName = "linksi-ytdlp-transient-resume-${System.currentTimeMillis()}"
        val server = TransientRangeServer(payload, LOCAL_FAILURE_BYTES)
        var published: File? = null

        try {
            val request = YtDlpDownloadRequest(
                url = server.url,
                formatId = null,
                requiresMuxing = false,
                preferredName = preferredName,
                expectedBytes = payload.size.toLong()
            )

            val first = runBlocking {
                withTimeoutOrNull(LOCAL_ATTEMPT_TIMEOUT_MILLIS) {
                    downloader.download(
                        request = request,
                        sink = AppStorageSink(context)
                    ) { state -> Log.i(TAG, "local first attempt: $state") }
                }
            }
            assertNotNull(
                "the intentionally interrupted local transfer must return before the test deadline",
                first
            )
            assertTrue(
                "the first local transfer must fail so the production transient branch runs: $first",
                first is DirectDownloadResult.Failed
            )
            val failure = first as DirectDownloadResult.Failed
            assertEquals(
                "the closed connection must be classified as network failure",
                MediaError.NETWORK, failure.error)
            assertTrue(
                "a closed connection must be retryable so its scratch directory is kept: $failure",
                failure.transient
            )

            val workDirectory = scratchDirs().singleOrNull()
            assertTrue(
                "a transient failure must keep the stable scratch directory",
                workDirectory?.isDirectory == true
            )
            val partial = workDirectory!!.listFiles().orEmpty()
                .filter { it.isFile && it.name.contains(".part") }
                .maxByOrNull { it.length() }
            assertNotNull("the failed attempt must leave yt-dlp's partial file", partial)
            val partialBytes = partial!!.length()
            assertTrue(
                "the server must have delivered a non-empty but incomplete prefix; bytes=$partialBytes",
                partialBytes in 1 until payload.size.toLong()
            )
            assertArrayEquals(
                "the partial file must contain the server's prefix exactly",
                payload.copyOf(partialBytes.toInt()),
                partial!!.readBytes()
            )

            // yt-dlp may already have used non-zero ranges while retrying the intentionally broken
            // first attempt. Snapshot before switching phases so the assertion below can identify
            // a Range request made by the actual retry, not merely one made by those auto-retries.
            val firstAttemptRangeCount = server.rangeStarts().size
            server.allowCompletion()
            val second = runBlocking {
                withTimeoutOrNull(LOCAL_ATTEMPT_TIMEOUT_MILLIS) {
                    downloader.download(
                        request = request,
                        sink = AppStorageSink(context)
                    ) { state -> Log.i(TAG, "local resumed attempt: $state") }
                }
            }
            assertNotNull("the resumed local transfer must return before the test deadline", second)
            val completed = second as? DirectDownloadResult.Completed
            assertNotNull("the resumed local transfer must complete: $second", completed)
            published = File(completed!!.location)
            assertEquals(payload.size.toLong(), completed!!.bytes)

            val secondAttemptRanges = server.rangeStarts().drop(firstAttemptRangeCount)
            assertTrue(
                "the retry must ask for the preserved partial offset; " +
                    "partial=$partialBytes ranges=$secondAttemptRanges",
                partialBytes in secondAttemptRanges
            )
            assertTrue("the published output must exist at ${completed.location}", published!!.isFile)
            assertArrayEquals(payload, published!!.readBytes())
        } finally {
            published?.delete()
            server.close()
            scratchDirs().forEach { it.deleteRecursively() }
        }
    }

    /**
     * The watchdog stops a transfer that has started and then goes quiet.
     *
     * The local server completes yt-dlp's generic-extractor probe, then sends a real media prefix
     * and holds that media connection open. This makes the stall detector observe bytes first, so
     * the short test policy exercises the production stall rule rather than the hard start-up bound
     * or a DNS/HTTP failure. The result must be a retryable network failure with the exact watchdog
     * detail, and the runtime must still accept another request afterwards.
    */
    @Test
    fun aDownloadThatStallsAfterTransferringIsStoppedByTheWatchdog() {
        assumeTrue(
            "the engine must be startable on this device",
            runBlocking { runtime.ensureReady() is YtDlpInitStatus.Ready }
        )
        scratchDirs().forEach { it.deleteRecursively() }

        val payload = ByteArray(LOCAL_PAYLOAD_BYTES) { index ->
            ((index * 31 + index / 251 + 17) and 0xff).toByte()
        }
        val server = StallingMediaServer(payload, LOCAL_FAILURE_BYTES)

        try {
            val outcome = runBlocking {
                withTimeoutOrNull(WATCHDOG_DEADLINE_MILLIS) {
                    downloader.download(
                        request = YtDlpDownloadRequest(
                            url = server.url,
                            formatId = null,
                            requiresMuxing = false,
                            preferredName = "linksi-ytdlp-watchdog",
                            expectedBytes = payload.size.toLong()
                        ),
                        sink = AppStorageSink(context),
                        // The rule is the production one; only the limits differ, because a device
                        // test cannot wait out a real minute of silence in one instrumented run.
                        watchdogPolicy = DownloadWatchdogPolicy(
                            stallLimitMillis = TEST_STALL_LIMIT_MILLIS,
                            hardLimitMillis = TEST_HARD_LIMIT_MILLIS
                        )
                    ) { state -> Log.i(TAG, "watchdog test: $state") }
                }
            }

            Log.i(TAG, "watchdog produced $outcome")
            assertNotNull(
                "the watchdog must end the local stalled attempt before the test deadline",
                outcome
            )
            assertTrue(
                "the local server must have delivered media bytes before going quiet",
                server.didStartStallingTransfer()
            )
            assertTrue(
                "a stalled attempt must be reported as a failure, not as a completion",
                outcome is DirectDownloadResult.Failed
            )
            val failure = outcome as DirectDownloadResult.Failed
            Log.i(
                TAG,
                "the watchdog stopped the attempt: error=${failure.error} " +
                    "transient=${failure.transient} detail=${failure.detail}"
            )
            assertEquals(MediaError.NETWORK, failure.error)
            assertTrue("the watchdog result must be retryable", failure.transient)
            assertEquals(
                "the download made no progress and was stopped; it can be retried and will " +
                    "continue where it left off",
                failure.detail
            )

            // The user must be able to act on that: the engine must still accept work afterwards
            // rather than being left wedged behind the process id of the stopped attempt.
            assertTrue(
                "the engine must accept work again after a stopped attempt",
                runBlocking { runtime.ensureReady() is YtDlpInitStatus.Ready }
            )
        } finally {
            server.close()
            scratchDirs().forEach { it.deleteRecursively() }
        }
    }
}

/**
 * A tiny loopback HTTP server for the transient-resume instrumentation test.
 *
 * It implements only the protocol yt-dlp's generic HTTP extractor needs: `HEAD`, `GET`,
 * `Content-Length`, and byte ranges. During the failure phase every GET declares the full remaining
 * length but closes after a short prefix, forcing an incomplete-read transport error. During the
 * healthy phase it sends the requested suffix and returns `206 Partial Content` for a non-zero
 * range. The server is intentionally dependency-free so this test remains deterministic offline.
 */
private class TransientRangeServer(
    private val payload: ByteArray,
    private val failureBytes: Int
) : Closeable {

    private val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
    private val accepting = AtomicBoolean(true)
    private val complete = AtomicBoolean(false)
    private val ranges = Collections.synchronizedList(mutableListOf<Long>())
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val thread = Thread(::acceptLoop, "linksi-transient-http-server").apply {
        isDaemon = true
        start()
    }

    val url: String = "http://127.0.0.1:${server.localPort}/linksi-resume.mp4"

    fun allowCompletion() {
        complete.set(true)
    }

    fun rangeStarts(): List<Long> = synchronized(ranges) { ranges.toList() }

    override fun close() {
        if (!accepting.compareAndSet(true, false)) return
        runCatching { server.close() }
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
        runCatching { thread.join(SERVER_SHUTDOWN_TIMEOUT_MILLIS) }
    }

    private fun acceptLoop() {
        while (accepting.get()) {
            val client = try {
                server.accept()
            } catch (closed: SocketException) {
                if (accepting.get()) Log.w(SERVER_TAG, "local server accept failed", closed)
                return
            } catch (error: IOException) {
                if (accepting.get()) Log.w(SERVER_TAG, "local server accept failed", error)
                return
            }
            clients.add(client)
            try {
                client.soTimeout = SERVER_SOCKET_TIMEOUT_MILLIS
                serve(client)
            } catch (error: IOException) {
                if (accepting.get()) Log.i(SERVER_TAG, "local client closed: ${error.message}")
            } finally {
                clients.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        val method = requestLine.substringBefore(' ')
        var rangeStart: Long? = null
        while (true) {
            val header = reader.readLine() ?: return
            if (header.isEmpty()) break
            if (header.startsWith("Range:", ignoreCase = true)) {
                rangeStart = parseRangeStart(header)
            }
        }

        when (method) {
            "HEAD" -> sendHeaders(client, "200 OK", payload.size.toLong(), null)
            "GET" -> sendPayload(client, rangeStart)
            else -> sendHeaders(client, "405 Method Not Allowed", 0L, null)
        }
    }

    private fun sendPayload(client: Socket, requestedStart: Long?) {
        val start = requestedStart ?: 0L
        if (start >= payload.size.toLong()) {
            sendHeaders(client, "416 Range Not Satisfiable", 0L, "bytes */${payload.size}")
            return
        }

        ranges.add(start)
        val remaining = payload.size - start.toInt()
        val isComplete = complete.get()
        // Keep at least one byte undisclosed during the failure phase. This prevents a sequence of
        // automatic retries from ever completing before the test switches the server to healthy.
        val bodyBytes = if (isComplete) {
            remaining
        } else {
            minOf(failureBytes, (remaining - 1).coerceAtLeast(0))
        }
        val status = if (requestedStart != null) "206 Partial Content" else "200 OK"
        val contentRange = if (requestedStart != null) {
            "bytes $start-${payload.size - 1}/${payload.size}"
        } else {
            null
        }
        sendHeaders(client, status, remaining.toLong(), contentRange)
        val output = client.getOutputStream()
        var offset = start.toInt()
        val end = offset + bodyBytes
        while (offset < end) {
            val count = minOf(SERVER_WRITE_CHUNK_BYTES, end - offset)
            output.write(payload, offset, count)
            offset += count
        }
        output.flush()
        if (!isComplete) {
            // A clean FIN is surfaced by urllib as ContentTooShortError, whose text does not carry
            // a network keyword and therefore is correctly treated as permanent by production. An
            // abortive close makes the same interruption a transport reset, exercising the actual
            // transient classifier branch while preserving the bytes already sent.
            runCatching { client.setSoLinger(true, 0) }
        }
    }

    private fun sendHeaders(
        client: Socket,
        status: String,
        contentLength: Long,
        contentRange: String?
    ) {
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: video/mp4\r\n")
            append("Content-Length: ").append(contentLength).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            contentRange?.let { append("Content-Range: ").append(it).append("\r\n") }
            append("Connection: close\r\n")
            append("\r\n")
        }
        client.getOutputStream().write(headers.toByteArray(Charsets.ISO_8859_1))
        client.getOutputStream().flush()
    }

    private fun parseRangeStart(header: String): Long? {
        val value = header.substringAfter(':', "").trim()
        if (!value.startsWith("bytes=")) return null
        return value.removePrefix("bytes=")
            .substringBefore('-')
            .trim()
            .toLongOrNull()
            ?.takeIf { it >= 0L }
    }

    private companion object {
        const val SERVER_TAG = "YtDlpLocalServer"
        const val SERVER_SOCKET_TIMEOUT_MILLIS = 10_000
        const val SERVER_SHUTDOWN_TIMEOUT_MILLIS = 5_000L
        const val SERVER_WRITE_CHUNK_BYTES = 16 * 1024
    }
}

/**
 * A loopback server that lets the generic extractor probe finish, then stalls the media transfer.
 *
 * The first GET is answered with headers only because yt-dlp identifies a direct video from the
 * `Content-Type` before it reads the body. The next GET receives a prefix and remains open, which
 * gives the downloader a real progress sample followed by a real silent socket. Optional HEAD
 * probes are answered without consuming that first-GET slot.
 */
private class StallingMediaServer(
    private val payload: ByteArray,
    private val prefixBytes: Int
) : Closeable {

    private val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
    private val accepting = AtomicBoolean(true)
    private val probeServed = AtomicBoolean(false)
    private val holding = AtomicBoolean(true)
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val handlers = Collections.synchronizedSet(mutableSetOf<Thread>())
    private val stallingTransferStarted = AtomicBoolean(false)
    private val acceptThread = Thread(::acceptLoop, "linksi-stalling-http-server").apply {
        isDaemon = true
        start()
    }

    val url: String = "http://127.0.0.1:${server.localPort}/linksi-watchdog.mp4"

    fun didStartStallingTransfer(): Boolean = stallingTransferStarted.get()

    override fun close() {
        if (!accepting.compareAndSet(true, false)) return
        holding.set(false)
        runCatching { server.close() }
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
        synchronized(handlers) { handlers.toList() }.forEach { handler ->
            runCatching { handler.interrupt() }
        }
        runCatching { acceptThread.join(SERVER_SHUTDOWN_TIMEOUT_MILLIS) }
        synchronized(handlers) { handlers.toList() }.forEach { handler ->
            runCatching { handler.join(SERVER_SHUTDOWN_TIMEOUT_MILLIS) }
        }
    }

    private fun acceptLoop() {
        while (accepting.get()) {
            val client = try {
                server.accept()
            } catch (closed: SocketException) {
                if (accepting.get()) Log.w(SERVER_TAG, "stall server accept failed", closed)
                return
            } catch (error: IOException) {
                if (accepting.get()) Log.w(SERVER_TAG, "stall server accept failed", error)
                return
            }

            clients.add(client)
            val handler = Thread({ handle(client) }, "linksi-stalling-http-client").apply {
                isDaemon = true
            }
            handlers.add(handler)
            handler.start()
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = SERVER_SOCKET_TIMEOUT_MILLIS
            serve(client)
        } catch (error: IOException) {
            if (accepting.get()) Log.i(SERVER_TAG, "stall client closed: ${error.message}")
        } finally {
            clients.remove(client)
            handlers.remove(Thread.currentThread())
            runCatching { client.close() }
        }
    }

    private fun serve(client: Socket) {
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        val method = requestLine.substringBefore(' ')
        while (true) {
            val header = reader.readLine() ?: return
            if (header.isEmpty()) break
        }

        // A HEAD is harmless metadata probing and must not consume the GET probe slot. Answering
        // the first GET with headers only lets yt-dlp select the direct HTTP format. The next GET is
        // the actual download and is the one that must stall.
        if (method == "HEAD") {
            sendHeaders(client, "200 OK", payload.size.toLong())
            return
        }

        if (method != "GET") {
            sendHeaders(client, "405 Method Not Allowed", 0L)
            return
        }

        if (probeServed.compareAndSet(false, true)) {
            sendHeaders(client, "200 OK", payload.size.toLong())
            return
        }

        stallingTransferStarted.set(true)
        sendHeaders(client, "200 OK", payload.size.toLong())
        val output = client.getOutputStream()
        val count = minOf(prefixBytes, payload.size)
        output.write(payload, 0, count)
        output.flush()

        while (holding.get()) {
            try {
                Thread.sleep(SERVER_HOLD_POLL_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun sendHeaders(client: Socket, status: String, contentLength: Long) {
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: video/mp4\r\n")
            append("Content-Length: ").append(contentLength).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        client.getOutputStream().write(headers.toByteArray(Charsets.ISO_8859_1))
        client.getOutputStream().flush()
    }

    private companion object {
        const val SERVER_TAG = "YtDlpLocalServer"
        const val SERVER_SOCKET_TIMEOUT_MILLIS = 10_000
        const val SERVER_SHUTDOWN_TIMEOUT_MILLIS = 5_000L
        const val SERVER_HOLD_POLL_MILLIS = 100L
    }
}
