package com.linksi.app.enhanced.media.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the download watchdog: [DownloadWatchdogPolicy] and [DownloadStallDetector].
 *
 * These exist because the decision they make is destructive - it kills the user's download - and
 * because it is the only thing standing between a hung socket and a progress bar that never moves.
 * On this device the engine stopped transferring entirely, with no further output at all, while its
 * process stayed alive for over nine minutes; "did the download stop?" therefore has to be answered
 * by arithmetic that is pinned by tests rather than by a guess about the network.
 */
class YtDlpDownloadWatchdogTest {

    // ── The stall rule ──────────────────────────────────────────────────────────────────────────

    @Test
    fun aDownloadThatHasNotStartedTransferringIsNotStalled() {
        // The bug this rule shipped with, caught by the first device run: yt-dlp fetches the page
        // and parses the manifest before any media moves, which takes longer than the stall limit.
        // Calling that a stall killed healthy downloads before they began.
        val detector = DownloadStallDetector(stallLimitMillis = 10_000L)

        assertFalse("with no bytes at all, nothing has stalled", detector.isStalled(now = 0L))
        assertFalse(
            "silence before the first byte is start-up, not a stall",
            detector.isStalled(now = 10_000L)
        )
        assertFalse("even well past the limit", detector.isStalled(now = 600_000L))
        assertFalse("and the detector knows no transfer happened", detector.hasTransferred())
    }

    @Test
    fun theStallClockOnlyRunsOnceBytesHaveMoved() {
        val detector = DownloadStallDetector(stallLimitMillis = 10_000L)

        // Start-up silence, then the first bytes arrive.
        assertFalse(detector.isStalled(now = 30_000L))
        assertFalse(detector.onProgress(bytesDownloaded = 1_000L, totalBytes = 10_000L, now = 30_000L))
        assertTrue("the clock started when the bytes did", detector.hasTransferred())
        assertFalse(detector.isStalled(now = 39_000L))
        assertTrue(detector.isStalled(now = 40_000L))
    }

    @Test
    fun silenceAfterAStreamReaches100PercentIsNotAStall() {
        // The bug the second device run found, and the longest real gap in the whole pipeline:
        // yt-dlp printed 100% and then spent 2 minutes 48 seconds in post-processing without a
        // single further progress line while FFmpeg rewrote 75 MiB. A byte-idle timer alone calls
        // that a hang and kills a download that was about to succeed.
        val detector = DownloadStallDetector(stallLimitMillis = 10_000L)
        detector.onProgress(bytesDownloaded = 9_000L, totalBytes = 10_000L, now = 0L)
        assertTrue("still transferring", detector.isStalled(now = 10_000L))

        assertFalse(
            "reaching the total means the stream is done, not stalled",
            detector.onProgress(bytesDownloaded = 10_000L, totalBytes = 10_000L, now = 10_000L)
        )
        assertFalse("post-processing is silent by design", detector.isStalled(now = 200_000L))
        assertFalse("however long it takes", detector.isStalled(now = 3_600_000L))
    }

    @Test
    fun theSecondStreamStartsTheClockAgain() {
        // A video+audio download reports 100% for the video and then starts the audio from near
        // zero. The audio stream is the one that hung in the real incident, so it must not inherit
        // the video's "finished" state.
        val detector = DownloadStallDetector(stallLimitMillis = 10_000L)
        detector.onProgress(bytesDownloaded = 16_000_000L, totalBytes = 16_000_000L, now = 0L)
        assertFalse("the video finished", detector.isStalled(now = 600_000L))

        detector.onProgress(bytesDownloaded = 40_000L, totalBytes = 5_000_000L, now = 600_000L)
        assertFalse("the audio is transferring", detector.isStalled(now = 609_000L))
        assertTrue("and now it has gone quiet", detector.isStalled(now = 610_000L))
    }

    @Test
    fun progressInASmallerSecondStreamKeepsTheClockMoving() {
        val detector = DownloadStallDetector(stallLimitMillis = 10_000L)
        detector.onProgress(bytesDownloaded = 16_000_000L, totalBytes = 16_000_000L, now = 0L)
        detector.onProgress(bytesDownloaded = 40_000L, totalBytes = 5_000_000L, now = 600_000L)

        assertFalse(
            "a later audio sample is progress even though it is below the completed video count",
            detector.onProgress(
                bytesDownloaded = 80_000L,
                totalBytes = 5_000_000L,
                now = 611_000L
            )
        )
        assertTrue("the audio is stalled only after its own quiet interval", detector.isStalled(now = 621_000L))
    }

