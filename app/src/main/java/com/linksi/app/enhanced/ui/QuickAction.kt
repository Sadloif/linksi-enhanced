package com.linksi.app.enhanced.ui

import com.linksi.app.enhanced.media.MediaFormat
import com.linksi.app.enhanced.media.MediaInfo
import com.linksi.app.enhanced.media.MediaSource
import com.linksi.app.enhanced.media.MediaSourceDetector
import com.linksi.app.utils.UrlCleaner

/**
 * The quick action panel model (specification sections 9.5, 13, 14, 65 and 67).
 *
 * Deliberately Android free: everything in this file is plain JVM logic so the action matrix can
 * be pinned by unit tests without an emulator, and so the panel itself is inert to build.
 */
enum class QuickAction {
    CLEAN_URL,
    COPY_CLEAN_URL,
    SHARE_CLEAN_URL,
    SAVE,
    FOLDER,
    TAGS,
    NOTES,
    DOWNLOAD,
    OPEN,
    SHARE,
    COPY,
    ;

    /** True for the cleaning actions required by specification section 9.5. */
    val isCleaningAction: Boolean
        get() = this == CLEAN_URL || this == COPY_CLEAN_URL || this == SHARE_CLEAN_URL

    /** True for the single action that depends on a working downloader. */
    val isDownloadAction: Boolean get() = this == DOWNLOAD
}

/**
 * What the panel is allowed to offer, derived from the content type (specification section 14).
 *
 * [UNSUPPORTED] is the "nothing beyond saving the link" case: such a panel still offers the
 * cleaning, open, share and copy actions, because those can never fail, but it never offers a
 * download control (specification section 67).
 */
enum class QuickPanelContentType {
    ARTICLE,
    VIDEO,
    IMAGE,
    DIRECT_FILE,
    UNSUPPORTED,
    ;

    /** True when this content type may legitimately expose a download control. */
    val isDownloadable: Boolean
        get() = this == VIDEO || this == IMAGE || this == DIRECT_FILE
}

/**
 * Derives the panel content type (specification section 14).
 *
 * Precedence, most specific first:
 *
 *  1. an extracted format list means the link really is downloadable media, so it is [VIDEO]
 *     (audio-only formats are presented through the same panel section, with audio last);
 *  2. an image extension means [IMAGE];
 *  3. any other direct-file extension means [DIRECT_FILE];
 *  4. [MediaSource.DIRECT_FILE] still means [DIRECT_FILE] even before a format was resolved;
 *  5. a source the app knows is a normal page host means [ARTICLE];
 *  6. anything else, including [MediaSource.UNKNOWN] with no extension, is [UNSUPPORTED].
 *
 * A page URL whose extractor returned nothing therefore stays an [ARTICLE] and simply loses the
 * download action, which is exactly what specification section 67 asks for.
 */
fun deriveContentType(
    source: MediaSource,
    url: String = "",
    formats: List<MediaFormat> = emptyList()
): QuickPanelContentType {
    if (formats.isNotEmpty()) return QuickPanelContentType.VIDEO

    val extension = MediaSourceDetector.extensionOf(url)
    if (extension != null) {
        return if (extension in IMAGE_EXTENSIONS) {
            QuickPanelContentType.IMAGE
        } else {
            QuickPanelContentType.DIRECT_FILE
        }
    }

    // No downloadable content was resolved. A direct file stays a direct file (it simply has no
    // formats yet), everything else that is a plausible page is an article, and anything the
    // detector could not place at all is unsupported.
    if (source == MediaSource.DIRECT_FILE) return QuickPanelContentType.DIRECT_FILE
    if (source in PAGE_SOURCES) return QuickPanelContentType.ARTICLE

    return QuickPanelContentType.UNSUPPORTED
}

/**
 * Everything the panel renders. [availableFormats] keeps the order it was handed in; use
 * [displayFormats] for the order the UI must actually show (section 19: best first, audio last).
 */
data class QuickPanelState(
    val url: String,
    val cleanedUrl: String,
    val removedParameters: List<String>,
    val source: MediaSource,
    val contentType: QuickPanelContentType,
    val title: String,
    val availableFormats: List<MediaFormat>
) {
    /** True when [cleanedUrl] differs from [url]; the panel then offers its "Show original" toggle. */
    val cleaningChanged: Boolean get() = cleanedUrl != url

    /** How many tracking parameters were removed. */
    val removedCount: Int get() = removedParameters.size

    /** The host of the original URL, or an empty string when it cannot be read. */
    val domain: String get() = hostOf(url)
}

/**
 * The single source of truth for what the panel may show (specification section 14).
 *
 * The rule that must never regress (specification section 67): no Download control is ever shown
 * for content that cannot be downloaded. A [QuickPanelContentType.VIDEO] additionally needs at
 * least one resolved format, because there is a real choice of qualities to offer and a video URL
 * on its own is a page, not a file. An [QuickPanelContentType.IMAGE] or
 * [QuickPanelContentType.DIRECT_FILE] *is* the file, so its download row is always valid.
 */
object QuickActionModel {

