package com.linksi.app.enhanced.ui

import com.linksi.app.enhanced.media.MediaFormat
import com.linksi.app.enhanced.media.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for the quick action panel model (specification sections 9.5, 13, 14, 65 and 67).
 *
 * Everything here is pure JVM logic: no Android dependency, no Compose, so the action matrix and
 * the cleaner integration can be pinned without an emulator.
 *
 * The single most important invariant is specification section 67: **never show a download control
 * for something that is not downloadable**, including the case of a downloadable content type whose
 * extractor resolved no formats at all.
 */
class QuickActionTest {

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /** A video format with a resolution. */
    private fun video(id: String, height: Int? = 720, fps: Double? = 30.0) = MediaFormat(
        id = id,
        label = "",
        extension = "mp4",
        height = height,
        fps = fps,
        isAudioOnly = false
    )

    /** An audio-only format. */
    private fun audio(id: String) = MediaFormat(
        id = id,
        label = "",
        extension = "m4a",
        isAudioOnly = true
    )

    /** Actions for a state, built through [buildPanelState] so the two never disagree. */
    private fun actions(
        url: String,
        source: MediaSource,
        formats: List<MediaFormat> = emptyList()
    ): List<QuickAction> = QuickActionModel.actionsFor(
        buildPanelState(url, source, formats)
    )

    private fun stateFor(
        url: String,
        source: MediaSource,
        formats: List<MediaFormat> = emptyList(),
        title: String = ""
    ) = buildPanelState(url, source, formats, title)

