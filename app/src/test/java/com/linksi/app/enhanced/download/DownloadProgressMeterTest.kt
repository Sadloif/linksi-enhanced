package com.linksi.app.enhanced.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DownloadProgressMeter].
 *
 * The specification (section 20) forbids presenting unreliable numbers as exact, so what is pinned
 * here is: progress is throttled, the speed is averaged over the emitted window rather than over a
 * single read, and an unknown total stays unknown instead of being faked.
 */
class DownloadProgressMeterTest {

    private class FakeClock(var now: Long = 1_000L) {
        fun advance(millis: Long) {
            now += millis
        }
    }

    private val clock = FakeClock()

    private fun meter(interval: Long = 250L) =
        DownloadProgressMeter(clock = { clock.now }, minIntervalMillis = interval)

    // ── Throttling ────────────────────────────────────────────────────────────

    @Test
    fun samplesInsideTheWindowAreDropped() {
        val meter = meter()
        meter.reset(0L)

        clock.advance(100)
        assertNull(meter.sample(1_024, 4_096))
        clock.advance(100)
        assertNull(meter.sample(2_048, 4_096))
        clock.advance(40)
        assertNull(meter.sample(3_000, 4_096))
    }

    @Test
    fun aSampleIsEmittedOnceTheWindowHasElapsed() {
        val meter = meter()
        meter.reset(0L)

        clock.advance(250)
        assertNotNull(meter.sample(1_024, 4_096))
    }

    @Test
    fun theWindowRestartsAfterEveryEmittedSample() {
        val meter = meter()
        meter.reset(0L)

        clock.advance(250)
        assertNotNull(meter.sample(1_024, 4_096))

        clock.advance(100)
        assertNull("the window restarted at the last sample", meter.sample(2_048, 4_096))
        clock.advance(150)
        assertNotNull(meter.sample(3_072, 4_096))
    }

    @Test
    fun zeroMeansAlwaysEmit() {
        val meter = meter(interval = 0L)
        meter.reset(0L)
        assertNotNull(meter.sample(1, 10))
        assertNotNull(meter.sample(2, 10))
    }

    @Test
    fun forceAlwaysEmits() {
        val meter = meter()
        meter.reset(0L)
        clock.advance(1)
        val forced = meter.sample(512, 1_024, force = true)
        assertNotNull("a forced sample is what publishes the final state", forced)
        assertEquals(512L, forced!!.bytesDownloaded)
    }

    // ── Speed arithmetic ──────────────────────────────────────────────────────

    @Test
    fun theSpeedIsAveragedOverTheEmittedWindow() {
        val meter = meter()
        meter.reset(0L)

        // 10 000 bytes in 500 ms is 20 000 B/s.
        clock.advance(500)
        val first = meter.sample(10_000, 100_000)!!
        assertEquals(20_000L, first.bytesPerSecond)

        // The next window is another 2 000 bytes in 1 s, so 2 000 B/s - not an average of both.
        clock.advance(1_000)
        val second = meter.sample(12_000, 100_000)!!
        assertEquals(2_000L, second.bytesPerSecond)
    }

    @Test
    fun aZeroWidthWindowKeepsThePreviousSpeedInsteadOfInventingOne() {
        val meter = meter()
        meter.reset(0L)

        clock.advance(1_000)
        assertEquals(2_000L, meter.sample(2_000, null)!!.bytesPerSecond)

        // Two samples in the same millisecond: dividing by zero must not produce an infinite rate.
        val forced = meter.sample(2_500, null, force = true)!!
        assertEquals(2_000L, forced.bytesPerSecond)
    }

    @Test
    fun theFirstForcedSampleHasNoSpeedYet() {
        val meter = meter()
        meter.reset(0L)
        val sample = meter.sample(0, 1_000, force = true)!!
        assertNull(sample.bytesPerSecond)
    }

    @Test
    fun resetForgetsTheSpeed() {
        val meter = meter()
        meter.reset(0L)
        clock.advance(1_000)
        assertEquals(1_000L, meter.sample(1_000, null)!!.bytesPerSecond)

        meter.reset(5_000L)
        clock.advance(0)
        assertNull(meter.sample(5_000, null, force = true)!!.bytesPerSecond)
    }

    @Test
    fun bytesTravelingBackwardsNeverProduceANegativeSpeed() {
        // A server that resends from the start must not make the UI show a negative rate.
        val meter = meter()
        meter.reset(0L)
        clock.advance(1_000)
        assertEquals(5_000L, meter.sample(5_000, null)!!.bytesPerSecond)

        clock.advance(1_000)
        val sample = meter.sample(4_000, null)!!
        assertEquals(0L, sample.bytesPerSecond)
        assertEquals(4_000L, sample.bytesDownloaded)
    }

    // ── Resume offsets and unknown totals ─────────────────────────────────────

    @Test
    fun aResumeOffsetIsNotCountedAsDownloadedBytes() {
        val meter = meter()
        // 1 000 bytes were already on disk when this attempt started.
        meter.reset(1_000L)

        clock.advance(1_000)
        val sample = meter.sample(3_000, 10_000)!!
        assertEquals(3_000L, sample.bytesDownloaded)
        assertEquals("only the 2 000 new bytes count", 2_000L, sample.bytesPerSecond)
    }

    @Test
    fun anUnknownTotalStaysUnknown() {
        val meter = meter()
        meter.reset(0L)
        clock.advance(500)
        val sample = meter.sample(1_000, null)!!
        assertNull(sample.totalBytes)
        assertNull(sample.percent)
        assertNull(sample.fraction)
        assertFalse(sample.hasKnownTotal)
    }

    @Test
    fun thePercentageComesFromTheStateItself() {
        val meter = meter()
        meter.reset(0L)
        clock.advance(500)
        val sample = meter.sample(512, 1_024)!!
        assertEquals(50, sample.percent)
        assertTrue(sample.hasKnownTotal)
    }

    @Test
    fun aNegativeResumeOffsetIsClampedToZero() {
        val meter = meter()
        meter.reset(-500L)
        clock.advance(1_000)
        assertEquals(1_000L, meter.sample(1_000, null)!!.bytesPerSecond)
    }

    @Test
    fun theDefaultIntervalIsAFractionOfASecond() {
        // The UI must look live without flooding WorkManager's database.
        assertTrue(DownloadProgressMeter.DEFAULT_INTERVAL_MILLIS in 100..1_000)
    }
}
