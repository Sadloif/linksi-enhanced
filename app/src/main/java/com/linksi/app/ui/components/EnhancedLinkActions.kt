package com.linksi.app.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linksi.app.enhanced.ui.DownloadNavigation
import com.linksi.app.utils.UrlCleaner

/**
 * The enhanced actions offered wherever a link is being added.
 *
 * ## Why this is shared rather than written twice
 *
 * The owner reported two versions of the same complaint: the enhanced features were invisible "when I
 * add link", and again "if I share to linksi". Both screens add a link, so both deserve the same
 * actions, and a single component is the only way to keep them honest with each other - the previous
 * arrangement put Enhanced features behind Settings, which is not where anyone looks while adding a
 * link.
 *
 * ## What it offers, and why these three
 *
 * - **Download** opens the quick action panel for the URL, which is where the real extractor runs and
 *   the formats appear. It does not download anything by itself.
 * - **Clean URL** rewrites the URL in place using the same [UrlCleaner] the save path uses, and says
 *   what was removed, so the effect is visible rather than implied.
 * - **Enhanced settings** is the route to the switches, for the cases where something is off.
 *
 * The component is deliberately stateless: it reports what the user chose and lets the host screen
 * decide what that means, because the add sheet edits a field while the share sheet is about to save.
 */
@Composable
fun EnhancedLinkActions(
    url: String,
    onCleanedUrl: (String) -> Unit,
    onOpenEnhancedSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasUrl = url.isNotBlank()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Enhanced actions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Download the media, or strip tracking from the link before saving it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    enabled = hasUrl,
                    onClick = { openQuickPanel(context, url) }
                ) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Download")
                }

                TextButton(
                    enabled = hasUrl,
                    onClick = { onCleanedUrl(UrlCleaner.cleanOrSelf(url.trim())) }
                ) {
                    Icon(Icons.Outlined.CleaningServices, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clean URL")
                }

                TextButton(onClick = onOpenEnhancedSettings) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Settings")
                }
            }
        }
    }
}

/**
 * Starts the quick action panel for [url] as a new task.
 *
 * The panel is a separate activity rather than a sheet inside the caller, because it must take window
 * focus to read the clipboard on the bubble path, and reusing the one activity keeps that behaviour
 * identical however the panel was opened.
 */
internal fun openQuickPanel(context: Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(context, com.linksi.app.enhanced.ui.QuickPanelActivity::class.java)
                .putExtra(DownloadNavigation.EXTRA_PANEL_URL, url.trim())
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
