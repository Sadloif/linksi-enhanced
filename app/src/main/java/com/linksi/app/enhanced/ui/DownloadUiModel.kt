package com.linksi.app.enhanced.ui

import androidx.annotation.StringRes
import com.linksi.app.R
import com.linksi.app.enhanced.download.DownloadFormatting
import com.linksi.app.enhanced.download.DownloadState
import com.linksi.app.enhanced.media.MediaError
import java.util.Locale

/**
 * The vocabulary of the download UI (specification sections 20, 22 and 26).
 *
 * Everything in this file is deliberately **Android free**: no `Context`, no `Composable`, no
 * platform call. The only Android type touched is `@StringRes Int`, which is just an `Int`, so the
 * whole mapping from a [DownloadState] to what the user reads can be pinned by plain JUnit tests -
 * including the rules that matter most:
 *
 *  - a percentage is only ever produced when the server actually reported a total size
 *    (specification section 20 forbids invented percentages);
 *  - every [MediaError] has a message, so `ENGINE_UNAVAILABLE` and `UNSUPPORTED_SITE` are as
 *    explainable as any other failure (specification section 26);
 *  - a failure is a value, never an exception, so a broken download cannot break the panel.
 */
object DownloadUiText {

    /**
     * A string resource plus, optionally, one already-formatted detail string.
     *
     * Formatting is resolved through [android.content.Context.getString] rather than
     * `String.format`, because Android supplies its own resource-aware formatter.
     */
    data class Label(@StringRes val res: Int, val argument: String? = null)

    /** The engine's own unknown-size wording, so the panel and the notification cannot disagree. */
    private const val UNKNOWN_SIZE = "Unknown size"

    /** "12.3 MB", or the engine's "Unknown size" wording. */
    fun formatBytes(bytes: Long?): String = DownloadFormatting.formatBytes(bytes, UNKNOWN_SIZE)

    /** "2.4 MB/s", or null when the speed is not known yet. */
    fun formatSpeed(bytesPerSecond: Long?): String? = DownloadFormatting.formatSpeed(bytesPerSecond)

    /**
     * The progress line for a running download: `12.3 MB of 48.0 MB · 2.4 MB/s`.
     *
     * The size halves come from [DownloadFormatting.formatProgressLine] so the downloads screen,
     * the panel and the notification all read identically, and the line degrades to just the
     * downloaded amount when the server reported no length.
     */
    fun progressLine(state: DownloadState.Downloading): String {
        val size = DownloadFormatting.formatProgressLine(state)
        val speed = formatSpeed(state.bytesPerSecond)
        return if (speed == null) size else "$size · $speed"
    }

    /**
     * The percentage to render, or null. Null whenever the total size is unknown, which is what
     * keeps the UI from inventing progress it cannot know.
     */
    fun percentOf(state: DownloadState.Downloading): Int? = state.percent

    /** The progress fraction for a determinate bar, or null for an indeterminate one. */
    fun fractionOf(state: DownloadState.Downloading): Float? = state.fraction

    /** The one-line status of any download state, as the downloads screen shows it. */
    fun statusLabel(state: DownloadState): Label = when (state) {
        is DownloadState.Idle -> Label(R.string.downloads_state_queued)
        is DownloadState.Analyzing -> Label(R.string.downloads_state_analyzing)
        is DownloadState.Ready -> Label(R.string.downloads_state_ready)
        is DownloadState.Queued -> Label(R.string.downloads_state_queued)
        is DownloadState.Downloading -> Label(R.string.downloads_state_running)
        is DownloadState.Completed -> Label(R.string.downloads_state_completed)
        is DownloadState.Failed -> Label(R.string.downloads_state_failed)
        is DownloadState.Cancelled -> Label(R.string.downloads_state_cancelled)
    }

