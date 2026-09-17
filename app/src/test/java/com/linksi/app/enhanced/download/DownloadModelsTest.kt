package com.linksi.app.enhanced.download

import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaInfo
import com.linksi.app.enhanced.media.MediaFormat
import com.linksi.app.enhanced.media.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DownloadState], [DownloadRequest], [DownloadDestination] and
 * [DownloadFormatting].
 *
 * Case identifiers in the comments map onto LINKSI_ENHANCED_REVISED_SPEC.md sections 20, 22 and
 * 69. The governing rule is that unreliable data is never presented as exact: an unknown total is
 * `null`, not `0`.
 */
class DownloadModelsTest {

    private fun info() = MediaInfo(
        webpageUrl = "https://www.instagram.com/reel/1/",
        source = MediaSource.INSTAGRAM,
        title = "A reel",
        formats = listOf(MediaFormat(id = "v1", label = "720p", extension = "mp4", height = 720))
    )

    // ── The state machine (spec 20) ─────────────────────────────────────────────

    @Test
    fun onlyCompletedFailedAndCancelledAreTerminal() {
        assertFalse(DownloadState.Idle.isTerminal)
        assertFalse(DownloadState.Analyzing("https://x/y").isTerminal)
        assertFalse(DownloadState.Ready(info()).isTerminal)
        assertFalse(DownloadState.Queued(0).isTerminal)
        assertFalse(DownloadState.Downloading(1, 100, 10).isTerminal)
        assertTrue(DownloadState.Completed("/sdcard/x.mp4", 100).isTerminal)
        assertTrue(DownloadState.Failed(MediaError.NETWORK).isTerminal)
        assertTrue(DownloadState.Cancelled.isTerminal)
    }

    @Test
    fun theDownloadingStateCarriesAllProgressFields() {
        val state = DownloadState.Downloading(bytesDownloaded = 512, totalBytes = 1024, bytesPerSecond = 128)
        assertEquals(512L, state.bytesDownloaded)
        assertEquals(1024L, state.totalBytes)
        assertEquals(128L, state.bytesPerSecond)
    }

    @Test
    fun aCompletedStateRemembersWhereTheFileWent() {
        val state = DownloadState.Completed(
            filePath = "/storage/emulated/0/Download/clip.mp4",
            bytes = 4_194_304,
            mimeType = "video/mp4",
            displayName = "clip.mp4"
        )
        assertEquals(4_194_304L, state.bytes)
        assertEquals("video/mp4", state.mimeType)
        assertEquals("clip.mp4", state.displayName)
        assertNull(DownloadState.Completed("/x", 1).mimeType)
    }

    @Test
    fun aFreshRequestDefaultsToPublicDownloadsAndAnUnknownSource() {
        val request = DownloadRequest(id = "d1", url = "https://x/y")
        // A request that has not been analysed yet must not claim to know its source.
        assertEquals(MediaSource.UNKNOWN, request.source)
        assertEquals(DownloadDestination.PUBLIC_DOWNLOADS, request.destination)
        assertNull(request.formatId)
        assertNull(request.suggestedFileName)
        assertFalse(request.requiresMuxing)
    }

    @Test
    fun everyDestinationIsAClosedSetWithThreeOptions() {
        assertEquals(
            listOf(
                DownloadDestination.PUBLIC_DOWNLOADS,
                DownloadDestination.APP_STORAGE,
                DownloadDestination.USER_SELECTED
            ),
            DownloadDestination.entries.toList()
        )
    }

    // ── fraction / percent (spec 20: no fake percentage) ────────────────────────

    @Test
    fun fractionIsNullWhenTheTotalIsUnknown() {
        assertNull(DownloadState.Downloading(1024, null, 512).fraction)
        assertNull(DownloadState.Downloading(1024, null, 512).percent)
        assertFalse(DownloadState.Downloading(1024, null, 512).hasKnownTotal)
    }

    @Test
    fun fractionIsNullWhenTheTotalIsNotPositive() {
        // A zero or negative Content-Length means "unknown", never "0%".
        assertNull(DownloadState.Downloading(10, 0, null).fraction)
        assertNull(DownloadState.Downloading(10, 0, null).percent)
        assertNull(DownloadState.Downloading(10, -5, null).fraction)
        assertFalse(DownloadState.Downloading(10, 0, null).hasKnownTotal)
    }

