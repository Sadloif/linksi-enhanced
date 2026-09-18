package com.linksi.app.enhanced.resolver

import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaBackend
import com.linksi.app.enhanced.media.MediaFormat
import com.linksi.app.enhanced.media.MediaInfo
import com.linksi.app.enhanced.media.MediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the local-first, optional-server fallback policy. */
class MediaResolverFallbackTest {

    private val url = "https://example.com/post/1"
    private val source = MediaSource.OTHER

    private fun success(title: String) = MediaExtractionResult.Success(
        MediaInfo(
            webpageUrl = url,
            source = source,
            title = title,
            formats = listOf(
                MediaFormat(
                    "server",
                    "",
                    "mp4",
                    directUrl = "https://cdn.example/v.mp4",
                    backend = MediaBackend.PRIVATE_SERVER
                )
            )
        )
    )

    private class FakeResolver(
        private val result: MediaExtractionResult,
        private val onResolve: () -> Unit = {}
    ) : MediaResolver {
        override val id: String = "fake"
        override val displayName: String = "Fake"
        override val config: ServerResolverConfig = ServerResolverConfig()

        override suspend fun resolve(url: String, source: MediaSource): MediaExtractionResult {
            onResolve()
            return result
        }
    }

    @Test
    fun localSuccessDoesNotCallTheServer() = runBlocking {
        var calls = 0
        val local = success("local")

        val result = resolveLocalThenPrivateServer(
            url,
            source,
            local = { local },
            resolver = FakeResolver(success("server")) { calls++ }
        )

        assertSame(local, result)
        assertEquals(0, calls)
    }

    @Test
    fun serverIsCalledOnlyAfterLocalFailureAndItsSuccessIsUsed() = runBlocking {
        val events = mutableListOf<String>()

        val result = resolveLocalThenPrivateServer(
            url,
            source,
            local = {
                events += "local"
                MediaExtractionResult.Unsupported(url, source)
            },
            resolver = FakeResolver(success("server")) { events += "server" }
        )

        assertEquals(listOf("local", "server"), events)
        assertEquals("server", (result as MediaExtractionResult.Success).info.title)
    }

    @Test
    fun aDisabledResolverDoesNotChangeTheLocalFailure() = runBlocking {
        val local = MediaExtractionResult.Failure(MediaError.NETWORK, url)

        val result = resolveLocalThenPrivateServer(
            url,
            source,
            local = { local },
            resolver = FakeResolver(MediaExtractionResult.Skipped(url, "disabled"))
        )

        assertSame(local, result)
    }

    @Test
    fun aConfiguredServerFailureIsVisibleAfterLocalFailure() = runBlocking {
        val result = resolveLocalThenPrivateServer(
            url,
            source,
            local = { MediaExtractionResult.Unsupported(url, source) },
            resolver = FakeResolver(MediaExtractionResult.Failure(MediaError.SERVER_UNAVAILABLE, url))
        )

        assertTrue(result is MediaExtractionResult.Failure)
        assertEquals(MediaError.SERVER_UNAVAILABLE, (result as MediaExtractionResult.Failure).error)
    }

    @Test
    fun aServerMuxingResultCannotBecomeAFalseDirectDownloadSuccess() = runBlocking {
        val unsafe = MediaExtractionResult.Success(
            MediaInfo(
                webpageUrl = url,
                source = source,
                title = "video-only",
                formats = listOf(
                    MediaFormat(
                        id = "video-only",
                        label = "720p",
                        extension = "mp4",
                        directUrl = "https://cdn.example/video.mp4",
                        requiresMuxing = true,
                        backend = MediaBackend.PRIVATE_SERVER
                    )
                )
            )
        )

        val result = resolveLocalThenPrivateServer(
            url,
            source,
            local = { MediaExtractionResult.Unsupported(url, source) },
            resolver = FakeResolver(unsafe)
        )

        assertTrue(result is MediaExtractionResult.Unsupported)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedIntoAResolverFailure() {
        runBlocking {
            resolveLocalThenPrivateServer(
                url,
                source,
                local = { MediaExtractionResult.Unsupported(url, source) },
                resolver = FakeResolver(MediaExtractionResult.Skipped(url, "unused")) {
                    throw CancellationException("panel closed")
                }
            )
        }
    }
}
