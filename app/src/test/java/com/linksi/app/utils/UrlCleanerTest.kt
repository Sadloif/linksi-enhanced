package com.linksi.app.utils

import com.linksi.app.utils.UrlCleaner.Options
import com.linksi.app.utils.UrlCleaner.Reason
import com.linksi.app.utils.UrlCleaner.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [UrlCleaner].
 *
 * Case identifiers in the comments map onto LINKSI_ENHANCED_REVISED_SPEC.md section 45 and
 * LINKSI_ENHANCED_TESTING_PLAN.md sections 19 to 27 and 92. These tests are pure JVM tests
 * (no Android dependency) and are mirrored into the standalone verification project so they
 * can be executed without the Android SDK.
 */
class UrlCleanerTest {

    /** Cleans [url], failing the test if the URL is rejected. */
    private fun cleaned(url: String, options: Options = Options()): String {
        val result = UrlCleaner.clean(url, options)
        assertTrue("expected '$url' to be cleanable, but got $result", result is Result.Cleaned)
        return (result as Result.Cleaned).cleanedUrl
    }

    /** Cleans [url] and returns the removed parameter list. */
    private fun removed(url: String, options: Options = Options()): List<String> {
        val result = UrlCleaner.clean(url, options)
        assertTrue("expected '$url' to be cleanable, but got $result", result is Result.Cleaned)
        return (result as Result.Cleaned).removedParameters
    }

    /** Asserts [url] is rejected and returns the reason. */
    private fun reasonFor(url: String): Reason {
        val result = UrlCleaner.clean(url)
        assertTrue("expected '$url' to be rejected, but got $result", result is Result.Invalid)
        return (result as Result.Invalid).reason
    }

    // ── Basic preservation (spec 45: HTTPS, HTTP, trailing slash) ────────────────

    @Test
    fun httpsUrlWithoutTrackingIsUnchanged() {
        assertEquals("https://example.com/article", cleaned("https://example.com/article"))
    }

    @Test
    fun httpUrlKeepsItsScheme() {
        // The cleaner must not silently upgrade http to https; that is normalizeUrl's job.
        assertEquals("http://example.com/article", cleaned("http://example.com/article"))
    }

