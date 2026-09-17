package com.linksi.app.enhanced.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linksi.app.R
import com.linksi.app.enhanced.download.DownloadState

/**
 * The Downloads screen (specification section 22), reached from Settings.
 *
 * It is an on-demand screen in every sense: it is only composed when the user opens it, its
 * view-model only starts observing the engine then, and nothing it does can affect saving,
 * sharing or searching. A download that fails shows why, in place, and the rest of the list keeps
 * working.
 *
 * All numbers come from [DownloadFormatting][com.linksi.app.enhanced.download.DownloadFormatting]
 * through [DownloadRow]: a size that is not known reads "Size unknown", and a download with no
 * server-reported total shows an indeterminate bar rather than an invented percentage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.downloads_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, stringResource(id = R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                state.isUnavailable -> DownloadsMessage(stringResource(R.string.downloads_empty))
                state.isLoading && state.isEmpty -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )

                state.isEmpty -> DownloadsMessage(
                    title = stringResource(R.string.downloads_empty),
                    subtitle = stringResource(R.string.downloads_empty_hint)
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.activeRows.isNotEmpty()) {
                        item { DownloadsSectionHeader(stringResource(R.string.downloads_section_active)) }
                        items(state.activeRows, key = { it.id }) { row ->
                            DownloadRowCard(
                                row = row,
                                onCancel = { viewModel.cancel(row.id) },
                                onRetry = { viewModel.retry(row.id) },
                                onDismiss = { viewModel.dismiss(row.id) }
                            )
                        }
                    }

                    if (state.finishedRows.isNotEmpty()) {
                        item { DownloadsSectionHeader(stringResource(R.string.downloads_section_finished)) }
                        items(state.finishedRows, key = { it.id }) { row ->
                            DownloadRowCard(
                                row = row,
                                onCancel = { viewModel.cancel(row.id) },
                                onRetry = { viewModel.retry(row.id) },
                                onDismiss = { viewModel.dismiss(row.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsSectionHeader(title: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun DownloadsMessage(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Download,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One download: what it is, how far along, and the one action that fits its state. */
@Composable
private fun DownloadRowCard(
    row: DownloadRow,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val failed = row.state is DownloadState.Failed

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (failed) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.isActive) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (failed) {
                            Icons.Outlined.ErrorOutline
                        } else {
                            Icons.Outlined.Download
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(Modifier.size(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (failed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        text = labelText(row.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when (row.action) {
                    DownloadRowAction.CANCEL -> IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Outlined.Delete,
                            stringResource(R.string.downloads_cancel_desc),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DownloadRowAction.RETRY -> IconButton(onClick = onRetry) {
                        Icon(
                            Icons.Outlined.Refresh,
                            stringResource(R.string.downloads_retry_desc),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DownloadRowAction.DISMISS -> IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Outlined.Delete,
                            stringResource(R.string.downloads_dismiss_desc),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DownloadRowAction.NONE -> Unit
                }
            }

            if (row.isActive) {
                Spacer(Modifier.height(10.dp))
                val fraction = row.progressFraction
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                row.progressLine?.let { line ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = row.sizeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
