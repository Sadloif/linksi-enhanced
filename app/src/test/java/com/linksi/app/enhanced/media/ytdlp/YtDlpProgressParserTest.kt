package com.linksi.app.enhanced.media.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [YtDlpProgressParser].
 *
 * The parser is what turns yt-dlp's human-readable progress line into the numbers
 * `DownloadState.Downloading` needs. It is pinned here because the lines it reads are not a
 * contract anyone maintains: they have changed shape between yt-dlp releases and between download
 * modes, and a parser that quietly returns nothing looks exactly like a slow download.
 */
class YtDlpProgressParserTest {

    @Test
    fun theOrdinaryProgressLineYieldsPercentTotalAndSpeed() {
        val progress = YtDlpProgressParser.parse(
            "[download]  45.2% of   12.34MiB at    1.23MiB/s ETA 00:05"
        )
        assertNotNull(progress)
        assertEquals(45.2f, progress!!.percent, 0.001f)
        assertEquals(12_939_427L, progress.totalBytes)
        assertEquals(5_848_621L, progress.downloadedBytes)
        assertEquals(1_289_748L, progress.bytesPerSecond)
    }

    @Test
    fun oneHundredPercentWithoutADecimalStillParses() {
        val progress = YtDlpProgressParser.parse("[download] 100% of    2.00MiB in 00:01")
        assertNotNull(progress)
        assertEquals(100f, progress!!.percent, 0.0f)
        assertEquals(2_097_152L, progress.totalBytes)
        assertEquals(2_097_152L, progress.downloadedBytes)
        assertNull("a finished stream states no speed", progress.bytesPerSecond)
    }

    @Test
    fun anEstimatedTotalIsReadThroughTheTilde() {
        // Fragment downloads report the total with a leading ~; ignoring it would lose the total
        // exactly where the percentage matters most.
        val progress = YtDlpProgressParser.parse(
            "[download]  12.3% of ~  1.23MiB at  456.78KiB/s ETA 00:02 (frag 3/10)"
        )
        assertNotNull(progress)
        assertEquals(1_289_748L, progress!!.totalBytes)
        assertEquals(467_742L, progress.bytesPerSecond)
        assertEquals(158_639L, progress.downloadedBytes)
    }

    @Test
    fun aLiveStreamLineWithoutATotalStillYieldsAPercentageAndSpeed() {
        val progress = YtDlpProgressParser.parse(
            "[download]   5.0% of ~  800.00KiB at  120.00KiB/s ETA Unknown"
        )
        assertNotNull(progress)
        assertEquals(5.0f, progress!!.percent, 0.001f)
        assertEquals(819_200L, progress.totalBytes)
        assertEquals(122_880L, progress.bytesPerSecond)
    }

    @Test
    fun aLineWithNoPercentageIsNotProgress() {
        assertNull(YtDlpProgressParser.parse("[download] Destination: /tmp/ytdlp/media.f137.mp4"))
    }

    @Test
    fun ytDlpChatterIsNotProgress() {
        assertNull(YtDlpProgressParser.parse("[youtube] Extracting URL: dQw4w9WgXcQ"))
        assertNull(YtDlpProgressParser.parse("[info] dQw4w9WgXcQ: Downloading webpage"))
        assertNull(YtDlpProgressParser.parse("[ffmpeg] Merging formats into \"media.mp4\""))
    }

    @Test
    fun nothingAtAllIsNotProgress() {
        assertNull(YtDlpProgressParser.parse(null))
        assertNull(YtDlpProgressParser.parse(""))
        assertNull(YtDlpProgressParser.parse("   "))
    }

    @Test
    fun bothUnitFamiliesAreAccepted() {
        // yt-dlp uses binary units for what it measures, but either family shows up in speeds.
        assertEquals(1000L, YtDlpProgressParser.size("1", "KB"))
        assertEquals(1024L, YtDlpProgressParser.size("1", "KiB"))
        assertEquals(1_000_000L, YtDlpProgressParser.size("1", "MB"))
        assertEquals(1_048_576L, YtDlpProgressParser.size("1", "MiB"))
        assertEquals(1_073_741_824L, YtDlpProgressParser.size("1", "GiB"))
        assertEquals(2_199_023_255_552L, YtDlpProgressParser.size("2", "TiB"))
        assertEquals(512L, YtDlpProgressParser.size("512", "B"))
    }

    @Test
    fun anUnknownUnitOrANonNumberIsUnknownRatherThanAGuess() {
        assertNull(YtDlpProgressParser.size("1", "XB"))
        assertNull(YtDlpProgressParser.size("1.2.3", "MiB"))
        assertNull(YtDlpProgressParser.size("", "MiB"))
        assertNull(YtDlpProgressParser.size("-1", "MiB"))
        assertNull(YtDlpProgressParser.size("NaN", "MiB"))
        assertNull(YtDlpProgressParser.size("Infinity", "MiB"))
    }

    @Test
    fun aValueBeyondLongRangeSaturatesRatherThanWrapping() {
        // A progress bar may read "as long as possible"; it must never read as a tiny file.
        val size = YtDlpProgressParser.size("99999999", "TiB")
        assertNotNull(size)
        assertEquals(Long.MAX_VALUE, size)
    }
}