    /**
     * The user-facing message for a failure reason (specification section 68).
     *
     * Exhaustive `when` on purpose: adding a [MediaError] without a message is then a compile error
     * rather than a silently blank row.
     */
    @StringRes
    fun errorRes(error: MediaError): Int = when (error) {
        MediaError.UNSUPPORTED_SITE -> R.string.error_download_unsupported
        MediaError.PRIVATE_CONTENT -> R.string.error_download_private
        MediaError.MEDIA_GONE -> R.string.error_download_gone
        MediaError.LOGIN_REQUIRED -> R.string.error_download_login_required
        MediaError.NETWORK -> R.string.error_download_network
        MediaError.EXTRACTOR_FAILED -> R.string.error_download_failed
        MediaError.NO_FORMATS -> R.string.error_download_no_formats
        MediaError.NO_STORAGE -> R.string.error_download_no_storage
        MediaError.ENGINE_UNAVAILABLE -> R.string.error_download_engine_unavailable
        MediaError.SERVER_UNAVAILABLE -> R.string.error_download_server_unavailable
        MediaError.CANCELLED -> R.string.error_download_cancelled
    }

    /** The failure message plus, when the state carries one, a short technical detail. */
    fun failureLabel(state: DownloadState.Failed): Label =
        Label(errorRes(state.error), state.detail?.takeIf { it.isNotBlank() })
}

/**
 * Which per-row controls a download offers.
 *
 * [CANCEL] and [RETRY]/[DISMISS] are mutually exclusive by construction, so the UI can never show
 * "cancel" on something that has already stopped.
 */
enum class DownloadRowAction { CANCEL, RETRY, DISMISS, NONE }

/** One row of the downloads screen: everything the UI needs, with no engine call in sight. */
data class DownloadRow(
    val id: String,
    val state: DownloadState,
    /** The requested URL, when this session still remembers it. */
    val url: String = "",
    /** A friendly name: the download's own file name, else the URL's last path segment. */
    val name: String = DownloadListModel.UNKNOWN_NAME,
    val isActive: Boolean = false,
    val action: DownloadRowAction = DownloadRowAction.NONE,
    val progressFraction: Float? = null,
    val progressPercent: Int? = null,
    val progressLine: String? = null,
    val status: DownloadUiText.Label = DownloadUiText.statusLabel(state)
) {
    /** "12.3 MB" when a size is known, else the explicit "size unknown" wording. */
    val sizeText: String
        get() = DownloadUiText.formatBytes(sizeBytes)

    /** The byte count this row can honestly show, or null when nothing was reported. */
    val sizeBytes: Long?
        get() = when (state) {
            is DownloadState.Downloading -> state.totalBytes?.takeIf { it > 0 } ?: state.bytesDownloaded
            is DownloadState.Completed -> state.bytes.takeIf { it > 0 }
            else -> null
        }

    /** The bytes actually on disk/complete, for the finished part of the row. */
    val downloadedBytes: Long?
        get() = when (state) {
            is DownloadState.Downloading -> state.bytesDownloaded
            is DownloadState.Completed -> state.bytes
            else -> null
        }
}

/**
 * Turns the engine's `observeAll()` map into the ordered, filterable list the screen renders.
 *
 * Ordering rule (specification section 22: the user must always be able to see what is happening):
 *
 *  1. **running downloads first**, so an in-progress row is never pushed off screen by history;
 *  2. then queued/analysing work, which is about to start;
 *  3. then the finished entries, newest work last - exactly the order the engine handed them in,
 *     which is request order, because the engine holds ids in insertion order.
 *
 * Within a group the incoming order is preserved, so a row does not jump around every time progress
 * is published. The filter drops entries the engine reports as [DownloadState.Idle]: an idle entry
 * means "known about, nothing to say", which is not a download the user should see.
 */
object DownloadListModel {

    /** True when [state] is worth a row on the downloads screen. */
    fun isVisible(state: DownloadState): Boolean = state !is DownloadState.Idle

    /** The ordered rows for [states]. Stable: equal inputs always produce equal outputs. */
    fun rowsOf(
        states: Map<String, DownloadState>,
        labels: Map<String, DownloadRequestLabel> = emptyMap()
    ): List<DownloadRow> = states
        .filterValues { isVisible(it) }
        .map { (id, state) -> rowOf(id, state, labels[id]) }
        .sortedBy { rank(it.state) }

