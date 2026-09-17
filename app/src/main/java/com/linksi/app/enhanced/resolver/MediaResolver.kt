package com.linksi.app.enhanced.resolver

import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaSource

/**
 * Configuration for the optional private server resolver (specification sections 24 and 25).
 *
 * The privacy rules from the specification are encoded in the contract rather than left to the
 * implementation:
 *  - the resolver is **off by default** and normal Linksi operation never depends on it;
 *  - [isUsable] refuses plain HTTP, because section 25 requires HTTPS;
 *  - [disclosure] is the exact text the UI must show before the user can enable it.
 */
data class ServerResolverConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String? = null,
    val timeoutSeconds: Int = 20
) {
    /** Usable only when explicitly enabled and pointed at an HTTPS endpoint. */
    val isUsable: Boolean
        get() = enabled && baseUrl.isNotBlank() && baseUrl.trim().lowercase().startsWith("https://")

    /** Why the resolver cannot be used, for showing next to a disabled switch. */
    val unusableReason: String?
        get() = when {
            !enabled -> "Server fallback is disabled"
            baseUrl.isBlank() -> "No resolver address configured"
            !baseUrl.trim().lowercase().startsWith("https://") -> "The resolver address must use HTTPS"
            else -> null
        }

    val disclosure: String
        get() = "When local extraction fails, the link's URL is sent to the server you configure " +
            "so it can return the media information. Nothing else is sent: no clipboard contents, " +
            "no screen contents and no saved links. Linksi keeps working if the server is offline."
}

/**
 * Optional fallback that resolves media on a private server (specification section 24).
 *
 * Implementations must:
 *  - send only the URL (plus request metadata) and never clipboard or accessibility content;
 *  - never send credentials, cookies or session data;
 *  - return a value rather than throwing, so a dead server cannot break the app.
 */
interface MediaResolver {

    val id: String
    val displayName: String
    val config: ServerResolverConfig

    suspend fun resolve(url: String, source: MediaSource): MediaExtractionResult
}

/** The default resolver: disabled, and explicit about it. */
class DisabledMediaResolver : MediaResolver {
    override val id: String = "disabled"
    override val displayName: String = "Disabled"
    override val config: ServerResolverConfig = ServerResolverConfig()

    override suspend fun resolve(url: String, source: MediaSource): MediaExtractionResult =
        MediaExtractionResult.Skipped(url, "server fallback is disabled")
}
