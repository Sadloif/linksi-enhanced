package com.linksi.app.enhanced.media

import com.linksi.app.enhanced.capability.RuntimeCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ExtractorRegistry] using fake extractors only.
 *
 * Case identifiers in the comments map onto LINKSI_ENHANCED_REVISED_SPEC.md sections 16, 26, 31
 * and 68. The registry is the single place that decides *which* backend runs and *what* the user
 * is told when none of them succeed, so both policies are pinned here rather than on a device.
 */
class ExtractorRegistryTest {

    /** Records what the registry asked each fake to do. */
    private val calls = mutableListOf<String>()

    private val capabilities = RuntimeCapabilities(
        sdkInt = 34,
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
    )

    private val legacyCapabilities = RuntimeCapabilities(
        sdkInt = 26,
        supportedAbis = listOf("armeabi-v7a")
    )

    /**
     * A configurable fake backend. Every knob defaults to the boring behaviour so each test only
     * states the one thing it is about.
     */
    private inner class FakeExtractor(
        override val id: String,
        override val displayName: String = "Fake $id",
        override val priority: Int = 0,
        private val supportedSources: Set<MediaSource> = MediaSource.entries.toSet(),
        private val available: Boolean = true,
        private val result: MediaExtractionResult = MediaExtractionResult.Success(defaultInfo(id)),
        private val throwOnAnalyze: Boolean = false,
        private val throwOnAvailability: Boolean = false
    ) : MediaExtractor {

        override fun supports(source: MediaSource, url: String): Boolean = source in supportedSources

        override fun isAvailable(capabilities: RuntimeCapabilities): Boolean {
            if (throwOnAvailability) throw IllegalStateException("$id could not probe the device")
            return available
        }

        override suspend fun analyze(url: String, source: MediaSource): MediaExtractionResult {
            calls += id
            if (throwOnAnalyze) throw IllegalStateException("$id exploded")
            return result
        }
    }

    private fun defaultInfo(id: String) = MediaInfo(
        webpageUrl = "https://www.instagram.com/reel/1/",
        source = MediaSource.INSTAGRAM,
        title = "resolved by $id",
        formats = listOf(MediaFormat(id = "v1", label = "", extension = "mp4", height = 720))
    )

    private fun success(id: String) = MediaExtractionResult.Success(defaultInfo(id))

    private fun analyze(
        registry: ExtractorRegistry,
        url: String = "https://www.instagram.com/reel/1/",
        source: MediaSource = MediaSource.INSTAGRAM,
        capabilities: RuntimeCapabilities = this.capabilities
    ): MediaExtractionResult = runBlocking { registry.analyze(url, source, capabilities) }

    // ── Ordering (spec 16: "higher runs first") ─────────────────────────────────

    @Test
    fun candidatesAreOrderedByDescendingPriority() {
        val registry = ExtractorRegistry(
            listOf(
                FakeExtractor("low", priority = 1),
                FakeExtractor("highest", priority = 100),
                FakeExtractor("middle", priority = 50)
            )
        )
        assertEquals(
            listOf("highest", "middle", "low"),
            registry.candidates(capabilities, MediaSource.INSTAGRAM, "https://x/y").map { it.id }
        )
    }

    @Test
    fun theHighestPriorityExtractorIsAskedFirst() {
        // A cheap direct-file handler must be able to answer before a full extraction is started.
        val registry = ExtractorRegistry(
            listOf(
                FakeExtractor("full", priority = 0, result = success("full")),
                FakeExtractor("direct", priority = 100, result = success("direct"))
            )
        )
        val result = analyze(registry)
        assertTrue(result is MediaExtractionResult.Success)
        assertEquals(listOf("direct"), calls)
        assertEquals("resolved by direct", (result as MediaExtractionResult.Success).info.title)
    }

    @Test
    fun equalPrioritiesKeepRegistrationOrder() {
        val registry = ExtractorRegistry(
            listOf(FakeExtractor("first", priority = 5), FakeExtractor("second", priority = 5))
        )
        assertEquals(
            listOf("first", "second"),
            registry.candidates(capabilities, MediaSource.INSTAGRAM, "https://x/y").map { it.id }
        )
    }

    // ── Capability filtering (spec 31) ──────────────────────────────────────────

    @Test
    fun anUnavailableExtractorIsSkipped() {
        val registry = ExtractorRegistry(
            listOf(
                FakeExtractor("needs-native-lib", priority = 100, available = false),
                FakeExtractor("fallback", priority = 1, available = true, result = success("fallback"))
            )
        )
        assertEquals(
            listOf("fallback"),
            registry.candidates(capabilities, MediaSource.INSTAGRAM, "https://x/y").map { it.id }
        )
        val result = analyze(registry)
        assertEquals("resolved by fallback", (result as MediaExtractionResult.Success).info.title)
        assertFalse("the unavailable extractor must never be asked", "needs-native-lib" in calls)
    }

