package com.linksi.app.enhanced.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MediaSourceDetector].
 *
 * Case identifiers in the comments map onto LINKSI_ENHANCED_REVISED_SPEC.md sections 15 and 18.
 * These are pure JVM tests (no Android dependency): [MediaSourceDetector] never reads
 * `android.net.Uri` and never throws, so every input below is a real unit-test case.
 *
 * The governing rule (spec 67) is that the detected source only decides which optional actions are
 * *offered*; an unrecognised host must therefore degrade to [MediaSource.OTHER] rather than fail.
 */
class MediaSourceDetectorTest {

    /** Asserts the URL maps to [expected]. */
    private fun assertSource(expected: MediaSource, url: String) {
        assertEquals("wrong source for '$url'", expected, MediaSourceDetector.fromUrl(url))
    }

    // ── Sub-domains (spec 15: m., www., web.) ───────────────────────────────────

    @Test
    fun facebookSubdomainsAllResolveToFacebook() {
        assertSource(MediaSource.FACEBOOK, "https://m.facebook.com/reel/123")
        assertSource(MediaSource.FACEBOOK, "https://www.facebook.com/reel/123")
        assertSource(MediaSource.FACEBOOK, "https://web.facebook.com/reel/123")
        assertSource(MediaSource.FACEBOOK, "https://facebook.com/reel/123")
    }

    @Test
    fun instagramSubdomainsResolveToInstagram() {
        assertSource(MediaSource.INSTAGRAM, "https://www.instagram.com/p/ABC123/")
        assertSource(MediaSource.INSTAGRAM, "https://instagram.com/reel/ABC123/")
        assertSource(MediaSource.INSTAGRAM, "https://cdninstagram.com/v/t51/video.mp4")
    }

    @Test
    fun tiktokSubdomainsResolveToTiktok() {
        assertSource(MediaSource.TIKTOK, "https://www.tiktok.com/@user/video/123")
        assertSource(MediaSource.TIKTOK, "https://tiktok.com/@user/video/123")
    }

    @Test
    fun redditSubdomainsResolveToReddit() {
        assertSource(MediaSource.REDDIT, "https://www.reddit.com/r/aww/comments/abc/")
        assertSource(MediaSource.REDDIT, "https://old.reddit.com/r/aww/comments/abc/")
    }

    @Test
    fun youtubeSubdomainsResolveToYoutube() {
        assertSource(MediaSource.YOUTUBE, "https://www.youtube.com/watch?v=abc")
        assertSource(MediaSource.YOUTUBE, "https://m.youtube.com/watch?v=abc")
    }

    @Test
    fun aHostThatMerelyEndsWithTheBrandNameIsNotMatched() {
        // Suffix matching must be anchored on a dot boundary, so lookalike domains are not claimed.
        assertSource(MediaSource.OTHER, "https://notfacebook.com/reel/1")
        assertSource(MediaSource.OTHER, "https://fakeinstagram.com/p/1")
        assertSource(MediaSource.OTHER, "https://myyoutube.com/watch?v=1")
    }

    // ── Short links (spec 15) ───────────────────────────────────────────────────

    @Test
    fun facebookShortLinksResolveToFacebook() {
        // The canonical short link from UrlCleanerTest; the host alone is what matters here.
        assertSource(MediaSource.FACEBOOK, "https://fb.watch/abc123")
        assertSource(MediaSource.FACEBOOK, "https://fb.watch/abc123/")
        assertSource(MediaSource.FACEBOOK, "https://fb.com/reel/1")
        assertSource(MediaSource.FACEBOOK, "https://fb.gg/abc")
    }

    @Test
    fun pinterestShortLinkResolvesToPinterest() {
        assertSource(MediaSource.PINTEREST, "https://pin.it/abc123")
    }

    @Test
    fun redditShortLinkResolvesToReddit() {
        assertSource(MediaSource.REDDIT, "https://redd.it/abc123")
    }

