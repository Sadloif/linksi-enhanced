package com.linksi.app.enhanced.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linksi.app.R
import com.linksi.app.enhanced.media.MediaFormat
import com.linksi.app.enhanced.media.MediaSource
import java.util.Locale

/**
 * Callbacks the quick action panel fires.
 *
 * All of them have inert defaults, so a caller that only cares about one action can pass just that
 * one. Nothing here performs I/O on its own: the download callback is presentational in this
 * module and is wired to the download engine by whoever owns that module.
 *
 * @param onCleanUrl invoked with the cleaned URL when the user chooses "Clean URL".
 * @param onDownload invoked with a format id when the user picks a quality, or with an empty
 *                   string when the user uses the generic download row.
 */
data class QuickPanelCallbacks(
    val onCleanUrl: (String) -> Unit = {},
    val onCopyCleanUrl: () -> Unit = {},
    val onShareCleanUrl: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onFolder: () -> Unit = {},
    val onTags: () -> Unit = {},
    val onNotes: () -> Unit = {},
    val onOpen: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onDownload: (String) -> Unit = {},
    val onDismiss: () -> Unit = {}
)

/**
 * The quick action panel (specification sections 13 and 14).
 *
 * Renders exactly the actions [QuickActionModel] allows for [state]: the panel never invents a
 * control, and never shows a download control for content that cannot be downloaded
 * (specification section 67).
 *
 * The panel is inert: it is only ever composed on demand, performs no work at construction, and
 * asks for no permission. It performs no I/O of its own either - every row reports a click to
 * [callbacks], which the caller wires to [QuickPanelActions] or to its own logic.
 *
 * @param downloadStatus an optional slot rendered directly under the DOWNLOAD section. The panel
 *   itself knows nothing about a download engine: the host that owns one passes the live progress,
 *   the cancel button and the failure message in through this slot, and a host that has no engine
 *   (or does not care) simply leaves it out. It is deliberately *not* part of [callbacks], so the
 *   panel's callback contract stays presentational and unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionPanel(
    state: QuickPanelState,
    callbacks: QuickPanelCallbacks,
    modifier: Modifier = Modifier,
    downloadStatus: (@Composable () -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val actions = remember(state) { QuickActionModel.actionsFor(state) }
    val formats = remember(state) { state.displayFormats() }

    // Only meaningful when cleaning actually changed something; reset whenever the panel is
    // reopened for a different URL.
    var showOriginal by rememberSaveable(state.url, state.cleanedUrl) { mutableStateOf(false) }

    val shownUrl = if (showOriginal) state.url else state.cleanedUrl

    ModalBottomSheet(
        onDismissRequest = callbacks.onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            PanelHeader(state = state)

            Spacer(Modifier.height(12.dp))

            UrlPreview(
                state = state,
                shownUrl = shownUrl,
                showOriginal = showOriginal,
                onToggleOriginal = { showOriginal = !showOriginal }
            )

            val saveSection = SAVE_SECTION.filter { it in actions }
            if (saveSection.isNotEmpty()) {
                QuickPanelSectionHeader(stringResource(R.string.quick_panel_section_save))
                for (action in saveSection) {
                    QuickPanelRow(
                        icon = iconFor(action),
                        title = titleFor(action)
                    ) {
                        when (action) {
                            QuickAction.SAVE -> callbacks.onSave()
                            QuickAction.FOLDER -> callbacks.onFolder()
                            QuickAction.TAGS -> callbacks.onTags()
                            QuickAction.NOTES -> callbacks.onNotes()
                            else -> Unit
                        }
                    }
                }
            }

            // Specification 67: the download section only exists when the model allows the
            // DOWNLOAD action, so a page never gets one.
            if (QuickAction.DOWNLOAD in actions) {
                QuickPanelSectionHeader(stringResource(R.string.quick_panel_section_download))
                if (formats.isEmpty()) {
                    // An image or a direct file is the downloadable thing itself, with no quality
                    // to choose: one row, and the format id is empty because there is no format.
                    QuickPanelRow(
                        icon = Icons.Outlined.Download,
                        title = directDownloadTitle(state),
                        subtitle = state.cleanedUrl
                    ) {
                        callbacks.onDownload("")
                    }
                } else {
                    for (format in formats) {
                        QuickPanelRow(
                            icon = Icons.Outlined.Download,
                            title = format.displayLabel,
                            subtitle = formatSubtitle(format)
                        ) {
                            callbacks.onDownload(format.id)
                        }
                    }
                }

                // The engine's live progress, cancel, retry and failure message. Supplied by the
                // host, so the panel stays presentational and this module keeps no engine
                // dependency (specification section 26: a download failure must not break the rest
                // of the panel, which is why this slot renders *inside* the scrollable column
                // rather than replacing the panel).
                downloadStatus?.invoke()
            }

            val actionSection = ACTION_SECTION.filter { it in actions }
            if (actionSection.isNotEmpty()) {
                QuickPanelSectionHeader(stringResource(R.string.quick_panel_section_actions))
                for (action in actionSection) {
                    QuickPanelRow(
                        icon = iconFor(action),
                        title = titleFor(action),
                        subtitle = subtitleFor(action, state)
                    ) {
                        when (action) {
                            QuickAction.OPEN -> callbacks.onOpen()
                            QuickAction.SHARE -> callbacks.onShare()
                            QuickAction.COPY -> callbacks.onCopy()
                            QuickAction.CLEAN_URL -> callbacks.onCleanUrl(state.cleanedUrl)
                            QuickAction.COPY_CLEAN_URL -> callbacks.onCopyCleanUrl()
                            QuickAction.SHARE_CLEAN_URL -> callbacks.onShareCleanUrl()
                            else -> Unit
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Section 13's SAVE group, in its documented order. */
private val SAVE_SECTION = listOf(
    QuickAction.SAVE,
    QuickAction.FOLDER,
    QuickAction.TAGS,
    QuickAction.NOTES
)

