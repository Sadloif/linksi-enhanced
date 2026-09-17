package com.linksi.app.enhanced.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MediaFormat], [MediaInfo] and [MediaError].
 *
 * Case identifiers in the comments map onto LINKSI_ENHANCED_REVISED_SPEC.md sections 13, 19, 26,
 * 68 and 69. Everything here is pure JVM logic, so the format ordering and the error
 * classification are pinned without an emulator.
 */
class MediaFormatTest {

    /** A video format with a resolution (and optionally a frame rate). */
    private fun video(
        id: String,
        label: String = "",
        height: Int? = null,
        fps: Double? = null,
        extension: String = "mp4"
    ) = MediaFormat(
        id = id,
        label = label,
        extension = extension,
        height = height,
        fps = fps,
        isAudioOnly = false
    )

    /** An audio-only format. */
    private fun audio(id: String, label: String = "", extension: String = "m4a") = MediaFormat(
        id = id,
        label = label,
        extension = extension,
        isAudioOnly = true
    )

    /** A [MediaInfo] with only the formats filled in; the rest is irrelevant to ordering. */
    private fun info(vararg formats: MediaFormat) = MediaInfo(
        webpageUrl = "https://www.instagram.com/reel/123/",
        source = MediaSource.INSTAGRAM,
        title = "A reel",
        formats = formats.toList()
    )

    // ── displayFormats: ordering (spec 19) ──────────────────────────────────────

    @Test
    fun displayFormatsOrdersVideoBestFirst() {
        val info = info(
            video("360", height = 360),
            video("1080", height = 1080),
            video("720", height = 720),
            video("1440", height = 1440)
        )
        assertEquals(listOf("1440", "1080", "720", "360"), info.displayFormats().map { it.id })
    }

    @Test
    fun displayFormatsPutsAudioOnlyLast() {
        val info = info(
            audio("a1"),
            video("360", height = 360),
            audio("a2"),
            video("1080", height = 1080)
        )
        val displayed = info.displayFormats()
        assertEquals(listOf("1080", "360", "a1", "a2"), displayed.map { it.id })
        assertTrue(displayed.take(2).all { it.isVideo })
        assertTrue(displayed.drop(2).all { it.isAudioOnly })
    }

    @Test
    fun audioOnlyIsLastEvenWhenThereIsNoVideoAtAll() {
        val info = info(audio("a1"), audio("a2"))
        assertEquals(listOf("a1", "a2"), info.displayFormats().map { it.id })
    }

    @Test
    fun equalHeightFallsBackToFrameRate() {
        val info = info(
            video("1080p30", height = 1080, fps = 30.0),
            video("1080p60", height = 1080, fps = 60.0),
            video("1080pUnknown", height = 1080, fps = null)
        )
        assertEquals(
            listOf("1080p60", "1080p30", "1080pUnknown"),
            info.displayFormats().map { it.id }
        )
    }

    @Test
    fun aFormatWithoutAHeightSortsBelowEveryKnownResolution() {
        val info = info(
            video("unknown"),
            video("360", height = 360),
            audio("a1")
        )
        assertEquals(listOf("360", "unknown", "a1"), info.displayFormats().map { it.id })
    }

    @Test
    fun orderingKeepsEveryFormatAndLosesNone() {
        val formats = listOf(
            video("v1", height = 1080),
            video("v2", height = 720),
            audio("a1"),
            audio("a2")
        )
        val displayed = info(*formats.toTypedArray()).displayFormats()
        assertEquals(formats.size, displayed.size)
        assertEquals(formats.map { it.id }.toSet(), displayed.map { it.id }.toSet())
    }

    // ── displayFormats: single-format rule (spec 19) ────────────────────────────

    @Test
    fun aSingleVideoFormatIsPresentedAsOriginalQuality() {
        // 720p is never invented as a label when the source did not advertise one.
        val info = info(video("best", label = "", height = null))
        val displayed = info.displayFormats()
        assertEquals(1, displayed.size)
        assertEquals("Original quality", displayed.first().label)
        assertEquals("Original quality", displayed.first().displayLabel)
    }

