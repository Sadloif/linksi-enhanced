package com.linksi.app.enhanced.resolver

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.linksi.app.enhanced.EnhancedFeatureDefaults
import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaSource
import com.linksi.app.utils.ENHANCED_SERVER_FALLBACK_API_KEY
import com.linksi.app.utils.ENHANCED_SERVER_FALLBACK_ENABLED
import com.linksi.app.utils.ENHANCED_SERVER_FALLBACK_URL
import com.linksi.app.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Hilt-facing resolver that reads the current opt-in settings at the moment a link is resolved.
 *
 * The settings screen and a WorkManager worker can be alive in different processes/lifetimes from
 * the object that first asks for this binding. Caching a [HttpMediaResolver] at injection time would
 * therefore make an old API key or disabled switch live forever. This wrapper keeps the binding
 * cheap and reads DataStore only on an actual fallback attempt.
 */
@Singleton
class DataStoreMediaResolver @Inject constructor(
    @ApplicationContext private val context: Context
) : MediaResolver {

    override val id: String = "private-server"
    override val displayName: String = "Private server"

    /** Latest settings snapshot, for diagnostics only; the next call always rereads DataStore. */
    @Volatile
    private var latestConfig: ServerResolverConfig = ServerResolverConfig()

    override val config: ServerResolverConfig
        get() = latestConfig

    override suspend fun resolve(url: String, source: MediaSource): MediaExtractionResult {
        val config = try {
            context.applicationContext.dataStore.data.first().toServerResolverConfig().also {
                latestConfig = it
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return MediaExtractionResult.Failure(MediaError.SERVER_UNAVAILABLE, url, error)
        }

        // HttpMediaResolver owns the HTTPS transport and cancellation rules. It is deliberately
        // created only after settings prove that a real fallback attempt is needed.
        return HttpMediaResolver(config).resolve(url, source)
    }
}

/** Maps the stored values without exposing the API key in a URL or a log message. */
internal fun Preferences.toServerResolverConfig(): ServerResolverConfig = ServerResolverConfig(
    enabled = this[ENHANCED_SERVER_FALLBACK_ENABLED] ?: EnhancedFeatureDefaults.SERVER_FALLBACK,
    baseUrl = this[ENHANCED_SERVER_FALLBACK_URL].orEmpty().trim(),
    apiKey = this[ENHANCED_SERVER_FALLBACK_API_KEY]
        ?.trim()
        ?.takeIf { it.isNotEmpty() },
    timeoutSeconds = ServerResolverConfig().timeoutSeconds
)
