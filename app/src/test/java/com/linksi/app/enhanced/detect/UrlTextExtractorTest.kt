package com.linksi.app.enhanced.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [UrlTextExtractor].
 *
 * Case identifiers in the comments map onto LINKSI_ENHANCED_REVISED_SPEC.md sections 10, 11 and 57.
 * This is the privacy boundary: text without an HTTP(S) URL must yield nothing at all, so that
 * plain prose is never stored, logged or uploaded.
 */
class UrlTextExtractorTest {

    /** Asserts the text yields exactly [expected] URLs. */
    private fun assertUrls(expected: List<String>, text: String?) {
        assertEquals("wrong URLs for '$text'", expected, UrlTextExtractor.allHttpUrls(text))
    }

    // ── URLs inside surrounding prose (spec 10) ────────────────────────────────

    @Test
    fun aUrlInsideASentenceIsFoundTogetherWithItsPath() {
        assertEquals(
            listOf("https://www.instagram.com/reel/ABC123/"),
            UrlTextExtractor.allHttpUrls("Look at this https://www.instagram.com/reel/ABC123/ it is great")
        )
    }

    @Test
    fun aUrlAtTheVeryStartOrEndOfTheTextIsFound() {
        assertEquals(listOf("https://example.com/a"), UrlTextExtractor.allHttpUrls("https://example.com/a is the link"))
        assertEquals(listOf("https://example.com/a"), UrlTextExtractor.allHttpUrls("the link is https://example.com/a"))
        assertEquals(listOf("https://example.com/a"), UrlTextExtractor.allHttpUrls("https://example.com/a"))
    }

    @Test
    fun httpAndHttpsAreBothAcceptedAndTheSchemeCaseIsKeptVerbatim() {
        assertEquals(listOf("http://example.com/a"), UrlTextExtractor.allHttpUrls("see http://example.com/a"))
        assertEquals(listOf("HTTPS://example.com/a"), UrlTextExtractor.allHttpUrls("see HTTPS://example.com/a"))
    }

    @Test
    fun nonHttpSchemesAreIgnored() {
        assertNull(UrlTextExtractor.firstHttpUrl("ftp://example.com/a"))
        assertNull(UrlTextExtractor.firstHttpUrl("mailto:someone@example.com"))
        assertNull(UrlTextExtractor.firstHttpUrl("content://media/external/images/1"))
        assertNull(UrlTextExtractor.firstHttpUrl("javascript:alert(1)"))
        // "httpx://" is not a supported scheme either.
        assertNull(UrlTextExtractor.firstHttpUrl("httpx://example.com/a"))
    }

    @Test
    fun aBareDomainInProseIsNotAUrl() {
        // The bubble must not offer to open "example.com" typed inside a sentence.
        assertNull(UrlTextExtractor.firstHttpUrl("visit example.com for more"))
        assertFalse(UrlTextExtractor.containsHttpUrl("visit example.com for more"))
    }

    // ── Trailing punctuation (spec 10) ─────────────────────────────────────────

    @Test
    fun sentencePunctuationAtTheEndOfAUrlIsStripped() {
        assertEquals(listOf("https://example.com/a"), UrlTextExtractor.allHttpUrls("see https://example.com/a."))
        assertEquals(listOf("https://example.com/a"), UrlTextExtractor.allHttpUrls("see https://example.com/a,"))
        assertEquals(listOf("https://example.com/a"), UrlTextExtractor.allHttpUrls("see https://example.com/a;"))
        assertEquals(listOf("https://example.com/a"), UrlTextExtractor.allHttpUrls("see https://example.com/a!"))
        assertEquals(listOf("https://example.com/a"), UrlTextExtractor.allHttpUrls("see https://example.com/a:"))
        // Several trailing marks in a row must all go.
        assertEquals(listOf("https://example.com/a"), UrlTextExtractor.allHttpUrls("see https://example.com/a.)."))
    }

