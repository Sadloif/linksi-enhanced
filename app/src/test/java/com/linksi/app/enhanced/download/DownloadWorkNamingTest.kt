package com.linksi.app.enhanced.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DownloadWorkNaming].
 *
 * These strings are load-bearing: the unique work name must stay stable, and a retry must be a
 * genuinely *new* name or `ExistingWorkPolicy.KEEP` silently drops it. The tag round-trip is what
 * lets the downloads screen recover work that outlived the process.
 */
class DownloadWorkNamingTest {

    // ── Tags ──────────────────────────────────────────────────────────────────

    @Test
    fun theRequestTagRoundTrips() {
        assertEquals("clip", DownloadWorkNaming.requestIdFromTags(setOf(DownloadWorkNaming.requestTag("clip"))))
        assertEquals(
            "3f2b8c14-9d0a-4e1e-9a3a-1f2e3d4c5b6a",
            DownloadWorkNaming.requestIdFromTags(
                setOf(DownloadWorkNaming.requestTag("3f2b8c14-9d0a-4e1e-9a3a-1f2e3d4c5b6a"))
            )
        )
    }

    @Test
    fun theRequestTagIsFoundAmongOtherTags() {
        val tags = setOf(DownloadWorkNaming.TAG_DOWNLOAD, "some.other.tag", DownloadWorkNaming.requestTag("clip"))
        assertEquals("clip", DownloadWorkNaming.requestIdFromTags(tags))
    }

    @Test
    fun unrelatedTagsYieldNoRequestId() {
        assertNull(DownloadWorkNaming.requestIdFromTags(emptySet()))
        assertNull(DownloadWorkNaming.requestIdFromTags(setOf(DownloadWorkNaming.TAG_DOWNLOAD)))
        assertNull(DownloadWorkNaming.requestIdFromTags(setOf("download", "linksi.downloads")))
    }

    @Test
    fun anEmptyRequestIdIsNotRecovered() {
        // A tag with nothing after the prefix must not resurrect a blank download id.
        assertNull(DownloadWorkNaming.requestIdFromTags(setOf(DownloadWorkNaming.requestTag(""))))
    }

    @Test
    fun tagsAreCaseSensitiveAndCollisionFree() {
        assertNotEquals(DownloadWorkNaming.requestTag("clip"), DownloadWorkNaming.requestTag("Clip"))
        assertTrue(DownloadWorkNaming.requestTag("clip").startsWith(DownloadWorkNaming.TAG_DOWNLOAD))
    }

    // ── Retry ids ─────────────────────────────────────────────────────────────

    @Test
    fun theFirstRetryGetsAFreshId() {
        assertEquals("clip#retry1", DownloadWorkNaming.retryId("clip"))
        assertNotEquals("clip", DownloadWorkNaming.retryId("clip"))
    }

    @Test
    fun retriesOfRetriesCountUpwards() {
        assertEquals("clip#retry2", DownloadWorkNaming.retryId("clip#retry1"))
        assertEquals("clip#retry9", DownloadWorkNaming.retryId("clip#retry8"))
        assertEquals("clip#retry10", DownloadWorkNaming.retryId("clip#retry9"))
    }

    @Test
    fun theBaseIdSurvivesEveryRetry() {
        // The derived id stays recognisable, which is what makes it useful in logs.
        var id = "3f2b8c14-9d0a-4e1e-9a3a-1f2e3d4c5b6a"
        repeat(5) {
            id = DownloadWorkNaming.retryId(id)
        }
        assertEquals("3f2b8c14-9d0a-4e1e-9a3a-1f2e3d4c5b6a#retry5", id)
    }

    @Test
    fun aMarkerWithoutADigitIsTreatedAsPartOfTheName() {
        // Not a shape the app produces, but it must not throw or lose the name.
        assertEquals("clip#retry#retry1", DownloadWorkNaming.retryId("clip#retry"))
    }

    @Test
    fun theRetrySuffixNeverOverflows() {
        val huge = "clip#retry2147483647"
        val next = DownloadWorkNaming.retryId(huge)
        assertTrue("'$next' should keep a positive counter", next.endsWith("#retry1000000"))
    }

    @Test
    fun everyRetryIsADistinctWorkName() {
        val ids = mutableSetOf("clip")
        var id = "clip"
        repeat(10) {
            id = DownloadWorkNaming.retryId(id)
            assertTrue("'$id' was already used", ids.add(id))
        }
        assertEquals(11, ids.size)
    }

    @Test
    fun anEmptyIdStillProducesAUsableName() {
        assertEquals("#retry1", DownloadWorkNaming.retryId(""))
    }

    @Test
    fun theDownloadTagIsTheOneTheEngineQueries() {
        assertEquals("linksi.download", DownloadWorkNaming.TAG_DOWNLOAD)
    }
}