    @Test
    fun anExtractorThatThrowsWhileProbingAvailabilityIsTreatedAsUnavailable() {
        val registry = ExtractorRegistry(
            listOf(
                FakeExtractor("broken-probe", priority = 100, throwOnAvailability = true),
                FakeExtractor("working", priority = 1, result = success("working"))
            )
        )
        assertEquals(
            listOf("working"),
            registry.candidates(capabilities, MediaSource.INSTAGRAM, "https://x/y").map { it.id }
        )
        assertTrue(registry.hasUsableExtractor(capabilities))
    }

    @Test
    fun hasUsableExtractorIsFalseWhenEverythingIsUnavailable() {
        val registry = ExtractorRegistry(
            listOf(FakeExtractor("a", available = false), FakeExtractor("b", available = false))
        )
        assertFalse(registry.hasUsableExtractor(capabilities))
        assertTrue(registry.candidates(capabilities, MediaSource.INSTAGRAM, "https://x/y").isEmpty())
    }

    @Test
    fun anExtractorThatDoesNotSupportTheSourceIsNeverACandidate() {
        val registry = ExtractorRegistry(
            listOf(
                FakeExtractor("facebook-only", supportedSources = setOf(MediaSource.FACEBOOK)),
                FakeExtractor("pinterest-only", supportedSources = setOf(MediaSource.PINTEREST))
            )
        )
        assertTrue(registry.candidates(capabilities, MediaSource.INSTAGRAM, "https://x/y").isEmpty())
        assertEquals(
            listOf("facebook-only"),
            registry.candidates(capabilities, MediaSource.FACEBOOK, "https://x/y").map { it.id }
        )
    }

    // ── Success and failure policy (spec 26 and 68) ─────────────────────────────

    @Test
    fun theFirstSuccessWinsAndLaterExtractorsAreNotAsked() {
        val registry = ExtractorRegistry(
            listOf(
                FakeExtractor(
                    "first",
                    priority = 10,
                    result = MediaExtractionResult.Failure(MediaError.EXTRACTOR_FAILED, "https://x/y")
                ),
                FakeExtractor("second", priority = 5, result = success("second")),
                FakeExtractor("third", priority = 1, result = success("third"))
            )
        )
        val result = analyze(registry)
        assertEquals("resolved by second", (result as MediaExtractionResult.Success).info.title)
        assertEquals(listOf("first", "second"), calls)
    }

    @Test
    fun aRealFailureBeatsAnUnsupportedResult() {
        // "This post is private" is more useful to the user than "unsupported site".
        val registry = ExtractorRegistry(
            listOf(
                FakeExtractor(
                    "generic",
                    priority = 10,
                    result = MediaExtractionResult.Unsupported("https://x/y", MediaSource.INSTAGRAM)
                ),
                FakeExtractor(
                    "site-specific",
                    priority = 5,
                    result = MediaExtractionResult.Failure(
                        MediaError.PRIVATE_CONTENT,
                        "https://x/y",
                        IllegalStateException("private account")
                    )
                )
            )
        )
        val result = analyze(registry)
        assertTrue(result is MediaExtractionResult.Failure)
        assertEquals(MediaError.PRIVATE_CONTENT, (result as MediaExtractionResult.Failure).error)
    }

    @Test
    fun theFirstFailureIsTheOneReported() {
        val registry = ExtractorRegistry(
            listOf(
                FakeExtractor(
                    "high",
                    priority = 10,
                    result = MediaExtractionResult.Failure(MediaError.LOGIN_REQUIRED, "https://x/y")
                ),
                FakeExtractor(
                    "low",
                    priority = 1,
                    result = MediaExtractionResult.Failure(MediaError.NETWORK, "https://x/y")
                )
            )
        )
        assertEquals(MediaError.LOGIN_REQUIRED, (analyze(registry) as MediaExtractionResult.Failure).error)
    }

    @Test
    fun unsupportedIsReportedOnlyWhenNoExtractorFailed() {
        val registry = ExtractorRegistry(
            listOf(
                FakeExtractor(
                    "high",
                    priority = 10,
                    result = MediaExtractionResult.Failure(MediaError.NETWORK, "https://x/y")
                ),
                FakeExtractor(
                    "low",
                    priority = 1,
                    result = MediaExtractionResult.Unsupported("https://x/y", MediaSource.INSTAGRAM)
                )
            )
        )
        // The failure is still the better answer...
        assertEquals(MediaError.NETWORK, (analyze(registry) as MediaExtractionResult.Failure).error)

        // ...but when the only outcome is Unsupported, that is what comes back.
        val onlyUnsupported = ExtractorRegistry(
            listOf(
                FakeExtractor(
                    "only",
                    result = MediaExtractionResult.Unsupported("https://x/y", MediaSource.INSTAGRAM)
                )
            )
        )
        val result = analyze(onlyUnsupported)
        assertTrue(result is MediaExtractionResult.Unsupported)
        assertEquals(MediaError.UNSUPPORTED_SITE, (result as MediaExtractionResult.Unsupported).error)
        assertEquals(MediaSource.INSTAGRAM, result.source)
    }

