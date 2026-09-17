package com.linksi.app.enhanced.media.direct

import com.linksi.app.enhanced.capability.RuntimeCapabilities
import com.linksi.app.enhanced.download.ContentRange
import com.linksi.app.enhanced.download.DownloadErrorClassifier
import com.linksi.app.enhanced.download.FilenameSanitizer
import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaExtractor
import com.linksi.app.enhanced.media.MediaFormat
import com.linksi.app.enhanced.media.MediaInfo
import com.linksi.app.enhanced.media.MediaSource
import com.linksi.app.enhanced.media.MediaSourceDetector
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Handles URLs that *are* the file (specification section 16).
 *
 * One `HEAD` request (falling back to a one-byte ranged `GET` when the server refuses `HEAD` or
 * answers without a content type) is enough to describe the download: name, extension, size and
 * whether it is something a browser would render instead of saving. It is cheap, so it has a high
 * [priority] and runs before any heavier engine; it never downloads the body.
 *
 * Failure policy (specification sections 26 and 68): a value is always returned, never an
 * exception. A page (`text/html`) is [MediaExtractionResult.Unsupported]; a transport problem is a
 * [MediaExtractionResult.Failure] carrying [MediaError.NETWORK] so the caller can retry.
 *
 * The pure HTTP helpers it shares with the download engine ([ContentRange],
 * [DownloadErrorClassifier], [DirectFileClassifier], [ContentDisposition]) live in the download
 * package and are deliberately free of Android dependencies so both sides use one implementation.
 */
@Singleton
class DirectFileExtractor @Inject constructor(
    private val client: OkHttpClient
) : MediaExtractor {

    override val id: String = ID

    override val displayName: String = "Direct file"

    /**
     * Higher than any media engine: probing one URL is two cheap requests, and a successful probe
     * saves a full extraction. [ExtractorRegistry] sorts descending, so this is tried first.
     */
    override val priority: Int = PRIORITY

    /**
     * Any HTTP(S) URL can be a direct file, including a CDN URL with no extension at all, so this
     * extractor does not pre-filter on the host or the path. A URL that turns out to be a page
     * costs one `HEAD` and reports [MediaExtractionResult.Unsupported], which lets the registry
     * fall through to the heavier engines.
     */
    override fun supports(source: MediaSource, url: String): Boolean =
        DirectFileClassifier.isHttpUrl(url)

    /** No native library, no ABI requirement: always available. */
    override fun isAvailable(capabilities: RuntimeCapabilities): Boolean = true

    override suspend fun analyze(url: String, source: MediaSource): MediaExtractionResult {
        if (!DirectFileClassifier.isHttpUrl(url)) {
            return MediaExtractionResult.Unsupported(url, source)
        }

        return try {
            withContext(Dispatchers.IO) { describe(url, source) }
        } catch (io: IOException) {
            MediaExtractionResult.Failure(MediaError.NETWORK, url, io)
        } catch (illegal: IllegalArgumentException) {
            // A malformed URL: permanent, and not worth retrying.
            MediaExtractionResult.Failure(MediaError.EXTRACTOR_FAILED, url, illegal)
        }
    }

    private fun describe(url: String, source: MediaSource): MediaExtractionResult {
        val head = probe(url, useHead = true)
        // `HEAD` is optional in HTTP and plenty of CDNs answer 405, or answer 200 without a
        // content type. Only then is the one-byte ranged GET worth spending.
        val probed = if (head.isInformative) head else probe(url, useHead = false)
        val probe = if (probed.httpCode in SUCCESS_RANGE) probed else head

        if (probe.httpCode !in SUCCESS_RANGE) {
            return MediaExtractionResult.Failure(
                DownloadErrorClassifier.forHttpCode(probe.httpCode),
                url
            )
        }

        if (!DirectFileClassifier.isDownloadable(probe.contentType, url)) {
            // A web page, not a file: something else may still be able to extract media from it.
            return MediaExtractionResult.Unsupported(url, source)
        }

        val extension = DirectFileClassifier.extensionFor(probe.contentType, url)
        val displayName = FilenameSanitizer.sanitize(
            DirectFileClassifier.baseName(probe.contentDisposition, url),
            extension
        )

        val format = MediaFormat(
            id = FORMAT_ID,
            label = ORIGINAL_QUALITY,
            extension = extension,
            fileSizeBytes = probe.contentLength,
            isAudioOnly = probe.contentType?.trim()?.lowercase()?.startsWith("audio/") == true,
            directUrl = url,
            requiresMuxing = false
        )

        return MediaExtractionResult.Success(
            MediaInfo(
                webpageUrl = url,
                source = source,
                // For a direct file the "title" is the file name the user will see, extension
                // included, so a caller that has no suggested name can use it verbatim.
                title = displayName,
                formats = listOf(format)
            )
        )
    }

    private fun probe(url: String, useHead: Boolean): Probe {
        val builder = Request.Builder()
            .url(url)
            // Without this the server may gzip the body and then Content-Length describes the
            // compressed size while the download counts decompressed bytes.
            .header("Accept-Encoding", IDENTITY)

        if (useHead) {
            builder.head()
        } else {
            builder.get().header("Range", ONE_BYTE_RANGE)
        }

        val response = client.newCall(builder.build()).execute()
        return response.use {
            val contentRange = ContentRange.parse(it.header("Content-Range"))
            // The body of a HEAD response is always empty, so the header is the only truthful
            // source for its length.
            val headerLength = it.header("Content-Length")?.toLongOrNull()?.takeIf { length -> length >= 0 }
            val bodyLength = it.body?.contentLength()?.takeIf { length -> length > 0 }
            Probe(
                httpCode = it.code,
                contentType = it.header("Content-Type"),
                contentLength = contentRange?.total ?: headerLength ?: bodyLength,
                contentDisposition = it.header("Content-Disposition")
            )
        }
    }

    private data class Probe(
        val httpCode: Int,
        val contentType: String?,
        val contentLength: Long?,
        val contentDisposition: String?
    ) {
        /** True when this response alone can describe the file, so no GET fallback is needed. */
        val isInformative: Boolean
            get() = httpCode in SUCCESS_RANGE && !contentType.isNullOrBlank()
    }

    companion object {
        const val ID = "direct-file"

        const val PRIORITY = 100

        const val FORMAT_ID = "direct"

        const val ORIGINAL_QUALITY = "Original quality"

        private const val IDENTITY = "identity"

        private const val ONE_BYTE_RANGE = "bytes=0-0"
    }
}

