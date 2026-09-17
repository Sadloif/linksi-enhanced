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
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaSource
import com.linksi.app.enhanced.media.direct.DirectFileExtractor
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The direct file downloader across the file types the specification names (section 22), over a
 * **local server** rather than the internet.
 *
 * Why a loopback server: the direct path's job is to read a URL whose bytes really are a file, save
 * them under the right name and extension, and publish them where the user looks. Until now all of
 * that had been proven against a single small PNG on `google.com`, which cannot distinguish "handles
 * images" from "handles anything". Here each type is served locally with an honest `Content-Type`, so
 * the test controls the bytes and the extension is a real assertion rather than a guess.
 *
 * The last case is the inverse, and it is the one that matters most: a server that labels its body
 * `text/html` must be refused by the extractor, which is what makes the site engine get a turn
 * instead of a web page landing in Downloads (addendum 9).
 */
@RunWith(AndroidJUnit4::class)
class DirectFileDownloaderInstrumentedTest {

    private companion object {
        const val TAG = "DirectFileTest"
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Both classes take an `OkHttpClient` under Hilt, so the test builds one.
     *
     * Generous read timeout: the server answers immediately, but a slow emulator should not turn a
     * local loopback transfer into a spurious network failure.
     */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val downloader by lazy { DirectFileDownloader(client) }

    private val extractor by lazy { DirectFileExtractor(client) }

    private var server: TypedFileServer? = null

    @After
    fun tearDown() {
        server?.close()
        server = null
    }

    private fun sink(): DownloadSink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreSink(context)
        else AppStorageSink(context)

    @Test
    fun eachFileTypeIsClassifiedNamedAndDownloaded() {
        val typed = TypedFileServer()
        server = typed
        typed.start()

        // The server owns the bytes, so the expected size is read back from it rather than duplicated
        // here: a copy of the payload in the test could drift from what is actually served.
        val cases = listOf(
            Triple("/photo.png", "image/png", "png"),
            Triple("/document.pdf", "application/pdf", "pdf"),
            Triple("/archive.zip", "application/zip", "zip"),
            Triple("/audio.mp3", "audio/mpeg", "mp3")
        )

        for ((path, contentType, extension) in cases) {
            val payload = typed.bodyFor(path)
            val url = typed.urlFor(path)

            // The extractor must call it a file, and derive the right extension from the header.
            val analysis = runBlocking { extractor.analyze(url, MediaSource.OTHER) }
            assertTrue(
                "$contentType must be recognised as a downloadable file, but was $analysis",
                analysis is MediaExtractionResult.Success
            )
            val format = (analysis as MediaExtractionResult.Success).info.formats.first()
            assertEquals("extension for $contentType", extension, format.extension)

            val displayName = "linksi-typed-$extension"
            val outcome = runBlocking {
                downloader.download(
                    DirectDownloadRequest(
                        url = url,
                        displayName = "$displayName.$extension",
                        expectedBytes = payload.size.toLong()
                    ),
                    sink()
                ) {}
            }

            Log.i(TAG, "$contentType -> $outcome")
            assertTrue("$contentType must download, but was $outcome", outcome is DirectDownloadResult.Completed)
            val completed = outcome as DirectDownloadResult.Completed
            assertEquals("$contentType byte count", payload.size.toLong(), completed.bytes)
            assertEquals(
                "$contentType must be readable where the user was told it is",
                payload.size.toLong(),
                readableBytes(completed.location)
            )
        }
    }

    @Test
    fun aPageAnnouncedAsHtmlIsRefusedRatherThanSaved() {
        val typed = TypedFileServer()
        server = typed
        typed.start()

        val url = typed.urlFor("/page.html")
        val analysis = runBlocking { extractor.analyze(url, MediaSource.OTHER) }

        Log.i(TAG, "text/html -> $analysis")
        assertTrue(
            "a page must not be treated as a file, or it would be handed to the downloader: $analysis",
            analysis is MediaExtractionResult.Unsupported
        )
    }

    /** The length of what a published location resolves to, following the same rule the sink used. */
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
 * A loopback HTTP server that serves a few fixed bodies with honest content types.
 *
 * Deliberately tiny: it understands one request line, ignores the method, and always answers with a
 * `Content-Length` and `Connection: close`, which is everything the downloader's probe and transfer
 * need. It exists so a test can control the bytes and the header independently of any real site.
 */
private class TypedFileServer : Closeable {

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val accepting = AtomicBoolean(true)
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val thread = Thread(::acceptLoop, "linksi-typed-file-server").apply {
        isDaemon = true
    }

    private val bodies: Map<String, Pair<String, ByteArray>> = mapOf(
        "/photo.png" to ("image/png" to
            (byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) +
                ByteArray(512) { (it % 251).toByte() })),
        "/document.pdf" to ("application/pdf" to
            ("%PDF-1.7\n%".toByteArray() + ByteArray(400) { (it % 241).toByte() })),
        "/archive.zip" to ("application/zip" to
            (byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(300) { (it % 233).toByte() })),
        "/audio.mp3" to ("audio/mpeg" to
            ("ID3\u0003\u0000".toByteArray() + ByteArray(350) { (it % 239).toByte() })),
        "/page.html" to ("text/html" to
            "<!DOCTYPE html><html><body>not media</body></html>".toByteArray())
    )

    fun start() {
        thread.start()
    }

    fun urlFor(path: String): String = "http://127.0.0.1:${server.localPort}$path"

    /** The exact bytes this server will send for [path]; throws for an unknown path. */
    fun bodyFor(path: String): ByteArray =
        bodies[path]?.second ?: error("the server has no body for $path")

    override fun close() {
        if (!accepting.compareAndSet(true, false)) return
        runCatching { server.close() }
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
        runCatching { thread.join(2_000) }
    }

    private fun acceptLoop() {
        while (accepting.get()) {
            val client = try {
                server.accept()
            } catch (closed: SocketException) {
                return
            } catch (error: IOException) {
                Log.w("TypedFileServer", "accept failed", error)
                return
            }
            clients.add(client)
            try {
                client.soTimeout = 5_000
                serve(client)
            } catch (error: IOException) {
                Log.w("TypedFileServer", "serving failed", error)
            } finally {
                clients.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        val request = client.getInputStream().bufferedReader().readLine() ?: return
        val path = request.split(' ').getOrNull(1)?.substringBefore('?') ?: return
        val (contentType, body) = bodies[path] ?: ("text/plain" to "not found".toByteArray())

        val header = buildString {
            append(if (bodies.containsKey(path)) "HTTP/1.1 200 OK" else "HTTP/1.1 404 Not Found").append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(header.toByteArray())
            write(body)
            flush()
        }
    }
}
