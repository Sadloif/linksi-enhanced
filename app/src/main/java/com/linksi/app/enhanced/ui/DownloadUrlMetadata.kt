package com.linksi.app.enhanced.ui

import com.linksi.app.enhanced.download.DownloadRequest
import com.linksi.app.enhanced.download.FilenameSanitizer
import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaFormat
import com.linksi.app.enhanced.media.MediaInfo
import com.linksi.app.enhanced.media.MediaSource
import com.linksi.app.enhanced.media.MediaSourceDetector
import com.linksi.app.enhanced.media.direct.DirectFileClassifier

/**
 * The little pieces of "what does this link look like as a download" logic the UI needs, kept in
 * one place and free of Android so they can be unit tested.
 */
object DownloadUrlMetadata {

    /** Upper bound on a suggested download file name, matching the engine's sanitiser budget. */
    private const val MAX_NAME_LENGTH = 120

    /**
     * A friendly file name for [url], extension included, or null when the URL's path is empty.
     *
     * It deliberately does **not** invent a name from the query string: a URL whose last path
     * segment carries no useful text simply gets no suggestion, and the engine falls back to the
     * server's own `Content-Disposition` or its extractor title.
     */
    fun fileName(url: String): String? {
        val last = lastPathSegment(url) ?: return null
        val decoded = runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        val base = decoded.substringBeforeLast('.', decoded).trim()
        val extension = MediaSourceDetector.extensionOf(url)

        // The base has to be worth keeping. A URL whose last path segment is a bare slug ("/watch",
        // "/reel/123") carries no file name, and the sanitiser would invent "linksi-download.bin";
        // handing the engine no suggestion at all is better, because the server's own
        // `Content-Disposition` or the extractor's title is the real answer.
        if (base.isEmpty() || base.none { it.isLetterOrDigit() }) return null

        return FilenameSanitizer.sanitize(base, extension)
            ?.take(MAX_NAME_LENGTH)
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * The last segment of the URL's **path**, never of its authority.
     *
     * Splitting on the first `/` after the scheme is what makes `https://example.com` produce
     * nothing rather than the host name "example.com", which would otherwise look like a perfectly
     * good file name for a page that has none.
     */
    private fun lastPathSegment(url: String): String? {
        val trimmed = url.trim()
        val scheme = trimmed.indexOf("://")
        val afterScheme = if (scheme < 0) trimmed else trimmed.substring(scheme + 3)
        val path = afterScheme.substringAfter('/', missingDelimiterValue = "")
        val withoutQuery = path.substringBefore('#').substringBefore('?')
        return withoutQuery.substringAfterLast('/').trim().ifEmpty { null }
    }

    /** The MIME type of a finished download, derived from its file name's extension. */
    fun mimeTypeOf(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MIME_TYPES[extension]
    }

    /**
     * The handful of types this app can actually produce. Anything else reports null, which the
     * downloads screen renders as "type unknown" rather than guessing.
     */
    private val MIME_TYPES: Map<String, String> = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "avif" to "image/avif",
        "svg" to "image/svg+xml",
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "json" to "application/json"
    )

    /** The source to label a request with, from the URL alone. */
    fun sourceOf(url: String): MediaSource = MediaSourceDetector.fromUrl(url)

    /**
     * The [DownloadRequest] for a link the user tapped.
     *
     * @param formatId the quality the user chose; empty for an image or direct file, whose format
     *   list has exactly one entry that *is* the file.
     */
    fun requestFor(
        url: String,
        formatId: String,
        source: MediaSource = sourceOf(url),
        destination: com.linksi.app.enhanced.download.DownloadDestination =
            com.linksi.app.enhanced.download.DownloadDestination.PUBLIC_DOWNLOADS
    ): DownloadRequest = DownloadRequest(
        id = DownloadRequestIds.forUrl(url, formatId),
        url = url,
        source = source,
        formatId = formatId.takeIf { it.isNotBlank() },
        suggestedFileName = fileName(url),
        destination = destination
    )

    /**
     * True when a successful analysis actually produced something downloadable.
     *
     * This is the "don't offer a download the engine cannot perform" guard: an unsupported page, a
     * private post or a transport failure all leave the panel without a download row, and the panel
     * keeps every other action.
     */
    fun isDownloadable(info: MediaInfo): Boolean = info.formats.isNotEmpty()

    /** The best format of [info], or null when the extractor resolved none. */
    fun bestFormat(info: MediaInfo): MediaFormat? = info.bestFormat()

    /**
     * Turns an analysis failure into the message the panel shows. Never throws, and never returns
     * null: [MediaError.ENGINE_UNAVAILABLE] and [MediaError.UNSUPPORTED_SITE] get their own
     * resources like any other reason (specification section 26).
     */
    fun failureMessage(error: MediaError): Int = DownloadUiText.errorRes(error)

    /** True when [url] is something the direct-file path can even consider. */
    fun isHttpUrl(url: String): Boolean = DirectFileClassifier.isHttpUrl(url)
}
