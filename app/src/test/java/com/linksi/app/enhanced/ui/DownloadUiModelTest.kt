package com.linksi.app.enhanced.ui

import com.linksi.app.R
import com.linksi.app.enhanced.download.DownloadState
import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaInfo
import com.linksi.app.enhanced.media.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the download UI's pure logic (specification sections 20, 22 and 26).
 *
 * The rules under test are the ones a screenshot cannot prove:
 *  - a percentage only ever exists when the server reported a total size;
 *  - the list orders running work first and keeps the engine's order inside a group;
 *  - every failure reason produces a real message, `ENGINE_UNAVAILABLE` and `UNSUPPORTED_SITE`
 *    included, so a broken engine can only ever degrade the panel.
 */
class DownloadUiModelTest {

    private val downloading = DownloadState.Downloading(
        bytesDownloaded = 512,
        totalBytes = 1024,
        bytesPerSecond = 128
    )

    private val downloadingUnknownTotal = DownloadState.Downloading(
        bytesDownloaded = 4096,
        totalBytes = null,
        bytesPerSecond = null
    )

    private val ready = DownloadState.Ready(
        MediaInfo(
            webpageUrl = "https://www.instagram.com/reel/1/",
            source = MediaSource.INSTAGRAM,
            title = "A reel"
        )
    )

    // ── progress formatting: never invent a percentage ────────────────────────

    @Test
    fun aKnownTotalProducesAFractionPercentageAndSizeLine() {
        assertEquals(0.5f, DownloadUiText.fractionOf(downloading)!!, 0.0001f)
        assertEquals(50, DownloadUiText.percentOf(downloading))
        assertEquals("512 B of 1 KB · 128 B/s", DownloadUiText.progressLine(downloading))
    }

    @Test
    fun anUnknownTotalProducesNoPercentageAtAll() {
        assertNull(DownloadUiText.fractionOf(downloadingUnknownTotal))
        assertNull(DownloadUiText.percentOf(downloadingUnknownTotal))
        // The size line degrades to the downloaded amount; no "of X" is invented.
        assertEquals("4 KB", DownloadUiText.progressLine(downloadingUnknownTotal))
    }

    @Test
    fun aZeroTotalIsTreatedAsUnknownRatherThanAsZeroPercentOfNothing() {
        val state = DownloadState.Downloading(bytesDownloaded = 10, totalBytes = 0, bytesPerSecond = null)
        assertNull(state.fraction)
        assertNull(DownloadUiText.percentOf(state))
        assertNull(DownloadUiText.fractionOf(state))
        assertEquals("10 B", DownloadUiText.progressLine(state))
    }

    @Test
    fun bytesAreFormattedThroughTheEnginesOwnFormatter() {
        assertEquals("Unknown size", DownloadUiText.formatBytes(null))
        assertEquals("Unknown size", DownloadUiText.formatBytes(-1))
        assertEquals("512 B", DownloadUiText.formatBytes(512))
        assertEquals("2.0 MB", DownloadUiText.formatBytes(2L * 1024 * 1024))
        assertNull(DownloadUiText.formatSpeed(null))
        assertNull(DownloadUiText.formatSpeed(0))
        assertEquals("1 KB/s", DownloadUiText.formatSpeed(1024))
    }

    // ── the list: ordering, filtering and actions ─────────────────────────────

    @Test
    fun runningDownloadsComeFirstAndFinishedOnesLast() {
        val states = linkedMapOf<String, DownloadState>(
            "done" to DownloadState.Completed("/sdcard/x.mp4", 1024),
            "failed" to DownloadState.Failed(MediaError.NETWORK),
            "queued" to DownloadState.Queued(0),
            "downloading" to downloading,
            "cancelled" to DownloadState.Cancelled
        )

        assertEquals(
            listOf("downloading", "queued", "failed", "cancelled", "done"),
            DownloadListModel.rowsOf(states).map { it.id }
        )
    }

    @Test
    fun orderingInsideAGroupKeepsTheEnginesOwnOrderSoRowsDoNotJump() {
        val first = DownloadState.Downloading(1, 10, null)
        val second = DownloadState.Downloading(2, 10, null)
        val states = linkedMapOf<String, DownloadState>("b" to first, "a" to second)

        assertEquals(listOf("b", "a"), DownloadListModel.rowsOf(states).map { it.id })
    }

