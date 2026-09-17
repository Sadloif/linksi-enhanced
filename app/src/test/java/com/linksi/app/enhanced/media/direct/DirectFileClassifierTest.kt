package com.linksi.app.enhanced.media.direct

import com.linksi.app.enhanced.download.FilenameSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure half of [DirectFileExtractor]: content-type policy and naming
 * (specification section 16). No network is involved - the OkHttp probe itself is exercised by
 * hand on a device, while the decisions it feeds are pinned here.
 */
class DirectFileClassifierTest {

    // ── Content type -> extension ─────────────────────────────────────────────

    @Test
    fun theExtensionComesFromTheContentType() {
        assertEquals("mp4", DirectFileClassifier.extensionFor("video/mp4", "https://cdn.example.com/abc"))
        assertEquals("jpg", DirectFileClassifier.extensionFor("image/jpeg", "https://cdn.example.com/abc"))
        assertEquals("png", DirectFileClassifier.extensionFor("image/png", "https://cdn.example.com/abc"))
        assertEquals("pdf", DirectFileClassifier.extensionFor("application/pdf", "https://cdn.example.com/abc"))
        assertEquals("mp3", DirectFileClassifier.extensionFor("audio/mpeg", "https://cdn.example.com/abc"))
        assertEquals("zip", DirectFileClassifier.extensionFor("application/zip", "https://cdn.example.com/abc"))
        assertEquals("epub", DirectFileClassifier.extensionFor("application/epub+zip", "https://cdn.example.com/abc"))
        assertEquals("svg", DirectFileClassifier.extensionFor("image/svg+xml", "https://cdn.example.com/abc"))
        assertEquals("txt", DirectFileClassifier.extensionFor("text/plain", "https://cdn.example.com/abc"))
    }

    @Test
    fun contentTypeParametersAreIgnored() {
        assertEquals("jpg", DirectFileClassifier.extensionFor("image/jpeg; charset=binary", "https://x/y"))
        assertEquals("mp4", DirectFileClassifier.extensionFor("VIDEO/MP4; codecs=avc1", "https://x/y"))
        assertEquals("json", DirectFileClassifier.extensionFor("  application/json ; charset=UTF-8 ", "https://x/y"))
    }

    @Test
    fun theContentTypeBeatsTheUrlExtension() {
        // The bytes are a PNG; the path is a lie. What the server sends wins.
        assertEquals("png", DirectFileClassifier.extensionFor("image/png", "https://x/clip.jpg"))
        assertEquals("mp4", DirectFileClassifier.extensionFor("video/mp4", "https://x/clip.mov"))
    }

    @Test
    fun anOctetStreamFallsBackToTheUrlExtensionInsteadOfOctetstr() {
        // FilenameSanitizer would map application/octet-stream to "octetstr", which is not a file
        // name anybody wants; the URL at least knows it is an mp4.
        assertEquals("mp4", DirectFileClassifier.extensionFor("application/octet-stream", "https://x/clip.mp4"))
        assertEquals("pdf", DirectFileClassifier.extensionFor("binary/octet-stream", "https://x/doc.pdf"))
    }

    @Test
    fun anOctetStreamWithoutAUrlExtensionBecomesBin() {
        assertEquals("bin", DirectFileClassifier.extensionFor("application/octet-stream", "https://x/download"))
        assertEquals("bin", DirectFileClassifier.extensionFor("application/octet-stream", "https://x/"))
    }

    @Test
    fun aMissingContentTypeFallsBackToTheUrl() {
        assertEquals("mp4", DirectFileClassifier.extensionFor(null, "https://x/clip.mp4"))
        assertEquals("pdf", DirectFileClassifier.extensionFor("", "https://x/doc.pdf"))
    }

    @Test
    fun anUnknownTypeAndUrlBecomesBin() {
        // Better a generic extension than no file name at all.
        assertEquals("bin", DirectFileClassifier.extensionFor(null, "https://x/"))
        assertEquals("bin", DirectFileClassifier.extensionFor(null, "https://x/abc123"))
    }

    @Test
    fun anExoticSubtypeStillYieldsAUsableExtension() {
        // This is FilenameSanitizer's documented fallback (pinned by its own test): the subtype
        // with every non-alphanumeric character removed. It is not always pretty, but it is always
        // a valid extension, and a known type always wins over it.
        assertEquals("formdata", DirectFileClassifier.extensionFor("multipart/form-data", "https://x/abc123"))
        assertEquals("xflv", DirectFileClassifier.extensionFor("video/x-flv", "https://x/clip.flv"))
        assertEquals("mp4", DirectFileClassifier.extensionFor("video/mp4", "https://x/clip.mp4"))
    }

    @Test
    fun aUrlWithAQueryStillYieldsItsExtension() {
        assertEquals("mp4", DirectFileClassifier.extensionFor("application/octet-stream", "https://x/clip.mp4?token=abc"))
        assertEquals("jpg", DirectFileClassifier.extensionFor(null, "https://x/pic.jpg#fragment"))
    }

    @Test
    fun normalizeKeepsOnlyTheBareType() {
        assertEquals("image/jpeg", DirectFileClassifier.normalize("image/jpeg; charset=utf-8"))
        assertEquals("video/mp4", DirectFileClassifier.normalize("  VIDEO/MP4  "))
        assertNull(DirectFileClassifier.normalize(null))
        assertNull(DirectFileClassifier.normalize(""))
        assertNull(DirectFileClassifier.normalize("   "))
        // A bare subtype is not a content type and must not be treated as one.
        assertNull(DirectFileClassifier.normalize("mp4"))
    }

    // ── Downloadable or not ───────────────────────────────────────────────────

