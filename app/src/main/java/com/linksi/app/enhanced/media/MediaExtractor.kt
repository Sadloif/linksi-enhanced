package com.linksi.app.enhanced.media

import com.linksi.app.enhanced.capability.RuntimeCapabilities
import kotlinx.coroutines.CancellationException

/**
 * A replaceable media extraction backend (specification section 16).
 *
 * The UI and the download layer depend on this interface, never on yt-dlp or any other concrete
 * engine, so a site change or an engine replacement never ripples through the app. Implementations
 * must never throw: every outcome is a [MediaExtractionResult].
 */
interface MediaExtractor {

    /** Stable identifier, used in logs and for diagnostics. */
    val id: String

    /** Shown to the user, for example "yt-dlp". */
    val displayName: String

    /** Higher runs first. Direct-file handling is cheap and must outrank a full extraction. */
    val priority: Int get() = 0

    /** True when this extractor is willing to look at [source] / [url]. */
    fun supports(source: MediaSource, url: String): Boolean

    /**
     * False when this extractor cannot run on the current device (missing native library, wrong
     * ABI, engine not bundled). The registry then simply skips it.
     */
    fun isAvailable(capabilities: RuntimeCapabilities): Boolean

    suspend fun analyze(url: String, source: MediaSource): MediaExtractionResult
}

/**
 * Ordered, capability-aware list of extractors (specification sections 16 and 31).
 *
 * Pure Kotlin, no Android dependency, so the selection and fallback policy is unit testable with
 * fake extractors.
 */
class ExtractorRegistry(private val extractors: List<MediaExtractor> = emptyList()) {

    /** Extractors that both support the URL and can run on this device, best first. */
    fun candidates(
        capabilities: RuntimeCapabilities,
        source: MediaSource,
        url: String
    ): List<MediaExtractor> = extractors
        .filter { it.supports(source, url) }
        .filter { extractor -> runCatching { extractor.isAvailable(capabilities) }.getOrDefault(false) }
        .sortedByDescending { it.priority }

    /** True when at least one extractor could run here at all. */
    fun hasUsableExtractor(capabilities: RuntimeCapabilities): Boolean =
        extractors.any { runCatching { it.isAvailable(capabilities) }.getOrDefault(false) }

    /**
     * Ask each candidate in turn and return the first success.
     *
     * Failure policy: when nothing succeeds, return the most informative failure seen, because
     * "this post is private" is far more useful to the user than "unsupported". If there was no
     * candidate at all, report [MediaError.ENGINE_UNAVAILABLE] rather than pretending the site is
     * unsupported.
     */
    suspend fun analyze(
        url: String,
        source: MediaSource,
        capabilities: RuntimeCapabilities
    ): MediaExtractionResult {
        val candidates = candidates(capabilities, source, url)
        if (candidates.isEmpty()) {
            return MediaExtractionResult.Skipped(url, "no extractor available for $source on ${
                capabilities.abi
            }")
        }

        var bestFailure: MediaExtractionResult.Failure? = null
        var unsupported: MediaExtractionResult.Unsupported? = null

        for (extractor in candidates) {
            val result = try {
                extractor.analyze(url, source)
            } catch (cancelled: CancellationException) {
                // Cancellation is control flow, not an extractor failure. Converting it into a
                // value would let a closed panel keep analysing or move on to another backend.
                throw cancelled
            } catch (error: Throwable) {
                MediaExtractionResult.Failure(
                    MediaError.EXTRACTOR_FAILED,
                    url,
                    error
                )
            }

            when (result) {
                is MediaExtractionResult.Success -> return result
                is MediaExtractionResult.Failure -> if (bestFailure == null) bestFailure = result
                is MediaExtractionResult.Unsupported -> if (unsupported == null) unsupported = result
                is MediaExtractionResult.Skipped -> Unit
            }
        }

        return bestFailure ?: unsupported ?: MediaExtractionResult.Failure(
            MediaError.EXTRACTOR_FAILED,
            url
        )
    }

    companion object {
        /** A registry with no backends: every request reports "engine unavailable", nothing crashes. */
        val EMPTY = ExtractorRegistry(emptyList())
    }
}