    /** The action set a page (article, or anything unsupported) must offer, in panel order. */
    private val pageActions = listOf(
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
     * The action set downloadable media must offer. Organising actions (folder/tags/notes) are
     * deliberately absent because the panel does not claim a workflow the media case does not have.
     */
    private val mediaActions = listOf(
        QuickAction.SAVE,
        QuickAction.OPEN,
        QuickAction.SHARE,
        QuickAction.COPY,
        QuickAction.CLEAN_URL,
        QuickAction.COPY_CLEAN_URL,
        QuickAction.SHARE_CLEAN_URL
    )

    // ── Section 9.5: the mandatory cleaning actions ─────────────────────────────

    @Test
    fun cleaningActionsAreAlwaysOffered() {
        for (source in MediaSource.entries) {
            val result = actions("https://example.com/a?utm_source=x", source)
            assertTrue(
                "$source must still offer the section 9.5 cleaning actions",
                result.containsAll(
                    listOf(
                        QuickAction.CLEAN_URL,
                        QuickAction.COPY_CLEAN_URL,
                        QuickAction.SHARE_CLEAN_URL
                    )
                )
            )
        }
    }

    @Test
    fun malformedInputStillGetsTheCleaningActions() {
        // The least capable case the panel handles: an unknown host with no extension.
        val result = actions("not a url", MediaSource.UNKNOWN)
        assertTrue(result.contains(QuickAction.CLEAN_URL))
        assertTrue(result.contains(QuickAction.COPY_CLEAN_URL))
        assertTrue(result.contains(QuickAction.SHARE_CLEAN_URL))
        assertTrue(result.contains(QuickAction.SAVE))
    }

    // ── Section 14: one case per content type ──────────────────────────────────

    @Test
    fun articleOffersSaveFolderTagsNotesOpenShareCopyAndCleaning() {
        val result = actions("https://example.com/article?id=42", MediaSource.OTHER)

        assertEquals(
            listOf(
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
            ),
            result
        )
        assertFalse("an article is never downloadable", QuickAction.DOWNLOAD in result)
    }

    @Test
    fun videoAddsDownloadWhenAtLeastOneFormatExists() {
        val formats = listOf(video("1080", height = 1080), video("720", height = 720))
        val result = actions("https://www.instagram.com/reel/123/", MediaSource.INSTAGRAM, formats)

        assertEquals(
            QuickPanelContentType.VIDEO,
            stateFor("https://www.instagram.com/reel/123/", MediaSource.INSTAGRAM, formats).contentType
        )
        assertTrue(QuickAction.DOWNLOAD in result)
        assertEquals(mediaActions + QuickAction.DOWNLOAD, result)
        // Downloadable media does not pretend to have a folder/tag/note workflow.
        assertFalse(QuickAction.FOLDER in result)
        assertFalse(QuickAction.TAGS in result)
        assertFalse(QuickAction.NOTES in result)
    }

    @Test
    fun videoWithNoFormatsShowsNoDownload() {
        // The extractor failed or the module is off: the panel must degrade, not lie (section 67).
        val result = actions("https://www.instagram.com/reel/123/", MediaSource.INSTAGRAM)

        assertFalse("a video with no resolved formats must not offer a download", QuickAction.DOWNLOAD in result)
        // With no formats the link is treated as what it still is - a page with a saveable link -
        // so it keeps the page actions, and the download section simply never appears.
        assertEquals(pageActions, result)
    }

    @Test
    fun imageOffersSaveLinkAndDownloadImage() {
        // An image URL *is* the file, so its download row needs no format list.
        val state = stateFor("https://example.com/photo.JPG", MediaSource.OTHER)
        val result = QuickActionModel.actionsFor(state)

        assertEquals(QuickPanelContentType.IMAGE, state.contentType)
        assertTrue("an image must offer a download", QuickAction.DOWNLOAD in result)
        assertEquals(mediaActions + QuickAction.DOWNLOAD, result)
    }

    @Test
    fun directFileOffersDownloadFile() {
        // A PDF or any other direct file is the file itself, so it needs no format list either.
        val state = stateFor("https://example.com/report.pdf", MediaSource.OTHER)
        val result = QuickActionModel.actionsFor(state)

        assertEquals(QuickPanelContentType.DIRECT_FILE, state.contentType)
        assertTrue("a direct file must offer a download", QuickAction.DOWNLOAD in result)
        assertEquals(mediaActions + QuickAction.DOWNLOAD, result)
    }

    @Test
    fun unsupportedShowsNoDownloadAndNoExtraActions() {
        val result = actions("not a url", MediaSource.UNKNOWN)

        assertFalse(QuickAction.DOWNLOAD in result)
        assertEquals(pageActions, result)
    }

    @Test
    fun contentWithoutFormatsOnlyShowsDownloadWhenTheUrlIsTheFile() {
        // Section 67: an article is never downloadable, and a video with no resolved format list
        // has nothing to offer. Image and direct-file URLs are the exceptions on purpose: the URL
        // *is* the file there, so their download row is valid with no format list at all.
        val urls = listOf(
            "https://example.com/article",
            "https://www.instagram.com/reel/123/",
            "https://example.com/view?next=clip.mp4",
            "not a url",
            "",
            "https://example.com/index.html",
            "https://example.com/photo.png",
            "https://example.com/report.pdf"
        )

        for (url in urls) {
            val result = actions(url, MediaSource.OTHER)
            if (QuickAction.DOWNLOAD !in result) continue

            val state = stateFor(url, MediaSource.OTHER)
            assertTrue(
                "'$url' offered a download but is a ${state.contentType}",
                state.contentType == QuickPanelContentType.IMAGE ||
                    state.contentType == QuickPanelContentType.DIRECT_FILE
            )
        }
    }

    @Test
    fun aVideoNeverShowsADownloadWithoutAtLeastOneFormat() {
        for (formats in listOf(emptyList(), listOf(video("only")))) {
            val state = stateFor("https://www.instagram.com/reel/123/", MediaSource.INSTAGRAM, formats)
            val result = QuickActionModel.actionsFor(state)

            if (state.contentType == QuickPanelContentType.VIDEO && formats.isEmpty()) {
                fail("a video content type must be derived with formats, not without")
            }
            assertEquals(
                "formats=$formats",
                QuickAction.DOWNLOAD in result,
                formats.isNotEmpty()
            )
        }
    }

    @Test
    fun contentWithoutFormatsIsNeverDerivedAsVideo() {
        for (url in listOf("https://example.com/a", "not a url", "")) {
            for (source in MediaSource.entries) {
                assertNotEquals(
                    "$source / '$url' must not derive VIDEO without formats",
                    QuickPanelContentType.VIDEO,
                    deriveContentType(source, url, emptyList())
                )
            }
        }
    }

    @Test
    fun downloadIsNeverOfferedTwice() {
        val result = actions(
            "https://cdn.example.com/clip.mp4?utm_source=x",
            MediaSource.OTHER,
            listOf(video("a"), video("b"))
        )
        assertEquals(1, result.count { it == QuickAction.DOWNLOAD })
    }

    // ── Content type derivation ────────────────────────────────────────────────

    @Test
    fun formatsWinOverEverythingElse() {
        // Even a PDF URL with a resolved format list is downloadable media.
        assertEquals(
            QuickPanelContentType.VIDEO,
            deriveContentType(MediaSource.OTHER, "https://example.com/report.pdf", listOf(video("v")))
        )
    }

    @Test
    fun imageExtensionsDeriveImage() {
        for (extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "svg")) {
            assertEquals(
                "expected $extension to be an image",
                QuickPanelContentType.IMAGE,
                deriveContentType(MediaSource.OTHER, "https://example.com/file.$extension")
            )
        }
        // Case does not matter.
        assertEquals(
            QuickPanelContentType.IMAGE,
            deriveContentType(MediaSource.OTHER, "https://example.com/photo.PNG")
        )
    }