    @Test
    fun tiktokShortLinksResolveToTiktok() {
        assertSource(MediaSource.TIKTOK, "https://vm.tiktok.com/ZMabc/")
        assertSource(MediaSource.TIKTOK, "https://vt.tiktok.com/ZMabc/")
    }

    @Test
    fun youtubeShortLinkResolvesToYoutube() {
        assertSource(MediaSource.YOUTUBE, "https://youtu.be/dQw4w9WgXcQ")
    }

    @Test
    fun instagramShortLinkResolvesToInstagram() {
        assertSource(MediaSource.INSTAGRAM, "https://instagr.am/p/ABC/")
    }

    // ── Pinterest country domains (spec 15: "many country domains") ─────────────

    @Test
    fun everyPinterestCountryDomainResolvesToPinterest() {
        val hosts = listOf(
            "pinterest.com", "pinterest.co.uk", "pinterest.de", "pinterest.fr", "pinterest.es",
            "pinterest.it", "pinterest.ca", "pinterest.com.au", "pinterest.com.mx",
            "pinterest.co.nz", "pinterest.jp", "pinterest.se", "pinterest.nl", "pinterest.pt",
            "pinterest.ru", "pinterest.ch", "pinterest.at", "pinterest.be", "pinterest.dk",
            "pinterest.no", "pinterest.fi", "pinterest.pl", "pinterest.ie", "pinterest.in",
            "pinterest.br", "pinterest.cl", "pinterest.ar", "pinterest.tr", "pinterest.gr",
            "pinterest.co.kr", "pinterest.co", "pinterest.ph", "pinterest.sg", "pinterest.tw"
        )
        for (host in hosts) {
            assertSource(MediaSource.PINTEREST, "https://$host/pin/123/")
            assertSource(MediaSource.PINTEREST, "https://www.$host/pin/123/")
        }
    }

    @Test
    fun pinterestSubdomainsAndShortLinksResolveToPinterest() {
        assertSource(MediaSource.PINTEREST, "https://de.pinterest.com/pin/123/")
        assertSource(MediaSource.PINTEREST, "https://uk.pinterest.com/pin/123/")
        assertSource(MediaSource.PINTEREST, "https://pin.it/abc")
        assertSource(MediaSource.PINTEREST, "https://www.pin.it/abc")
    }

    // ── Case, ports and authority decorations ───────────────────────────────────

    @Test
    fun hostMatchingIsCaseInsensitive() {
        assertSource(MediaSource.FACEBOOK, "https://FACEBOOK.COM/reel/1")
        assertSource(MediaSource.FACEBOOK, "HTTPS://M.FACEBOOK.COM/reel/1")
        assertSource(MediaSource.INSTAGRAM, "https://WWW.INSTAGRAM.COM/p/1")
        assertSource(MediaSource.TIKTOK, "https://VM.TIKTOK.COM/abc")
        assertSource(MediaSource.PINTEREST, "https://WWW.PINTEREST.DE/pin/1")
    }

    @Test
    fun anExplicitPortDoesNotHideTheHost() {
        assertSource(MediaSource.FACEBOOK, "https://www.facebook.com:443/reel/1")
        assertSource(MediaSource.OTHER, "https://example.com:8443/article")
        assertEquals("www.facebook.com", MediaSourceDetector.hostOf("https://www.facebook.com:443/reel/1"))
    }

    @Test
    fun userInfoIsStrippedBeforeMatching() {
        // UrlCleanerTest confirms such URLs are supported, so detection must survive them too.
        assertSource(MediaSource.FACEBOOK, "https://user:pw@www.facebook.com/x")
        assertSource(MediaSource.INSTAGRAM, "https://user:pw@instagram.com/p/1")
        assertEquals("www.facebook.com", MediaSourceDetector.hostOf("https://user:pw@www.facebook.com/x"))
    }