/** Section 13's ACTIONS group, in its documented order. */
private val ACTION_SECTION = listOf(
    QuickAction.OPEN,
    QuickAction.SHARE,
    QuickAction.COPY,
    QuickAction.CLEAN_URL,
    QuickAction.COPY_CLEAN_URL,
    QuickAction.SHARE_CLEAN_URL
)

/** Header: the link title (or the detected source name) and the domain. */
@Composable
private fun PanelHeader(state: QuickPanelState) {
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Text(
            text = headerTitle(state),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = state.domain.ifBlank { state.source.displayName },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The cleaned URL preview plus, when cleaning changed something, the "Show original" toggle and a
 * short line saying how many tracking parameters were dropped.
 */
@Composable
private fun UrlPreview(
    state: QuickPanelState,
    shownUrl: String,
    showOriginal: Boolean,
    onToggleOriginal: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(
                    if (showOriginal) {
                        R.string.quick_panel_original_url
                    } else {
                        R.string.quick_panel_cleaned_url
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = shownUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )

            if (state.cleaningChanged) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onToggleOriginal) {
                        Text(
                            text = stringResource(
                                if (showOriginal) {
                                    R.string.quick_panel_show_cleaned
                                } else {
                                    R.string.quick_panel_show_original
                                }
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    if (state.removedCount > 0) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.quick_panel_removed_params,
                                state.removedCount,
                                state.removedCount
                            ).trim(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** One section label (SAVE / DOWNLOAD / ACTIONS). */
@Composable
private fun QuickPanelSectionHeader(title: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(18.dp))
        Text(
            text = title.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(6.dp))
    }
}

/** One action row. Presentational only: it reports the click and nothing else. */
@Composable
private fun QuickPanelRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** The header title: the link title, else the detected source name, else the domain. */
private fun headerTitle(state: QuickPanelState): String = when {
    state.title.isNotBlank() -> state.title
    state.source != MediaSource.UNKNOWN -> state.source.displayName
    state.domain.isNotBlank() -> state.domain
    else -> state.url
}

/**
 * The label of the single download row used when the content type is the file itself (an image or
 * a direct file). Those have no quality list, so one row stands for "download this".
 */
@Composable
private fun directDownloadTitle(state: QuickPanelState): String = stringResource(
    when (state.contentType) {
        QuickPanelContentType.IMAGE -> R.string.quick_panel_download_image
        QuickPanelContentType.DIRECT_FILE -> R.string.quick_panel_download_file
        else -> R.string.quick_panel_section_download
    }
)

/** `MP4 · 12.3 MB`, omitting whatever is unknown. */
private fun formatSubtitle(format: MediaFormat): String? {
    val size = format.fileSizeBytes?.takeIf { it > 0 }?.let(::formatBytes)
    return when {
        size == null -> null
        format.extension.isBlank() -> size
        else -> "${format.extension.uppercase(Locale.getDefault())} · $size"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun iconFor(action: QuickAction): ImageVector = when (action) {
    QuickAction.CLEAN_URL -> Icons.Outlined.LinkOff
    QuickAction.COPY_CLEAN_URL -> Icons.Outlined.ContentCopy
    QuickAction.SHARE_CLEAN_URL -> Icons.Outlined.Share
    QuickAction.SAVE -> Icons.Outlined.BookmarkAdd
    QuickAction.FOLDER -> Icons.Outlined.Folder
    QuickAction.TAGS -> Icons.Outlined.Tag
    QuickAction.NOTES -> Icons.Outlined.Notes
    QuickAction.DOWNLOAD -> Icons.Outlined.Download
    QuickAction.OPEN -> Icons.Outlined.OpenInNew
    QuickAction.SHARE -> Icons.Outlined.Share
    QuickAction.COPY -> Icons.Outlined.ContentCopy
}

@Composable
private fun titleFor(action: QuickAction): String = stringResource(
    when (action) {
        QuickAction.SAVE -> R.string.quick_panel_save_link
        QuickAction.FOLDER -> R.string.move_to_folder
        QuickAction.TAGS -> R.string.manage_tags
        QuickAction.NOTES -> R.string.set_note
        QuickAction.CLEAN_URL -> R.string.quick_panel_clean_url
        QuickAction.COPY_CLEAN_URL -> R.string.quick_panel_copy_clean_url
        QuickAction.SHARE_CLEAN_URL -> R.string.quick_panel_share_clean_url
        QuickAction.DOWNLOAD -> R.string.quick_panel_section_download
        QuickAction.OPEN -> R.string.open_link
        QuickAction.SHARE -> R.string.quick_panel_share
        QuickAction.COPY -> R.string.quick_panel_copy_url
    }
)

/** The cleaning rows preview the cleaned URL so the panel is self explaining. */
@Composable
private fun subtitleFor(action: QuickAction, state: QuickPanelState): String? = when (action) {
    QuickAction.CLEAN_URL,
    QuickAction.COPY_CLEAN_URL,
    QuickAction.SHARE_CLEAN_URL -> state.cleanedUrl
    else -> null
}
