package com.linksi.app.enhanced.resolver

import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaSource
import kotlinx.coroutines.CancellationException

/**
 * Applies the local-first policy shared by the panel's analysis path and its tests.
 *
 * A disabled/unusable resolver returns [MediaExtractionResult.Skipped], which is intentionally
 * ignored so the optional feature does not alter the baseline UI. A configured server's actual
 * failure is retained because it is actionable feedback for an opted-in user.
 */
suspend fun resolveLocalThenPrivateServer(
    url: String,
    source: MediaSource,
    local: suspend () -> MediaExtractionResult,
    resolver: MediaResolver
): MediaExtractionResult {
    val localResult = try {
        local()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        MediaExtractionResult.Failure(MediaError.EXTRACTOR_FAILED, url, error)
    }

    if (localResult is MediaExtractionResult.Success) return localResult

    val serverResult = try {
        rejectUnsafePrivateServerFormats(resolver.resolve(url, source))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        MediaExtractionResult.Failure(MediaError.SERVER_UNAVAILABLE, url, error)
    }

    return if (serverResult is MediaExtractionResult.Skipped) localResult else serverResult
}

/**
 * Applies the private resolver's one-URL contract to any resolver implementation, not only the
 * HTTP parser. This keeps a future resolver or a test double from reintroducing a silent
 * video-only success after the wire parser has rejected it.
 */
internal fun rejectUnsafePrivateServerFormats(
    result: MediaExtractionResult
): MediaExtractionResult = when (result) {
    is MediaExtractionResult.Success -> {
        val safeFormats = result.info.formats.filterNot { it.requiresMuxing }
        when {
            safeFormats.size == result.info.formats.size -> result
            safeFormats.isNotEmpty() -> MediaExtractionResult.Success(result.info.copy(formats = safeFormats))
            else -> MediaExtractionResult.Unsupported(result.info.webpageUrl, result.info.source)
        }
    }

    else -> result
}
