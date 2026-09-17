package com.linksi.app.enhanced.resolver

import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ServerResolverConfig] and [DisabledMediaResolver].
 *
 * Case identifiers in the comments map onto LINKSI_ENHANCED_REVISED_SPEC.md sections 24 and 25.
 * The privacy contract lives in the data class rather than the (Android) implementation, so it is
 * pinned here: off by default, HTTPS only, and an explicit disclosure before anything is enabled.
 */
class MediaResolverTest {

    /** A fully configured, usable resolver. */
    private fun configured(
        enabled: Boolean = true,
        baseUrl: String = "https://resolver.example.com",
        apiKey: String? = "secret",
        timeoutSeconds: Int = 20
    ) = ServerResolverConfig(enabled = enabled, baseUrl = baseUrl, apiKey = apiKey, timeoutSeconds = timeoutSeconds)

    // ── isUsable (spec 24: off by default) ──────────────────────────────────────

    @Test
    fun theDefaultConfigurationIsNotUsable() {
        val config = ServerResolverConfig()
        assertFalse(config.enabled)
        assertEquals("", config.baseUrl)
        assertNull(config.apiKey)
        assertEquals(20, config.timeoutSeconds)
        assertFalse(config.isUsable)
    }

    @Test
    fun aConfiguredHttpsUrlThatIsEnabledIsUsable() {
        assertTrue(configured().isUsable)
        assertTrue(configured(baseUrl = "https://10.0.0.5:8443/resolve").isUsable)
        assertTrue(configured(baseUrl = "https://resolver.example.com/base/path").isUsable)
        // A trailing API key is not part of the check but must not break it.
        assertTrue(configured(apiKey = null).isUsable)
    }

    @Test
    fun plainHttpIsNeverUsable() {
        // Spec 25: the URL is sent to the server, so the transport must be encrypted.
        assertFalse(configured(baseUrl = "http://resolver.example.com").isUsable)
        assertFalse(configured(baseUrl = "HTTP://resolver.example.com").isUsable)
        assertFalse(configured(baseUrl = "http://10.0.0.5:8443/resolve").isUsable)
        assertFalse(configured(baseUrl = "ftp://resolver.example.com").isUsable)
        assertFalse(configured(baseUrl = "ws://resolver.example.com").isUsable)
        assertFalse(configured(baseUrl = "resolver.example.com").isUsable)
    }

    @Test
    fun aUrlThatMerelyStartsWithTheLettersHttpsIsNotEnough() {
        // "https:/" and "https:foo" are not HTTPS endpoints.
        assertFalse(configured(baseUrl = "https:/resolver.example.com").isUsable)
        assertFalse(configured(baseUrl = "https:resolver.example.com").isUsable)
        assertFalse(configured(baseUrl = "httpsx://resolver.example.com").isUsable)
    }

    @Test
    fun anEnabledButBlankUrlIsNotUsable() {
        assertFalse(configured(baseUrl = "").isUsable)
        assertFalse(configured(baseUrl = "   ").isUsable)
        assertFalse(configured(baseUrl = "\n\t").isUsable)
    }

    @Test
    fun aDisabledConfigIsNotUsableEvenWithAPerfectUrl() {
        assertFalse(configured(enabled = false).isUsable)
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        // A value pasted into the settings field often carries a stray space or newline.
        assertTrue(configured(baseUrl = "  https://resolver.example.com  ").isUsable)
        assertTrue(configured(baseUrl = "\nhttps://resolver.example.com\t").isUsable)
    }

    // ── unusableReason ────────────────────────────────────────────────────────

    @Test
    fun unusableReasonExplainsADisabledResolver() {
        assertEquals("Server fallback is disabled", ServerResolverConfig().unusableReason)
        assertEquals("Server fallback is disabled", configured(enabled = false).unusableReason)
    }

    @Test
    fun unusableReasonExplainsAMissingAddress() {
        assertEquals("No resolver address configured", configured(baseUrl = "").unusableReason)
        assertEquals("No resolver address configured", configured(baseUrl = "   ").unusableReason)
    }

