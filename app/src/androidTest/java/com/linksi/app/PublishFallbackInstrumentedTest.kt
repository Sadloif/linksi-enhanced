package com.linksi.app

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The publish fallback that reads the **filesystem**, exercised deliberately.
 *
 * Publishing a finished download is a move MediaStore performs on the caller's behalf, and it has
 * failed four distinct ways in this project — a stale row, a renamed file, a constraint rejection with
 * the correct row present, and a phantom row for a file that no longer exists. Two of those produced a
 * *false failure* (the user is told it failed while the file is in Downloads) and the fourth produced a
 * *false success* (a location pointing at nothing).
 *
 * The guard added for that is easy to reach in production and hard to reach on purpose, so this test
 * constructs the state that triggers it: a visible MediaStore entry already claims the name, which
 * makes the publish collide, while nothing is actually published. The downloader must then report the
 * completed file from disk rather than failing.
 */
@RunWith(AndroidJUnit4::class)
class PublishFallbackInstrumentedTest {

    private companion object {
        const val TAG = "PublishFallbackTest"

        /** Small and quick: this test is about the publish step, not throughput. */
        const val BODY_BYTES = 64 * 1024

        const val DISPLAY_NAME = "linksi-fallback-probe.mp4"
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private var server: SmallFileServer? = null
    private var blocker: Uri? = null

    @After
    fun tearDown() {
        server?.close()
        server = null
        // Leave the collection as it was found, or the next run inherits the contrived state.
        blocker?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        blocker = null
        downloadsFile().let { if (it.isFile) runCatching { it.delete() } }
    }

    private fun downloadsFile(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DISPLAY_NAME)

    @Test
    fun aFinishedFileIsReportedFromDiskWhenTheCollectionRefusesToPublishIt() {
        assumeTrue(
            "this tests the MediaStore sink, which is the API 29+ path",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        )

        // 1. Remove any leftover file of this name, so the only thing in the way is the entry below.
        downloadsFile().let { if (it.isFile) runCatching { it.delete() } }

        // 2. Insert a visible entry claiming the name. The downloader's own insert will then be given a
        //    de-duplicated name, and promoting it to its requested name collides on `files._data`.
        blocker = runCatching {
            context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, DISPLAY_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/")
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
            )
        }.getOrNull()
        Log.i(TAG, "blocking entry: $blocker")
        assumeTrue(
            "this device refused a MediaStore entry, so the collision cannot be set up",
            blocker != null
        )

        val server = SmallFileServer(BODY_BYTES)
        this.server = server
        server.start()

        val sink: DownloadSink = MediaStoreSink(context)
        val outcome = runBlocking {
            DirectFileDownloader(client).download(
                DirectDownloadRequest(
                    url = server.url,
                    displayName = DISPLAY_NAME,
                    expectedBytes = BODY_BYTES.toLong()
                ),
                sink
            ) {}
        }
        Log.i(TAG, "outcome: $outcome")
        Log.i(TAG, "file on disk: ${downloadsFile().let { "${it.absolutePath} exists=${it.isFile} len=${it.length()}" }}")

        assertTrue(
            "a download whose bytes are complete must not be reported as a failure: $outcome",
            outcome is DirectDownloadResult.Completed
        )
        val completed = outcome as DirectDownloadResult.Completed
        assertEquals("every byte must be reported", BODY_BYTES.toLong(), completed.bytes)

        // The reported location must be somewhere the bytes actually are.
        val reported = completed.location
        val readable = if (reported.startsWith("content://")) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(reported))?.use { stream ->
                    var total = 0L
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                    }
                    total
                }
            }.getOrNull() ?: -1L
        } else {
            File(reported).takeIf { it.isFile }?.length() ?: -1L
        }
        assertEquals(
            "the reported location must resolve to the bytes that were written",
            BODY_BYTES.toLong(),
            readable
        )
    }

    @Test
    fun sameSizeRowsAndHigherCollisionSuffixesCannotClaimAnotherOperation() {
        assumeTrue(
            "this tests the MediaStore sink, which is the API 29+ path",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        )

        val blockers = mutableListOf<Uri>()
        val unrelated = ByteArray(BODY_BYTES) { 0x5a }
        repeat(2) {
            insertPublishedRow(DISPLAY_NAME, unrelated)?.let { blockers += it }
        }
        assumeTrue(
            "this device refused enough rows to exercise a suffix beyond (1)",
            blockers.size == 2
        )

        val server = SmallFileServer(BODY_BYTES)
        this.server = server
        server.start()

        try {
            val outcome = runBlocking {
                DirectFileDownloader(client).download(
                    DirectDownloadRequest(
                        url = server.url,
                        displayName = DISPLAY_NAME,
                        expectedBytes = BODY_BYTES.toLong()
                    ),
                    MediaStoreSink(context)
                ) {}
            }

            assertTrue("the current download should still complete: $outcome", outcome is DirectDownloadResult.Completed)
            val completed = outcome as DirectDownloadResult.Completed
            assertTrue("completion must identify a MediaStore row", completed.location.startsWith("content://"))
            assertFalse(
                "completion must not claim either pre-existing row",
                blockers.any { it.toString() == completed.location }
            )
            assertArrayEquals(
                "the reported row must contain this operation's bytes, not a same-size blocker",
                server.bodyCopy(),
                readableContent(completed.location)
            )
        } finally {
            blockers.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        }
    }

    private fun insertPublishedRow(displayName: String, bytes: ByteArray): Uri? {
        val resolver = context.contentResolver
        val uri = runCatching {
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            )
        }.getOrNull() ?: return null

        val published = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no output stream")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            ) > 0
        }.getOrDefault(false)
        if (!published) runCatching { resolver.delete(uri, null, null) }
        return uri.takeIf { published }
    }

    private fun readableContent(location: String): ByteArray =
        context.contentResolver.openInputStream(Uri.parse(location))?.use { it.readBytes() }
            ?: error("the published URI was not readable: $location")
}

/** Serves one small body at a fixed path. Plain HTTP, which the debug variant permits for loopback. */
private class SmallFileServer(private val bytes: Int) : Closeable {

    private val body = ByteArray(bytes) { (it % 251).toByte() }

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val accepting = AtomicBoolean(true)
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val thread = Thread(::acceptLoop, "linksi-small-file-server").apply { isDaemon = true }

    val url: String get() = "http://127.0.0.1:${server.localPort}/probe.mp4"

    fun bodyCopy(): ByteArray = body.copyOf()

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
                Log.w("SmallFileServer", "accept failed", error)
                return
            }
            clients.add(socket)
            try {
                socket.soTimeout = 5_000
                serve(socket)
            } catch (error: IOException) {
                Log.w("SmallFileServer", "serve failed", error)
            } finally {
                clients.remove(socket)
                runCatching { socket.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: video/mp4\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(header.toByteArray())
            write(body)
            flush()
        }
    }
}