    @Test
    fun nonImageDirectFileExtensionsDeriveDirectFile() {
        for (extension in listOf("pdf", "zip", "apk", "mp4", "mp3", "epub", "csv")) {
            assertEquals(
                "expected $extension to be a direct file",
                QuickPanelContentType.DIRECT_FILE,
                deriveContentType(MediaSource.OTHER, "https://example.com/file.$extension")
            )
        }
    }

    @Test
    fun extensionIsReadFromThePathAndIgnoresQueryAndFragment() {
        assertEquals(
            QuickPanelContentType.DIRECT_FILE,
            deriveContentType(MediaSource.OTHER, "https://example.com/a/report.pdf?token=1#page=2")
        )
        // A page that merely mentions a file in its query is still a page.
        assertEquals(
            QuickPanelContentType.ARTICLE,
            deriveContentType(MediaSource.OTHER, "https://example.com/view?file=a.pdf")
        )
    }

    @Test
    fun knownPageSourcesWithoutFormatsDeriveArticle() {
        val pageSources = listOf(
            MediaSource.INSTAGRAM,
            MediaSource.FACEBOOK,
            MediaSource.TIKTOK,
            MediaSource.PINTEREST,
            MediaSource.REDDIT,
            MediaSource.YOUTUBE,
            MediaSource.OTHER
        )
        for (source in pageSources) {
            assertEquals(
                "$source should be a plain page without formats",
                QuickPanelContentType.ARTICLE,
                deriveContentType(source, "https://example.com/a")
            )
        }
        // The detector reports DIRECT_FILE for a URL it could not place but that looks like a file.
        assertEquals(
            QuickPanelContentType.DIRECT_FILE,
            deriveContentType(MediaSource.DIRECT_FILE, "https://example.com/a")
        )
    }

    @Test
    fun unknownSourceWithoutFormatsOrExtensionIsUnsupported() {
        assertEquals(
            QuickPanelContentType.UNSUPPORTED,
            deriveContentType(MediaSource.UNKNOWN, "not a url")
        )
        assertEquals(
            QuickPanelContentType.UNSUPPORTED,
            deriveContentType(MediaSource.UNKNOWN, "")
        )
    }

    @Test
    fun emptyFormatListNeverDerivesAVideoType() {
        for (extension in listOf("jpg", "pdf", "mp4")) {
            assertNotEquals(
                QuickPanelContentType.VIDEO,
                deriveContentType(MediaSource.OTHER, "https://example.com/f.$extension", emptyList())
            )
        }
    }

    // ── buildPanelState: cleaning integration ──────────────────────────────────

    @Test
    fun trackingHeavyUrlIsCleanedAndRecorded() {
        val raw = "https://www.facebook.com/reel/1710485373378939/?referral_source=external_link" +
            "&surface_type=tab&in_reels_tab_context=TRUE&utm_source=share"

        val state = stateFor(raw, MediaSource.FACEBOOK)

        assertEquals(raw, state.url)
        assertEquals("https://www.facebook.com/reel/1710485373378939", state.cleanedUrl)
        assertEquals(
            listOf("referral_source", "surface_type", "in_reels_tab_context", "utm_source"),
            state.removedParameters
        )
        assertEquals(4, state.removedCount)
        assertTrue(state.cleaningChanged)
        assertEquals("www.facebook.com", state.domain)
    }

    @Test
    fun trackingHeavyUrlKeepsMeaningfulParameters() {
        val state = stateFor(
            "https://example.com/article?id=42&utm_source=facebook&fbclid=abc&custom=KeepMe",
            MediaSource.OTHER
        )

        assertEquals("https://example.com/article?id=42&custom=KeepMe", state.cleanedUrl)
        assertEquals(listOf("utm_source", "fbclid"), state.removedParameters)
        assertTrue(state.cleaningChanged)
    }

    @Test
    fun alreadyCleanUrlReportsNoChange() {
        val state = stateFor("https://example.com/article", MediaSource.OTHER)

        assertEquals(state.url, state.cleanedUrl)
        assertTrue(state.removedParameters.isEmpty())
        assertFalse(state.cleaningChanged)
    }

    @Test
    fun malformedUrlKeepsTheOriginalAndDoesNotCrash() {
        val raw = "https://exa mple.com/x"
        val state = stateFor(raw, MediaSource.UNKNOWN)

        assertEquals(raw, state.url)
        assertEquals("a rejected URL must be kept verbatim", raw, state.cleanedUrl)
        assertTrue(state.removedParameters.isEmpty())
        assertFalse(state.cleaningChanged)
        assertEquals(QuickPanelContentType.UNSUPPORTED, state.contentType)
        assertEquals(pageActions, QuickActionModel.actionsFor(state))
    }