    /** Active downloads only, for the settings subtitle and any "N in progress" summary. */
    fun activeCount(states: Map<String, DownloadState>): Int =
        states.values.count { isVisible(it) && !it.isTerminal }

    /** Finished downloads only (complete, failed, cancelled), in list order. */
    fun finishedRows(rows: List<DownloadRow>): List<DownloadRow> = rows.filter { it.state.isTerminal }

    fun activeRows(rows: List<DownloadRow>): List<DownloadRow> = rows.filter { !it.state.isTerminal }

    /**
     * The single row builder, shared by the downloads screen and the quick action panel so the two
     * can never disagree about what a state means.
     */
    fun rowOf(id: String, state: DownloadState, label: DownloadRequestLabel? = null): DownloadRow {
        val downloading = state as? DownloadState.Downloading
        return DownloadRow(
            id = id,
            state = state,
            url = label?.url.orEmpty(),
            name = nameOf(state, label),
            isActive = !state.isTerminal,
            action = actionFor(state),
            progressFraction = downloading?.let { DownloadUiText.fractionOf(it) },
            progressPercent = downloading?.let { DownloadUiText.percentOf(it) },
            progressLine = downloading?.let { DownloadUiText.progressLine(it) },
            status = DownloadUiText.statusLabel(state)
        )
    }

    /** Cancel what runs, retry what failed, remove what is done, and nothing for the rest. */
    fun actionFor(state: DownloadState): DownloadRowAction = when (state) {
        is DownloadState.Completed -> DownloadRowAction.DISMISS
        is DownloadState.Failed -> DownloadRowAction.RETRY
        is DownloadState.Cancelled -> DownloadRowAction.DISMISS
        is DownloadState.Downloading -> DownloadRowAction.CANCEL
        is DownloadState.Queued -> DownloadRowAction.CANCEL
        is DownloadState.Analyzing -> DownloadRowAction.CANCEL
        // `Ready` is a decision point, not a running download: there is nothing to cancel yet.
        is DownloadState.Ready -> DownloadRowAction.NONE
        is DownloadState.Idle -> DownloadRowAction.NONE
    }

    /** The name to show: the finished file's own name, else the requested URL's last segment. */
    private fun nameOf(state: DownloadState, label: DownloadRequestLabel?): String {
        (state as? DownloadState.Completed)?.displayName?.takeIf { it.isNotBlank() }?.let { return it }
        label?.fileName?.takeIf { it.isNotBlank() }?.let { return it }
        label?.url?.takeIf { it.isNotBlank() }?.let { url -> fileNameOfUrl(url)?.let { return it } }
        return UNKNOWN_NAME
    }

    /** The name the UI shows when neither the state nor the label carries a file name. */
    const val UNKNOWN_NAME = "Download"

    private fun fileNameOfUrl(url: String): String? = url
        .substringBefore('#')
        .substringBefore('?')
        .substringAfterLast('/')
        .trim()
        .takeIf { it.isNotEmpty() && it.contains('.') }

    /** Lower ranks render first. Ties keep the engine's own order (Kotlin's sort is stable). */
    private fun rank(state: DownloadState): Int = when (state) {
        is DownloadState.Downloading -> 0
        is DownloadState.Queued -> 1
        is DownloadState.Analyzing -> 2
        is DownloadState.Ready -> 3
        is DownloadState.Failed -> 4
        is DownloadState.Cancelled -> 5
        is DownloadState.Completed -> 6
        is DownloadState.Idle -> 7
    }
}

/** What the downloads screen knows about a request that the engine's state does not carry. */
data class DownloadRequestLabel(val url: String, val fileName: String? = null)

// ── The quick action panel's download area ──────────────────────────────────────────────────────

/**
 * The download area of the quick action panel.
 *
 * The panel is presentational, so it is handed this value rather than the engine: [phase] is the
 * engine's own [DownloadState], [label] is the message to show, and [progressFraction] is null
 * whenever the total size is unknown - the panel then renders an indeterminate bar instead of a
 * made-up percentage (specification sections 20 and 26).
 */
