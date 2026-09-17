package com.linksi.app.enhanced.download

import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaInfo
import com.linksi.app.enhanced.media.MediaSource
import kotlin.math.roundToInt

/** Where a download should end up (specification section 22). */
enum class DownloadDestination {
    /** Public Downloads collection, visible to other apps. Preferred default. */
    PUBLIC_DOWNLOADS,

    /** App-specific external storage, no permission required and removed on uninstall. */
    APP_STORAGE,

    /** A folder the user picked through the Storage Access Framework. */
    USER_SELECTED
}

/** One queued download. */
data class DownloadRequest(
    val id: String,
    val url: String,
    val source: MediaSource = MediaSource.UNKNOWN,
    val formatId: String? = null,
    val suggestedFileName: String? = null,
    val destination: DownloadDestination = DownloadDestination.PUBLIC_DOWNLOADS,
    /** True when the format needs video and audio merged, which needs FFmpeg. */
    val requiresMuxing: Boolean = false
)

/**
 * The explicit download state machine (specification section 20). Every state is a value the UI
 * can render, and unknown progress is modelled as null rather than faked as an exact number.
 */
sealed interface DownloadState {

    val isTerminal: Boolean get() = this is Completed || this is Failed || this is Cancelled

    data object Idle : DownloadState

    data class Analyzing(val url: String) : DownloadState

    /** Formats are known and the user may choose one. */
    data class Ready(val info: MediaInfo) : DownloadState

    data class Queued(val position: Int) : DownloadState

    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long?
    ) : DownloadState {
        /** Null when the server did not report a length: the UI must not show a fake percentage. */
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }
                ?.let { (bytesDownloaded.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) }

        val percent: Int? get() = fraction?.let { (it * 100).roundToInt() }

        val hasKnownTotal: Boolean get() = totalBytes != null && totalBytes > 0
    }

    data class Completed(
        val filePath: String,
        val bytes: Long,
        val mimeType: String? = null,
        val displayName: String? = null
    ) : DownloadState

    data class Failed(val error: MediaError, val detail: String? = null) : DownloadState

    data object Cancelled : DownloadState
}

/**
 * Progress and size formatting for the download UI (specification section 20: never present
 * unreliable data as exact). Pure JVM and unit testable.
 */
object DownloadFormatting {

    private const val KIB = 1024.0
    private const val MIB = KIB * 1024
    private const val GIB = MIB * 1024

    /** "1.2 MB", or "size unknown" style text when the value is not known. */
    fun formatBytes(bytes: Long?, unknownLabel: String = "Unknown size"): String {
        if (bytes == null || bytes < 0) return unknownLabel
        val value = bytes.toDouble()
        return when {
            value >= GIB -> String.format("%.2f GB", value / GIB)
            value >= MIB -> String.format("%.1f MB", value / MIB)
            value >= KIB -> String.format("%.0f KB", value / KIB)
            else -> "$bytes B"
        }
    }

    /** "2.4 MB/s", or null when the speed is not known yet. */
    fun formatSpeed(bytesPerSecond: Long?): String? {
        if (bytesPerSecond == null || bytesPerSecond <= 0) return null
        return "${formatBytes(bytesPerSecond)}/s"
    }

    /**
     * Rough remaining time. Returns null unless both the total size and a recent speed are known,
     * because the specification forbids showing unreliable estimates as exact.
     */
    fun formatRemaining(
        bytesDownloaded: Long,
        totalBytes: Long?,
        bytesPerSecond: Long?
    ): String? {
        if (totalBytes == null || totalBytes <= 0) return null
        if (bytesPerSecond == null || bytesPerSecond <= 0) return null
        val remaining = totalBytes - bytesDownloaded
        if (remaining <= 0) return null

        val seconds = (remaining / bytesPerSecond).coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s left"
            seconds < 3600 -> "${seconds / 60}m left"
            else -> "${seconds / 3600}h left"
        }
    }

    /** "12.3 MB of 48.0 MB" when the total is known, otherwise just the downloaded amount. */
    fun formatProgressLine(state: DownloadState.Downloading): String {
        val downloaded = formatBytes(state.bytesDownloaded)
        return if (state.hasKnownTotal) {
            "$downloaded of ${formatBytes(state.totalBytes)}"
        } else {
            downloaded
        }
    }
}
