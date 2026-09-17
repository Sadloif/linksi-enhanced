package com.linksi.app.enhanced.media.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ContentDisposition].
 *
 * Servers disagree wildly about this header, so the parser is pinned against everything seen in
 * the wild: quoted, unquoted, RFC 5987 extended, both at once, and absent. The invariant that
 * matters most is the last group: a hostile header must never produce a path.
 */
class ContentDispositionTest {

    // ── Missing and empty ─────────────────────────────────────────────────────

    @Test
    fun aMissingHeaderYieldsNoName() {
        assertNull(ContentDisposition.filename(null))
        assertNull(ContentDisposition.filename(""))
        assertNull(ContentDisposition.filename("   "))
    }

    @Test
    fun aDispositionWithoutAFilenameYieldsNoName() {
        assertNull(ContentDisposition.filename("attachment"))
        assertNull(ContentDisposition.filename("inline"))
        assertNull(ContentDisposition.filename("attachment; size=1024"))
    }

    @Test
    fun anEmptyFilenameYieldsNoName() {
        assertNull(ContentDisposition.filename("attachment; filename="))
        assertNull(ContentDisposition.filename("attachment; filename=\"\""))
        assertNull(ContentDisposition.filename("attachment; filename=\"   \""))
    }

    // ── The plain form ────────────────────────────────────────────────────────

    @Test
    fun aQuotedFilenameIsUnquoted() {
        assertEquals("clip.mp4", ContentDisposition.filename("attachment; filename=\"clip.mp4\""))
    }

    @Test
    fun anUnquotedFilenameIsAccepted() {
        assertEquals("clip.mp4", ContentDisposition.filename("attachment; filename=clip.mp4"))
        assertEquals("clip.mp4", ContentDisposition.filename("attachment;filename=clip.mp4"))
    }

    @Test
    fun spacesInsideAQuotedFilenameSurvive() {
        assertEquals("my clip.mp4", ContentDisposition.filename("inline; filename=\"my clip.mp4\""))
    }

    @Test
    fun theParameterNameIsCaseInsensitive() {
        assertEquals("clip.mp4", ContentDisposition.filename("ATTACHMENT; FILENAME=\"clip.mp4\""))
        assertEquals("clip.mp4", ContentDisposition.filename("attachment; FileName=clip.mp4"))
    }

    @Test
    fun asemicolonInsideQuotesDoesNotEndTheParameter() {
        assertEquals("a;b.mp4", ContentDisposition.filename("attachment; filename=\"a;b.mp4\""))
    }

    @Test
    fun laterParametersDoNotConfuseTheParser() {
        assertEquals(
            "clip.mp4",
            ContentDisposition.filename("attachment; filename=\"clip.mp4\"; size=1024")
        )
        assertEquals(
            "logo.png",
            ContentDisposition.filename("form-data; name=\"file\"; filename=\"logo.png\"")
        )
    }

    // ── The RFC 5987 extended form ────────────────────────────────────────────

    @Test
    fun anExtendedFilenameIsPercentDecoded() {
        assertEquals(
            "naïve clip.mp4",
            ContentDisposition.filename("attachment; filename*=UTF-8''na%C3%AFve%20clip.mp4")
        )
    }

    @Test
    fun theExtendedFormBeatsThePlainForm() {
        // RFC 6266 section 4.3: filename* is the correct one when both are present.
        assertEquals(
            "real.mp4",
            ContentDisposition.filename("attachment; filename=\"fallback.mp4\"; filename*=UTF-8''real.mp4")
        )
    }

    @Test
    fun aLanguageTagIsAccepted() {
        assertEquals("clip.mp4", ContentDisposition.filename("attachment; filename*=utf-8'en'clip.mp4"))
        assertEquals("clip.mp4", ContentDisposition.filename("attachment; filename*=UTF-8''clip.mp4"))
    }

    @Test
    fun anUnknownCharsetFallsBackToUtf8() {
        assertEquals(
            "naïve.mp4",
            ContentDisposition.filename("attachment; filename*=x-nonsense''na%C3%AFve.mp4")
        )
    }

    @Test
    fun nonAsciiNamesSurvive() {
        assertEquals(
            "文件.mp4",
            ContentDisposition.filename("attachment; filename*=UTF-8''%E6%96%87%E4%BB%B6.mp4")
        )
    }

    @Test
    fun aQuotedExtendedFilenameIsStillDecoded() {
        // Not RFC-conformant, but some servers send it.
        assertEquals("clip.mp4", ContentDisposition.filename("attachment; filename*=\"UTF-8''clip.mp4\""))
    }

    @Test
    fun aPlusIsALiteralPlusNotASpace() {
        // RFC 5987 is not a form encoding; decoding '+' as a space would corrupt the name.
        assertEquals("a+b.mp4", ContentDisposition.filename("attachment; filename*=UTF-8''a+b.mp4"))
    }

    @Test
    fun anInvalidEscapeIsCopiedRatherThanThrowing() {
        assertEquals("bad%ZZ.mp4", ContentDisposition.filename("attachment; filename*=UTF-8''bad%ZZ.mp4"))
    }

    @Test
    fun anExtendedValueWithoutAnEncodingPrefixIsRejected() {
        // "clip.mp4" is not charset'lang'value, so the plain parameter (absent here) wins.
        assertNull(ContentDisposition.filename("attachment; filename*=clip.mp4"))
    }

    @Test
    fun anEmptyExtendedValueYieldsNoName() {
        assertNull(ContentDisposition.filename("attachment; filename*=UTF-8''"))
    }

    // ── Hostile input ─────────────────────────────────────────────────────────

    @Test
    fun directoryComponentsAreStrippedFromAQuotedName() {
        assertEquals("passwd", ContentDisposition.filename("attachment; filename=\"../../etc/passwd\""))
        assertEquals("clip.mp4", ContentDisposition.filename("attachment; filename=\"C:\\Users\\clip.mp4\""))
        assertEquals("clip.mp4", ContentDisposition.filename("attachment; filename=\"/tmp/clip.mp4\""))
    }

    @Test
    fun directoryComponentsAreStrippedFromAnExtendedName() {
        // Percent-encoded separators must not survive decoding.
        assertEquals("passwd", ContentDisposition.filename("attachment; filename*=UTF-8''%2Fetc%2Fpasswd"))
        assertEquals("passwd", ContentDisposition.filename("attachment; filename*=UTF-8''..%2F..%2Fetc%2Fpasswd"))
    }

    @Test
    fun aNameOfOnlySeparatorsYieldsNoName() {
        assertNull(ContentDisposition.filename("attachment; filename=\"/\""))
        assertNull(ContentDisposition.filename("attachment; filename=\"../../\""))
    }

    @Test
    fun theResultNeverContainsAPathSeparator() {
        val headers = listOf(
            "attachment; filename=\"../../etc/passwd\"",
            "attachment; filename*=UTF-8''%2Fetc%2Fpasswd",
            "attachment; filename=\"a/b/c/d.mp4\"",
            "attachment; filename*=UTF-8''a%5Cb%5Cclip.mp4"
        )
        for (header in headers) {
            val name = ContentDisposition.filename(header)
            if (name != null) {
                assertEquals("'$name' from '$header' still contains a separator", false, name.contains('/'))
                assertEquals("'$name' from '$header' still contains a separator", false, name.contains('\\'))
            }
        }
    }
}