    @Test
    fun aChangingByteCountIsNeverAStall() {
        val detector = DownloadStallDetector(stallLimitMillis = 1_000L)
        var now = 0L

        // Five seconds of steady progress, in one-second steps, is not a stall however long it runs.
        for (byte in 1L..5L) {
            now += 1_000L
            assertFalse(
                "progress at $byte bytes must not be treated as a stall",
                detector.onProgress(bytesDownloaded = byte * 100L, now = now)
            )
        }
    }

    @Test
    fun noProgressForTheLimitIsAStall() {
        val detector = DownloadStallDetector(stallLimitMillis = 60_000L)
        detector.onProgress(bytesDownloaded = 3_672_160L, now = 0L)

        assertFalse("half the limit is not yet a stall", detector.onProgress(3_672_160L, now = 30_000L))
        assertTrue("the limit itself is a stall", detector.onProgress(3_672_160L, now = 60_000L))
        assertTrue("and it stays one", detector.onProgress(3_672_160L, now = 600_000L))
    }

    @Test
    fun progressResetsTheStallClock() {
        val detector = DownloadStallDetector(stallLimitMillis = 60_000L)
        detector.onProgress(bytesDownloaded = 1_000L, now = 0L)

        // A trickle just inside the limit keeps the download alive; it is slow, not stuck.
        assertFalse(detector.onProgress(1_001L, now = 59_000L))
        assertFalse("the clock restarted at the last byte", detector.onProgress(1_001L, now = 100_000L))
        assertTrue(detector.onProgress(1_001L, now = 119_000L))
    }

    @Test
    fun outputWithoutBytesIsNotProgress() {
        // The whole point of the rule: yt-dlp can print lines without transferring anything, and a
        // hung socket can sit inside a line that was already printed. The transfer has to have
        // started for this to be a stall, so the first byte is recorded here.
        val detector = DownloadStallDetector(stallLimitMillis = 10_000L)
        detector.onProgress(bytesDownloaded = 5_000L, now = 0L)

        var now = 1_000L
        // Strictly inside the window: the limit itself is covered by the next assertion, so this
        // loop must not accidentally test the boundary.
        while (now < 10_000L) {
            assertFalse(
                "a callback that reports no new bytes at ${now}ms must not count as progress",
                detector.onProgress(5_000L, now = now)
            )
            now += 1_000L
        }

        assertTrue(
            "the callback that lands on the limit reports the stall it has just measured",
            detector.onProgress(5_000L, now = 10_000L)
        )
        assertTrue(
            "and the bytes never moved, so it stays a stall",
            detector.isStalled(now = 10_001L)
        )
    }

    @Test
    fun aByteCountThatGoesBackwardsIsNotProgress() {
        // yt-dlp reports per-file progress: when it moves from the video stream to the audio one the
        // number legitimately starts again from near zero. That must not be read as forward motion.
        val detector = DownloadStallDetector(stallLimitMillis = 10_000L)
        detector.onProgress(bytesDownloaded = 16_000_000L, now = 0L)
        detector.onProgress(bytesDownloaded = 40_000L, now = 9_000L)

        assertTrue(
            "a smaller count must not restart the stall clock",
            detector.isStalled(now = 10_000L)
        )
    }

    @Test
    fun theBytesSeenAreReportedForTheFailureMessage() {
        val detector = DownloadStallDetector(stallLimitMillis = 1_000L)
        assertEquals(0L, detector.bytesSeen())
        detector.onProgress(bytesDownloaded = 4_242L, now = 0L)
        assertEquals(4_242L, detector.bytesSeen())
        detector.onProgress(bytesDownloaded = 100L, now = 500L)
        assertEquals("the largest count is what the user is told", 4_242L, detector.bytesSeen())
    }