    @Test
    fun unusableReasonExplainsANonHttpsAddress() {
        assertEquals("The resolver address must use HTTPS", configured(baseUrl = "http://x.example").unusableReason)
        assertEquals("The resolver address must use HTTPS", configured(baseUrl = "x.example").unusableReason)
        assertEquals("The resolver address must use HTTPS", configured(baseUrl = "https:/x.example").unusableReason)
    }

    @Test
    fun unusableReasonIsNullExactlyWhenTheConfigIsUsable() {
        val configs = listOf(
            ServerResolverConfig(),
            configured(enabled = false),
            configured(baseUrl = ""),
            configured(baseUrl = "http://x.example"),
            configured(),
            configured(baseUrl = "  https://x.example  ")
        )
        for (config in configs) {
            assertEquals(
                "unusableReason/isUsable disagree for $config",
                config.isUsable,
                config.unusableReason == null
            )
        }
    }

    // ── disclosure (spec 24: the exact text shown before enabling) ─────────────

    @Test
    fun theDisclosureIsNonEmptyAndNamesWhatIsSent() {
        val disclosure = ServerResolverConfig().disclosure
        assertTrue(disclosure.isNotBlank())
        assertTrue("the disclosure must say the link is sent", disclosure.contains("URL is sent"))
        assertTrue("the disclosure must name the user's own server", disclosure.contains("the server you configure"))
    }

    @Test
    fun theDisclosureStatesThatNothingElseIsSent() {
        val disclosure = ServerResolverConfig().disclosure
        // The privacy promise is explicit: no clipboard, no screen contents, no saved links.
        assertTrue(disclosure.contains("no clipboard contents"))
        assertTrue(disclosure.contains("no screen contents"))
        assertTrue(disclosure.contains("no saved links"))
    }

    @Test
    fun theDisclosurePromisesTheAppStillWorksOffline() {
        assertTrue(ServerResolverConfig().disclosure.contains("keeps working if the server is offline"))
    }

    @Test
    fun theDisclosureIsIdenticalForEveryConfiguration() {
        // It is a constant in the contract, so it cannot drift between settings screens.
        assertEquals(ServerResolverConfig().disclosure, configured().disclosure)
        assertEquals(ServerResolverConfig().disclosure, configured(enabled = false).disclosure)
    }

    // ── DisabledMediaResolver (spec 24: the default resolver) ──────────────────

    @Test
    fun theDisabledResolverIsIdentifiableAndOff() {
        val resolver = DisabledMediaResolver()
        assertEquals("disabled", resolver.id)
        assertEquals("Disabled", resolver.displayName)
        assertEquals(ServerResolverConfig(), resolver.config)
        assertFalse(resolver.config.isUsable)
        assertEquals("Server fallback is disabled", resolver.config.unusableReason)
    }

    @Test
    fun theDisabledResolverReturnsSkippedInsteadOfThrowing() = runBlocking {
        val result = DisabledMediaResolver().resolve("https://www.instagram.com/reel/1/", MediaSource.INSTAGRAM)
        assertTrue(result is MediaExtractionResult.Skipped)
        val skipped = result as MediaExtractionResult.Skipped
        assertEquals("https://www.instagram.com/reel/1/", skipped.url)
        assertEquals(MediaError.ENGINE_UNAVAILABLE, skipped.error)
        assertEquals("server fallback is disabled", skipped.reason)
    }

    @Test
    fun theDisabledResolverWorksForEverySourceWithoutThrowing() = runBlocking {
        val resolver = DisabledMediaResolver()
        for (source in MediaSource.entries) {
            val result = resolver.resolve("https://example.com/x", source)
            assertTrue("$source should be skipped, not extracted", result is MediaExtractionResult.Skipped)
        }
    }

    @Test
    fun theDisabledResolverNeverEchoesAnApiKey() {
        // There is no key to leak in the default configuration.
        assertNull(DisabledMediaResolver().config.apiKey)
    }

    @Test
    fun theConfigIsAValueObjectSoSettingsCanCompareIt() {
        assertEquals(configured(), configured())
        assertEquals(configured().hashCode(), configured().hashCode())
        assertFalse(configured() == configured(baseUrl = "http://x.example"))
        assertEquals(configured(), configured().copy())
    }
}
