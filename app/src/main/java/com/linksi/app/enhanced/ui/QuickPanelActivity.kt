package com.linksi.app.enhanced.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.linksi.app.R
import com.linksi.app.data.repository.LinkRepository
import com.linksi.app.domain.model.Link
import com.linksi.app.enhanced.detect.ClipboardUrlReader
import com.linksi.app.ui.theme.LinksTheme
import com.linksi.app.utils.SecurityManager
import com.linksi.app.utils.UrlCleaner
import com.linksi.app.utils.extractDomain
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The compact quick action panel the floating bubble opens (specification section 13).
 *
 * This activity is deliberately **thin and self-contained**. It reads the clipboard once, at the
 * only moment Android permits it - the user tapped the bubble, so the app is in the foreground and
 * this window holds input focus (specification section 11.1) - and offers the small set of actions
 * that need no other module: Save, Open, Share, Copy, Copy clean URL, Cancel.
 *
 * A richer reusable panel is being built elsewhere and will replace the body of [QuickPanelContent].
 * Keeping this activity to "read, clean, act" means that swap is a UI-only change.
 *
 * Failure isolation: every platform call is guarded, and a missing URL simply shows the "No valid
 * URL detected." message. Nothing here can affect normal saving, sharing or browsing.
 *
 * ### App lock
 *
 * The panel deliberately does **not** run the app lock. [SecurityManager.shouldLockApp] answers
 * "has the configured delay elapsed since the app was last paused", which is the right question for
 * `MainActivity` but the wrong one for a panel opened over another app a moment after a copy:
 * locking here would either fire a biometric prompt for every detected link or look like an
 * unrelated credential request on a screen the user does not recognise. The panel shows only the
 * URL it just read, never any stored link content, so the normal app lock is untouched. Wiring the
 * shared lock flow in is left to the reusable panel that replaces [QuickPanelContent].
 */
@AndroidEntryPoint
class QuickPanelActivity : AppCompatActivity() {

    /** Injected so the panel saves through exactly the same path the rest of the app uses. */
    @Inject
    lateinit var linkRepository: LinkRepository

    private var panelUrl: String? = null
    private var clipboardReadDone = false
    private var clipboardReadListener: ((String?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent { LinksTheme { QuickPanelHost() } }
    }

    /**
     * Reads the clipboard once, on the first moment this window actually holds input focus.
     *
     * `onWindowFocusChanged` is the guard the specification asks for: this cannot run while the app
     * is in the background, and it runs at most once per panel.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || clipboardReadDone) return
        clipboardReadDone = true
        panelUrl = ClipboardUrlReader.read(this).url
        clipboardReadListener?.invoke(panelUrl)
    }

    @Composable
    private fun QuickPanelHost() {
        var url by remember { mutableStateOf(panelUrl) }

        // Compose-side fallback for the case where focus arrived before the first composition.
        // It still refuses to read unless the window is focused right now.
        LaunchedEffect(Unit) {
            clipboardReadListener = { url = it }
            if (!clipboardReadDone && hasWindowFocus()) {
                clipboardReadDone = true
                panelUrl = ClipboardUrlReader.read(this@QuickPanelActivity).url
                url = panelUrl
            }
        }

        QuickPanelContent(
            url = url,
            onSave = { link -> saveLink(link) },
            onOpen = { target -> openUrl(target) },
            onShare = { target -> shareUrl(target) },
            onCopy = { text -> copyToClipboard(text) },
            onDismiss = { finish() }
        )
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private suspend fun saveLink(link: Link): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (linkRepository.isUrlAlreadySaved(link.url)) return@runCatching false
            linkRepository.insertLink(link)
            true
        }.getOrDefault(false)
    }

    private fun openUrl(url: String) {
        val launched = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        }.getOrDefault(false)
        if (!launched) toast(getString(R.string.enhanced_quick_panel_no_app_for_link))
        finish()
    }

    private fun shareUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(
                Intent.createChooser(intent, getString(R.string.enhanced_quick_panel_action_share))
            )
        }
        finish()
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@runCatching
            manager.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.enhanced_quick_panel_title), text)
            )
        }
    }

    private fun toast(message: String) {
        runCatching {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * The panel body.
 *
 * Kept as a standalone composable so the richer reusable panel from the other module can replace it
 * without touching the clipboard, lifecycle or repository wiring above.
 */
@Composable
fun QuickPanelContent(
    url: String?,
    onSave: suspend (Link) -> Boolean,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var saveState by remember { mutableStateOf(SaveState.IDLE) }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.enhanced_quick_panel_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.enhanced_quick_panel_action_cancel))
                }
            }

            if (url == null) {
                Text(
                    stringResource(R.string.enhanced_quick_panel_no_url),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.enhanced_quick_panel_close))
                }
                return@Column
            }

            val cleaned = remember(url) { UrlCleaner.cleanOrSelf(url) }
            val differs = cleaned != url
            val domain = remember(url) { extractDomain(url) }
            val heading = domain.ifBlank { url }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    heading,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(R.string.enhanced_quick_panel_clean_url_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    cleaned,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (differs) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.enhanced_quick_panel_original_url_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = {
                    if (saveState == SaveState.SAVING || saveState == SaveState.SAVED) return@Button
                    saveState = SaveState.SAVING
                    scope.launch {
                        val saved = onSave(
                            Link(
                                url = cleaned,
                                title = domain,
                                domain = domain,
                                faviconUrl = "https://www.google.com/s2/favicons?domain=$domain&sz=64"
                            )
                        )
                        saveState = if (saved) SaveState.SAVED else SaveState.ALREADY_SAVED
                    }
                },
                enabled = saveState != SaveState.SAVING && saveState != SaveState.SAVED,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (saveState) {
                    SaveState.SAVING -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    SaveState.SAVED -> {
                        Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.enhanced_quick_panel_saved))
                    }
                    SaveState.ALREADY_SAVED ->
                        Text(stringResource(R.string.enhanced_quick_panel_already_saved))
                    SaveState.IDLE ->
                        Text(stringResource(R.string.enhanced_quick_panel_action_save))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { onOpen(cleaned) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.enhanced_quick_panel_action_open))
                }
                OutlinedButton(onClick = { onShare(cleaned) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.enhanced_quick_panel_action_share))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { onCopy(url) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.enhanced_quick_panel_action_copy))
                }
                OutlinedButton(onClick = { onCopy(cleaned) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.enhanced_quick_panel_action_copy_clean),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Save button state for the panel. */
enum class SaveState { IDLE, SAVING, SAVED, ALREADY_SAVED }