data class PanelDownloadStatus(
    val url: String,
    /** The wire id of the download being watched. Empty when nothing has been queued. */
    val downloadId: String = "",
    /** The format the user picked; empty for a direct file or an image. */
    val formatId: String = "",
    val phase: DownloadState = DownloadState.Idle,
    val label: DownloadUiText.Label = DownloadUiText.Label(R.string.download_panel_analyzing)
) {
    /** True while this panel has something running, so cancel is offered. */
    val isActive: Boolean get() = !downloadId.isBlank() && !phase.isTerminal

    /**
     * The progress fraction, or null. Reading it only from the engine's own [DownloadState] keeps
     * the "never invent a percentage" rule in one place.
     */
    val progressFraction: Float?
        get() = (phase as? DownloadState.Downloading)?.fraction

    /** The progress line, or null when nothing is downloading. */
    val progressLine: String?
        get() = (phase as? DownloadState.Downloading)?.let { DownloadUiText.progressLine(it) }

    /** True when the download finished, so the panel can offer "Open Downloads". */
    val isCompleted: Boolean get() = phase is DownloadState.Completed

    /** True when the download stopped badly, so the panel can offer "Try again". */
    val isRetryable: Boolean get() = phase is DownloadState.Failed || phase is DownloadState.Cancelled

    /** True when there is something to show: anything but the initial idle state. */
    val isVisible: Boolean get() = phase !is DownloadState.Idle

    companion object {
        /** The status shown while the URL is being analysed, before anything is queued. */
        fun analyzing(url: String): PanelDownloadStatus = PanelDownloadStatus(
            url = url,
            phase = DownloadState.Analyzing(url),
            label = DownloadUiText.Label(R.string.download_panel_analyzing)
        )

        /** The status map for a download that has just been queued. */
        fun queued(url: String, id: String, formatId: String): PanelDownloadStatus =
            PanelDownloadStatus(
                url = url,
                downloadId = id,
                formatId = formatId,
                phase = DownloadState.Queued(0),
                label = DownloadUiText.Label(R.string.download_panel_queued)
            )
    }
}

/**
 * The label for the panel's status card, derived from the engine state.
 *
 * Kept as a pure function so the "clear message even when the engine is unavailable" requirement
 * (specification section 26) is testable: [DownloadState.Failed] always yields the message of its
 * [MediaError], whatever that error is.
 */
fun panelLabelFor(state: DownloadState): DownloadUiText.Label = when (state) {
    is DownloadState.Idle -> DownloadUiText.Label(R.string.download_panel_queued)
    is DownloadState.Analyzing -> DownloadUiText.Label(R.string.download_panel_analyzing)
    is DownloadState.Ready -> DownloadUiText.Label(R.string.download_panel_queued)
    is DownloadState.Queued -> DownloadUiText.Label(R.string.download_panel_queued)
    is DownloadState.Downloading -> DownloadUiText.Label(R.string.download_panel_queued)
    is DownloadState.Completed -> DownloadUiText.Label(R.string.download_panel_complete)
    is DownloadState.Cancelled -> DownloadUiText.Label(R.string.download_panel_cancelled)
    is DownloadState.Failed -> DownloadUiText.failureLabel(state)
}

/**
 * A request id that is unique per download and stable per URL, so `ExistingWorkPolicy.KEEP` in
 * [com.linksi.app.enhanced.download.WorkManagerDownloadEngine] cannot silently swallow the second
 * tap of the same format.
 */
object DownloadRequestIds {

    /** `clip-a1b2c3d4` for a URL, suffixed with the format so two qualities are two downloads. */
    fun forUrl(url: String, formatId: String, nowMillis: Long = System.currentTimeMillis()): String {
        val digest = url.hashCode().toUInt().toString(16).padStart(8, '0')
        val format = formatId.ifBlank { "direct" }
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifBlank { "direct" }
        return "clip-$digest-$format-$nowMillis"
    }
}
