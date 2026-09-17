package com.linksi.app

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linksi.app.enhanced.download.AppStorageSink
import com.linksi.app.enhanced.download.DirectDownloadRequest
import com.linksi.app.enhanced.download.DirectDownloadResult
import com.linksi.app.enhanced.download.DirectFileDownloader
import com.linksi.app.enhanced.download.DownloadSink
import com.linksi.app.enhanced.download.MediaStoreSink
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A **slow but healthy** transfer must not be mistaken for a stalled one.
 *
 * The download watchdog stops a transfer that reports no new bytes for its stall limit. The risk on
 * the other side of that rule is a false positive: a genuinely slow link that keeps trickling data
 * would be killed if the limit were measured wrongly or applied to the wrong phase. The unit tests pin
 * the arithmetic, but only a real transfer over a real socket shows that a throttled response is
 * treated as progress.
 *
 * The server below sends a large body in small, deliberately slow chunks. The total transfer takes far
 * longer than a short stall limit, yet every chunk resets the clock, so the download must complete
 * rather than be stopped.
 */
@RunWith(AndroidJUnit4::class)
class SlowTransferInstrumentedTest {
    private companion object {
        const val TAG = "SlowTransferTest"
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // The whole point: long read timeout, so a slow-but-alive socket is never cut off by OkHttp.
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private var server: TrickleServer? = null

    @After
    fun tearDown() {
        server?.close()
        server = null
    }

    private fun sink(): DownloadSink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreSink(context)
        else AppStorageSink(context)

    @Test
    fun aSlowButSteadyTransferIsCompletedNotStopped() {
        val trickle = TrickleServer()
        server = trickle
        trickle.start()

        val expectedBytes = SLOW_BODY_BYTES.toLong()
        val started = System.currentTimeMillis()
        val outcome = runBlocking {
            DirectFileDownloader(client).download(
                DirectDownloadRequest(
                    url = trickle.url,
                    displayName = "linksi-slow.bin",
                    expectedBytes = expectedBytes
                ),
                sink()
            ) {}
        }
        val elapsed = System.currentTimeMillis() - started
        Log.i(TAG, "slow transfer ($SLOW_BODY_BYTES bytes) -> $outcome in ${elapsed}ms")

        assertTrue(
            "a slow transfer must complete rather than be treated as stalled: $outcome",
            outcome is DirectDownloadResult.Completed
        )
        val completed = outcome as DirectDownloadResult.Completed
        assertEquals("every byte must arrive", expectedBytes, completed.bytes)
        assertEquals(
            "and be readable where the user was told",
            expectedBytes,
            readableBytes(completed.location)
        )
        assertTrue(
            "the transfer must really have been slow, or this proves nothing: ${elapsed}ms",
            elapsed > 2_000
        )
    }

    /** The length of a published location, the same way the sink's callers read it. */
    private fun readableBytes(location: String): Long {
        val uri = runCatching { Uri.parse(location) }.getOrNull() ?: return -1L
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return File(location).takeIf { it.isFile }?.length() ?: -1L
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                var total = 0L
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
        }.getOrNull() ?: -1L
    }
}

/**
 * Serves one fixed-size body in small chunks with a pause between them, so the transfer is slow but
 * never silent for long. Plain HTTP, which is why the debug variant permits loopback.
 */
private class TrickleServer : Closeable {
    private val body = ByteArray(SLOW_BODY_BYTES) { (it % 251).toByte() }

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val accepting = AtomicBoolean(true)
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val thread = Thread(::acceptLoop, "linksi-trickle-server").apply { isDaemon = true }

    val url: String get() = "http://127.0.0.1:${server.localPort}/big.bin"

    fun start() = thread.start()

    override fun close() {
        if (!accepting.compareAndSet(true, false)) return
        runCatching { server.close() }
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
        runCatching { thread.join(2_000) }
    }

    private fun acceptLoop() {
        while (accepting.get()) {
            val socket = try {
                server.accept()
            } catch (closed: SocketException) {
                return
            } catch (error: IOException) {
                Log.w("TrickleServer", "accept failed", error)
                return
            }
            clients.add(socket)
            try {
                socket.soTimeout = 5_000
                serve(socket)
            } catch (error: IOException) {
                Log.w("TrickleServer", "serve failed", error)
            } finally {
                clients.remove(socket)
                runCatching { socket.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        // Consume the request head so the client's write completes.
        val reader = socket.getInputStream().bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(header.toByteArray())
        out.flush()

        var offset = 0
        while (offset < body.size && accepting.get()) {
            val count = minOf(SLOW_CHUNK_BYTES, body.size - offset)
            out.write(body, offset, count)
            out.flush()
            offset += count
            Thread.sleep(SLOW_CHUNK_DELAY_MILLIS)
        }
        runCatching { socket.shutdownOutput() }
    }
}

/**
 * File-scope so the server class can see them too.
 *
 * 512 KiB in 4 KiB chunks with a 60 ms pause is a ~7.5 second trickle: long enough that a stall rule
 * measured wrongly would fire, short enough to keep the test quick.
 */
private const val SLOW_BODY_BYTES = 512 * 1024

private const val SLOW_CHUNK_BYTES = 4 * 1024

private const val SLOW_CHUNK_DELAY_MILLIS = 60L