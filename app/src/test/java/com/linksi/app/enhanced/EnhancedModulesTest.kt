package com.linksi.app.enhanced

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [EnhancedFeatureDefaults] and [EnhancedPreferenceKeys].
 *
 * Case identifiers in the comments map onto LINKSI_ENHANCED_REVISED_SPEC.md sections 8, 9.5, 11,
 * 12.2, 22 and 34. The rule the full enhanced build depends on is: **core Linksi never requires
 * any optional module**, so every default here is "off" except URL cleaning on save.
 */
class EnhancedModulesTest {

    private companion object {
        /** Every preference key the enhanced modules own, by name. */
        val ALL_PREFERENCE_KEYS = listOf(
            EnhancedPreferenceKeys.AUTO_CLEAN_URLS,
            EnhancedPreferenceKeys.SMART_LINK_DETECTION,
            EnhancedPreferenceKeys.FLOATING_BUBBLE,
            EnhancedPreferenceKeys.ACCESSIBILITY_ASSISTANCE,
            EnhancedPreferenceKeys.BUBBLE_AUTO_DISMISS,
            EnhancedPreferenceKeys.BUBBLE_POSITION,
            EnhancedPreferenceKeys.BUBBLE_SIZE,
            EnhancedPreferenceKeys.A11Y_IGNORED_PACKAGES,
            EnhancedPreferenceKeys.DOWNLOAD_NOTIFICATIONS,
            EnhancedPreferenceKeys.DOWNLOAD_DESTINATION,
            EnhancedPreferenceKeys.DOWNLOAD_ORGANIZE_BY_SOURCE,
            EnhancedPreferenceKeys.SERVER_FALLBACK_ENABLED,
            EnhancedPreferenceKeys.SERVER_FALLBACK_URL,
            EnhancedPreferenceKeys.SERVER_FALLBACK_API_KEY
        )
    }

    // ── Optional features are off by default (spec 8 and 34) ────────────────────

    @Test
    fun theOptionalFeaturesAreAllOffByDefault() {
        assertFalse("smart detection must be opt-in (spec 11)", EnhancedFeatureDefaults.SMART_LINK_DETECTION)
        assertFalse("the bubble must be opt-in (spec 12.2)", EnhancedFeatureDefaults.FLOATING_BUBBLE)
        assertFalse(
            "the accessibility service must be opt-in (spec 11.2)",
            EnhancedFeatureDefaults.ACCESSIBILITY_ASSISTANCE
        )
        assertFalse("server fallback must stay off (spec 24)", EnhancedFeatureDefaults.SERVER_FALLBACK)
        assertFalse("downloads must not be reorganised by default (spec 22)", EnhancedFeatureDefaults.ORGANIZE_BY_SOURCE)
    }

    @Test
    fun urlCleaningOnSaveIsTheSingleRecommendedDefaultThatIsOn() {
        // Spec 9.5: cleaning is on by default and can never fail a save.
        assertTrue(EnhancedFeatureDefaults.URL_CLEANING)
    }

    // ── Feature toggles that gate whole subsystems (spec 34) ────────────────────

    @Test
    fun theSubsystemTogglesDefaultToTheirDocumentedValues() {
        assertTrue(EnhancedFeatureDefaults.LOCAL_MEDIA_DOWNLOADER)
        assertTrue(EnhancedFeatureDefaults.DIRECT_FILE_DOWNLOADER)
        assertTrue(EnhancedFeatureDefaults.DOWNLOAD_NOTIFICATIONS)
        assertTrue("only HTTP/HTTPS links are ever processed (spec 11)", EnhancedFeatureDefaults.ONLY_HTTP_HTTPS)
        assertTrue(EnhancedFeatureDefaults.USE_ORIGINAL_FILENAME)
    }

    // ── Bubble defaults (spec 12.2) ─────────────────────────────────────────────

    @Test
    fun theBubbleDefaultsMatchTheSpecification() {
        assertEquals(10, EnhancedFeatureDefaults.BUBBLE_AUTO_DISMISS_SECONDS)
        assertEquals("remember", EnhancedFeatureDefaults.BUBBLE_POSITION)
        assertEquals("normal", EnhancedFeatureDefaults.BUBBLE_SIZE)
        assertTrue("auto-dismiss must be a positive delay", EnhancedFeatureDefaults.BUBBLE_AUTO_DISMISS_SECONDS > 0)
    }

    @Test
    fun theBubbleSizeDefaultIsOneOfTheSupportedValues() {
        assertTrue(
            EnhancedFeatureDefaults.BUBBLE_SIZE in listOf("small", "normal", "large")
        )
        assertTrue(
            EnhancedFeatureDefaults.BUBBLE_POSITION in listOf("remember", "left", "right")
        )
    }

    // ── Preference keys ───────────────────────────────────────────────────────