    @Test
    fun aWebPageIsNotADownloadableFile() {
        // This is the verdict that lets the registry fall through to a heavier engine.
        assertFalse(DirectFileClassifier.isDownloadable("text/html", "https://x/post/1"))
        assertFalse(DirectFileClassifier.isDownloadable("text/html; charset=utf-8", "https://x/post/1"))
        assertFalse(DirectFileClassifier.isDownloadable("TEXT/HTML", "https://x/post/1"))
        assertFalse(DirectFileClassifier.isDownloadable("application/xhtml+xml", "https://x/post/1"))
    }

    @Test
    fun scriptsStylesheetsAndFeedDocumentsAreNotFiles() {
        assertFalse(DirectFileClassifier.isDownloadable("text/css", "https://x/a.css"))
        assertFalse(DirectFileClassifier.isDownloadable("text/javascript", "https://x/a.js"))
        assertFalse(DirectFileClassifier.isDownloadable("application/javascript", "https://x/a.js"))
        assertFalse(DirectFileClassifier.isDownloadable("application/xml", "https://x/a.xml"))
        assertFalse(DirectFileClassifier.isDownloadable("application/rss+xml", "https://x/feed"))
    }

    @Test
    fun realFilesAreDownloadable() {
        for (type in listOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/svg+xml",
            "video/mp4",
            "video/webm",
            "audio/mpeg",
            "audio/ogg",
            "application/pdf",
            "application/zip",
            "application/epub+zip",
            "application/octet-stream",
            "application/json",
            "text/plain",
            "text/csv"
        )) {
            assertTrue("$type should be downloadable", DirectFileClassifier.isDownloadable(type, "https://x/y"))
        }
    }

    @Test
    fun anUnknownContentTypeIsTrustedUnlessItLooksLikeNothing() {
        assertTrue(DirectFileClassifier.isDownloadable("application/x-thing", "https://x/y"))
        assertTrue(DirectFileClassifier.isDownloadable("model/gltf-binary", "https://x/y"))
    }

    @Test
    fun withoutAContentTypeTheUrlDecides() {
        assertTrue(DirectFileClassifier.isDownloadable(null, "https://x/clip.mp4"))
        assertTrue(DirectFileClassifier.isDownloadable("", "https://x/pic.jpg"))
        // No extension means no evidence at all, so the probe refuses to call it a file.
        assertFalse(DirectFileClassifier.isDownloadable(null, "https://x/article"))
        assertFalse(DirectFileClassifier.isDownloadable(null, "https://x/"))
        // Even an explicit .html path is not in the direct-file extension list.
        assertFalse(DirectFileClassifier.isDownloadable(null, "https://x/page.html"))
    }

    // ── URL handling ──────────────────────────────────────────────────────────

    @Test
    fun onlyHttpAndHttpsUrlsAreSupported() {
        assertTrue(DirectFileClassifier.isHttpUrl("https://x/y"))
        assertTrue(DirectFileClassifier.isHttpUrl("http://x/y"))
        assertTrue(DirectFileClassifier.isHttpUrl("HTTPS://X/Y"))
        assertTrue(DirectFileClassifier.isHttpUrl("  https://x/y  "))
        assertFalse(DirectFileClassifier.isHttpUrl("ftp://x/y"))
        assertFalse(DirectFileClassifier.isHttpUrl("file:///etc/passwd"))
        assertFalse(DirectFileClassifier.isHttpUrl("content://media/external/downloads/1"))
        assertFalse(DirectFileClassifier.isHttpUrl(""))
        assertFalse(DirectFileClassifier.isHttpUrl("https//x"))
    }

    // ── Naming ────────────────────────────────────────────────────────────────

    @Test
    fun theServersFileNameWins() {
        assertEquals(
            "server-name.mp4",
            DirectFileClassifier.baseName(
                "attachment; filename=\"server-name.mp4\"",
                "https://x/url-name.mp4"
            )
        )
    }

    @Test
    fun theUrlPathIsTheFallbackName() {
        assertEquals("clip", DirectFileClassifier.baseName(null, "https://x/clip.mp4"))
        assertEquals("clip", DirectFileClassifier.baseName("", "https://x/clip.mp4"))
        assertEquals("clip", DirectFileClassifier.baseName("attachment", "https://x/clip.mp4"))
        assertEquals("clip", DirectFileClassifier.baseName(null, "https://x/clip.mp4?token=abc"))
        assertEquals("clip", DirectFileClassifier.baseName(null, "https://x/clip.mp4#frag"))
        assertEquals("abc123", DirectFileClassifier.baseName(null, "https://x/abc123"))
        assertNull(DirectFileClassifier.baseName(null, "https://x/"))
        assertNull(DirectFileClassifier.baseName(null, ""))
    }

    @Test
    fun theDisplayNameCombinesTheBaseNameAndTheExtension() {
        assertEquals(
            "clip.mp4",
            DirectFileClassifier.displayName(
                "attachment; filename=\"clip.mp4\"",
                "https://x/other",
                "video/mp4"
            )
        )
        assertEquals(
            "clip.mp4",
            DirectFileClassifier.displayName(null, "https://x/clip.mp4", "video/mp4")
        )
    }

    @Test
    fun theDisplayNameIsAlwaysSafe() {
        // A hostile Content-Disposition must not be able to escape the download folder.
        val name = DirectFileClassifier.displayName(
            "attachment; filename=\"../../etc/passwd\"",
            "https://x/y",
            "application/pdf"
        )
        assertEquals("passwd.pdf", name)
        assertTrue("'$name' is not a safe file name", FilenameSanitizer.isSafe(name))
    }

    @Test
    fun aUrlWithoutANameFallsBackToTheDefaultBase() {
        val name = DirectFileClassifier.displayName(null, "https://x/", "image/png")
        assertEquals("${FilenameSanitizer.DEFAULT_BASE}.png", name)
    }
}