    @Test
    fun blankAndHostileUrlsNeverThrowAndNeverLoseTheInput() {
        val inputs = listOf(
            "",
            "   ",
            "not a url",
            "javascript:alert(1)",
            "mailto:someone@example.com",
            "http://",
            "://example.com",
            "https://example/x",
            "https://%%%/a"
        )
        for (input in inputs) {
            val state = stateFor(input, MediaSource.UNKNOWN)
            assertEquals("input '$input' must survive", input, state.url)
            assertEquals("input '$input' must survive cleaning", input, state.cleanedUrl)
            assertTrue(state.removedParameters.isEmpty())
            assertFalse(state.cleaningChanged)
            // The model must still produce a usable action list rather than an exception.
            assertTrue(QuickActionModel.actionsFor(state).isNotEmpty())
        }
    }

    @Test
    fun titleIsCarriedThroughAndHeaderFallsBackToTheSource() {
        val withTitle = stateFor("https://example.com/a", MediaSource.OTHER, title = "An article")
        assertEquals("An article", withTitle.title)

        val withoutTitle = stateFor("https://example.com/a", MediaSource.OTHER)
        assertEquals("", withoutTitle.title)
        // The panel header falls back to the detected source name; just assert it is available.
        assertEquals("Other website", withoutTitle.source.displayName)
    }

    @Test
    fun contentTypeIsDerivedFromTheCleanedUrl() {
        // A tracking parameter cannot be mistaken for a file extension once cleaning has run.
        val state = stateFor(
            "https://example.com/view?next=clip.mp4&utm_source=x",
            MediaSource.OTHER
        )
        assertEquals("https://example.com/view?next=clip.mp4", state.cleanedUrl)
        assertEquals(QuickPanelContentType.ARTICLE, state.contentType)
    }

    @Test
    fun availableFormatsAreKeptInPanelOrder() {
        val formats = listOf(video("360", height = 360), video("1080", height = 1080), audio("a"))
        val state = stateFor("https://www.instagram.com/reel/1/", MediaSource.INSTAGRAM, formats)

        assertEquals(formats, state.availableFormats)

        val ordered = state.displayFormats()
        assertEquals(listOf("1080", "360", "a"), ordered.map { it.id })
    }

    @Test
    fun singleVideoFormatIsPresentedAsOriginalQuality() {
        val state = stateFor(
            "https://www.instagram.com/reel/1/",
            MediaSource.INSTAGRAM,
            listOf(video("only", height = 720))
        )

        assertEquals(listOf("Original quality"), state.displayFormats().map { it.displayLabel })
    }

    @Test
    fun audioOnlyFormatIsShownLast() {
        val state = stateFor(
            "https://www.instagram.com/reel/1/",
            MediaSource.INSTAGRAM,
            listOf(audio("a"), video("v", height = 1080))
        )

        assertEquals(listOf("v", "a"), state.displayFormats().map { it.id })
    }

    @Test
    fun buildPanelStateIsIdempotentForAnAlreadyCleanedUrl() {
        val once = stateFor(
            "https://example.com/a?id=1&utm_source=x",
            MediaSource.OTHER
        ).cleanedUrl
        val twice = stateFor(once, MediaSource.OTHER)

        assertEquals(once, twice.cleanedUrl)
        assertFalse(twice.cleaningChanged)
    }

    // ── Action metadata ────────────────────────────────────────────────────────

    @Test
    fun cleaningActionMetadataIsCorrect() {
        assertTrue(QuickAction.CLEAN_URL.isCleaningAction)
        assertTrue(QuickAction.COPY_CLEAN_URL.isCleaningAction)
        assertTrue(QuickAction.SHARE_CLEAN_URL.isCleaningAction)
        assertFalse(QuickAction.SAVE.isCleaningAction)
        assertFalse(QuickAction.DOWNLOAD.isCleaningAction)
        assertTrue(QuickAction.DOWNLOAD.isDownloadAction)
        assertFalse(QuickAction.COPY.isDownloadAction)
    }

    @Test
    fun downloadabilityMetadataMatchesTheSpecification() {
        assertTrue(QuickPanelContentType.VIDEO.isDownloadable)
        assertTrue(QuickPanelContentType.IMAGE.isDownloadable)
        assertTrue(QuickPanelContentType.DIRECT_FILE.isDownloadable)
        assertFalse(QuickPanelContentType.ARTICLE.isDownloadable)
        assertFalse(QuickPanelContentType.UNSUPPORTED.isDownloadable)
    }

    @Test
    fun everyActionBelongsToAtLeastOneContentType() {
        val offered = mutableSetOf<QuickAction>()
        val formats = listOf(video("v"))
        for (source in MediaSource.entries) {
            offered += QuickActionModel.actionsFor(stateFor("https://example.com/a", source))
            offered += QuickActionModel.actionsFor(
                stateFor("https://example.com/a.png", source, formats)
            )
            offered += QuickActionModel.actionsFor(stateFor("not a url", source))
        }
        assertEquals(QuickAction.entries.toSet(), offered)
    }
}