    @Test
    fun userInfoWithoutAHostIsUnknownRatherThanACrash() {
        assertEquals(null, MediaSourceDetector.hostOf("https://user:pw@"))
        assertSource(MediaSource.UNKNOWN, "https://user:pw@")
    }

    @Test
    fun ipv6BracketedHostsAreReadAndClassifiedAsOther() {
        assertEquals("[::1]", MediaSourceDetector.hostOf("https://[::1]:8080/a"))
        assertEquals("[2001:db8::1]", MediaSourceDetector.hostOf("https://[2001:db8::1]/a"))
        assertSource(MediaSource.OTHER, "https://[::1]:8080/a")
    }

    @Test
    fun unterminatedIpv6BracketIsUnknown() {
        assertNull(MediaSourceDetector.hostOf("https://[::1/a"))
        assertSource(MediaSource.UNKNOWN, "https://[::1/a")
    }

    // ── Schemeless and garbage input ────────────────────────────────────────────

    @Test
    fun schemelessInputIsUnknownBecauseThereIsNoHostToRead() {
        // Detection only ever runs on already-normalised links, so a missing scheme is "unknown".
        assertNull(MediaSourceDetector.hostOf("www.facebook.com/reel/1"))
        assertSource(MediaSource.UNKNOWN, "www.facebook.com/reel/1")
        assertSource(MediaSource.UNKNOWN, "example.com:8080/path")
    }

    @Test
    fun garbageInputIsUnknownAndNeverThrows() {
        // Every one of these has no readable authority at all, so the honest answer is UNKNOWN.
        val garbage = listOf(
            "", "   ", "not a url", "://example.com", "http:/example.com",
            "facebook.com", "example.com/path", "http://", "https://", "https:///",
            "https:///path/", "java script:alert(1)", "file:///sdcard/x.mp4",
            "https://[::1/a"
        )
        for (input in garbage) {
            val source = MediaSourceDetector.fromUrl(input)
            assertEquals("'$input' should be UNKNOWN", MediaSource.UNKNOWN, source)
        }
    }

    @Test
    fun nonsenseAuthoritiesDegradeToOtherInsteadOfUnknown() {
        // These *do* parse to a host string, it is simply not a site we know: that is OTHER.
        val nonsense = listOf(
            "https://%", "https:// a b c/x", "content://media/external/images/1",
            "intent://scan/#Intent;scheme=zxing;end"
        )
        for (input in nonsense) {
            assertEquals("'$input' should be OTHER", MediaSource.OTHER, MediaSourceDetector.fromUrl(input))
        }
    }

    @Test
    fun unknownSitesMapToOtherRatherThanCrashing() {
        val unknownSites = listOf(
            "https://example.com/article",
            "https://news.ycombinator.com/item?id=1",
            "https://vimeo.com/12345",
            "https://twitter.com/user/status/1",
            "https://x.com/user/status/1",
            "https://twitch.tv/channel",
            "https://soundcloud.com/artist/track",
            "https://mastodon.social/@user/1",
            "https://linkedin.com/posts/1"
        )
        for (url in unknownSites) {
            assertSource(MediaSource.OTHER, url)
        }
        // The same rule through the host entry point.
        assertEquals(MediaSource.OTHER, MediaSourceDetector.fromHost("vimeo.com"))
    }

    @Test
    fun anEmptyHostIsUnknown() {
        assertEquals(MediaSource.UNKNOWN, MediaSourceDetector.fromHost(""))
    }

    @Test
    fun hostsShorterThanTheWwwPrefixAreOtherRatherThanUnknown() {
        // Nothing is stripped from these, so they stay "not a site we know" rather than "unreadable".
        assertEquals(MediaSource.OTHER, MediaSourceDetector.fromHost("ww"))
        assertEquals(MediaSource.OTHER, MediaSourceDetector.fromHost("w"))
        assertEquals(MediaSource.OTHER, MediaSourceDetector.fromHost("   "))
    }

    // ── Direct files (spec 18) ─────────────────────────────────────────────────

