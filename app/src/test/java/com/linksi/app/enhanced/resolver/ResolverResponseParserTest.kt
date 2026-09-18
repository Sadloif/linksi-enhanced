package com.linksi.app.enhanced.resolver

import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaBackend
import com.linksi.app.enhanced.media.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the private-resolver wire contract.
 *
 * The point of these tests is that a broken, hostile or simply wrong server response can only ever
 * produce a [MediaExtractionResult.Failure] — never an exception, and never a crash in the app
 * (specification sections 25 and 26).
 */
class ResolverResponseParserTest {

    private val url = "https://www.instagram.com/reel/ABC123/"
    private val source = MediaSource.INSTAGRAM

    private fun parse(body: String?) = ResolverResponseParser.parse(url, source, body)

    private fun success(body: String): MediaExtractionResult.Success {
        val result = parse(body)
        assertTrue("expected success but got $result", result is MediaExtractionResult.Success)
        return result as MediaExtractionResult.Success
    }

    private fun failure(body: String?): MediaExtractionResult.Failure {
        val result = parse(body)
        assertTrue("expected failure but got $result", result is MediaExtractionResult.Failure)
        return result as MediaExtractionResult.Failure
    }

    // ── failure handling ────────────────────────────────────────────────────────

    @Test
    fun blankBodyIsServerUnavailable() {
        assertEquals(MediaError.SERVER_UNAVAILABLE, failure(null).error)
        assertEquals(MediaError.SERVER_UNAVAILABLE, failure("").error)
        assertEquals(MediaError.SERVER_UNAVAILABLE, failure("   ").error)
    }

    @Test
    fun invalidJsonIsServerUnavailable() {
        assertEquals(MediaError.SERVER_UNAVAILABLE, failure("not json at all").error)
        assertEquals(MediaError.SERVER_UNAVAILABLE, failure("{ unclosed").error)
        assertEquals(MediaError.SERVER_UNAVAILABLE, failure("[1,2,3]").error)
    }

    @Test
    fun okFalseClassifiesTheServerError() {
        assertEquals(MediaError.PRIVATE_CONTENT, failure("""{"ok":false,"error":"private content"}""").error)
        assertEquals(MediaError.LOGIN_REQUIRED, failure("""{"ok":false,"error":"login required"}""").error)
        assertEquals(MediaError.MEDIA_GONE, failure("""{"ok":false,"error":"video unavailable"}""").error)
        assertEquals(MediaError.NETWORK, failure("""{"ok":false,"error":"connection timeout"}""").error)
        assertEquals(MediaError.UNSUPPORTED_SITE, failure("""{"ok":false,"error":"unsupported url"}""").error)
        assertEquals(MediaError.EXTRACTOR_FAILED, failure("""{"ok":false,"error":"boom"}""").error)
    }

    @Test
    fun okFalseWithoutAnErrorTextIsExtractorFailed() {
        assertEquals(MediaError.EXTRACTOR_FAILED, failure("""{"ok":false}""").error)
    }

    @Test
    fun missingOkFlagIsTreatedAsFailureNotSuccess() {
        // A server that forgets "ok" must not be trusted to have succeeded.
        assertEquals(MediaError.EXTRACTOR_FAILED, failure("""{"title":"x","formats":[]}""").error)
    }

    @Test
    fun successWithNoFormatsReportsNoFormats() {
        assertEquals(MediaError.NO_FORMATS, failure("""{"ok":true,"title":"x","formats":[]}""").error)
        assertEquals(MediaError.NO_FORMATS, failure("""{"ok":true,"title":"x"}""").error)
    }

    @Test
    fun hostileFormatPayloadsDoNotThrow() {
        val bodies = listOf(
            """{"ok":true,"formats":"not an array"}""",
            """{"ok":true,"formats":[1,2,3]}""",
            """{"ok":true,"formats":[null]}""",
            """{"ok":true,"formats":[{}]}""",
            """{"ok":true,"formats":[{"url":null,"height":"tall"}]}"""
        )
        for (body in bodies) {
            val result = parse(body)
            // Either it produced no usable formats (failure) or it produced defaults (success);
            // what matters is that it did not throw.
            assertTrue(result is MediaExtractionResult.Failure || result is MediaExtractionResult.Success)
        }
    }

    // ── success parsing ─────────────────────────────────────────────────────────

    @Test
    fun fullResponseIsMappedFieldByField() {
        val info = success(
            """
            {
              "ok": true,
              "title": "A public reel",
              "uploader": "someaccount",
              "duration": 42,
              "thumbnail": "https://cdn.example/thumb.jpg",
              "webpage_url": "https://www.instagram.com/reel/ABC123/",
              "formats": [
                {
                  "id": "137", "label": "1080p", "ext": "mp4", "height": 1080, "width": 1920,
                  "fps": 30, "vcodec": "avc1", "acodec": "none", "filesize": 12345678,
                  "url": "https://cdn.example/video.mp4", "audio_only": false, "requires_muxing": false
                }
              ]
            }
            """.trimIndent()
        ).info

        assertEquals("A public reel", info.title)
        assertEquals("someaccount", info.uploader)
        assertEquals(42, info.durationSeconds)
        assertEquals("https://cdn.example/thumb.jpg", info.thumbnailUrl)
        assertEquals(url, info.webpageUrl)
        assertEquals(source, info.source)
        assertEquals(1, info.formats.size)

        val format = info.formats.first()
        assertEquals("137", format.id)
        assertEquals("1080p", format.label)
        assertEquals("mp4", format.extension)
        assertEquals(1080, format.height)
        assertEquals(1920, format.width)
        assertEquals(30.0, format.fps!!, 0.001)
        assertEquals("avc1", format.videoCodec)
        assertNull("acodec 'none' means no audio stream", format.audioCodec)
        assertEquals(12345678L, format.fileSizeBytes)
        assertFalse(format.isAudioOnly)
        assertEquals("https://cdn.example/video.mp4", format.directUrl)
        assertFalse(format.requiresMuxing)
        assertEquals(MediaBackend.PRIVATE_SERVER, format.backend)
    }

