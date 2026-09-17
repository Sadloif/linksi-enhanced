package com.linksi.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.linksi.app.R
import com.linksi.app.enhanced.download.rememberDownloadNotificationRequest
import com.linksi.app.enhanced.media.ytdlp.YtDlpRefreshResult
import com.linksi.app.enhanced.ui.DownloadUiEntryPoint
import com.linksi.app.ui.components.ExpressiveSettingsCard
import com.linksi.app.ui.components.IconContainer
import kotlinx.coroutines.launch

/**
 * Settings for every optional enhanced module (specification sections 33 and 34).
 *
 * The screen is deliberately built around one promise: **none of this is required**. Each toggle is
 * off by default, each one explains what it does, and the permission-dependent ones send the user to
 * the relevant Android settings page rather than pretending the permission was granted. Turning any
 * of them off leaves normal Linksi behaviour exactly as it was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedSettingsScreen(
    smartLinkDetection: Boolean,
    floatingBubble: Boolean,
    accessibilityAssistance: Boolean,
    downloadNotifications: Boolean,
    serverFallbackEnabled: Boolean,
    serverFallbackUrl: String,
    serverFallbackApiKey: String,
    onSmartLinkDetectionToggled: (Boolean) -> Unit,
    onFloatingBubbleToggled: (Boolean) -> Unit,
    onAccessibilityToggled: (Boolean) -> Unit,
    onDownloadNotificationsToggled: (Boolean) -> Unit,
    onServerFallbackEnabledToggled: (Boolean) -> Unit,
    onServerFallbackUrlChanged: (String) -> Unit,
    onServerFallbackApiKeyChanged: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Reached through the existing download entry point rather than a new view-model: this screen
    // only needs to read a version and ask for a refresh, and the entry point already exposes the
    // engine's machinery to any Compose screen.
    val entryPoint = remember(context) { DownloadUiEntryPoint.from(context) }

    // `POST_NOTIFICATIONS` is asked for the moment the user enables *Download notifications* - never
    // at launch - because the feature is optional (specification section 33). Passing the switch's
    // new value in avoids racing the DataStore write the view-model is still performing.
    val requestNotificationPermission = rememberDownloadNotificationRequest()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, stringResource(id = R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResource(id = R.string.enhanced_features_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(id = R.string.enhanced_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            // ── Detection and overlay ───────────────────────────────────────────
            ExpressiveSettingsCard {
                EnhancedToggleRow(
                    icon = Icons.Outlined.ContentPaste,
                    title = stringResource(id = R.string.enhanced_smart_detection),
                    subtitle = stringResource(id = R.string.enhanced_smart_detection_subtitle),
                    checked = smartLinkDetection,
                    onCheckedChange = onSmartLinkDetectionToggled
                )
                EnhancedDivider()
                EnhancedToggleRow(
                    icon = Icons.Outlined.BubbleChart,
                    title = stringResource(id = R.string.enhanced_floating_bubble),
                    subtitle = stringResource(id = R.string.enhanced_floating_bubble_subtitle),
                    checked = floatingBubble,
                    onCheckedChange = { enabled ->
                        onFloatingBubbleToggled(enabled)
                        // The bubble needs to draw over other apps. Send the user to the switch
                        // instead of silently failing later; the feature degrades if it is denied.
                        if (enabled && !canDrawOverlays(context)) openOverlaySettings(context)
                    }
                )
                EnhancedDivider()
                EnhancedToggleRow(
                    icon = Icons.Outlined.Accessibility,
                    title = stringResource(id = R.string.enhanced_accessibility),
                    subtitle = stringResource(id = R.string.enhanced_accessibility_subtitle),
                    checked = accessibilityAssistance,
                    onCheckedChange = { enabled ->
                        onAccessibilityToggled(enabled)
                        if (enabled) openAccessibilitySettings(context)
                    }
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(id = R.string.enhanced_accessibility_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(20.dp))

            // ── Downloads ───────────────────────────────────────────────────────
            ExpressiveSettingsCard {
                EnhancedToggleRow(
                    icon = Icons.Outlined.Download,
                    title = stringResource(id = R.string.enhanced_download_notifications),
                    subtitle = stringResource(id = R.string.enhanced_download_notifications_subtitle),
                    checked = downloadNotifications,
                    onCheckedChange = { enabled ->
                        onDownloadNotificationsToggled(enabled)
                        // Switching the feature off needs no request, and the helper refuses an
                        // already-granted permission or a second dialog in the same visit.
                        requestNotificationPermission(enabled)
                    }
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Site engine ─────────────────────────────────────────────────────
            // The engine is what reads Instagram, Facebook, TikTok, Pinterest and Reddit, and the
            // copy bundled with the app goes stale as those sites change - measurably so: the pinned
            // 2024.09.27 build could not read a single one of the owner's Facebook links, and the
            // current release reads five of nine. So the version is shown, and refreshing is a
            // deliberate action here rather than something silent.
            ExpressiveSettingsCard {
                val scope = rememberCoroutineScope()
                var engineState by remember { mutableStateOf(YtDlpEngineState()) }
                val refreshLabel = stringResource(id = R.string.enhanced_engine_refresh)

                LaunchedEffect(Unit) {
                    engineState = engineState.copy(version = runCatching { entryPoint.ytDlpRuntime().engineVersion() }.getOrNull())
                }

                ListItem(
                    headlineContent = {
                        Text(stringResource(id = R.string.enhanced_engine_title), fontWeight = FontWeight.SemiBold)
                    },
                    supportingContent = {
                        Text(
                            text = when {
                                engineState.busy -> stringResource(id = R.string.enhanced_engine_checking)
                                engineState.message != null -> engineState.message!!
                                engineState.version != null ->
                                    stringResource(id = R.string.enhanced_engine_version, engineState.version!!)
                                else -> stringResource(id = R.string.enhanced_engine_unknown)
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    leadingContent = { IconContainer(Icons.Outlined.Download) },
                    trailingContent = {
                        TextButton(
                            enabled = !engineState.busy,
                            onClick = {
                                engineState = engineState.copy(busy = true, message = null)
                                scope.launch {
                                    val result = runCatching { entryPoint.ytDlpRuntime().refreshEngine() }
                                        .getOrElse { YtDlpRefreshResult.Failed(it.message ?: "unknown error") }
                                    engineState = YtDlpEngineState(
                                        busy = false,
                                        version = runCatching { entryPoint.ytDlpRuntime().engineVersion() }.getOrNull(),
                                        message = engineResultMessage(result)
                                    )
                                }
                            }
                        ) { Text(refreshLabel) }
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Optional private server ─────────────────────────────────────────
            ExpressiveSettingsCard {
                EnhancedToggleRow(
                    icon = Icons.Outlined.Dns,
                    title = stringResource(id = R.string.enhanced_server_fallback),
                    subtitle = stringResource(id = R.string.enhanced_server_fallback_subtitle),
                    checked = serverFallbackEnabled,
                    onCheckedChange = onServerFallbackEnabledToggled
                )

                if (serverFallbackEnabled) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OutlinedTextField(
                            value = serverFallbackUrl,
                            onValueChange = onServerFallbackUrlChanged,
                            label = { Text(stringResource(id = R.string.enhanced_server_fallback_url)) },
                            placeholder = { Text(stringResource(id = R.string.enhanced_server_fallback_url_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = serverFallbackApiKey,
                            onValueChange = onServerFallbackApiKeyChanged,
                            label = { Text(stringResource(id = R.string.enhanced_server_fallback_key)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(id = R.string.enhanced_server_fallback_disclosure),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (serverFallbackUrl.isNotBlank() &&
                            !serverFallbackUrl.trim().lowercase().startsWith("https://")
                        ) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(id = R.string.enhanced_server_fallback_https_required),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnhancedToggleRow(    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = {
            Text(title, fontWeight = FontWeight.SemiBold)
        },
        supportingContent = {
            Text(subtitle, style = MaterialTheme.typography.labelMedium)
        },
        leadingContent = { IconContainer(icon) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    )
}

@Composable
private fun EnhancedDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

private fun canDrawOverlays(context: Context): Boolean =
    runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

/** What the site-engine row is showing while a refresh is in flight, and what it said afterwards. */
private data class YtDlpEngineState(
    val busy: Boolean = false,
    val version: String? = null,
    val message: String? = null
)

/**
 * The sentence the engine row shows after a refresh (pure, so the wording is unit testable).
 *
 * A failure is phrased as "still on", not as an error, because that is what happened: the updater
 * only ever replaces the engine with one it has verified, so a refused update leaves a working
 * download path exactly as it was.
 */
internal fun engineResultMessage(result: YtDlpRefreshResult): String = when (result) {
    is YtDlpRefreshResult.Updated -> "Updated to ${result.version}"
    is YtDlpRefreshResult.AlreadyCurrent -> "Already up to date (${result.version})"
    is YtDlpRefreshResult.Skipped -> "Not checked yet: ${result.reason}"
    is YtDlpRefreshResult.Failed -> "Could not update, still on the installed version: ${result.reason}"
}

private fun openOverlaySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
}

private fun openAccessibilitySettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
}
