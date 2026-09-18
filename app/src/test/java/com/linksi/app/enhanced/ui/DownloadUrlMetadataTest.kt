package com.linksi.app.enhanced.ui

import com.linksi.app.enhanced.download.DownloadDestination
import com.linksi.app.enhanced.media.MediaBackend
import com.linksi.app.enhanced.media.MediaFormat
import com.linksi.app.enhanced.media.MediaInfo
import com.linksi.app.enhanced.media.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DownloadUrlMetadata] - the small pure decisions the UI makes before handing a
 * link to the engine.
 *
 * They matter because a wrong suggestion here becomes a wrong file on disk: the name has to be
 * sanitised, an extension has to survive, and a URL with nothing usable in its path must produce no
 * suggestion at all so the engine's own `Content-Disposition` handling can win.
 */
class DownloadUrlMetadataTest {

    // ── file names ────────────────────────────────────────────────────────────

    @Test
    fun anImageUrlKeepsItsOwnNameAndExtension() {
        assertEquals("cat.png", DownloadUrlMetadata.fileName("https://example.com/pics/cat.png"))
    }

    @Test
    fun theQueryStringAndFragmentAreNeverPartOfTheName() {
        assertEquals(
            "cat.png",
            DownloadUrlMetadata.fileName("https://example.com/pics/cat.png?sig=abc&x=1#frag")
        )
    }

    @Test
    fun aPercentEncodedNameIsDecoded() {
        assertEquals("naïve.mp4", DownloadUrlMetadata.fileName("https://example.com/v/na%C3%AFve.mp4"))
    }

    @Test
    fun illegalCharactersInTheNameAreMadeSafe() {
        val name = DownloadUrlMetadata.fileName("https://example.com/a:b*c.mp4")
        assertTrue(name != null)
        assertFalse(name!!.contains(':'))
        assertFalse(name.contains('*'))
        assertTrue(name.endsWith(".mp4"))
    }

    @Test
    fun aUrlWithNothingUsableInItsPathProducesNoSuggestionAtAll() {
        // No extension anywhere: the sanitiser would have to invent "linksi-download.bin", and a
        // wrong guess is worse than no suggestion, because the engine knows the real name.
        assertNull(DownloadUrlMetadata.fileName("https://example.com/"))
        assertNull(DownloadUrlMetadata.fileName("https://example.com"))
        assertNull(DownloadUrlMetadata.fileName(""))
        assertNull(DownloadUrlMetadata.fileName("not a url"))
    }

    @Test
    fun aPathSegmentThatIsOnlyAQueryStillProducesNoSuggestion() {
        assertNull(DownloadUrlMetadata.fileName("https://example.com/?file=clip.mp4"))
    }

    // ── sources and requests ─────────────────────────────────────────────────

    @Test
    fun theSourceComesFromTheHostAndDefaultsToUnknown() {
        assertEquals(MediaSource.YOUTUBE, DownloadUrlMetadata.sourceOf("https://youtu.be/abc"))
        assertEquals(MediaSource.INSTAGRAM, DownloadUrlMetadata.sourceOf("https://www.instagram.com/reel/1/"))
        assertEquals(MediaSource.UNKNOWN, DownloadUrlMetadata.sourceOf("nonsense"))
    }

    @Test
    fun aDirectFileUrlIsItsOwnRequest() {
        val request = DownloadUrlMetadata.requestFor("https://example.com/pics/cat.png", "")
        assertEquals("https://example.com/pics/cat.png", request.url)
        assertEquals("cat.png", request.suggestedFileName)
        assertEquals(DownloadDestination.PUBLIC_DOWNLOADS, request.destination)
        // An empty format id means "the URL is the file", so nothing is recorded that a retry
        // would have to reinterpret.
        assertNull(request.formatId)
        assertFalse(request.requiresMuxing)
        assertTrue(request.id.isNotBlank())
    }

    @Test
    fun aChosenQualityIsRecordedOnTheRequest() {
        val request = DownloadUrlMetadata.requestFor("https://example.com/video", "1080p")
        assertEquals("1080p", request.formatId)
        assertTrue(request.id.contains("1080p"))
    }

    @Test
    fun aPrivateServerFormatKeepsItsBackendAndMuxingChoiceOnTheRequest() {
        val request = DownloadUrlMetadata.requestFor(
            url = "https://example.com/video",
            formatId = "server-1080",
            backend = MediaBackend.PRIVATE_SERVER,
            requiresMuxing = true
        )

        assertEquals(MediaBackend.PRIVATE_SERVER, request.backend)
        assertTrue(request.requiresMuxing)
    }

    @Test
    fun twoQualitiesOfTheSameLinkAreTwoDifferentRequests() {
        val first = DownloadUrlMetadata.requestFor("https://example.com/video", "720")
        val second = DownloadUrlMetadata.requestFor("https://example.com/video", "1080")
        assertFalse(first.id == second.id)
    }

    // ── analysis results ────────────────────────────────────────────────────

    @Test
    fun onlyAnExtractionWithFormatsIsDownloadable() {
        val empty = MediaInfo(
            webpageUrl = "https://example.com/post",
            source = MediaSource.OTHER,
            title = "A page"
        )
        assertFalse(DownloadUrlMetadata.isDownloadable(empty))
        assertNull(DownloadUrlMetadata.bestFormat(empty))

        val withFormat = empty.copy(
            formats = listOf(
                MediaFormat(id = "a", label = "720p", extension = "mp4", height = 720),
                MediaFormat(id = "b", label = "", extension = "mp4", height = 1080)
            )
        )
        assertTrue(DownloadUrlMetadata.isDownloadable(withFormat))
        assertEquals("b", DownloadUrlMetadata.bestFormat(withFormat)?.id)
    }

    @Test
    fun onlyHttpUrlsAreConsidered() {
        assertTrue(DownloadUrlMetadata.isHttpUrl("https://example.com/x"))
        assertTrue(DownloadUrlMetadata.isHttpUrl("http://example.com/x"))
        assertFalse(DownloadUrlMetadata.isHttpUrl("content://media/1"))
        assertFalse(DownloadUrlMetadata.isHttpUrl("ftp://example.com/x"))
    }

    @Test
    fun aMimeTypeIsDerivedFromTheExtensionWhenItIsKnown() {
        assertEquals("image/png", DownloadUrlMetadata.mimeTypeOf("cat.png"))
        assertEquals("video/mp4", DownloadUrlMetadata.mimeTypeOf("clip.MP4"))
        assertNull(DownloadUrlMetadata.mimeTypeOf("mystery"))
        assertNull(DownloadUrlMetadata.mimeTypeOf("archive.rar"))
    }
}