    @Test
    fun theSingleFormatRuleAlsoAppliesWhenAHeightIsKnown() {
        // Spec 19: one format and no separate audio track means "Original quality", not "1080p".
        val displayed = info(video("best", label = "", height = 1080)).displayFormats()
        assertEquals(listOf("Original quality"), displayed.map { it.label })
    }

    @Test
    fun theSingleFormatRuleDoesNotMutateTheStoredFormats() {
        val original = video("best", label = "", height = 480)
        val info = info(original)
        val displayed = info.displayFormats()
        assertNotSame(displayed.first(), info.formats.first())
        assertEquals("", info.formats.first().label)
        assertEquals("best", displayed.first().id)
        assertEquals(480, displayed.first().height)
    }

    @Test
    fun aSingleAudioFormatIsNotRenamedToOriginalQuality() {
        // The rule is explicitly about a single *video* format with no audio track.
        val displayed = info(audio("a1", label = "")).displayFormats()
        assertEquals("Audio only", displayed.single().displayLabel)
        assertEquals("", displayed.single().label)
    }

    @Test
    fun oneVideoPlusOneAudioIsNotCollapsed() {
        val displayed = info(video("v1", height = 720), audio("a1")).displayFormats()
        assertEquals(listOf("v1", "a1"), displayed.map { it.id })
        assertEquals("720p", displayed.first().displayLabel)
    }

    @Test
    fun twoVideoFormatsAreNotCollapsed() {
        val displayed = info(video("v1", height = 1080), video("v2", height = 720)).displayFormats()
        assertEquals(listOf("v1", "v2"), displayed.map { it.id })
    }

    @Test
    fun anEmptyFormatListStaysEmpty() {
        val info = info()
        assertTrue(info.displayFormats().isEmpty())
        assertFalse(info.hasFormats)
    }

    // ── MediaInfo.bookkeeping ───────────────────────────────────────────────────

    @Test
    fun hasFormatsAndIsSingleFormatDescribeTheStoredList() {
        assertFalse(info().hasFormats)
        assertTrue(info(video("v1")).hasFormats)
        assertTrue(info(video("v1")).isSingleFormat)
        assertFalse(info(video("v1"), video("v2")).isSingleFormat)
        assertFalse(info().isSingleFormat)
    }

    // ── bestFormat (spec 19) ────────────────────────────────────────────────────

    @Test
    fun bestFormatIsTheHighestResolutionVideo() {
        val info = info(video("360", height = 360), video("1080", height = 1080), video("720", height = 720))
        assertEquals("1080", info.bestFormat()?.id)
    }

    @Test
    fun bestFormatIgnoresAudioWhenAnyVideoExists() {
        val info = info(audio("a1"), video("480", height = 480))
        assertEquals("480", info.bestFormat()?.id)
    }

    @Test
    fun bestFormatKeepsAVideoWithNoMeasurableHeightOverAnAudioTrack() {
        // Characterisation of the current selection rule: the video filter is applied first and any
        // video - even one whose height the source never advertised - wins over the audio track.
        // bestFormat() falls back to the first format only when there is no video entry at all.
        val mediaInfo = info(video("unknown"), audio("a1").copy(height = 128))
        assertEquals("unknown", mediaInfo.bestFormat()?.id)
    }

    @Test
    fun bestFormatFallsBackToTheFirstFormatWhenNothingHasAMeasurableHeight() {
        // Two entries with equal keys: maxByOrNull keeps the first, so the video stays selected.
        val mediaInfo = info(video("unknown"), audio("a1"))
        assertEquals("unknown", mediaInfo.bestFormat()?.id)
    }

    @Test
    fun bestFormatFallsBackToTheOnlyFormat() {
        val info = info(video("only", height = 720))
        assertEquals("only", info.bestFormat()?.id)
    }

    @Test
    fun bestFormatIsNullWithoutFormats() {
        assertNull(info().bestFormat())
    }

