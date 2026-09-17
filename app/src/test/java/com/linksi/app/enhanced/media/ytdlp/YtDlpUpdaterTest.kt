package com.linksi.app.enhanced.media.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the zipapp check that guards an engine replacement.
 *
 * The check exists because a bad update is worse than no update: the release-download URL answers
 * with an HTML error page often enough to matter, and installing one would replace a working site
 * engine with a file that cannot run. The executable check that ultimately protects the user is
 * running the candidate before installing it - this only stops the obviously-wrong bytes early.
 *
 * The real shebangs are included verbatim, because rejecting a *good* update is the failure mode that
 * would matter most in practice: every current yt-dlp release for Android is a zipapp with one of the
 * two spellings below.
 */
class YtDlpUpdaterTest {

    private fun head(text: String, zip: Boolean = true): ByteArray {
        val prefix = text.toByteArray(Charsets.US_ASCII)
        val magic = if (zip) byteArrayOf(0x50, 0x4B, 0x03, 0x04) else byteArrayOf(0x3C, 0x21, 0x2D, 0x2D)
        return prefix + magic + ByteArray(64) { 0x41 }
    }

    @Test
    fun theEnvShebangFormIsAccepted() {
        assertTrue(looksLikeZipAppHead(head("#!/usr/bin/env python3\n")))
    }

    @Test
    fun theDirectInterpreterShebangFormIsAccepted() {
        // Older and platform-specific builds use this spelling; rejecting it would refuse a valid
        // update and leave the user on a broken extractor.
        assertTrue(looksLikeZipAppHead(head("#!/usr/bin/python3\n")))
    }

    @Test
    fun aVersionedInterpreterIsAccepted() {
        assertTrue(looksLikeZipAppHead(head("#!/usr/bin/env python3.11\n")))
    }

    @Test
    fun anHtmlErrorPageIsRejected() {
        // What the release URL actually returns when something goes wrong.
        val html = "<!DOCTYPE html><html><head><title>Not Found</title></head>".toByteArray(Charsets.US_ASCII)
        assertFalse(looksLikeZipAppHead(html))
    }

    @Test
    fun aNonPythonScriptIsRejected() {
        assertFalse(looksLikeZipAppHead(head("#!/bin/sh\n", zip = false)))
    }

    @Test
    fun aPythonScriptWithoutTheZipPayloadIsRejected() {
        // A plain .py file is not the release asset: it has no bundled modules and would run, then
        // fail on the first import.
        assertFalse(looksLikeZipAppHead(head("#!/usr/bin/env python3\n", zip = false)))
    }

    @Test
    fun aHeadThatStopsBeforeTheZipHeaderIsRejected() {
        val tooShort = "#!/usr/bin/env python3\n".toByteArray(Charsets.US_ASCII)
        assertFalse(looksLikeZipAppHead(tooShort))
        assertFalse(looksLikeZipAppHead(ByteArray(0)))
    }

    @Test
    fun anEmptyOrMissingFileIsRejected() {
        val missing = java.io.File("does-not-exist-${System.nanoTime()}")
        assertFalse(looksLikeZipApp(missing))
    }

    @Test
    fun aShebangWithoutANewlineLineIsRejected() {
        // No line terminator at all: not a script, however python-ish it looks.
        assertFalse(looksLikeZipAppHead("#!/usr/bin/env python3".toByteArray(Charsets.US_ASCII)))
    }

    // ── Version ordering: the check that refuses a suspicious "update" ──────────────────────────

    @Test
    fun aLaterReleaseDateIsNewer() {
        assertTrue(compareVersions("2026.08.19", "2024.09.27") > 0)
    }

    @Test
    fun anEarlierReleaseDateIsNotNewer() {
        // The real case: a device fetched the published URL and was handed bytes that reported the
        // version already installed. Installing them would have been pointless at best.
        assertTrue(compareVersions("2024.09.27", "2026.08.19") < 0)
    }

    @Test
    fun theSameVersionIsEqual() {
        assertEquals(0, compareVersions("2024.09.27", "2024.09.27"))
    }

    @Test
    fun versionOrderingIsNumericNotAlphabetical() {
        // Lexicographic order compares the first differing *character*, so a leading "2024" sorts
        // before "2026" here only by luck of the digits. The case that matters is same-length dates
        // where the month differs, which plain string comparison gets backwards.
        assertTrue("string order disagrees with release order", "2024.09.27" < "2024.10.01")
        assertTrue(
            "the numeric comparison must follow the calendar, not the characters",
            compareVersions("2024.09.27", "2024.10.01") < 0
        )
        assertTrue(compareVersions("2024.09.27", "2026.08.19") < 0)
    }

    @Test
    fun aMissingOrUnparseableVersionNeverWins() {
        assertTrue("an unknown candidate must not displace a known engine", compareVersions(null, "2024.09.27") < 0)
        assertTrue(compareVersions("", "2024.09.27") < 0)
        assertTrue(compareVersions("not-a-version", "2024.09.27") < 0)
        assertTrue("and an unknown installed version does not block a real one", compareVersions("2026.08.19", null) > 0)
    }

    @Test
    fun aNightlyStyleSuffixIsIgnored() {
        assertTrue(compareVersions("2026.08.19.dev0", "2026.08.18") > 0)
        assertEquals(0, compareVersions("2026.08.19.0", "2026.08.19"))
    }}
