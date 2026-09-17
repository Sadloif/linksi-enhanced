package com.linksi.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaSource
import com.linksi.app.enhanced.resolver.HttpMediaResolver
import com.linksi.app.enhanced.resolver.ServerResolverConfig
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The optional private server resolver's **privacy and failure contract**, over a real HTTPS
 * connection (specification sections 24, 25 and 26).
 *
 * Until now this module had no verification of any kind: it was described as "client done, inert
 * unless enabled and HTTPS", which asserts the configuration gate and nothing about what the client
 * actually sends. The specification's rules here are about *what leaves the device*, and the only way
 * to check those is to be the server that receives it:
 *
 *  - **Only the link goes.** The captured request body must be exactly `{"url": …}` — no clipboard
 *    contents, no accessibility content, no database.
 *  - **The API key is not in the URL.** It travels in an `Authorization` header, so it cannot appear
 *    in a proxy log or an access log. The assertion is explicit about that.
 *  - **A hostile or broken server degrades to a value.** A 500, a malformed body and a refused
 *    connection each have to come back as `SERVER_UNAVAILABLE` rather than an exception.
 *
 * A real TLS handshake is used rather than plain HTTP for two reasons: the resolver refuses anything
 * that is not `https://`, and OkHttp forbids sending an `Authorization` header over cleartext, so a
 * header test over HTTP would prove the opposite of what it claims.
 */
@RunWith(AndroidJUnit4::class)
class ServerResolverInstrumentedTest {

    private companion object {
        const val TAG = "ResolverTest"

        const val TARGET = "https://www.facebook.com/reel/1710485373378939/"

        /**
         * A minimal but structurally valid response the parser can map.
         *
         * `ok: true` is not decoration: the parser treats its absence as a refusal, so a response
         * without it is a failure by contract. The field names are the wire contract's own
         * (`id`, `ext`, `requires_muxing`), which is what a real private server has to emit.
         */
        val VALID_RESPONSE = """
            {"ok":true,"title":"Resolved clip","uploader":"someone","duration":42,
             "thumbnail":"https://cdn.example.com/t.jpg",
             "formats":[{"id":"mp4-720","label":"720p","ext":"mp4","height":720,"width":1280,
                         "fps":30,"vcodec":"avc1","acodec":"none","filesize":12345678,
                         "url":"https://cdn.example.com/v.mp4","audio_only":false,
                         "requires_muxing":true}]}
        """.trimIndent().toByteArray()
    }

    private var server: TlsResolverServer? = null

    @After
    fun tearDown() {
        server?.close()
        server = null
    }

    /** A client that accepts the server's self-signed certificate; production uses the system trust store. */
    private fun trustAllClient(config: ServerResolverConfig): OkHttpClient {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val context = SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(), trustAll, SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun resolverFor(config: ServerResolverConfig) =
        HttpMediaResolver(config, trustAllClient(config))

    @Test
    fun onlyTheLinkLeavesTheDeviceAndTheKeyTravelsInAHeader() {
        val tls = TlsResolverServer(response = VALID_RESPONSE, status = 200)
        server = tls
        tls.start()

        val config = ServerResolverConfig(
            enabled = true,
            baseUrl = tls.baseUrl,
            apiKey = "secret-token-value",
            timeoutSeconds = 10
        )
        val result = runBlocking { resolverFor(config).resolve(TARGET, MediaSource.FACEBOOK) }
        Log.i(TAG, "resolve -> $result")

        val request = tls.lastRequest()
        assertNotNull("the server must have received a request", request)
        val captured = request!!

        // The endpoint the specification documents.
        assertTrue("must POST to /resolve, was ${captured.requestLine}", captured.requestLine.contains("POST /resolve"))

        // Exactly one field, and it is the link. Parsed rather than string-matched, because JSON
        // escapes the slashes in a URL ("https:\/\/...") and a literal comparison would fail against a
        // perfectly correct body.
        val body = captured.body.trim()
        Log.i(TAG, "captured body: $body")
        val parsed = org.json.JSONObject(body)
        assertEquals("the body must carry exactly one field", 1, parsed.length())
        assertTrue("that field must be the url", parsed.has("url"))
        assertEquals("and it must be the link itself", TARGET, parsed.getString("url"))
        assertFalse("nothing about the clipboard may be sent", body.contains("clipboard", ignoreCase = true))
        assertFalse("nothing from the database may be sent", body.contains("database", ignoreCase = true))
        assertFalse("no saved links may be sent", body.contains("saved", ignoreCase = true))

        // The credential is a header, never the URL.
        assertTrue(
            "the API key must travel as a bearer header, headers were ${captured.headers}",
            captured.headers.any { it.startsWith("Authorization: Bearer secret-token-value") }
        )
        assertFalse(
            "the API key must never appear in the request target",
            captured.requestLine.contains("secret-token-value")
        )

        // And a well-formed answer is mapped rather than discarded.
        assertTrue("a valid response must parse, was $result", result is MediaExtractionResult.Success)
    }

    @Test
    fun aBrokenOrHostileServerDegradesToAValue() {
        val tls = TlsResolverServer(response = "not json at all".toByteArray(), status = 200)
        server = tls
        tls.start()
        val config = ServerResolverConfig(enabled = true, baseUrl = tls.baseUrl, timeoutSeconds = 10)

        val malformed = runBlocking { resolverFor(config).resolve(TARGET, MediaSource.FACEBOOK) }
        Log.i(TAG, "malformed body -> $malformed")
        assertTrue(
            "an unparseable body must be a failure value, not an exception: $malformed",
            malformed is MediaExtractionResult.Failure
        )

        tls.respondWith(status = 500, body = "server exploded".toByteArray())
        val serverError = runBlocking { resolverFor(config).resolve(TARGET, MediaSource.FACEBOOK) }
        Log.i(TAG, "HTTP 500 -> $serverError")
        assertTrue(serverError is MediaExtractionResult.Failure)
        assertEquals(
            MediaError.SERVER_UNAVAILABLE,
            (serverError as MediaExtractionResult.Failure).error
        )

        // A connection that cannot be made at all: the port is closed before the call.
        val dead = TlsResolverServer(response = VALID_RESPONSE, status = 200)
        val deadUrl = dead.baseUrl.also { dead.close() }
        val refused = runBlocking {
            resolverFor(ServerResolverConfig(enabled = true, baseUrl = deadUrl, timeoutSeconds = 3))
                .resolve(TARGET, MediaSource.FACEBOOK)
        }
        Log.i(TAG, "dead server -> $refused")
        assertTrue(
            "an unreachable server must be a value, not an exception: $refused",
            refused is MediaExtractionResult.Failure
        )
        assertEquals(
            MediaError.SERVER_UNAVAILABLE,
            (refused as MediaExtractionResult.Failure).error
        )
    }

    @Test
    fun plainHttpIsRefusedBeforeAnythingIsSent() {
        // The specification requires HTTPS. The gate must stop the request rather than send the link
        // in the clear and fail afterwards.
        val config = ServerResolverConfig(
            enabled = true,
            baseUrl = "http://127.0.0.1:9/",
            apiKey = "would-leak",
            timeoutSeconds = 5
        )
        assertFalse("a plain-HTTP endpoint is not usable", config.isUsable)
        assertNotNull("and the reason is explainable to the user", config.unusableReason)

        val result = runBlocking { resolverFor(config).resolve(TARGET, MediaSource.FACEBOOK) }
        Log.i(TAG, "plain http -> $result")
        assertTrue(
            "an unusable configuration must be skipped, not attempted: $result",
            result is MediaExtractionResult.Skipped
        )
    }
}

/** One captured HTTP request, kept as text so a test can assert on exactly what was sent. */
data class CapturedRequest(
    val requestLine: String,
    val headers: List<String>,
    val body: String
)

/**
 * The server side of the test connection: a self-signed key pair loaded from a test resource.
 *
 * The keystore is generated once with `keytool` (`CN=127.0.0.1`, SAN `ip:127.0.0.1`, `dns:localhost`,
 * password `linkstest`) and lives in `androidTest/resources`, so no certificate-generation code has to
 * exist in the test. It is a throwaway: nothing outside this test trusts it.
 */
internal object SelfSignedTls {