    @Test
    fun fractionAndPercentAreComputedWhenTheTotalIsKnown() {
        val state = DownloadState.Downloading(25, 100, 10)
        assertEquals(0.25f, state.fraction!!, 0.0001f)
        assertEquals(25, state.percent)
        assertTrue(state.hasKnownTotal)
    }

    @Test
    fun aCompleteDownloadIsExactlyOneHundredPercent() {
        val state = DownloadState.Downloading(100, 100, 10)
        assertEquals(1.0f, state.fraction!!, 0.0001f)
        assertEquals(100, state.percent)
    }

    @Test
    fun progressIsClampedAtOneHundredPercent() {
        // Some servers report more bytes than the advertised Content-Length; the UI must not
        // render 130%.
        val state = DownloadState.Downloading(130, 100, 10)
        assertEquals(1.0f, state.fraction!!, 0.0001f)
        assertEquals(100, state.percent)
    }

    @Test
    fun progressIsClampedAtZero() {
        val state = DownloadState.Downloading(-50, 100, 10)
        assertEquals(0.0f, state.fraction!!, 0.0001f)
        assertEquals(0, state.percent)
    }

    @Test
    fun percentRoundsToTheNearestWholeNumber() {
        assertEquals(33, DownloadState.Downloading(1, 3, null).percent)
        assertEquals(67, DownloadState.Downloading(2, 3, null).percent)
        // 0.1% rounds down to zero; the UI shows "0%" rather than inventing progress.
        assertEquals(0, DownloadState.Downloading(1, 1000, null).percent)
        assertEquals(50, DownloadState.Downloading(500, 1000, null).percent)
        assertEquals(75, DownloadState.Downloading(3, 4, null).percent)
        // Just below the end: 99.4% rounds to 99, and only a full total reaches 100.
        assertEquals(99, DownloadState.Downloading(994, 1000, null).percent)
    }

    // ── formatBytes boundaries ─────────────────────────────────────────────────

    @Test
    fun formatBytesUsesTheBaseUnitUnderOneKilobyte() {
        assertEquals("0 B", DownloadFormatting.formatBytes(0))
        assertEquals("1 B", DownloadFormatting.formatBytes(1))
        assertEquals("512 B", DownloadFormatting.formatBytes(512))
        assertEquals("1023 B", DownloadFormatting.formatBytes(1023))
    }

    @Test
    fun formatBytesSwitchesToKilobytesAtExactlyOneKilobyte() {
        assertEquals("1 KB", DownloadFormatting.formatBytes(1024))
        assertEquals("2 KB", DownloadFormatting.formatBytes(2048))
        // Zero decimal places, so this rounds up to a whole kilobyte before the MB threshold.
        assertEquals("1024 KB", DownloadFormatting.formatBytes(1024L * 1024 - 1))
    }