    // ── MediaFormat labels and helpers ──────────────────────────────────────────

    @Test
    fun anExplicitLabelAlwaysWins() {
        val format = video("v1", label = "Full HD", height = 1080)
        assertEquals("Full HD", format.displayLabel)
    }

    @Test
    fun aBlankLabelFallsBackToTheResolutionThenToOriginalQuality() {
        assertEquals("480p", video("v1", label = "", height = 480).displayLabel)
        assertEquals("Original quality", video("v1", label = "   ", height = null).displayLabel)
        assertEquals("Audio only", audio("a1", label = "").displayLabel)
    }

    @Test
    fun hasKnownSizeRequiresAPositiveSize() {
        assertFalse(video("v1").hasKnownSize)
        assertFalse(video("v1").copy(fileSizeBytes = 0).hasKnownSize)
        assertFalse(video("v1").copy(fileSizeBytes = -1).hasKnownSize)
        assertTrue(video("v1").copy(fileSizeBytes = 1).hasKnownSize)
    }

    @Test
    fun videoAndAudioFlagsAreMirrorImages() {
        assertTrue(video("v1").isVideo)
        assertFalse(video("v1").isAudioOnly)
        assertTrue(audio("a1").isAudioOnly)
        assertFalse(audio("a1").isVideo)
    }

    // ── MediaError.classify (spec 68) ───────────────────────────────────────────

    @Test
    fun privateWordingIsClassifiedAsPrivateContent() {
        assertEquals(MediaError.PRIVATE_CONTENT, MediaError.classify("This video is private"))
        assertEquals(MediaError.PRIVATE_CONTENT, MediaError.classify("ERROR: Private account"))
        assertEquals(MediaError.PRIVATE_CONTENT, MediaError.classify("Request not authorized"))
        // Original casing must not matter.
        assertEquals(MediaError.PRIVATE_CONTENT, MediaError.classify("PRIVATE"))
    }

    @Test
    fun loginWordingIsClassifiedAsLoginRequired() {
        assertEquals(MediaError.LOGIN_REQUIRED, MediaError.classify("Login required to view this post"))
        assertEquals(MediaError.LOGIN_REQUIRED, MediaError.classify("Please sign in to continue"))
        assertEquals(MediaError.LOGIN_REQUIRED, MediaError.classify("authentication failed"))
        assertEquals(MediaError.LOGIN_REQUIRED, MediaError.classify("You must Sign In first"))
    }

    @Test
    fun deletedAndMissingWordingIsClassifiedAsMediaGone() {
        assertEquals(MediaError.MEDIA_GONE, MediaError.classify("Video deleted by the user"))
        assertEquals(MediaError.MEDIA_GONE, MediaError.classify("This content was removed"))
        assertEquals(MediaError.MEDIA_GONE, MediaError.classify("HTTP Error 404: Not Found"))
        assertEquals(MediaError.MEDIA_GONE, MediaError.classify("Video unavailable"))
    }

    @Test
    fun timeoutAndConnectionWordingIsClassifiedAsNetwork() {
        assertEquals(MediaError.NETWORK, MediaError.classify("Read timed out"))
        assertEquals(MediaError.NETWORK, MediaError.classify("request timeout"))
        assertEquals(MediaError.NETWORK, MediaError.classify("Unable to resolve host \"example.com\""))
        assertEquals(MediaError.NETWORK, MediaError.classify("Connection reset by peer"))
        assertEquals(MediaError.NETWORK, MediaError.classify("network is unreachable"))
    }

    @Test
    fun unsupportedWordingIsClassifiedAsUnsupportedSite() {
        assertEquals(MediaError.UNSUPPORTED_SITE, MediaError.classify("Unsupported URL"))
        assertEquals(MediaError.UNSUPPORTED_SITE, MediaError.classify("No video could be found"))
        assertEquals(MediaError.UNSUPPORTED_SITE, MediaError.classify("This site is not supported"))
    }