    @Test
    fun bareHostIsUnchanged() {
        assertEquals("https://example.com", cleaned("https://example.com"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("https://example.com/a", cleaned("  https://example.com/a  "))
        assertEquals("https://example.com/a", cleaned("\n\thttps://example.com/a\r\n"))
    }

    @Test
    fun schemeIsCaseFolded() {
        assertEquals("https://example.com/A", cleaned("HTTPS://example.com/A"))
    }

    @Test
    fun authorityCaseIsPreserved() {
        // Only the scheme is case folded; the host is preserved rather than rewritten.
        assertEquals("https://EXAMPLE.com/A", cleaned("https://EXAMPLE.com/A"))
    }

    // ── Facebook (spec 9.4, plan 19) ────────────────────────────────────────────

    @Test
    fun facebookReelExampleFromSpecCleansExactly() {
        val input = "https://www.facebook.com/reel/1710485373378939/?referral_source=external_link" +
            "&surface_type=tab&in_reels_tab_context=TRUE"
        assertEquals("https://www.facebook.com/reel/1710485373378939", cleaned(input))
        assertEquals(
            listOf("referral_source", "surface_type", "in_reels_tab_context"),
            removed(input)
        )
    }

    /**
     * The same rule, on a link that was actually shared into the app on a device.
     *
     * This exact URL was handed to the share receiver through `am start -a android.intent.action.SEND`
     * on the emulator, saved, and then read back out of the app's own SQLite database, where it is
     * stored as `https://www.facebook.com/reel/28178846218433472` with both trackers gone. It is kept
     * here because that is the difference between the cleaner being unit tested and the cleaner being
     * **wired in**: the preview could show a cleaned URL while the stored row kept its trackers, and
     * only reading the row proves otherwise.
     */
    @Test
    fun aLinkSharedIntoTheAppIsStoredCleaned() {
        val input = "https://www.facebook.com/reel/28178846218433472/?utm_source=round4&fbclid=trackme99"
        assertEquals("https://www.facebook.com/reel/28178846218433472", cleaned(input))
        assertEquals(listOf("utm_source", "fbclid"), removed(input))
    }

    @Test
    fun facebookReelCleaningIsIdempotent() {
        val once = cleaned("https://www.facebook.com/reel/1710485373378939/?referral_source=external_link")
        assertEquals("https://www.facebook.com/reel/1710485373378939", once)
        assertEquals(once, cleaned(once))
    }

    @Test
    fun facebookMobileHostUsesTheSameRules() {
        assertEquals(
            "https://m.facebook.com/reel/123",
            cleaned("https://m.facebook.com/reel/123/?surface_type=tab")
        )
    }

    @Test
    fun fbWatchShortLinkIsCleaned() {
        assertEquals("https://fb.watch/abc123", cleaned("https://fb.watch/abc123/?referral_source=x"))
    }

    @Test
    fun facebookParamsAreKeptOnNonFacebookHosts() {
        // These names are generic; they are only tracking parameters on Facebook.
        val url = "https://example.com/watch?surface_type=tab&referral_source=x"
        assertEquals(url, cleaned(url))
    }

    @Test
    fun facebookUnknownParameterIsKept() {
        // __cft__ is a Facebook parameter but it is NOT in the centralized list, so it stays.
        val url = "https://www.facebook.com/reel/1?__cft__[0]=AZXabc"
        assertEquals(url, cleaned(url))
    }

    // ── Tracking parameters (spec 9.3, plan 20 and 21) ──────────────────────────

    @Test
    fun utmParametersAreRemovedAndMeaningfulParameterIsKept() {
        assertEquals(
            "https://example.com/article?id=42",
            cleaned("https://example.com/article?id=42&utm_source=facebook&utm_medium=social&utm_campaign=test")
        )
    }

    @Test
    fun everyKnownTrackingParameterIsRemoved() {
        for (parameter in UrlCleaner.TRACKING_PARAMETERS) {
            val url = "https://example.com/article?id=42&$parameter=tracking-value"
            assertEquals(
                "parameter '$parameter' should have been removed",
                "https://example.com/article?id=42",
                cleaned(url)
            )
        }
    }

    @Test
    fun utmPrefixFamilyCoversUnknownSuffixes() {
        assertEquals("https://example.com/a", cleaned("https://example.com/a?utm_custom_new=1"))
        assertEquals("https://example.com/a", cleaned("https://example.com/a?utm_source_platform=1"))
    }

    @Test
    fun trackingParameterMatchingIsCaseInsensitive() {
        assertEquals("https://example.com/a", cleaned("https://example.com/a?UTM_Source=x&FBCLID=y"))
    }

    @Test
    fun trackingParameterWithoutValueIsRemoved() {
        assertEquals("https://example.com/a", cleaned("https://example.com/a?utm_source"))
    }

    @Test
    fun percentEncodedTrackingParameterNameIsRemoved() {
        // utm%5Fsource decodes to utm_source
        assertEquals("https://example.com/a", cleaned("https://example.com/a?utm%5Fsource=x"))
    }

    @Test
    fun repeatedTrackingParametersAreAllRemoved() {
        assertEquals("https://example.com/a", cleaned("https://example.com/a?fbclid=a&fbclid=b"))
    }

    // ── Case preservation (spec 9.2, plan 22 - mandatory regression) ────────────

    @Test
    fun mixedCasePathAndQueryValueArePreserved() {
        assertEquals("https://example.com/File?id=AbC123", cleaned("https://example.com/File?id=AbC123"))
    }

    @Test
    fun mixedCaseIsNeverLowercased() {
        val input = "https://example.com/File?id=AbC123"
        val result = cleaned(input)
        assertFalse(
            "the cleaner must never lowercase the whole URL",
            result == input.lowercase()
        )
        assertTrue("path case must survive", result.contains("/File"))
        assertTrue("query value case must survive", result.contains("AbC123"))
    }

    @Test
    fun mixedCaseIsPreservedWhileTrackingIsRemoved() {
        assertEquals(
            "https://Example.COM/Dir/File?Name=AbC123",
            cleaned("https://Example.COM/Dir/File?Name=AbC123&utm_id=zzz")
        )
    }

    // ── Encoding robustness (plan 23) ───────────────────────────────────────────

    @Test
    fun percentEncodedPathIsNotCorrupted() {
        val url = "https://example.com/a%20b/c%2Fd"
        assertEquals(url, cleaned(url))
    }

    @Test
    fun utf8PercentEncodedQueryIsNotCorrupted() {
        val url = "https://example.com/s?q=%C3%84%C3%B6&name=Gr%C3%BC%C3%9Fe"
        assertEquals(url, cleaned(url))
    }

    @Test
    fun plusAndEncodedAmpersandArePreserved() {
        val url = "https://example.com/s?q=a+b%26c"
        assertEquals(url, cleaned(url))
    }

    @Test
    fun malformedPercentEscapeDoesNotCorruptTheUrl() {
        val url = "https://example.com/a%zzb?q=%"
        assertEquals(url, cleaned(url))
    }

    @Test
    fun internationalDomainNameIsAcceptedAndPreserved() {
        val url = "https://münchen.example/Straße?q=Ä"
        assertEquals(url, cleaned(url))
    }

    @Test
    fun userInfoIsPreserved() {
        val url = "https://user:pass@example.com/a"
        assertEquals(url, cleaned(url))
    }

    @Test
    fun explicitPortIsPreserved() {
        val url = "https://example.com:8443/Path?q=Value"
        assertEquals(url, cleaned(url))
    }

    // ── Fragments (spec 9.1.7, plan 23) ─────────────────────────────────────────

    @Test
    fun fragmentIsPreservedByDefault() {
        assertEquals("https://example.com/a#section-2", cleaned("https://example.com/a?utm_source=x#section-2"))
    }

    @Test
    fun fragmentOnlyUrlIsPreserved() {
        assertEquals("https://example.com/a#frag", cleaned("https://example.com/a#frag"))
    }

    @Test
    fun hashFragmentTrackerIsKeptByDefault() {
        // Removing fragments is opt-in because single page applications encode state there.
        assertEquals("https://example.com/a#utm_source=x", cleaned("https://example.com/a#utm_source=x"))
    }

    @Test
    fun fragmentIsRemovedOnlyWhenExplicitlyRequested() {
        // The meaningful parameter is kept; only the fragment is dropped.
        assertEquals(
            "https://example.com/a?id=42",
            cleaned(
                "https://example.com/a?id=42#section-2",
                Options(removeFragment = true)
            )
        )
        // Without the option the fragment survives untouched.
        assertEquals(
            "https://example.com/a?id=42#section-2",
            cleaned("https://example.com/a?id=42#section-2")
        )
    }

    // ── Unknown and meaningful parameters (plan 24) ─────────────────────────────

    @Test
    fun unknownParameterIsKept() {
        val url = "https://example.com/item?customImportantValue=ABC123"
        assertEquals(url, cleaned(url))
    }

    @Test
    fun repeatedMeaningfulParametersAreKept() {
        assertEquals("https://example.com/p?tag=a&tag=b", cleaned("https://example.com/p?tag=a&tag=b&fbclid=x"))
    }

    @Test
    fun parameterOrderIsPreserved() {
        assertEquals("https://example.com/p?b=2&a=1", cleaned("https://example.com/p?b=2&a=1&fbclid=x"))
    }

    @Test
    fun emptyValueIsKeptByDefault() {
        assertEquals("https://example.com/p?a=", cleaned("https://example.com/p?a="))
    }

    @Test
    fun emptyValueIsRemovedWhenRequested() {
        assertEquals(
            "https://example.com/p?b=1",
            cleaned("https://example.com/p?a=&b=1", Options(removeEmptyParameters = true))
        )
    }

    @Test
    fun valueContainingEqualsSignIsPreserved() {
        assertEquals("https://example.com/p?token=a=b", cleaned("https://example.com/p?token=a=b"))
    }

    @Test
    fun emptyQuerySegmentsAreDropped() {
        assertEquals("https://example.com/p?a=1&b=2", cleaned("https://example.com/p?a=1&&b=2&"))
    }

    @Test
    fun queryWithNoParametersIsDropped() {
        assertEquals("https://example.com/p", cleaned("https://example.com/p?"))
    }

    @Test
    fun trackingRemovalCanBeDisabled() {
        val url = "https://example.com/a?utm_source=x"
        assertEquals(url, cleaned(url, Options(removeTrackingParameters = false)))
    }

    // ── Trailing slash handling (plan 45) ───────────────────────────────────────

    @Test
    fun oneTrailingSlashIsRemovedFromThePath() {
        assertEquals("https://example.com/article", cleaned("https://example.com/article/"))
    }

    @Test
    fun onlyOneTrailingSlashIsRemoved() {
        assertEquals("https://example.com/article/", cleaned("https://example.com/article//"))
    }

    @Test
    fun rootTrailingSlashBecomesBareHost() {
        assertEquals("https://example.com", cleaned("https://example.com/"))
    }

    // ── Malformed input (plan 25) ───────────────────────────────────────────────

    @Test
    fun blankInputIsRejected() {
        assertEquals(Reason.BLANK, reasonFor(""))
        assertEquals(Reason.BLANK, reasonFor("     "))
        assertEquals(Reason.BLANK, reasonFor("\n\t "))
    }

    @Test
    fun missingSchemeIsRejected() {
        assertEquals(Reason.MISSING_SCHEME, reasonFor("example.com/path"))
        assertEquals(Reason.MISSING_SCHEME, reasonFor("not a url"))
        assertEquals(Reason.MISSING_SCHEME, reasonFor("example"))
    }

    @Test
    fun schemeWithoutHostIsRejected() {
        assertEquals(Reason.MISSING_HOST, reasonFor("http://"))
        assertEquals(Reason.MISSING_HOST, reasonFor("https://"))
    }

    @Test
    fun nonHttpSchemeIsRejected() {
        assertEquals(Reason.UNSUPPORTED_SCHEME, reasonFor("ftp://example.com/a"))
        assertEquals(Reason.UNSUPPORTED_SCHEME, reasonFor("mailto:someone@example.com"))
        assertEquals(Reason.UNSUPPORTED_SCHEME, reasonFor("javascript:alert(1)"))
    }

    @Test
    fun supportedSchemeWithBrokenSeparatorIsMalformed() {
        assertEquals(Reason.MALFORMED, reasonFor("http:/example.com"))
        assertEquals(Reason.MALFORMED, reasonFor("https:example.com"))
        // A schemeless host with a port is a missing scheme, not an unsupported one.
        assertEquals(Reason.MISSING_SCHEME, reasonFor("example.com:8080/path"))
    }

    @Test
    fun hostWithoutDotIsRejected() {
        assertEquals(Reason.MALFORMED, reasonFor("https://example/x"))
    }

    @Test
    fun whitespaceInsideAuthorityIsRejected() {
        assertEquals(Reason.MALFORMED, reasonFor("https://exa mple.com/x"))
    }

    @Test
    fun rejectedInputsKeepTheOriginalStringIntact() {
        val input = "not a url"
        val result = UrlCleaner.clean(input)
        assertTrue(result is Result.Invalid)
        assertEquals(input, (result as Result.Invalid).originalUrl)
    }

    @Test
    fun cleanOrSelfFallsBackToTheOriginalUrl() {
        assertEquals("not a url", UrlCleaner.cleanOrSelf("not a url"))
        assertEquals("https://example.com/a", UrlCleaner.cleanOrSelf("https://example.com/a?fbclid=x"))
    }

    @Test
    fun cleanOrNullReportsFailure() {
        assertNull(UrlCleaner.cleanOrNull("not a url"))
        assertEquals("https://example.com/a", UrlCleaner.cleanOrNull("https://example.com/a?fbclid=x"))
    }

    @Test
    fun hostileInputsNeverThrow() {
        val inputs = listOf(
            "://example.com",
            "http:/example.com",
            "javascript:alert(1)",
            "data:text/html;base64,AAAA",
            "https://%%%/a",
            "https://example.com/?=&&=&",
            "https://[::1]:8080/a",
            "https://example.com/" + "a".repeat(5000) + "?utm_source=" + "x".repeat(5000)
        )
        for (input in inputs) {
            // The only contract: no exception, and a rejection preserves the input verbatim.
            when (val result = UrlCleaner.clean(input)) {
                is Result.Invalid -> assertEquals(input, result.originalUrl)
                is Result.Cleaned -> assertTrue(result.cleanedUrl.isNotEmpty())
            }
        }
    }

    // ── Long URLs (plan 92) ─────────────────────────────────────────────────────

    @Test
    fun veryLongUrlWithMixedParametersIsHandledSafely() {
        val path = "a".repeat(300)
        val trackers = (1..30).joinToString("&") { "utm_param_$it=v$it" }
        val url = "https://example.com/$path?id=42&$trackers&custom=KeepMe"
        val result = cleaned(url)
        assertEquals("https://example.com/$path?id=42&custom=KeepMe", result)
        assertEquals(30, removed(url).size)
    }

    // ── Idempotence ─────────────────────────────────────────────────────────────

    @Test
    fun cleaningIsIdempotent() {
        val inputs = listOf(
            "https://www.facebook.com/reel/1710485373378939/?referral_source=external_link&surface_type=tab",
            "https://example.com/article?id=42&utm_source=facebook",
            "https://example.com/a/?utm_source=x",
            "https://example.com/p?a=1&&b=2&",
            "https://example.com/File?id=AbC123",
            "https://example.com/a#frag"
        )
        for (input in inputs) {
            val once = cleaned(input)
            assertEquals("cleaning '$input' was not idempotent", once, cleaned(once))
        }
    }

    // ── Helper predicates ───────────────────────────────────────────────────────

    @Test
    fun facebookHostDetectionIsCaseInsensitiveAndSuffixBased() {
        assertTrue(UrlCleaner.isFacebookHost("facebook.com"))
        assertTrue(UrlCleaner.isFacebookHost("www.facebook.com"))
        assertTrue(UrlCleaner.isFacebookHost("m.facebook.com"))
        assertTrue(UrlCleaner.isFacebookHost("FACEBOOK.COM"))
        assertTrue(UrlCleaner.isFacebookHost("fb.watch"))
        assertFalse(UrlCleaner.isFacebookHost("notfacebook.com"))
        assertFalse(UrlCleaner.isFacebookHost("example.com"))
    }

    @Test
    fun trackingParameterPredicateMatchesTheCentralRegistry() {
        assertTrue(UrlCleaner.isTrackingParameter("utm_source"))
        assertTrue(UrlCleaner.isTrackingParameter("utm_anything"))
        assertTrue(UrlCleaner.isTrackingParameter("fbclid"))
        assertFalse(UrlCleaner.isTrackingParameter("id"))
        assertFalse(UrlCleaner.isTrackingParameter(""))
        assertFalse(UrlCleaner.isTrackingParameter("referral_source"))
        assertTrue(UrlCleaner.isTrackingParameter("referral_source", isFacebookHost = true))
    }
}
