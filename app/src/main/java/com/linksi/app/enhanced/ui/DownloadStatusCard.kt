package com.linksi.app.enhanced.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linksi.app.R
import com.linksi.app.enhanced.download.DownloadState

/**
 * The download area of the quick action panel (specification sections 20 and 26).
 *
 * Rendered by [QuickPanelActivity] and handed to [QuickActionPanel] through its `downloadStatus`
 * slot, so the panel itself keeps no knowledge of the engine. The three rules it enforces:
 *
 *  - **progress is never invented.** A determinate bar and a percentage appear only when
 *    [PanelDownloadStatus.progressFraction] is non-null, which happens only when the server
 *    reported a total size; otherwise the bar is indeterminate and no number is shown.
 *  - **cancel is always reachable** while something runs.
 *  - **a failure is a message, not a dead end.** The reason comes from
 *    [DownloadUiText.errorRes], and the panel's other actions stay exactly where they were.
 */
@Composable
fun QuickPanelDownloadStatusCard(
    status: PanelDownloadStatus?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismissStatus: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = status != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        // `status` is captured by the animation; the null case is never composed for long enough to
        // matter, and the fallback keeps the composable total.
        val current = status ?: return@AnimatedVisibility
        DownloadStatusBody(
            status = current,
            onCancel = onCancel,
            onRetry = onRetry,
            onDismissStatus = onDismissStatus,
            onOpenDownloads = onOpenDownloads
        )
    }
}

@Composable
private fun DownloadStatusBody(
    status: PanelDownloadStatus,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismissStatus: () -> Unit,
    onOpenDownloads: () -> Unit
) {
    val failed = status.phase is DownloadState.Failed
    val completed = status.isCompleted

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = when {
            failed -> MaterialTheme.colorScheme.errorContainer
            completed -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status.isActive) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (completed) {
                            Icons.Outlined.CheckCircle
                        } else {
                            Icons.Outlined.ErrorOutline
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = when {
                            failed -> MaterialTheme.colorScheme.error
                            completed -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(Modifier.size(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = labelText(status.label),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (failed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    status.progressLine?.let { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Only a real, server-reported total produces a determinate bar. `null` means
            // indeterminate: a spinner-like bar with no number, never a fabricated percentage.
            if (status.isActive) {
                Spacer(Modifier.height(8.dp))
                val fraction = status.progressFraction
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (status.isActive) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.download_panel_cancel))
                    }
                }
                if (status.isRetryable) {
                    TextButton(onClick = onDismissStatus) {
                        Text(stringResource(R.string.download_panel_done))
                    }
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.download_panel_retry))
                    }
                }
                if (completed) {
                    TextButton(onClick = onOpenDownloads) {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.download_panel_open_downloads))
                    }
                    TextButton(onClick = onDismissStatus) {
                        Text(stringResource(R.string.download_panel_done))
                    }
                }
            }
        }
    }
}

/** Resolves a [DownloadUiText.Label] against the current resources. */
@Composable
internal fun labelText(label: DownloadUiText.Label): String =
    if (label.argument == null) {
        stringResource(label.res)
    } else {
        stringResource(label.res, label.argument)
    }