/** HTTP status codes that mean "here is the resource". */
private val SUCCESS_RANGE = 200..299

/**
 * Content-type policy for direct downloads (specification section 16), pure JVM.
 *
 * The rule is a deny list, not an allow list: the app only refuses content the platform would
 * *render as a document* instead of saving, because a server is free to use a type nobody has
 * heard of for a perfectly good file. When no content type is sent at all, the URL's own extension
 * is the only remaining evidence.
 */
object DirectFileClassifier {

    /** Used when nothing - neither the content type nor the URL - suggests a real extension. */
    const val DEFAULT_EXTENSION = "bin"

    /**
     * `application/octet-stream` is the fallback subtype [FilenameSanitizer] would turn into
     * "octetstr". That is a poor file name, so a URL extension wins over it instead.
     */
    private const val OCTET_STREAM_EXTENSION = "octetstr"

    /** Types a browser renders as a document. Everything else is treated as a downloadable file. */
    private val PAGE_LIKE_TYPES = setOf(
        "text/html",
        "application/xhtml+xml",
        "text/xml",
        "application/xml",
        "application/rss+xml",
        "application/atom+xml",
        "text/css",
        "text/javascript",
        "application/javascript",
        "application/ecmascript"
    )

    /** `image/jpeg; charset=binary` becomes `image/jpeg`; blank or malformed input becomes null. */
    fun normalize(contentType: String?): String? = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() && it.contains('/') }

    fun isHttpUrl(url: String): Boolean {
        val trimmed = url.trim().lowercase()
        return trimmed.startsWith("http://") || trimmed.startsWith("https://")
    }

    fun isDownloadable(contentType: String?, url: String): Boolean {
        val mime = normalize(contentType) ?: return MediaSourceDetector.looksLikeDirectFile(url)
        return mime !in PAGE_LIKE_TYPES
    }

    /**
     * The extension to save [url] under. The content type wins because it describes the actual
     * bytes; the URL is only a hint. `application/octet-stream` is skipped in favour of the URL
     * because it carries no information at all.
     */
    fun extensionFor(contentType: String?, url: String): String {
        val fromMime = normalize(contentType)?.let { FilenameSanitizer.extensionForMimeType(it) }
        if (fromMime != null && fromMime != OCTET_STREAM_EXTENSION) return fromMime
        return MediaSourceDetector.extensionOf(url) ?: DEFAULT_EXTENSION
    }

    /** The base name (no extension) for [url]: the server's `Content-Disposition`, else the path. */
    fun baseName(contentDisposition: String?, url: String): String? =
        ContentDisposition.filename(contentDisposition)?.takeIf { it.isNotBlank() }
            ?: urlBaseName(url)

    /** The sanitised file name a direct download should be saved as. */
    fun displayName(contentDisposition: String?, url: String, contentType: String?): String =
        FilenameSanitizer.sanitize(baseName(contentDisposition, url), extensionFor(contentType, url))

    private fun urlBaseName(url: String): String? {
        val path = url.substringBefore('#').substringBefore('?')
        val last = path.substringAfterLast('/').trim()
        if (last.isEmpty()) return null
        // The extension is dropped because the caller decides it from the content type.
        return last.substringBeforeLast('.', last).trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * RFC 6266 `Content-Disposition` parsing (pure JVM).
 *
 * Servers are wildly inconsistent here, so the parser accepts everything seen in the wild:
 * quoted and unquoted names, a missing header, a header whose only parameter is `filename*`
 * (RFC 5987 `charset'language'percent-encoded`), and both spellings at once - in which case the
 * extended form wins, as the RFC requires.
 *
 * The result is never a path: directory components are stripped, because a name that can escape the
 * download folder is the one thing this parser must not produce. Final sanitising (illegal
 * characters, length) belongs to [FilenameSanitizer].
 */
object ContentDisposition {

    fun filename(header: String?): String? {
        if (header.isNullOrBlank()) return null

        val parameters = splitParameters(header)

        // RFC 6266 section 4.3: when both are present, filename* is the correct one.
        parameters.firstOrNull { it.first == EXTENDED }?.let { (_, value) ->
            decodeExtended(value)?.let { decoded -> return clean(decoded) }
        }

        val plain = parameters.firstOrNull { it.first == PLAIN }?.second ?: return null
        return clean(unquote(plain))
    }

    private const val PLAIN = "filename"
    private const val EXTENDED = "filename*"

    /** Splits `attachment; filename="a;b.mp4"` without breaking on the quoted semicolon. */
    private fun splitParameters(header: String): List<Pair<String, String>> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false

        for (character in header) {
            when {
                character == '"' -> {
                    quoted = !quoted
                    current.append(character)
                }

                character == ';' && !quoted -> {
                    chunks += current.toString()
                    current.clear()
                }

                else -> current.append(character)
            }
        }
        chunks += current.toString()

        return chunks.mapNotNull { chunk ->
            val name = chunk.substringBefore('=', "").trim().lowercase()
            if (name.isEmpty()) return@mapNotNull null
            name to chunk.substringAfter('=', "").trim()
        }
    }

    private fun unquote(value: String): String = value.trim().removeSurrounding("\"").trim()

    /**
     * `UTF-8''na%C3%AFve.mp4` -> `naïve.mp4`. Returns null when the value is not in the extended
     * form at all, so the caller can fall back to the plain parameter.
     */
    private fun decodeExtended(value: String): String? {
        val raw = unquote(value)
        val firstQuote = raw.indexOf('\'')
        if (firstQuote < 0) return null
        val secondQuote = raw.indexOf('\'', firstQuote + 1)
        if (secondQuote < 0) return null

        val charsetName = raw.substring(0, firstQuote).trim().ifEmpty { "UTF-8" }
        val encoded = raw.substring(secondQuote + 1)
        val bytes = percentDecode(encoded)

        return runCatching { String(bytes, charset(charsetName)) }
            .getOrElse { String(bytes, Charsets.UTF_8) }
    }

    /**
     * Percent decoding for RFC 5987 values. Unlike a query string, `+` is a literal plus, and an
     * invalid escape is copied through rather than throwing.
     */
    private fun percentDecode(input: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(input.length)
        var index = 0
        while (index < input.length) {
            val character = input[index]
            if (character == '%' && index + 2 < input.length) {
                val high = Character.digit(input[index + 1], 16)
                val low = Character.digit(input[index + 2], 16)
                if (high >= 0 && low >= 0) {
                    out.write((high shl 4) or low)
                    index += 3
                    continue
                }
            }
            out.write(character.toString().toByteArray(Charsets.UTF_8))
            index++
        }
        return out.toByteArray()
    }

    /** Strips quotes, whitespace and any directory component; null when nothing usable is left. */
    private fun clean(value: String): String? {
        val withoutDirectories = value
            .trim()
            .trim('"')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        return withoutDirectories.ifEmpty { null }
    }
}