    @Test
    fun aQueryOrFragmentDelimiterAtTheEndOfAUrlIsKept() {
        // Only the punctuation in ALWAYS_TRAILING (and unbalanced brackets) is stripped. "?" and "#"
        // are URL syntax, so a trailing one always survives.
        assertEquals(listOf("https://example.com/a?"), UrlTextExtractor.allHttpUrls("see https://example.com/a?"))
        assertEquals(listOf("https://example.com/a#"), UrlTextExtractor.allHttpUrls("see https://example.com/a#"))
    }

    @Test
    fun anUnbalancedClosingBracketIsStrippedButABalancedOneIsKept() {
        assertEquals(
            listOf("https://example.com/a"),
            UrlTextExtractor.allHttpUrls("(see https://example.com/a)")
        )
        // A query value that genuinely contains balanced brackets must survive intact.
        assertEquals(
            listOf("https://example.com/x?q=(a)"),
            UrlTextExtractor.allHttpUrls("see https://example.com/x?q=(a)")
        )
        assertEquals(
            listOf("https://example.com/wiki/Foo_(bar)"),
            UrlTextExtractor.allHttpUrls("see https://example.com/wiki/Foo_(bar)")
        )
    }

    @Test
    fun anUnbalancedBracketInTheMiddleOfALongerProseMatchIsAlsoStripped() {
        // The leading "(" is captured by the regex, which used to leave the closing bracket behind.
        assertEquals(
            listOf("https://example.com/a"),
            UrlTextExtractor.allHttpUrls("see (https://example.com/a)")
        )
    }

    @Test
    fun squareAndCurlyBracketsFollowTheSameBalanceRule() {
        assertEquals(listOf("https://example.com/a"), UrlTextExtractor.allHttpUrls("[https://example.com/a]"))
        assertEquals(listOf("https://example.com/a"), UrlTextExtractor.allHttpUrls("{https://example.com/a}"))
        assertEquals(
            listOf("https://example.com/a?arr[]=1"),
            UrlTextExtractor.allHttpUrls("https://example.com/a?arr[]=1")
        )
    }

    @Test
    fun aUrlInsideAngleBracketsIsBoundedByTheRegex() {
        // < and > are excluded from the match, so autolink style text yields a clean URL.
        assertEquals(listOf("https://example.com/a"), UrlTextExtractor.allHttpUrls("see <https://example.com/a>"))
    }

    @Test
    fun aUrlInsideQuotesIsBoundedByTheRegex() {
        assertEquals(listOf("https://example.com/a"), UrlTextExtractor.allHttpUrls("the link \"https://example.com/a\" here"))
        assertEquals(listOf("https://example.com/a"), UrlTextExtractor.allHttpUrls("it's at 'https://example.com/a'"))
    }

    @Test
    fun stripTrailingPunctuationLeavesACleanUrlAlone() {
        assertEquals("https://example.com/a", UrlTextExtractor.stripTrailingPunctuation("https://example.com/a"))
        assertEquals("https://example.com/a?b=c#d", UrlTextExtractor.stripTrailingPunctuation("https://example.com/a?b=c#d"))
        // The helper is what performs the stripping, so it removes the sentence punctuation itself.
        assertEquals("https://example.com/a", UrlTextExtractor.stripTrailingPunctuation("https://example.com/a."))
        assertEquals("https://example.com/a", UrlTextExtractor.stripTrailingPunctuation("https://example.com/a.)"))
    }

    // ── Multiple URLs and de-duplication ──────────────────────────────────────

    @Test
    fun aSharedTextWithSeveralUrlsYieldsAllOfThemInOrder() {
        assertUrls(
            listOf("https://a.example/1", "https://b.example/2", "https://c.example/3"),
            "first https://a.example/1 then https://b.example/2 and finally https://c.example/3"
        )
    }

    @Test
    fun duplicateUrlsAreReportedOnlyOnce() {
        assertUrls(
            listOf("https://a.example/1", "https://b.example/2"),
            "https://a.example/1 https://b.example/2 https://a.example/1"
        )
    }

    @Test
    fun deDuplicationHappensAfterPunctuationIsStripped() {
        // The same URL appears once with a full stop and once without; it is one link.
        assertUrls(
            listOf("https://a.example/1"),
            "see https://a.example/1. then https://a.example/1 again"
        )
    }