    @Test
    fun idleEntriesAreNotRenderedAtAll() {
        val states = mapOf<String, DownloadState>(
            "idle" to DownloadState.Idle,
            "downloading" to downloading
        )
        assertEquals(listOf("downloading"), DownloadListModel.rowsOf(states).map { it.id })
        assertFalse(DownloadListModel.isVisible(DownloadState.Idle))
        assertTrue(DownloadListModel.isVisible(DownloadState.Cancelled))
    }

    @Test
    fun activeAndFinishedRowsArePartitionedWithoutLosingAnything() {
        val states = linkedMapOf<String, DownloadState>(
            "downloading" to downloading,
            "queued" to DownloadState.Queued(0),
            "analyzing" to DownloadState.Analyzing("https://x/y"),
            "ready" to ready,
            "done" to DownloadState.Completed("/sdcard/x.mp4", 10),
            "failed" to DownloadState.Failed(MediaError.NETWORK)
        )
        val rows = DownloadListModel.rowsOf(states)

        assertEquals(6, rows.size)
        assertEquals(listOf("downloading", "queued", "analyzing", "ready"), DownloadListModel.activeRows(rows).map { it.id })
        assertEquals(listOf("failed", "done"), DownloadListModel.finishedRows(rows).map { it.id })
        assertEquals(4, DownloadListModel.activeCount(states))
    }

    @Test
    fun eachStateGetsExactlyTheActionItCanSupport() {
        assertEquals(DownloadRowAction.CANCEL, DownloadListModel.actionFor(downloading))
        assertEquals(DownloadRowAction.CANCEL, DownloadListModel.actionFor(DownloadState.Queued(0)))
        assertEquals(DownloadRowAction.CANCEL, DownloadListModel.actionFor(DownloadState.Analyzing("u")))
        assertEquals(DownloadRowAction.RETRY, DownloadListModel.actionFor(DownloadState.Failed(MediaError.NETWORK)))
        assertEquals(DownloadRowAction.DISMISS, DownloadListModel.actionFor(DownloadState.Completed("p", 1)))
        assertEquals(DownloadRowAction.DISMISS, DownloadListModel.actionFor(DownloadState.Cancelled))
        // "Ready" is a decision point: there is nothing running to cancel yet.
        assertEquals(DownloadRowAction.NONE, DownloadListModel.actionFor(ready))
    }

    @Test
    fun aRunningRowCarriesItsProgressAndAKnownSize() {
        val row = DownloadListModel.rowOf("a", downloading)
        assertTrue(row.isActive)
        assertEquals(0.5f, row.progressFraction!!, 0.0001f)
        assertEquals(50, row.progressPercent)
        assertEquals("512 B of 1 KB · 128 B/s", row.progressLine)
        assertEquals("1 KB", row.sizeText)
        assertEquals(R.string.downloads_state_running, row.status.res)
    }

    @Test
    fun aRowWithNoReportedSizeSaysSoInsteadOfShowingZero() {
        val row = DownloadListModel.rowOf("a", downloadingUnknownTotal)
        assertNull(row.progressFraction)
        assertNull(row.progressPercent)
        // `sizeBytes` falls back to what was actually downloaded, and a finished-but-sizeless
        // download reads "Size unknown" rather than "0 B".
        assertEquals("4 KB", row.sizeText)
        assertEquals("Unknown size", DownloadListModel.rowOf("b", DownloadState.Cancelled).sizeText)
    }

    @Test
    fun aCompletedRowPrefersItsOwnFileNameAndAFailedOneFallsBackToTheUrl() {
        val completed = DownloadState.Completed(
            filePath = "content://downloads/1",
            bytes = 2048,
            displayName = "clip.mp4"
        )
        assertEquals("clip.mp4", DownloadListModel.rowOf("a", completed).name)

        val failed = DownloadState.Failed(MediaError.NETWORK)
        val labelled = DownloadListModel.rowOf(
            "b",
            failed,
            DownloadRequestLabel(url = "https://cdn.example.com/videos/holiday.mp4?sig=1")
        )
        assertEquals("holiday.mp4", labelled.name)
        assertEquals("https://cdn.example.com/videos/holiday.mp4?sig=1", labelled.url)

        // Nothing known at all still produces a renderable row.
        assertEquals(
            DownloadListModel.UNKNOWN_NAME,
            DownloadListModel.rowOf("c", DownloadState.Queued(0)).name
        )
    }

    // ── failure messages: section 26, every error is explainable ──────────────