    @Test
    fun unrecognisedWordingFallsBackToExtractorFailed() {
        assertEquals(MediaError.EXTRACTOR_FAILED, MediaError.classify("ffmpeg exited with code 1"))
        assertEquals(MediaError.EXTRACTOR_FAILED, MediaError.classify("Unknown extractor error"))
        assertEquals(MediaError.EXTRACTOR_FAILED, MediaError.classify(null))
        assertEquals(MediaError.EXTRACTOR_FAILED, MediaError.classify(""))
        assertEquals(MediaError.EXTRACTOR_FAILED, MediaError.classify("   "))
    }

    @Test
    fun theMoreSpecificWordingWinsWhenSeveralKeywordsArePresent() {
        // A private post from a logged-out session is first and foremost private.
        assertEquals(MediaError.PRIVATE_CONTENT, MediaError.classify("Private video, please log in"))
        assertEquals(MediaError.LOGIN_REQUIRED, MediaError.classify("Login required: video deleted"))
        // "unavailable" outranks "timeout" because the gone check runs first.
        assertEquals(MediaError.MEDIA_GONE, MediaError.classify("Video unavailable after timeout"))
    }

    @Test
    fun classifyNeverThrowsForHostileText() {
        // The only contract is "always returns an error", never "throws".
        assertEquals(MediaError.PRIVATE_CONTENT, MediaError.classify("private"))
        assertEquals(MediaError.PRIVATE_CONTENT, MediaError.classify("\u0000private\u0001"))
        assertEquals(MediaError.PRIVATE_CONTENT, MediaError.classify("private " + "x".repeat(10_000)))
        assertEquals(MediaError.PRIVATE_CONTENT, MediaError.classify("PRIVATE PRIVATE private"))
        // Text with no recognised wording is simply an unknown extractor failure.
        assertEquals(MediaError.EXTRACTOR_FAILED, MediaError.classify("\u00a1"))
        assertEquals(MediaError.EXTRACTOR_FAILED, MediaError.classify("\u0000\u0001"))
        assertEquals(MediaError.EXTRACTOR_FAILED, MediaError.classify("a".repeat(10_000)))
    }

    @Test
    fun everyErrorCarriesAStringResourceKey() {
        // The UI shows a resource key, never a raw extractor exception (spec 68).
        for (error in MediaError.entries) {
            assertTrue("${error.name} has no message key", error.messageKey.startsWith("error_download_"))
        }
        assertEquals("error_download_private", MediaError.PRIVATE_CONTENT.messageKey)
        assertEquals("error_download_engine_unavailable", MediaError.ENGINE_UNAVAILABLE.messageKey)
    }

    // ── MediaExtractionResult (spec 26) ─────────────────────────────────────────

    @Test
    fun unsupportedAndSkippedResultsExposeTheirCanonicalError() {
        assertEquals(
            MediaError.UNSUPPORTED_SITE,
            MediaExtractionResult.Unsupported("https://example.com/a", MediaSource.OTHER).error
        )
        assertEquals(
            MediaError.ENGINE_UNAVAILABLE,
            MediaExtractionResult.Skipped("https://example.com/a", "engine not bundled").error
        )
    }

    @Test
    fun aFailureKeepsTheTechnicalDetailOutOfTheUserFacingError() {
        val cause = IllegalStateException("yt-dlp: 500 Internal Server Error")
        val failure = MediaExtractionResult.Failure(MediaError.EXTRACTOR_FAILED, "https://x/y", cause)
        assertEquals(MediaError.EXTRACTOR_FAILED, failure.error)
        assertNull(MediaExtractionResult.Failure(MediaError.NETWORK, "https://x/y").cause)
        assertEquals(cause, failure.cause)
    }

    @Test
    fun aSuccessCarriesTheMediaInfoItWasGiven() {
        val media = info(video("v1", height = 720))
        val success = MediaExtractionResult.Success(media)
        assertEquals(media, success.info)
        assertTrue(success.info.hasFormats)
    }
}
