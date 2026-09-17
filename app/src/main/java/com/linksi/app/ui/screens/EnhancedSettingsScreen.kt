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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.linksi.app.R
import com.linksi.app.ui.components.ExpressiveSettingsCard
import com.linksi.app.ui.components.IconContainer

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
                    onCheckedChange = onDownloadNotificationsToggled
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
private fun EnhancedToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