    @Test
    fun everyFailureReasonHasItsOwnMessage() {
        val ids = MediaError.entries.map { DownloadUiText.errorRes(it) }
        assertTrue("every message resource must be a real id", ids.all { it != 0 })
        assertEquals("the mapping must be one-to-one", ids.size, ids.toSet().size)
    }

    @Test
    fun theTwoDegradedEngineStatesHaveTheirOwnClearMessages() {
        assertEquals(
            R.string.error_download_engine_unavailable,
            DownloadUiText.errorRes(MediaError.ENGINE_UNAVAILABLE)
        )
        assertEquals(
            R.string.error_download_unsupported,
            DownloadUiText.errorRes(MediaError.UNSUPPORTED_SITE)
        )
        assertNotEquals(
            DownloadUiText.errorRes(MediaError.ENGINE_UNAVAILABLE),
            DownloadUiText.errorRes(MediaError.UNSUPPORTED_SITE)
        )
    }

    @Test
    fun aFailureCarriesItsMessageAndItsDetail() {
        val label = DownloadUiText.failureLabel(
            DownloadState.Failed(MediaError.NO_STORAGE, "the Downloads collection refused a new entry")
        )
        assertEquals(R.string.error_download_no_storage, label.res)
        assertEquals("the Downloads collection refused a new entry", label.argument)

        // A blank detail must not produce an empty second line.
        assertNull(DownloadUiText.failureLabel(DownloadState.Failed(MediaError.NETWORK, "  ")).argument)
    }

    // ── the panel's own status ────────────────────────────────────────────────

    @Test
    fun thePanelOffersCancelOnlyWhileSomethingIsRunning() {
        val queued = PanelDownloadStatus.queued("https://x/y", "id-1", "")
        assertTrue(queued.isActive)
        assertFalse(queued.isRetryable)
        assertFalse(queued.isCompleted)

        val failed = queued.copy(
            phase = DownloadState.Failed(MediaError.ENGINE_UNAVAILABLE),
            label = panelLabelFor(DownloadState.Failed(MediaError.ENGINE_UNAVAILABLE))
        )
        assertFalse(failed.isActive)
        assertTrue(failed.isRetryable)
        assertEquals(R.string.error_download_engine_unavailable, failed.label.res)

        val done = queued.copy(
            phase = DownloadState.Completed("content://downloads/1", 10),
            label = panelLabelFor(DownloadState.Completed("content://downloads/1", 10))
        )
        assertTrue(done.isCompleted)
        assertFalse(done.isActive)
        assertFalse(done.isRetryable)
    }

    @Test
    fun thePanelNeverShowsProgressWithoutAKnownTotal() {
        val status = PanelDownloadStatus(
            url = "https://x/y",
            downloadId = "id",
            phase = downloadingUnknownTotal
        )
        assertNull(status.progressFraction)
        assertEquals("4 KB", status.progressLine)
        assertTrue(status.isActive)

        val known = status.copy(phase = downloading)
        assertEquals(0.5f, known.progressFraction!!, 0.0001f)
    }

    @Test
    fun anAnalyzingPanelIsActiveButHasNoDownloadIdToCancel() {
        val analyzing = PanelDownloadStatus.analyzing("https://example.com/cat.png")
        assertFalse("there is nothing queued yet, so nothing to cancel", analyzing.isActive)
        assertFalse(analyzing.isCompleted)
        assertEquals(R.string.download_panel_analyzing, analyzing.label.res)
        assertTrue(analyzing.isVisible)
        assertFalse(PanelDownloadStatus("https://x").isVisible)
    }

    // ── request ids ───────────────────────────────────────────────────────────

    @Test
    fun aRequestIdIsUniquePerUrlFormatAndMoment() {
        val first = DownloadRequestIds.forUrl("https://example.com/cat.png", "", nowMillis = 1)
        val second = DownloadRequestIds.forUrl("https://example.com/cat.png", "", nowMillis = 2)
        assertNotEquals(first, second)

        val otherFormat = DownloadRequestIds.forUrl("https://example.com/cat.png", "720p", nowMillis = 1)
        assertNotEquals(first, otherFormat)
        assertTrue(otherFormat.contains("720p"))

        // An empty format still produces a usable, readable marker.
        assertTrue(first.startsWith("clip-"))
        assertTrue(first.contains("direct"))
    }

    @Test
    fun aRequestIdSurvivesANastyFormatId() {
        val id = DownloadRequestIds.forUrl("https://example.com/v", "best video/audio #1")
        assertTrue(Regex("^clip-[0-9a-f]{8}-[a-z0-9_-]+-\\d+$").matches(id))
    }
}