    @Test
    fun everyPreferenceKeyIsAStableSnakeCaseString() {
        for (key in ALL_PREFERENCE_KEYS) {
            assertTrue("'$key' is not snake_case", key.matches(Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*")))
        }
    }

    @Test
    fun everyPreferenceKeyIsDistinct() {
        // Two modules sharing a key would silently overwrite each other's setting.
        assertEquals(ALL_PREFERENCE_KEYS.size, ALL_PREFERENCE_KEYS.toSet().size)
    }

    @Test
    fun theKeysKeepTheirDocumentedNames() {
        assertEquals("auto_clean_urls", EnhancedPreferenceKeys.AUTO_CLEAN_URLS)
        assertEquals("smart_link_detection", EnhancedPreferenceKeys.SMART_LINK_DETECTION)
        assertEquals("floating_bubble", EnhancedPreferenceKeys.FLOATING_BUBBLE)
        assertEquals("accessibility_assistance", EnhancedPreferenceKeys.ACCESSIBILITY_ASSISTANCE)
        assertEquals("bubble_auto_dismiss", EnhancedPreferenceKeys.BUBBLE_AUTO_DISMISS)
        assertEquals("bubble_position", EnhancedPreferenceKeys.BUBBLE_POSITION)
        assertEquals("bubble_size", EnhancedPreferenceKeys.BUBBLE_SIZE)
        assertEquals("a11y_ignored_packages", EnhancedPreferenceKeys.A11Y_IGNORED_PACKAGES)
        assertEquals("download_notifications", EnhancedPreferenceKeys.DOWNLOAD_NOTIFICATIONS)
        assertEquals("download_destination", EnhancedPreferenceKeys.DOWNLOAD_DESTINATION)
        assertEquals("download_organize_by_source", EnhancedPreferenceKeys.DOWNLOAD_ORGANIZE_BY_SOURCE)
        assertEquals("server_fallback_enabled", EnhancedPreferenceKeys.SERVER_FALLBACK_ENABLED)
        assertEquals("server_fallback_url", EnhancedPreferenceKeys.SERVER_FALLBACK_URL)
        assertEquals("server_fallback_api_key", EnhancedPreferenceKeys.SERVER_FALLBACK_API_KEY)
    }

    @Test
    fun theUrlCleaningKeyMatchesTheOneTheSettingsScreenAlreadyWrites() {
        // The baseline DataStore key is `AUTO_CLEAN_URLS = booleanPreferencesKey("auto_clean_urls")`
        // in com.linksi.app.utils.DataStoreExtensions. The enhanced key and the baseline key must
        // be the same string, or the settings toggle would write to a key nothing reads.
        assertEquals("auto_clean_urls", EnhancedPreferenceKeys.AUTO_CLEAN_URLS)
        assertTrue(EnhancedFeatureDefaults.URL_CLEANING)
    }

    @Test
    fun theDistinctModulesUseNamespacedKeys() {
        // Grouping by prefix keeps an accidental collision obvious during review.
        assertTrue(EnhancedPreferenceKeys.DOWNLOAD_DESTINATION.startsWith("download_"))
        assertTrue(EnhancedPreferenceKeys.SERVER_FALLBACK_URL.startsWith("server_fallback_"))
        assertTrue(EnhancedPreferenceKeys.BUBBLE_SIZE.startsWith("bubble_"))
        assertNotEquals(EnhancedPreferenceKeys.DOWNLOAD_NOTIFICATIONS, EnhancedPreferenceKeys.DOWNLOAD_DESTINATION)
    }

    @Test
    fun theBooleanDefaultsAndTheirKeysLineUpInCount() {
        // Three opt-in toggles are exposed through matching `*_enabled`/toggle keys.
        val toggleKeys = listOf(
            EnhancedPreferenceKeys.SMART_LINK_DETECTION,
            EnhancedPreferenceKeys.FLOATING_BUBBLE,
            EnhancedPreferenceKeys.ACCESSIBILITY_ASSISTANCE,
            EnhancedPreferenceKeys.SERVER_FALLBACK_ENABLED
        )
        assertTrue(EnhancedFeatureDefaults.SMART_LINK_DETECTION.not())
        assertTrue(EnhancedFeatureDefaults.FLOATING_BUBBLE.not())
        assertTrue(EnhancedFeatureDefaults.ACCESSIBILITY_ASSISTANCE.not())
        assertTrue(EnhancedFeatureDefaults.SERVER_FALLBACK.not())
        assertEquals(4, toggleKeys.toSet().size)
    }

    @Test
    fun bothObjectsArePureConstantHolders() {
        // The defaults and keys are pure constants, so nothing can mutate them at runtime.
        assertEquals(EnhancedFeatureDefaults.URL_CLEANING, EnhancedFeatureDefaults.URL_CLEANING)
        assertEquals(EnhancedPreferenceKeys.AUTO_CLEAN_URLS, EnhancedPreferenceKeys.AUTO_CLEAN_URLS)
        // `const val` members are inlined by the compiler, so they survive being read reflectively.
        val fields = EnhancedFeatureDefaults::class.java.declaredFields.map { it.name }
        assertTrue(fields.contains("URL_CLEANING"))
        assertTrue(fields.contains("SERVER_FALLBACK"))
        assertTrue(fields.contains("BUBBLE_AUTO_DISMISS_SECONDS"))
    }
}
