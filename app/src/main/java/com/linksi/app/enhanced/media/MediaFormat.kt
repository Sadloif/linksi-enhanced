package com.linksi.app.enhanced.media

/**
 * One selectable download format (specification section 19).
 *
 * Nothing here is hard coded to 720p/1080p: the list always comes from what the source actually
 * offers, and an empty list means "no download available". Pure JVM, unit testable.
 */
data class MediaFormat(
    val id: String,
    val label: String,
    val extension: String,
    val height: Int? = null,
    val width: Int? = null,
    val fps: Double? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val fileSizeBytes: Long? = null,
    val isAudioOnly: Boolean = false,
    /** Direct media URL when the extractor could resolve one; null when a further step is needed. */
    val directUrl: String? = null,
    /** True when video and audio arrive as separate streams and must be merged (FFmpeg needed). */
    val requiresMuxing: Boolean = false
) {
    val isVideo: Boolean get() = !isAudioOnly

    /** Label to show in the UI, falling back to the resolution then to "Original quality". */
    val displayLabel: String
        get() = label.ifBlank {
            height?.let { "${it}p" } ?: if (isAudioOnly) "Audio only" else "Original quality"
        }

    val hasKnownSize: Boolean get() = fileSizeBytes != null && fileSizeBytes > 0
}

/** Everything known about a link's media (specification section 13's panel header plus formats). */
data class MediaInfo(
    val webpageUrl: String,
    val source: MediaSource,
    val title: String,
    val uploader: String? = null,
    val durationSeconds: Int? = null,
    val thumbnailUrl: String? = null,
    val formats: List<MediaFormat> = emptyList()
) {
    val hasFormats: Boolean get() = formats.isNotEmpty()

    val isSingleFormat: Boolean get() = formats.size == 1

    /**
     * Formats in the order the UI should offer them (specification section 19):
     * video best-first, then audio-only. When a source offers exactly one format and no separate
     * audio track, that single entry is presented as "Original quality" rather than inventing a
     * resolution the site never advertised.
     */
    fun displayFormats(): List<MediaFormat> {
        val videos = formats.filter { it.isVideo }
            .sortedWith(
                compareByDescending<MediaFormat> { it.height ?: 0 }
                    .thenByDescending { it.fps ?: 0.0 }
            )
        val audios = formats.filter { it.isAudioOnly }

        if (videos.size == 1 && audios.isEmpty()) {
            return listOf(videos.first().copy(label = "Original quality"))
        }
        return videos + audios
    }

    /** The highest quality video format, or the first audio format, or null. */
    fun bestFormat(): MediaFormat? =
        formats.filter { it.isVideo }.maxByOrNull { it.height ?: 0 } ?: formats.firstOrNull()
}

/**
 * Result of asking an extractor about a URL (specification sections 26 and 68).
 *
 * A failure is a *value*, never an exception escaping into the UI, so a broken extractor can only
 * ever disable the download action.
 */
sealed interface MediaExtractionResult {

    data class Success(val info: MediaInfo) : MediaExtractionResult

    /** The URL is not something this app can download (a normal article, an unsupported site). */
    data class Unsupported(val url: String, val source: MediaSource) : MediaExtractionResult {
        val error: MediaError get() = MediaError.UNSUPPORTED_SITE
    }

    data class Failure(
        val error: MediaError,
        val url: String,
        val cause: Throwable? = null
    ) : MediaExtractionResult

    /** No extractor was even attempted, for example when the engine is unavailable on this ABI. */
    data class Skipped(val url: String, val reason: String) : MediaExtractionResult {
        val error: MediaError get() = MediaError.ENGINE_UNAVAILABLE
    }
}

/**
 * User-facing failure reasons (specification section 68). Each maps to a string resource key so
 * the UI never shows a technical exception message; technical detail goes to the log instead.
 */
enum class MediaError(val messageKey: String) {
    UNSUPPORTED_SITE("error_download_unsupported"),
    PRIVATE_CONTENT("error_download_private"),
    MEDIA_GONE("error_download_gone"),
    LOGIN_REQUIRED("error_download_login_required"),
    NETWORK("error_download_network"),
    EXTRACTOR_FAILED("error_download_failed"),
    NO_FORMATS("error_download_no_formats"),
    NO_STORAGE("error_download_no_storage"),
    ENGINE_UNAVAILABLE("error_download_engine_unavailable"),
    SERVER_UNAVAILABLE("error_download_server_unavailable"),
    CANCELLED("error_download_cancelled");

    companion object {
        /**
         * Best-effort classification of an extractor's raw error text. Keeps site specific
         * wording out of the UI while still giving the user a useful message.
         */
        fun classify(message: String?): MediaError {
            val text = message?.lowercase().orEmpty()
            return when {
                text.isBlank() -> EXTRACTOR_FAILED
                "private" in text || "not authorized" in text -> PRIVATE_CONTENT
                "login" in text || "sign in" in text || "authentication" in text -> LOGIN_REQUIRED
                "unavailable" in text || "deleted" in text || "removed" in text || "404" in text -> MEDIA_GONE
                "timeout" in text || "timed out" in text || "unable to resolve host" in text ||
                    "connection" in text || "network" in text -> NETWORK
                "unsupported" in text || "no video" in text || "not supported" in text -> UNSUPPORTED_SITE
                else -> EXTRACTOR_FAILED
            }
        }
    }
}