    @Test
    fun formatBytesSwitchesToMegabytesAtExactlyOneMegabyte() {
        assertEquals("1.0 MB", DownloadFormatting.formatBytes(1024L * 1024))
        assertEquals("1.5 MB", DownloadFormatting.formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("1024 KB", DownloadFormatting.formatBytes(1024L * 1024 - 1))
    }

    @Test
    fun formatBytesSwitchesToGigabytesAtExactlyOneGigabyte() {
        assertEquals("1.00 GB", DownloadFormatting.formatBytes(1024L * 1024 * 1024))
        assertEquals("2.50 GB", DownloadFormatting.formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("1024.0 MB", DownloadFormatting.formatBytes(1024L * 1024 * 1024 - 1))
    }

    @Test
    fun formatBytesReportsUnknownForMissingOrNegativeValues() {
        assertEquals("Unknown size", DownloadFormatting.formatBytes(null))
        assertEquals("Unknown size", DownloadFormatting.formatBytes(-1))
        assertEquals("Unknown size", DownloadFormatting.formatBytes(Long.MIN_VALUE))
    }

    @Test
    fun formatBytesHonoursACustomUnknownLabel() {
        assertEquals("", DownloadFormatting.formatBytes(null, ""))
        assertEquals("--", DownloadFormatting.formatBytes(-5, "--"))
        // The label is ignored once there is a real value.
        assertEquals("1 KB", DownloadFormatting.formatBytes(1024, "--"))
    }

    // ── formatSpeed ────────────────────────────────────────────────────────────

    @Test
    fun formatSpeedIsNullWhenTheSpeedIsUnknownOrNotPositive() {
        assertNull(DownloadFormatting.formatSpeed(null))
        assertNull(DownloadFormatting.formatSpeed(0))
        assertNull(DownloadFormatting.formatSpeed(-1))
    }

    @Test
    fun formatSpeedAppendsAUnitSuffix() {
        assertEquals("1 B/s", DownloadFormatting.formatSpeed(1))
        assertEquals("1 KB/s", DownloadFormatting.formatSpeed(1024))
        assertEquals("1.0 MB/s", DownloadFormatting.formatSpeed(1024L * 1024))
        assertEquals("2.4 MB/s", DownloadFormatting.formatSpeed((2.4 * 1024 * 1024).toLong()))
    }

    // ── formatRemaining (spec 20: never show an unreliable estimate) ────────────

    @Test
    fun formatRemainingIsNullWithoutAReliableTotal() {
        assertNull(DownloadFormatting.formatRemaining(0, null, 1024))
        assertNull(DownloadFormatting.formatRemaining(0, 0, 1024))
        assertNull(DownloadFormatting.formatRemaining(0, -100, 1024))
    }

    @Test
    fun formatRemainingIsNullWithoutAReliableSpeed() {
        assertNull(DownloadFormatting.formatRemaining(0, 1024, null))
        assertNull(DownloadFormatting.formatRemaining(0, 1024, 0))
        assertNull(DownloadFormatting.formatRemaining(0, 1024, -10))
    }

    @Test
    fun formatRemainingIsNullWhenNothingIsLeft() {
        // A finished or over-reported download has no meaningful "time left".
        assertNull(DownloadFormatting.formatRemaining(1024, 1024, 100))
        assertNull(DownloadFormatting.formatRemaining(2048, 1024, 100))
    }

    @Test
    fun formatRemainingReportsSecondsUnderAMinute() {
        assertEquals("1s left", DownloadFormatting.formatRemaining(900, 1000, 100))
        assertEquals("10s left", DownloadFormatting.formatRemaining(0, 1000, 100))
        assertEquals("0s left", DownloadFormatting.formatRemaining(0, 10, 100))
    }

    @Test
    fun formatRemainingReportsMinutesAndHours() {
        assertEquals("1m left", DownloadFormatting.formatRemaining(0, 6000, 100))
        assertEquals("59m left", DownloadFormatting.formatRemaining(0, 354_000, 100))
        assertEquals("1h left", DownloadFormatting.formatRemaining(0, 360_000, 100))
        assertEquals("2h left", DownloadFormatting.formatRemaining(0, 720_000, 100))
    }

    // ── formatProgressLine ────────────────────────────────────────────────────

    @Test
    fun formatProgressLineShowsBothAmountsWhenTheTotalIsKnown() {
        assertEquals(
            "1.0 MB of 2.0 MB",
            DownloadFormatting.formatProgressLine(DownloadState.Downloading(1024L * 1024, 2L * 1024 * 1024, 1024))
        )
    }

    @Test
    fun formatProgressLineShowsOnlyTheDownloadedAmountWithoutATotal() {
        assertEquals(
            "1.0 MB",
            DownloadFormatting.formatProgressLine(DownloadState.Downloading(1024L * 1024, null, null))
        )
        // A zero total is "unknown", so the line must not claim "1.0 MB of 0 B".
        assertEquals(
            "1.0 MB",
            DownloadFormatting.formatProgressLine(DownloadState.Downloading(1024L * 1024, 0, null))
        )
    }

    @Test
    fun formatProgressLineHandlesAZeroByteStart() {
        assertEquals(
            "0 B of 4.0 MB",
            DownloadFormatting.formatProgressLine(DownloadState.Downloading(0, 4L * 1024 * 1024, null))
        )
    }

    @Test
    fun formatProgressLineCarriesTheUnknownSizeLabelWhenUnknown() {
        assertEquals(
            "Unknown size of 4.0 MB",
            DownloadFormatting.formatProgressLine(DownloadState.Downloading(-1, 4L * 1024 * 1024, null))
        )
    }
}
