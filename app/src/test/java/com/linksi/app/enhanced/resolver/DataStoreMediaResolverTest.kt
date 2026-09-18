package com.linksi.app.enhanced.resolver

import androidx.datastore.preferences.core.preferencesOf
import com.linksi.app.utils.ENHANCED_SERVER_FALLBACK_API_KEY
import com.linksi.app.utils.ENHANCED_SERVER_FALLBACK_ENABLED
import com.linksi.app.utils.ENHANCED_SERVER_FALLBACK_URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM coverage for the DataStore-to-resolver settings mapping. */
class DataStoreMediaResolverTest {

    @Test
    fun missingSettingsKeepTheFallbackOff() {
        val config = preferencesOf().toServerResolverConfig()

        assertFalse(config.enabled)
        assertEquals("", config.baseUrl)
        assertNull(config.apiKey)
        assertFalse(config.isUsable)
    }

    @Test
    fun theResolverReadsTheCurrentAddressAndKeyWithoutPuttingTheKeyInTheAddress() {
        val config = preferencesOf(
            ENHANCED_SERVER_FALLBACK_ENABLED to true,
            ENHANCED_SERVER_FALLBACK_URL to "  https://resolver.example/base/  ",
            ENHANCED_SERVER_FALLBACK_API_KEY to "  secret-token  "
        ).toServerResolverConfig()

        assertEquals("https://resolver.example/base/", config.baseUrl)
        assertEquals("secret-token", config.apiKey)
        assertFalse(config.baseUrl.contains("secret-token"))
        assertEquals("https://resolver.example/base/", config.baseUrl)
    }
}