    @Test
    fun directFileUrlsAreDetectedByExtension() {
        assertSource(MediaSource.DIRECT_FILE, "https://cdn.example.com/video.mp4")
        assertSource(MediaSource.DIRECT_FILE, "https://cdn.example.com/photo.JPG")
        assertSource(MediaSource.DIRECT_FILE, "https://cdn.example.com/photo.jpeg")
        assertSource(MediaSource.DIRECT_FILE, "https://cdn.example.com/a/b/c/clip.MOV")
        assertSource(MediaSource.DIRECT_FILE, "https://cdn.example.com/audio.flac")
        assertSource(MediaSource.DIRECT_FILE, "https://cdn.example.com/doc.pdf")
        assertSource(MediaSource.DIRECT_FILE, "https://cdn.example.com/archive.zip")
        assertSource(MediaSource.DIRECT_FILE, "https://cdn.example.com/app.apk")
        assertSource(MediaSource.DIRECT_FILE, "https://cdn.example.com/data.json")
    }

    @Test
    fun aKnownSourceBeatsTheDirectFileRule() {
        // A .mp4 on a Facebook CDN host is still Facebook content, not a generic file.
        assertSource(MediaSource.INSTAGRAM, "https://cdninstagram.com/v/t51/video.mp4")
        assertSource(MediaSource.FACEBOOK, "https://www.facebook.com/video.mp4")
    }

    @Test
    fun queryStringAndFragmentDoNotHideTheExtension() {
        assertEquals("jpg", MediaSourceDetector.extensionOf("https://cdn.example.com/a/b.jpg?w=100#frag"))
        assertTrue(MediaSourceDetector.looksLikeDirectFile("https://cdn.example.com/a/b.jpg?w=100"))
        assertTrue(MediaSourceDetector.looksLikeDirectFile("https://cdn.example.com/a/b.mp4#t=1"))
    }

    @Test
    fun urlsWithoutAKnownFileExtensionAreNotDirectFiles() {
        assertNull(MediaSourceDetector.extensionOf("https://example.com/article"))
        assertNull(MediaSourceDetector.extensionOf("https://example.com/index.html"))
        assertNull(MediaSourceDetector.extensionOf("https://example.com/path.d/endpoint"))
        assertNull(MediaSourceDetector.extensionOf("https://example.com/verylongextension"))
        assertNull(MediaSourceDetector.extensionOf("https://example.com/trailing."))
        assertFalse(MediaSourceDetector.looksLikeDirectFile("https://example.com/article"))
        assertEquals(MediaSource.OTHER, MediaSourceDetector.fromUrl("https://example.com/article"))
    }

    @Test
    fun directFileDetectionIsOnlyAppliedWhenAUrlIsAvailable() {
        // fromHost without a URL cannot know about the path, so it must not guess DIRECT_FILE.
        assertEquals(MediaSource.OTHER, MediaSourceDetector.fromHost("cdn.example.com"))
        assertEquals(
            MediaSource.DIRECT_FILE,
            MediaSourceDetector.fromHost("cdn.example.com", "https://cdn.example.com/x.mp4")
        )
    }

    @Test
    fun primaryTargetsMatchTheSpecification() {
        assertEquals(
            setOf(
                MediaSource.INSTAGRAM,
                MediaSource.FACEBOOK,
                MediaSource.TIKTOK,
                MediaSource.PINTEREST,
                MediaSource.REDDIT
            ),
            MediaSourceDetector.PRIMARY_TARGETS
        )
        assertFalse(MediaSource.YOUTUBE in MediaSourceDetector.PRIMARY_TARGETS)
    }

    @Test
    fun displayNamesAreUserFacing() {
        assertEquals("Instagram", MediaSource.INSTAGRAM.displayName)
        assertEquals("Other website", MediaSource.OTHER.displayName)
        assertEquals("Direct file", MediaSource.DIRECT_FILE.displayName)
        assertEquals("Unknown", MediaSource.UNKNOWN.displayName)
    }
}
