package com.linksi.app.enhanced.media

/**
 * Where a link points (specification sections 15 and 18).
 *
 * Deliberately a small, closed list of the sources the enhanced app targets, plus [OTHER] for
 * anything the generic extractor might still handle, and [UNKNOWN] for a URL that could not be
 * parsed at all. No Android dependency: this is pure JVM and unit testable.
 */
enum class MediaSource(val displayName: String) {
    INSTAGRAM("Instagram"),
    FACEBOOK("Facebook"),
    TIKTOK("TikTok"),
    PINTEREST("Pinterest"),
    REDDIT("Reddit"),
    YOUTUBE("YouTube"),
    OTHER("Other website"),
    DIRECT_FILE("Direct file"),
    UNKNOWN("Unknown")
}

/**
 * Maps a URL to a [MediaSource] by host.
 *
 * Matching is suffix based so that `m.facebook.com`, `www.facebook.com` and `web.facebook.com` all
 * resolve to [MediaSource.FACEBOOK] without listing every sub-domain. Pinterest is special cased
 * because it uses many country domains (`pinterest.co.uk`, `pinterest.de`, ...).
 *
 * The result never influences whether a link can be *saved* - only which optional actions are
 * offered (specification section 67).
 */
object MediaSourceDetector {

    private val EXACT_HOSTS: Map<String, MediaSource> = mapOf(
        "fb.watch" to MediaSource.FACEBOOK,
        "fb.com" to MediaSource.FACEBOOK,
        "fb.gg" to MediaSource.FACEBOOK,
        "pin.it" to MediaSource.PINTEREST,
        "instagr.am" to MediaSource.INSTAGRAM,
        "redd.it" to MediaSource.REDDIT,
        "youtu.be" to MediaSource.YOUTUBE,
        "vt.tiktok.com" to MediaSource.TIKTOK,
        "vm.tiktok.com" to MediaSource.TIKTOK
    )

    private val SUFFIX_HOSTS: List<Pair<String, MediaSource>> = listOf(
        "instagram.com" to MediaSource.INSTAGRAM,
        "cdninstagram.com" to MediaSource.INSTAGRAM,
        "facebook.com" to MediaSource.FACEBOOK,
        "tiktok.com" to MediaSource.TIKTOK,
        "reddit.com" to MediaSource.REDDIT,
        "redd.it" to MediaSource.REDDIT,
        "youtube.com" to MediaSource.YOUTUBE
    )

    private val PINTEREST_PREFIX = "pinterest."

    /** Extensions that mean "this URL is the file itself" rather than a page about a file. */
    private val DIRECT_FILE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "svg",
        "mp4", "m4v", "mov", "webm", "mkv", "avi",
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac",
        "pdf", "zip", "apk", "txt", "csv", "json", "epub"
    )

    fun fromUrl(url: String): MediaSource {
        val host = hostOf(url) ?: return MediaSource.UNKNOWN
        return fromHost(host, url)
    }

    fun fromHost(host: String, url: String = ""): MediaSource {
        val normalized = host.lowercase().removePrefix("www.")

        if (normalized.isEmpty()) return MediaSource.UNKNOWN

        EXACT_HOSTS[normalized]?.let { return it }

        if (normalized == PINTEREST_PREFIX.dropLast(1) || normalized.startsWith(PINTEREST_PREFIX) ||
            normalized.contains(".$PINTEREST_PREFIX")
        ) {
            return MediaSource.PINTEREST
        }

        for ((suffix, source) in SUFFIX_HOSTS) {
            if (normalized == suffix || normalized.endsWith(".$suffix")) return source
        }

        if (url.isNotEmpty() && looksLikeDirectFile(url)) return MediaSource.DIRECT_FILE

        return MediaSource.OTHER
    }

    /** True when the URL path ends in a known file extension. */
    fun looksLikeDirectFile(url: String): Boolean = extensionOf(url) != null

    /** The lowercase file extension of the URL path, or null when there is none. */
    fun extensionOf(url: String): String? {
        val withoutFragment = url.substringBefore('#')
        val withoutQuery = withoutFragment.substringBefore('?')
        val lastSegment = withoutQuery.substringAfterLast('/')
        if (!lastSegment.contains('.')) return null
        val extension = lastSegment.substringAfterLast('.').lowercase()
        if (extension.isEmpty() || extension.length > 5) return null
        return extension.takeIf { it in DIRECT_FILE_EXTENSIONS }
    }

    /** The host of [url], or null when it cannot be read. Never throws. */
    fun hostOf(url: String): String? {
        val trimmed = url.trim()
        val separator = trimmed.indexOf("://")
        if (separator <= 0) return null
        var rest = trimmed.substring(separator + 3)
        rest = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        rest = rest.substringAfterLast('@')
        if (rest.startsWith("[")) {
            val end = rest.indexOf(']')
            return if (end > 0) rest.substring(0, end + 1) else null
        }
        val host = rest.substringBefore(':')
        return host.ifEmpty { null }
    }

    /** Sources the specification names as primary download targets. */
    val PRIMARY_TARGETS: Set<MediaSource> =
        setOf(MediaSource.INSTAGRAM, MediaSource.FACEBOOK, MediaSource.TIKTOK, MediaSource.PINTEREST, MediaSource.REDDIT)
}