    @Test
    fun aMuxingFormatWithOnlyOneUrlIsRejectedAsUnsupported() {
        val result = parse(
            """
            {"ok":true,"title":"t","formats":[
              {"id":"video-only","ext":"mp4","url":"https://cdn.example/video.mp4",
               "audio_only":false,"requires_muxing":true}
            ]}
            """.trimIndent()
        )

        assertTrue(result is MediaExtractionResult.Unsupported)
        assertEquals(source, (result as MediaExtractionResult.Unsupported).source)
    }

    @Test
    fun aMuxingFormatIsFilteredWhenACompleteFormatIsAlsoPresent() {
        val info = success(
            """
            {"ok":true,"title":"t","formats":[
              {"id":"video-only","url":"https://cdn.example/video.mp4","requires_muxing":true},
              {"id":"complete","url":"https://cdn.example/complete.mp4","requires_muxing":false}
            ]}
            """.trimIndent()
        ).info

        assertEquals(listOf("complete"), info.formats.map { it.id })
    }

    @Test
    fun audioOnlyFormatDefaultsToAnAudioExtension() {
        val format = success(
            """{"ok":true,"title":"t","formats":[{"id":"140","audio_only":true,"label":"Audio only"}]}"""
        ).info.formats.first()

        assertTrue(format.isAudioOnly)
        assertFalse(format.isVideo)
        assertEquals("m4a", format.extension)
    }

    @Test
    fun extensionIsTakenFromExtAndStripsALeadingDot() {
        val format = success(
            """{"ok":true,"title":"t","formats":[{"ext":".webm","url":"https://x/y.webm"}]}"""
        ).info.formats.first()
        assertEquals("webm", format.extension)
    }

    @Test
    fun missingTitleFallsBackToTheUrl() {
        val info = success("""{"ok":true,"formats":[{"url":"https://x/y.mp4"}]}""").info
        assertEquals(url, info.title)
    }

    @Test
    fun zeroDurationAndSizeBecomeNullRatherThanZero() {
        val info = success(
            """{"ok":true,"title":"t","duration":0,"formats":[{"url":"https://x/y.mp4","filesize":0,"height":0,"fps":0}]}"""
        ).info
        assertNull(info.durationSeconds)
        val format = info.formats.first()
        assertNull(format.fileSizeBytes)
        assertNull(format.height)
        assertNull(format.fps)
        assertFalse(format.hasKnownSize)
    }

    @Test
    fun missingFieldsStillProduceAUsableFormat() {
        val format = success("""{"ok":true,"formats":[{"url":"https://x/y"}]}""").info.formats.first()
        assertEquals("format-0", format.id)
        assertEquals("mp4", format.extension)
        assertEquals("https://x/y", format.directUrl)
        assertFalse(format.isAudioOnly)
        assertFalse(format.requiresMuxing)
        assertNull(format.videoCodec)
    }

    @Test
    fun serverSuppliedWebpageUrlIsRespected() {
        val info = success(
            """{"ok":true,"title":"t","webpage_url":"https://canonical.example/post","formats":[{"url":"https://x/y.mp4"}]}"""
        ).info
        assertEquals("https://canonical.example/post", info.webpageUrl)
    }

    @Test
    fun multipleFormatsAreAllParsedAndOrderedBestFirstOnDisplay() {
        val info = success(
            """
            {"ok":true,"title":"t","formats":[
              {"id":"a","label":"720p","height":720,"url":"https://x/720.mp4"},
              {"id":"b","label":"1080p","height":1080,"url":"https://x/1080.mp4"},
              {"id":"c","audio_only":true,"label":"Audio only"}
            ]}
            """.trimIndent()
        ).info

        assertEquals(3, info.formats.size)
        val display = info.displayFormats()
        assertEquals(listOf("1080p", "720p", "Audio only"), display.map { it.displayLabel })
    }

    @Test
    fun aSingleVideoFormatIsPresentedAsOriginalQuality() {
        val info = success(
            """{"ok":true,"title":"t","formats":[{"id":"a","height":720,"url":"https://x/720.mp4"}]}"""
        ).info
        assertEquals(listOf("Original quality"), info.displayFormats().map { it.displayLabel })
    }

    @Test
    fun resolverFailureKeepsTheOriginalUrlForTheCaller() {
        assertEquals(url, failure("""{"ok":false,"error":"private"}""").url)
        assertEquals(url, failure(null).url)
    }

    @Test
    fun sourceIsCarriedThroughUnchanged() {
        val result = ResolverResponseParser.parse(
            "https://www.tiktok.com/@a/video/1",
            MediaSource.TIKTOK,
            """{"ok":true,"title":"t","formats":[{"url":"https://x/y.mp4"}]}"""
        )
        assertEquals(MediaSource.TIKTOK, (result as MediaExtractionResult.Success).info.source)
    }
}