    @Test
    fun everyExtractorReturningSkippedBecomesAPlainFailureRatherThanSkipped() {
        // Skipped is reserved for "no candidate at all"; an attempted backend that skipped is a failure.
        val registry = ExtractorRegistry(
            listOf(FakeExtractor("skipping", result = MediaExtractionResult.Skipped("https://x/y", "busy")))
        )
        val result = analyze(registry)
        assertTrue(result is MediaExtractionResult.Failure)
        assertEquals(MediaError.EXTRACTOR_FAILED, (result as MediaExtractionResult.Failure).error)
        assertEquals(listOf("skipping"), calls)
    }

    // ── Throwing extractors (spec 26: a broken backend can never crash the UI) ───

    @Test
    fun anExtractorThatThrowsIsConvertedIntoAFailure() {
        val registry = ExtractorRegistry(
            listOf(
                FakeExtractor(
                    "throwing",
                    result = MediaExtractionResult.Success(defaultInfo("throwing")),
                    throwOnAnalyze = true
                )
            )
        )
        val result = analyze(registry)
        assertTrue("a throwing extractor must not propagate", result is MediaExtractionResult.Failure)
        val failure = result as MediaExtractionResult.Failure
        assertEquals(MediaError.EXTRACTOR_FAILED, failure.error)
        assertEquals("https://www.instagram.com/reel/1/", failure.url)
        // The throwable is kept for the log, while the user-facing error stays generic.
        assertTrue(failure.cause is IllegalStateException)
        assertEquals("throwing exploded", failure.cause?.message)
        assertEquals("error_download_failed", failure.error.messageKey)
    }

    @Test
    fun anExceptionFromOneExtractorDoesNotStopTheNextOne() {
        val registry = ExtractorRegistry(
            listOf(
                FakeExtractor("throwing", priority = 10, throwOnAnalyze = true),
                FakeExtractor("working", priority = 5, result = success("working"))
            )
        )
        val result = analyze(registry)
        assertTrue(result is MediaExtractionResult.Success)
        assertEquals("resolved by working", (result as MediaExtractionResult.Success).info.title)
        assertEquals(listOf("throwing", "working"), calls)
    }

    @Test
    fun aThrowFromEveryExtractorStillReturnsAValue() {
        val registry = ExtractorRegistry(
            listOf(FakeExtractor("a", priority = 2, throwOnAnalyze = true), FakeExtractor("b", throwOnAnalyze = true))
        )
        val result = analyze(registry)
        assertEquals(MediaError.EXTRACTOR_FAILED, (result as MediaExtractionResult.Failure).error)
    }

    // ── Empty registry (spec 31: a missing engine disables the action) ───────────

    @Test
    fun theEmptyRegistryReturnsSkippedWithTheEngineUnavailableReason() {
        val result = analyze(ExtractorRegistry.EMPTY)
        assertTrue(result is MediaExtractionResult.Skipped)
        val skipped = result as MediaExtractionResult.Skipped
        assertEquals("https://www.instagram.com/reel/1/", skipped.url)
        assertEquals(MediaError.ENGINE_UNAVAILABLE, skipped.error)
        assertTrue("the reason should name the source", skipped.reason.contains("INSTAGRAM"))
        assertTrue("the reason should name the ABI", skipped.reason.contains("arm64-v8a"))
    }

    @Test
    fun theEmptyRegistryHasNoCandidatesAndNoUsableExtractor() {
        assertTrue(ExtractorRegistry.EMPTY.candidates(capabilities, MediaSource.INSTAGRAM, "https://x/y").isEmpty())
        assertFalse(ExtractorRegistry.EMPTY.hasUsableExtractor(capabilities))
        assertFalse(ExtractorRegistry().hasUsableExtractor(capabilities))
    }

    @Test
    fun aRegistryWhoseExtractorsAreAllUnavailableReportsSkippedRatherThanUnsupported() {
        // On a device that cannot run the engine the user must be told the engine is missing
        // (spec 31), not that their link is unsupported.
        val registry = ExtractorRegistry(listOf(FakeExtractor("native-only", available = false)))
        val result = analyze(registry, capabilities = legacyCapabilities)
        assertTrue(result is MediaExtractionResult.Skipped)
        assertEquals(MediaError.ENGINE_UNAVAILABLE, (result as MediaExtractionResult.Skipped).error)
        assertTrue(result.reason.contains("armeabi-v7a"))
    }

    @Test
    fun theDefaultConstructorProducesAnEmptyRegistry() {
        assertTrue(ExtractorRegistry().candidates(capabilities, MediaSource.OTHER, "https://x/y").isEmpty())
    }

    // ── Candidate list contents ─────────────────────────────────────────────────

    @Test
    fun candidatesKeepTheExtractorInstancesThemselves() {
        val extractor = FakeExtractor("only", priority = 3)
        val registry = ExtractorRegistry(listOf(extractor))
        val candidates = registry.candidates(capabilities, MediaSource.INSTAGRAM, "https://x/y")
        assertEquals(1, candidates.size)
        assertSame(extractor, candidates.single())
        assertEquals("Fake only", candidates.single().displayName)
    }
}