    /**
     * The actions a normal page offers (specification section 14, article case). Also the set an
     * [QuickPanelContentType.UNSUPPORTED] link gets: every one of them works regardless of what the
     * link points at, and none of them can fail.
     */
    private val PAGE_ACTIONS: List<QuickAction> = listOf(
        QuickAction.SAVE,
        QuickAction.FOLDER,
        QuickAction.TAGS,
        QuickAction.NOTES,
        QuickAction.OPEN,
        QuickAction.SHARE,
        QuickAction.COPY,
        QuickAction.CLEAN_URL,
        QuickAction.COPY_CLEAN_URL,
        QuickAction.SHARE_CLEAN_URL
    )

    /**
     * The actions downloadable media gets before any format check. Organising actions
     * (folder/tags/notes) are deliberately absent: a video, an image or a PDF has no
     * folder/tag/note workflow in this panel.
     */
    private val MEDIA_ACTIONS: List<QuickAction> = listOf(
        QuickAction.SAVE,
        QuickAction.OPEN,
        QuickAction.SHARE,
        QuickAction.COPY,
        QuickAction.CLEAN_URL,
        QuickAction.COPY_CLEAN_URL,
        QuickAction.SHARE_CLEAN_URL
    )

    /** The media set plus the download row, for the content types that are the file itself. */
    private val DIRECT_MEDIA_ACTIONS: List<QuickAction> = MEDIA_ACTIONS + QuickAction.DOWNLOAD

    fun actionsFor(state: QuickPanelState): List<QuickAction> =
        baseActions(state.contentType) + videoDownloadActions(state)

    /** The actions that are valid for the content type, before any format availability check. */
    private fun baseActions(contentType: QuickPanelContentType): List<QuickAction> =
        when (contentType) {
            // Normal article: save, organise, annotate, then the URL actions.
            QuickPanelContentType.ARTICLE -> PAGE_ACTIONS

            // Video: the format list decides, see videoDownloadActions.
            QuickPanelContentType.VIDEO -> MEDIA_ACTIONS

            // Image: save link, download image, and the URL actions.
            QuickPanelContentType.IMAGE -> DIRECT_MEDIA_ACTIONS

            // PDF / direct file: save link, download file, and the URL actions.
            QuickPanelContentType.DIRECT_FILE -> DIRECT_MEDIA_ACTIONS

            // Unsupported: cleaning, open, share and copy still work, so the page set stays.
            QuickPanelContentType.UNSUPPORTED -> PAGE_ACTIONS
        }

    /**
     * Specification section 67: a video only gets a Download control when at least one format was
     * actually resolved. A downloadable video with an empty format list therefore offers none.
     */
    private fun videoDownloadActions(state: QuickPanelState): List<QuickAction> {
        if (state.contentType != QuickPanelContentType.VIDEO) return emptyList()
        if (state.availableFormats.isEmpty()) return emptyList()
        return listOf(QuickAction.DOWNLOAD)
    }
}

/**
 * Builds the panel state and runs the cleaner (specification sections 9.5 and 45).
 *
 * Failure safe by construction: when [UrlCleaner] rejects the URL the original string is kept
 * verbatim as both [QuickPanelState.url] and [QuickPanelState.cleanedUrl], with an empty removed
 * list, so a malformed link can never be lost or crash the panel.
 */
fun buildPanelState(
    rawUrl: String,
    source: MediaSource,
    formats: List<MediaFormat> = emptyList(),
    title: String = ""
): QuickPanelState {
    val result = UrlCleaner.clean(rawUrl)
    val cleaned = result as? UrlCleaner.Result.Cleaned
    val cleanedUrl = cleaned?.cleanedUrl ?: rawUrl
    val removed = cleaned?.removedParameters.orEmpty()

    return QuickPanelState(
        url = rawUrl,
        cleanedUrl = cleanedUrl,
        removedParameters = removed,
        source = source,
        contentType = deriveContentType(source, cleanedUrl, formats),
        title = title,
        availableFormats = formats
    )
}

/**
 * [QuickPanelState.availableFormats] in the order the panel must show them (specification section
 * 19), reusing [MediaInfo.displayFormats] so the panel and the rest of the app cannot drift:
 * video best first, audio only last, and "Original quality" when there is exactly one video format
 * and no separate audio track.
 */
fun QuickPanelState.displayFormats(): List<MediaFormat> =
    MediaInfo(
        webpageUrl = url,
        source = source,
        title = title,
        formats = availableFormats
    ).displayFormats()

/** Extensions that mean the link points at an image (specification section 14). */
private val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "svg")

/** Sources whose links are normal web pages rather than raw files. */
private val PAGE_SOURCES: Set<MediaSource> = setOf(
    MediaSource.INSTAGRAM,
    MediaSource.FACEBOOK,
    MediaSource.TIKTOK,
    MediaSource.PINTEREST,
    MediaSource.REDDIT,
    MediaSource.YOUTUBE,
    MediaSource.OTHER
)

/**
 * Best effort host of [url] for the panel header. Never throws and never returns null, so the UI
 * always has something to show even for a URL the cleaner rejected.
 */
internal fun hostOf(url: String): String {
    val trimmed = url.trim()
    val separator = trimmed.indexOf("://")
    if (separator <= 0) return ""
    var rest = trimmed.substring(separator + 3)
    rest = rest.substringBefore('/').substringBefore('?').substringBefore('#')
    rest = rest.substringAfterLast('@')
    if (rest.startsWith("[")) {
        val end = rest.indexOf(']')
        return if (end > 0) rest.substring(0, end + 1) else ""
    }
    return rest.substringBefore(':')
}