    // ── The limits ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun theDefaultStallLimitIsWellAboveAnyHealthyGap() {
        // Healthy fragment downloads on the test emulator reported progress every few hundred
        // milliseconds. A minute is tens of times that, so a slow link is not mistaken for a hang.
        assertTrue(DownloadWatchdogPolicy.DEFAULT_STALL_LIMIT_MILLIS >= 30_000L)
        assertTrue(DownloadWatchdogPolicy.DEFAULT_STALL_LIMIT_MILLIS <= 120_000L)
    }

    @Test
    fun anUnknownOrSmallSizeUsesTheLimitsUnchanged() {
        val policy = DownloadWatchdogPolicy()
        assertEquals(policy, policy.scaleFor(null))
        assertEquals(policy, policy.scaleFor(0L))
        assertEquals(policy, policy.scaleFor(-1L))
        assertEquals(policy, policy.scaleFor(DownloadWatchdogPolicy.ANCHOR_BYTES))
    }

    @Test
    fun aLargerDownloadGetsProportionallyMoreRoom() {
        val policy = DownloadWatchdogPolicy()
        val double = policy.scaleFor(DownloadWatchdogPolicy.ANCHOR_BYTES * 2)

        assertEquals(2.0, DownloadWatchdogPolicy.scaleFactor(DownloadWatchdogPolicy.ANCHOR_BYTES * 2), 0.0001)
        assertEquals(
            DownloadWatchdogPolicy.DEFAULT_STALL_LIMIT_MILLIS * 2,
            double.stallLimitMillis
        )
        assertEquals(
            DownloadWatchdogPolicy.DEFAULT_HARD_LIMIT_MILLIS * 2,
            double.hardLimitMillis
        )
    }

    @Test
    fun theScaleIsCappedSoABogusSizeCannotBuyUnlimitedPatience() {
        val policy = DownloadWatchdogPolicy()
        val huge = policy.scaleFor(Long.MAX_VALUE / 2)

        assertEquals(DownloadWatchdogPolicy.MAX_SCALE, DownloadWatchdogPolicy.scaleFactor(Long.MAX_VALUE), 0.0001)
        assertEquals(
            (DownloadWatchdogPolicy.DEFAULT_HARD_LIMIT_MILLIS * DownloadWatchdogPolicy.MAX_SCALE).toLong(),
            huge.hardLimitMillis
        )
    }

    @Test
    fun theHardLimitIsAlwaysTheLongerOfTheTwo() {
        val policy = DownloadWatchdogPolicy()
        assertTrue(policy.hardLimitMillis > policy.stallLimitMillis)
        val scaled = policy.scaleFor(DownloadWatchdogPolicy.ANCHOR_BYTES * 4)
        assertTrue(scaled.hardLimitMillis > scaled.stallLimitMillis)
    }

    // ── The scratch directory: the thing that decides whether a retry resumes ───────────────────

    private fun request(
        url: String = "https://example.test/video",
        formatId: String? = "137",
        requiresMuxing: Boolean = true,
        preferredName: String? = null
    ) = YtDlpDownloadRequest(
        url = url,
        formatId = formatId,
        requiresMuxing = requiresMuxing,
        preferredName = preferredName
    )

    @Test
    fun theSameDownloadAlwaysMapsToTheSameDirectory() {
        // This is the property that makes a retry a resume: yt-dlp's `.part` and `.ytdl` files are
        // only useful if the next attempt looks in the same place.
        assertEquals(workDirectoryName(request()), workDirectoryName(request()))
    }

    @Test
    fun adifferentQualityIsADifferentDirectory() {
        // Inheriting another format's partial file would publish the wrong video, so the format
        // selector has to be part of the identity.
        assertNotEquals(
            workDirectoryName(request(formatId = "137")),
            workDirectoryName(request(formatId = "22"))
        )
        assertNotEquals(
            workDirectoryName(request(formatId = "137", requiresMuxing = true)),
            workDirectoryName(request(formatId = "137", requiresMuxing = false))
        )
    }

    @Test
    fun adifferentUrlOrNameIsADifferentDirectory() {
        assertNotEquals(
            workDirectoryName(request(url = "https://example.test/one")),
            workDirectoryName(request(url = "https://example.test/two"))
        )
        assertNotEquals(
            workDirectoryName(request(preferredName = "holiday")),
            workDirectoryName(request(preferredName = null))
        )
    }

    @Test
    fun theDirectoryNameIsSafeForAFilesystemAndForAHostileTitle() {
        val name = workDirectoryName(
            request(
                url = "https://example.test/a b?c=d&e=f/../../etc/passwd",
                preferredName = "../../../data/data/com.linksi.app/files/db"
            )
        )
        assertTrue("a plain prefix", name.startsWith("work-"))
        assertTrue(
            "only hex after the prefix, so no traversal and no separators are possible: $name",
            name.removePrefix("work-").all { it in "0123456789abcdef" }
        )
        assertTrue("short enough for any filesystem", name.length < 32)
    }

    @Test
    fun twoDifferentRequestsRarelyCollide() {
        // Not a cryptographic claim, just that the digest is wide enough that the app's own
        // downloads do not land on each other. 48 bits over a few thousand downloads.
        val names = (1..500).map { index ->
            workDirectoryName(request(url = "https://example.test/video/$index"))
        }
        assertEquals("500 distinct downloads must get 500 distinct directories", 500, names.toSet().size)
    }
}
