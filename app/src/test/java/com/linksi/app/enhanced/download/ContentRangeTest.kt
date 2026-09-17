package com.linksi.app.enhanced.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ContentRange].
 *
 * This is what tells the downloader whether the server really honoured a resume: if the body does
 * not start at the byte that is already on disk, appending to the file would corrupt it, so the
 * parse result is a safety check rather than a nicety.
 */
class ContentRangeTest {

    @Test
    fun aFullRangeIsParsed() {
        val range = ContentRange.parse("bytes 0-99/1000")!!
        assertEquals(0L, range.start)
        assertEquals(99L, range.end)
        assertEquals(1000L, range.total)
    }

    @Test
    fun aLateRangeIsParsed() {
        // The one-byte probe: "bytes 0-0/*" and the resume case "bytes 1000-9999/67589".
        val probe = ContentRange.parse("bytes 0-0/*")!!
        assertEquals(0L, probe.start)
        assertEquals(0L, probe.end)
        assertNull(probe.total)

        val resumed = ContentRange.parse("bytes 1000-9999/67589")!!
        assertEquals(1000L, resumed.start)
        assertEquals(9999L, resumed.end)
        assertEquals(67589L, resumed.total)
    }

    @Test
    fun anUnsatisfiableRangeIsRejected() {
        // "bytes */1000" is what a 416 sends: there is no start offset to append to.
        assertNull(ContentRange.parse("bytes */1000"))
        assertNull(ContentRange.parse("bytes */*"))
    }

    @Test
    fun aMissingOrBlankHeaderIsRejected() {
        assertNull(ContentRange.parse(null))
        assertNull(ContentRange.parse(""))
        assertNull(ContentRange.parse("   "))
    }

    @Test
    fun aMalformedHeaderIsRejected() {
        assertNull(ContentRange.parse("garbage"))
        assertNull(ContentRange.parse("bytes"))
        assertNull(ContentRange.parse("bytes "))
        assertNull(ContentRange.parse("bytes abc-10/1000"))
        assertNull(ContentRange.parse("bytes=0-99/1000"))
    }

    @Test
    fun anOpenEndedRangeIsAccepted() {
        val range = ContentRange.parse("bytes 500-")!!
        assertEquals(500L, range.start)
        assertNull(range.end)
        assertNull(range.total)
    }

    @Test
    fun aRangeWithoutAnEndOrTotalIsAccepted() {
        val range = ContentRange.parse("bytes 500")!!
        assertEquals(500L, range.start)
        assertNull(range.end)
        assertNull(range.total)
    }

    @Test
    fun anUnknownTotalIsNull() {
        assertNull(ContentRange.parse("bytes 0-99/abc")!!.total)
        assertNull(ContentRange.parse("bytes 0-99/")!!.total)
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        val range = ContentRange.parse("  bytes 0-99/1000  ")!!
        assertEquals(0L, range.start)
        assertEquals(1000L, range.total)
    }

    @Test
    fun aNegativeStartIsRejected() {
        assertNull(ContentRange.parse("bytes -10-99/1000"))
    }
}
