package com.linksi.app.enhanced.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NotificationThrottle].
 *
 * Research brief section 4.5: progress notifications must be rate limited (Android 15 reduces the
 * volume, sound and vibration of repetitive notifications, and there is no opt-out API), so the
 * rule - at most one post per interval, or earlier when the percentage moves - is pinned here
 * rather than buried in the worker.
 */
class NotificationThrottleTest {

    private var now = 10_000L

    private fun throttle(interval: Long = 500L, percentDelta: Int = 1) =
        NotificationThrottle(
            clock = { now },
            minIntervalMillis = interval,
            minPercentDelta = percentDelta
        )

    private fun state(downloaded: Long, total: Long?) =
        DownloadState.Downloading(downloaded, total, bytesPerSecond = null)

    // ── Interval ──────────────────────────────────────────────────────────────

    @Test
    fun theFirstPostAlwaysGoesThrough() {
        assertTrue(throttle().shouldPost("a", state(0, 1_000)))
    }

    @Test
    fun aSecondPostInsideTheWindowIsDropped() {
        val throttle = throttle()
        assertTrue(throttle.shouldPost("a", state(0, 1_000)))
        now += 100
        assertFalse(throttle.shouldPost("a", state(1, 1_000)))
    }

    @Test
    fun aPostIsAllowedOnceTheWindowHasElapsed() {
        val throttle = throttle()
        assertTrue(throttle.shouldPost("a", state(0, 1_000)))
        now += 500
        assertTrue(throttle.shouldPost("a", state(1, 1_000)))
    }

    @Test
    fun theWindowRestartsAtEveryPost() {
        val throttle = throttle()
        assertTrue(throttle.shouldPost("a", state(0, 1_000)))

        now += 500
        assertTrue(throttle.shouldPost("a", state(2, 1_000)))

        now += 400
        assertFalse(throttle.shouldPost("a", state(3, 1_000)))

        now += 100
        assertTrue(throttle.shouldPost("a", state(4, 1_000)))
    }

    // ── Percentage movement ───────────────────────────────────────────────────

    @Test
    fun aWholePercentMovesTheNotificationEarly() {
        val throttle = throttle()
        assertTrue(throttle.shouldPost("a", state(0, 1_000)))

        now += 50
        // 0 % -> 1 %: a visible change, so it is worth the update even inside the window.
        assertTrue(throttle.shouldPost("a", state(10, 1_000)))
    }

    @Test
    fun lessThanOnePercentIsNotWorthAnUpdate() {
        val throttle = throttle()
        assertTrue(throttle.shouldPost("a", state(0, 1_000)))

        now += 50
        // 0.4 % still rounds to 0 %, so nothing visible changed.
        assertFalse(throttle.shouldPost("a", state(4, 1_000)))
    }

    @Test
    fun aBigJumpIsPostedImmediately() {
        val throttle = throttle()
        assertTrue(throttle.shouldPost("a", state(0, 1_000)))

        now += 10
        assertTrue(throttle.shouldPost("a", state(500, 1_000)))
    }

    @Test
    fun anUnknownTotalNeverTriggersThePercentageRule() {
        val throttle = throttle()
        assertTrue(throttle.shouldPost("a", state(1_024, null)))

        now += 10
        assertFalse("with no total there is no percentage to move", throttle.shouldPost("a", state(4_096, null)))

        now += 500
        assertTrue(throttle.shouldPost("a", state(8_192, null)))
    }

    @Test
    fun theTotalBecomingKnownIsNotSpecialCasedButTheIntervalStillApplies() {
        val throttle = throttle()
        assertTrue(throttle.shouldPost("a", state(1_024, null)))

        now += 500
        // 50 % of a newly discovered total: both rules agree this is worth posting.
        assertTrue(throttle.shouldPost("a", state(512, 1_024)))
    }

    @Test
    fun aCustomPercentDeltaIsHonoured() {
        val throttle = throttle(percentDelta = 10)
        assertTrue(throttle.shouldPost("a", state(0, 1_000)))

        now += 10
        assertFalse(throttle.shouldPost("a", state(50, 1_000)))
        assertTrue(throttle.shouldPost("a", state(100, 1_000)))
    }

    // ── Forcing and forgetting ────────────────────────────────────────────────

    @Test
    fun forceAlwaysPosts() {
        val throttle = throttle()
        assertTrue(throttle.shouldPost("a", state(0, 1_000)))
        now += 1
        assertTrue(throttle.shouldPost("a", state(1, 1_000), force = true))
    }

    @Test
    fun forgetAllowsTheNextPostImmediately() {
        val throttle = throttle()
        assertTrue(throttle.shouldPost("a", state(0, 1_000)))
        now += 1
        assertFalse(throttle.shouldPost("a", state(1, 1_000)))

        throttle.forget("a")
        assertTrue(throttle.shouldPost("a", state(2, 1_000)))
    }

    // ── Per download ──────────────────────────────────────────────────────────

    @Test
    fun eachDownloadIsThrottledSeparately() {
        val throttle = throttle()
        assertTrue(throttle.shouldPost("a", state(0, 1_000)))
        assertTrue(throttle.shouldPost("b", state(0, 1_000)))

        now += 10
        assertFalse(throttle.shouldPost("a", state(1, 1_000)))
        assertFalse(throttle.shouldPost("b", state(1, 1_000)))

        throttle.forget("a")
        assertTrue(throttle.shouldPost("a", state(2, 1_000)))
        assertFalse("forgetting one download must not release the other", throttle.shouldPost("b", state(2, 1_000)))
    }

    @Test
    fun theDefaultRateMatchesTheDocumentedGuidance() {
        assertEquals(500L, NotificationThrottle.DEFAULT_INTERVAL_MILLIS)
        assertEquals(1, NotificationThrottle.DEFAULT_PERCENT_DELTA)
    }
}