    @Test
    fun caseDifferencesInTheHostAreTreatedAsSeparateStrings() {
        // The extractor does not normalise: that is UrlNormalizer's job, and de-duplication is
        // deliberately a verbatim string comparison.
        assertUrls(
            listOf("https://a.example/1", "https://A.example/1"),
            "https://a.example/1 and https://A.example/1"
        )
    }

    @Test
    fun firstHttpUrlReturnsTheFirstMatchOnly() {
        assertEquals(
            "https://a.example/1",
            UrlTextExtractor.firstHttpUrl("https://a.example/1 and https://b.example/2")
        )
    }

    @Test
    fun firstActionableUrlSkipsAnEarlierMalformedHost() {
        val text = "ignore https://intranethost/path and use https://example.com/media"

        assertEquals(
            "https://example.com/media",
            UrlTextExtractor.firstActionableUrl(text)
        )
        assertTrue(UrlTextExtractor.isActionableUrl(text))
    }

    @Test
    fun urlsOnSeparateLinesAreAllFound() {
        assertUrls(
            listOf("https://a.example/1", "https://b.example/2"),
            "https://a.example/1\n\t  https://b.example/2\r\n"
        )
    }

    // ── Non-URL text (the privacy boundary, spec 57) ───────────────────────────

    @Test
    fun textWithoutAUrlYieldsNothing() {
        val plainText = listOf(
            "just some words",
            "hello world",
            "1 + 1 = 2",
            "",
            "   ",
            "\n\t\r",
            "example.com/path without a scheme",
            "www.facebook.com/reel/1",
            "the quick brown fox jumps over the lazy dog"
        )
        for (text in plainText) {
            assertNull("'$text' should not yield a URL", UrlTextExtractor.firstHttpUrl(text))
            assertTrue("'$text' should yield no URLs", UrlTextExtractor.allHttpUrls(text).isEmpty())
            assertFalse("'$text' should not be actionable", UrlTextExtractor.isActionableUrl(text))
            assertFalse("'$text' should not contain a URL", UrlTextExtractor.containsHttpUrl(text))
        }
    }

    @Test
    fun aPlainSentenceNeverYieldsAUrl() {
        val sentence = "Remember to buy milk and eggs on the way home today"
        assertNull(UrlTextExtractor.firstHttpUrl(sentence))
        // This is exactly the baseline defect: ShareReceiverActivity used to save the whole text.
        assertEquals(0, UrlTextExtractor.allHttpUrls(sentence).size)
    }

    @Test
    fun nullInputIsHandledWithoutThrowing() {
        assertNull(UrlTextExtractor.firstHttpUrl(null))
        assertTrue(UrlTextExtractor.allHttpUrls(null).isEmpty())
        assertFalse(UrlTextExtractor.containsHttpUrl(null))
        assertFalse(UrlTextExtractor.isActionableUrl(null))
    }

    @Test
    fun aSchemeWithNoHostIsNotAUrl() {
        // The regex matches "https://" but the length guard drops it before it can be saved.
        assertNull(UrlTextExtractor.firstHttpUrl("https://"))
        assertNull(UrlTextExtractor.firstHttpUrl("just https:// here"))
        assertTrue(UrlTextExtractor.allHttpUrls("https://").isEmpty())
    }

    // ── isActionableUrl (spec 11: no accidental bubble) ────────────────────────

    @Test
    fun isActionableUrlRejectsASchemeWithoutAHost() {
        assertFalse(UrlTextExtractor.isActionableUrl("https://"))
        assertFalse(UrlTextExtractor.isActionableUrl("https:///path"))
        assertFalse(UrlTextExtractor.isActionableUrl("http://"))
        assertFalse(UrlTextExtractor.isActionableUrl("https://user:pw@"))
    }

    @Test
    fun isActionableUrlRejectsAHostWithoutADot() {
        // An intranet host name is not actionable from a shared bubble.
        assertFalse(UrlTextExtractor.isActionableUrl("https://intranethost/path"))
        assertFalse(UrlTextExtractor.isActionableUrl("https://mars/page"))
        // A loopback address in brackets contains no dot and is not spelled "localhost".
        assertFalse(UrlTextExtractor.isActionableUrl("http://[::1]"))
    }

