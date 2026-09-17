package com.linksi.app.enhanced.download

import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaInfo
import com.linksi.app.enhanced.media.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [DownloadEngine] companion helpers in `DownloadEngine.kt`.
 *
 * Case identifiers in the comments map onto LINKSI_ENHANCED_REVISED_SPEC.md section 23. The
 * engine itself is an interface implemented with WorkManager on Android, so only the pure
 * functions that callers use to reason about a whole batch are pinned here.
 */
class DownloadEngineTest {

    private val ready = DownloadState.Ready(
        MediaInfo(
            webpageUrl = "https://www.instagram.com/reel/1/",
            source = MediaSource.INSTAGRAM,
            title = "A reel"
        )
    )

    private val downloading = DownloadState.Downloading(
        bytesDownloaded = 512,
        totalBytes = 1024,
        bytesPerSecond = 128
    )

    private val anotherDownloading = DownloadState.Downloading(
        bytesDownloaded = 1,
        totalBytes = null,
        bytesPerSecond = null
    )

    // ── activeDownloads ───────────────────────────────────────────────────────

    @Test
    fun activeDownloadsReturnsOnlyDownloadingEntries() {
        val states = linkedMapOf<String, DownloadState>(
            "idle" to DownloadState.Idle,
            "analyzing" to DownloadState.Analyzing("https://x/y"),
            "ready" to ready,
            "queued" to DownloadState.Queued(0),
            "a" to downloading,
            "b" to anotherDownloading,
            "done" to DownloadState.Completed("/sdcard/x.mp4", 1024),
            "failed" to DownloadState.Failed(MediaError.NETWORK),
            "cancelled" to DownloadState.Cancelled
        )
        assertEquals(listOf("a", "b"), states.activeDownloads().map { it.first })
    }

    @Test
    fun activeDownloadsKeepsTheProgressValuesAttachedToTheirId() {
        val states = mapOf("a" to downloading, "b" to anotherDownloading)
        val active = states.activeDownloads().toMap()
        assertEquals(512L, active.getValue("a").bytesDownloaded)
        assertEquals(1024L, active.getValue("a").totalBytes)
        assertEquals(50, active.getValue("a").percent)
        assertEquals(null, active.getValue("b").percent)
    }

    @Test
    fun activeDownloadsIsEmptyWhenNothingIsRunning() {
        val states = mapOf<String, DownloadState>(
            "ready" to ready,
            "done" to DownloadState.Completed("/sdcard/x.mp4", 1024)
        )
        assertTrue(states.activeDownloads().isEmpty())
        assertTrue(emptyMap<String, DownloadState>().activeDownloads().isEmpty())
    }

    // ── hasActiveWork ─────────────────────────────────────────────────────────

    @Test
    fun hasActiveWorkIsTrueForEveryNonTerminalState() {
        // "Active work" covers analysis and queueing too: the downloads screen must keep talking
        // to the engine until the batch settles, not only while bytes are moving.
        for (state in listOf(
            DownloadState.Idle,
            DownloadState.Analyzing("https://x/y"),
            ready,
            DownloadState.Queued(1),
            downloading
        )) {
            assertTrue("$state should count as active work", mapOf("x" to state).hasActiveWork())
        }
    }

    @Test
    fun hasActiveWorkIsFalseOnceEveryEntryIsTerminal() {
        val states = mapOf<String, DownloadState>(
            "done" to DownloadState.Completed("/sdcard/x.mp4", 1024),
            "failed" to DownloadState.Failed(MediaError.NO_FORMATS),
            "cancelled" to DownloadState.Cancelled
        )
        assertFalse(states.hasActiveWork())
    }

    @Test
    fun hasActiveWorkIsFalseForAnEmptyMap() {
        assertFalse(emptyMap<String, DownloadState>().hasActiveWork())
    }

    @Test
    fun aSingleTerminalEntryIsEnoughToSettleABatch() {
        val mixed = mapOf<String, DownloadState>(
            "running" to downloading,
            "done" to DownloadState.Completed("/sdcard/x.mp4", 1024)
        )
        assertTrue(mixed.hasActiveWork())
        assertFalse((mixed - "running").hasActiveWork())
    }
}
