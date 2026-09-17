package com.linksi.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the URL helpers in `UrlNormalizer.kt`.
 *
 * The important regression here is spec section 9.2 / acceptance criterion 70.13: normalisation
 * must never lowercase the whole URL, because paths and query values are case sensitive.
 *
 * The legacy implementation returned `result.lowercase()` from the `try` block (and
 * `normalized.lowercase()` from the `catch`), so `https://example.com/File?id=AbC123` was stored
 * as `https://example.com/file?id=abc123`. Database migration 11 to 12 did the same to every
 * previously stored row, which is why existing data cannot be repaired.
 */
class UrlNormalizerTest {

    // ── Case preservation (mandatory) ───────────────────────────────────────────

    @Test
    fun mixedCasePathAndQueryArePreservedExactly() {
        assertEquals("https://example.com/File?id=AbC123", normalizeUrl("https://example.com/File?id=AbC123"))
    }

    @Test
    fun wholeUrlIsNeverLowercased() {
        val input = "https://example.com/File?id=AbC123"
        assertNotEquals(input.lowercase(), normalizeUrl(input))
    }

    @Test
    fun authorityCaseIsPreserved() {
        assertEquals("https://Example.COM/A", normalizeUrl("https://Example.COM/A"))
    }

    @Test
    fun trackingParametersAreLeftAloneByNormalisation() {
        // Cleaning and normalising are separate concerns; normalizeUrl must not drop parameters.
        val url = "https://example.com/A?utm_source=x&Id=AbC"
        assertEquals(url, normalizeUrl(url))
    }

    // ── Scheme handling ─────────────────────────────────────────────────────────

    @Test
    fun schemeLessInputGetsHttps() {
        assertEquals("https://example.com/File", normalizeUrl("example.com/File"))
    }

    @Test
    fun httpIsUpgradedToHttps() {
        assertEquals("https://example.com/File", normalizeUrl("http://example.com/File"))
    }

    @Test
    fun uppercaseHttpSchemeIsRebuiltInsteadOfDoubled() {
        // Legacy behaviour produced "https://HTTP://example.com/File" here.
        assertEquals("https://Example.COM/File", normalizeUrl("HTTP://Example.COM/File"))
    }

    @Test
    fun uppercaseHttpsSchemeIsFoldedToLowerCase() {
        assertEquals("https://Example.COM/File", normalizeUrl("HtTpS://Example.COM/File"))
    }

    // ── Whitespace and trailing slash ───────────────────────────────────────────

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("https://example.com/a", normalizeUrl("  https://example.com/a  "))
    }

    @Test
    fun blankInputBecomesEmpty() {
        assertEquals("", normalizeUrl("   "))
        assertEquals("", normalizeUrl(""))
    }

    @Test
    fun oneTrailingSlashIsRemoved() {
        assertEquals("https://example.com/a", normalizeUrl("https://example.com/a/"))
    }

    @Test
    fun rootTrailingSlashBecomesBareHost() {
        assertEquals("https://example.com", normalizeUrl("https://example.com/"))
    }

    // ── Encoding and dot segments ───────────────────────────────────────────────

    @Test
    fun percentEncodingIsNotCorrupted() {
        val url = "https://example.com/a%20b?q=%C3%84"
        assertEquals(url, normalizeUrl(url))
    }

    @Test
    fun dotSegmentsAreResolved() {
        assertEquals("https://example.com/b", normalizeUrl("https://example.com/a/../b"))
    }

    @Test
    fun dotSegmentsAreResolvedWithoutLosingCase() {
        assertEquals("https://example.com/File", normalizeUrl("https://example.com/a%20b/../File"))
    }

    @Test
    fun unparseableInputIsReturnedWithoutCaseFolding() {
        // A space in the authority makes java.net.URI throw. The legacy catch branch lowercased
        // the input; now it is returned exactly as received.
        val url = "https://exa mple.com/File"
        assertEquals(url, normalizeUrl(url))
    }

    @Test
    fun normalisationIsIdempotent() {
        val inputs = listOf(
            "https://example.com/File?id=AbC123",
            "http://Example.com/Dir/File/",
            "example.com/a/../b",
            "https://example.com/a%20b?q=%C3%84"
        )
        for (input in inputs) {
            val once = normalizeUrl(input)
            assertEquals("normalising '$input' was not idempotent", once, normalizeUrl(once))
        }
    }

    // ── extractDomain ───────────────────────────────────────────────────────────

    @Test
    fun extractDomainStripsWww() {
        assertEquals("example.com", extractDomain("https://www.example.com/a"))
    }

    @Test
    fun extractDomainKeepsSubdomains() {
        assertEquals("blog.example.com", extractDomain("https://blog.example.com/a"))
    }

    @Test
    fun extractDomainFallsBackToTheRawInputWhenUnparseable() {
        assertEquals("not a url", extractDomain("not a url"))
    }

    // ── isValidUrl ──────────────────────────────────────────────────────────────

    @Test
    fun validUrlsAreAccepted() {
        assertTrue(isValidUrl("https://example.com/a"))
        assertTrue(isValidUrl("http://example.com/a"))
        assertTrue(isValidUrl("example.com/a"))
        assertTrue(isValidUrl("HTTP://example.com/a"))
    }

    @Test
    fun invalidUrlsAreRejected() {
        assertFalse(isValidUrl(""))
        assertFalse(isValidUrl("    "))
        assertFalse(isValidUrl("not a url"))
        assertFalse(isValidUrl("ftp://example.com/a"))
        assertFalse(isValidUrl("https://example/x"))
    }
}