    private const val KEYSTORE_RESOURCE = "resolver-test.p12"
    private const val PASSWORD = "linkstest"

    fun serverContext(): SSLContext {
        val keystore = KeyStore.getInstance("PKCS12")
        val stream = requireNotNull(
            SelfSignedTls::class.java.classLoader?.getResourceAsStream(KEYSTORE_RESOURCE)
        ) { "the test keystore $KEYSTORE_RESOURCE is missing from the test resources" }
        stream.use { keystore.load(it, PASSWORD.toCharArray()) }

        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keystore, PASSWORD.toCharArray())

        return SSLContext.getInstance("TLS").apply {
            init(factory.keyManagers, null, SecureRandom())
        }
    }
}

/**
 * A single-shot HTTPS server that records the request it receives and answers with a scripted status
 * and body.
 *
 * TLS rather than plain HTTP because both facts under test depend on it: the resolver refuses
 * `http://`, and OkHttp refuses to send `Authorization` over cleartext. The certificate is generated
 * at runtime (`SelfSignedCertificate`-style, via the platform's own keystore) and the test client
 * trusts it explicitly.
 */
private class TlsResolverServer(
    private val response: ByteArray,
    private val status: Int
) : Closeable {

    private val context = SelfSignedTls.serverContext()
    private val server: SSLServerSocket = (
        context.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            as SSLServerSocket
        )
    private val accepting = AtomicBoolean(true)
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val captured = AtomicReference<CapturedRequest?>(null)

    @Volatile
    private var scriptedStatus = status

    @Volatile
    private var scriptedBody = response

    private val thread = Thread(::acceptLoop, "linksi-tls-resolver").apply { isDaemon = true }

    val baseUrl: String get() = "https://127.0.0.1:${server.localPort}"

    fun start() = thread.start()

    fun lastRequest(): CapturedRequest? = captured.get()

    fun respondWith(status: Int, body: ByteArray) {
        scriptedStatus = status
        scriptedBody = body
    }

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
                Log.w("TlsResolverServer", "accept failed", error)
                return
            }
            clients.add(client)
            try {
                client.soTimeout = 5_000
                serve(client)
            } catch (error: IOException) {
                Log.w("TlsResolverServer", "serving failed", error)
            } finally {
                clients.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream()
        val reader = input.bufferedReader()

        val requestLine = reader.readLine() ?: return
        val headers = mutableListOf<String>()
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headers.add(line)
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val body = if (contentLength > 0) {
            val buffer = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = reader.read(buffer, read, contentLength - read)
                if (count < 0) break
                read += count
            }
            String(buffer, 0, read)
        } else {
            ""
        }

        captured.set(CapturedRequest(requestLine.trim(), headers, body))

        val header = buildString {
            append("HTTP/1.1 ").append(scriptedStatus).append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(scriptedBody.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(header.toByteArray())
            write(scriptedBody)
            flush()
        }
    }
}