    @Test
    fun isActionableUrlAcceptsLocalhost() {
        assertTrue(UrlTextExtractor.isActionableUrl("http://localhost:8080/admin"))
        assertTrue(UrlTextExtractor.isActionableUrl("http://LOCALHOST/x"))
        assertTrue(UrlTextExtractor.isActionableUrl("https://localhost"))
    }

    @Test
    fun isActionableUrlAcceptsADottedHostWithAPortOrPath() {
        assertTrue(UrlTextExtractor.isActionableUrl("https://example.com"))
        assertTrue(UrlTextExtractor.isActionableUrl("https://example.com:8443/x"))
        assertTrue(UrlTextExtractor.isActionableUrl("https://sub.example.co.uk/x?y=1"))
        assertTrue(UrlTextExtractor.isActionableUrl("see https://example.com/x here"))
    }

    @Test
    fun isActionableUrlStripsUserInfoBeforeJudgingTheHost() {
        // The userinfo must not be mistaken for the host.
        assertTrue(UrlTextExtractor.isActionableUrl("https://user:pw@example.com/x"))
        assertTrue(UrlTextExtractor.isActionableUrl("https://user:pw@sub.example.com"))
        // A host with no dot at all is not actionable.
        assertFalse(UrlTextExtractor.isActionableUrl("https://user:pw@mars/x"))
        // Known quirk: userinfo is stripped after the localhost comparison, so `user:pw@localhost`
        // is still accepted. Characterisation, not approval.
        assertTrue(UrlTextExtractor.isActionableUrl("https://user:pw@localhost"))
    }

    @Test
    fun isActionableUrlRejectsABracketedIpv6Host() {
        // A bracketed host is read (the brackets are not mistaken for a port or a path), but a
        // loopback literal still has no dot and is not spelled "localhost", so it is not actionable.
        assertFalse(UrlTextExtractor.isActionableUrl("http://[::1]:8080/x"))
        assertFalse(UrlTextExtractor.isActionableUrl("http://[::1]"))
        // The userinfo is stripped before the bracket check.
        assertFalse(UrlTextExtractor.isActionableUrl("http://user:pw@[::1]:8080/x"))
    }

    @Test
    fun isActionableUrlIsFalseForPlainProse() {
        assertFalse(UrlTextExtractor.isActionableUrl("no links at all"))
        assertFalse(UrlTextExtractor.isActionableUrl("call me at 555-0100"))
        assertFalse(UrlTextExtractor.isActionableUrl("version 1.2.3 is out"))
    }

    // ── Robustness ────────────────────────────────────────────────────────────

    @Test
    fun hostileInputNeverThrows() {
        val hostile = listOf(
            "https://" + "a".repeat(10_000) + "/x",
            "https://example.com/" + ".".repeat(1000),
            "((( https://example.com/a " + ")".repeat(500),
            "https://example.com/a<b>c</b>",
            "https://\u0000\u0001/x",
            "https://example.com/a\tb"
        )
        for (text in hostile) {
            // Only the contract matters here: a value comes back and it is never blank.
            val urls = UrlTextExtractor.allHttpUrls(text)
            for (url in urls) {
                assertTrue("extracted an empty URL from '${text.take(40)}'", url.isNotEmpty())
            }
        }
    }

    @Test
    fun aVeryLongRealisticShareTextYieldsOnlyTheUrls() {
        val text = buildString {
            append("Check this out! ")
            append("https://www.instagram.com/reel/Cx1y2z3/?utm_source=ig_web_copy_link, ")
            append("and also ")
            append("https://fb.watch/abc123/. ")
            append("That is all.")
        }
        assertUrls(
            listOf(
                "https://www.instagram.com/reel/Cx1y2z3/?utm_source=ig_web_copy_link",
                "https://fb.watch/abc123/"
            ),
            text
        )
    }

    @Test
    fun multipleUrlsAreExtractedFromARealisticGoogleMapsShare() {
        assertUrls(
            listOf("https://maps.google.com/?q=1,2"),
            "Location: https://maps.google.com/?q=1,2"
        )
    }
}
