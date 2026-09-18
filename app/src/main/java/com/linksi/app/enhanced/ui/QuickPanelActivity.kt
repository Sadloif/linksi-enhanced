package com.linksi.app.enhanced.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linksi.app.MainActivity
import com.linksi.app.R
import com.linksi.app.enhanced.detect.ClipboardUrlReader
import com.linksi.app.enhanced.download.rememberDownloadNotificationRequest
import com.linksi.app.enhanced.download.rememberDownloadNotificationsEnabled
import com.linksi.app.ui.theme.LinksTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The quick action panel the floating bubble opens (specification section 13).
 *
 * This activity is deliberately **thin**. It finds out which URL it is about, then hands the whole
 * body to the real [QuickActionPanel]; everything the panel does - cleaning, copying, sharing,
 * opening, saving, and downloading through [QuickPanelViewModel] - is wired once here and belongs
 * to the modules that own it.
 *
 * ### Where the URL comes from
 *
 *  - **The link options sheet** passes it in [DownloadNavigation.EXTRA_PANEL_URL]. Nothing is read
 *    from the clipboard, so the panel works for a link the user tapped regardless of what the
 *    clipboard happens to hold.
 *  - **The floating bubble** passes nothing, so the clipboard is read once, at the only moment
 *    Android permits it: the user tapped the bubble, the app is in the foreground, and this window
 *    holds input focus (specification section 11.1). The read happens at most once per panel.
 *
 * ### Failure isolation (specification section 26)
 *
 * Every platform call is guarded. A missing URL shows the existing "No valid URL detected."
 * message and a Close button. A download that fails - including `ENGINE_UNAVAILABLE` and
 * `UNSUPPORTED_SITE` - appears as a message inside the still-working panel, and can never affect
 * saving, sharing, searching or the panel's other actions.
 *
 * ### App lock
 *
 * The panel deliberately does **not** run the app lock. [SecurityManager.shouldLockApp] answers
 * "has the configured delay elapsed since the app was last paused", which is the right question for
 * `MainActivity` but the wrong one for a panel opened over another app a moment after a copy:
 * locking here would either fire a biometric prompt for every detected link or look like an
 * unrelated credential request on a screen the user does not recognise. The panel shows only the
 * URL it was handed or just read, never any stored link content, so the normal app lock is
 * untouched.
 */
@AndroidEntryPoint
class QuickPanelActivity : AppCompatActivity() {

    private val viewModel: QuickPanelViewModel by viewModels()

    private var panelUrl: String? = null
    private var clipboardReadDone = false
    private var clipboardReadListener: ((String?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        acceptIntent(intent)

        setContent { LinksTheme { QuickPanelHost() } }
    }

    /**
     * `singleTop` reuses this activity when another link or bubble opens the panel. Reset the URL
     * source for that new request; otherwise the old link remains visible and a second bubble tap
     * never reads the new clipboard value because [clipboardReadDone] is still true.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }

    private fun acceptIntent(intent: Intent?) {
        val explicit = intent?.getStringExtra(DownloadNavigation.EXTRA_PANEL_URL)
        if (!explicit.isNullOrBlank()) {
            panelUrl = explicit
            clipboardReadDone = true
            clipboardReadListener?.invoke(panelUrl)
            return
        }

        // A bubble launch carries no URL. Clear the previous panel immediately, then let the next
        // focus callback perform the one permitted foreground clipboard read. If this singleTop
        // instance is already focused, no new focus callback is guaranteed, so read immediately.
        panelUrl = null
        clipboardReadDone = false
        if (hasWindowFocus()) {
            clipboardReadDone = true
            panelUrl = ClipboardUrlReader.read(this).url
            clipboardReadListener?.invoke(panelUrl)
        } else {
            clipboardReadListener?.invoke(null)
        }
    }

    /**
     * Reads the clipboard once, on the first moment this window actually holds input focus.
     *
     * `onWindowFocusChanged` is the guard the specification asks for: this cannot run while the app
     * is in the background, and it runs at most once per panel. It is skipped completely when the
     * URL was passed in explicitly.
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
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val context = LocalContext.current

        // The first download is where `POST_NOTIFICATIONS` is asked for, and it has to be asked for
        // *here*: the request needs an activity result registry, which DownloadWorker - the thing
        // that actually downloads - does not have and must not need. The worker keeps tolerating the
        // permission being absent, so a denial only loses the notice, never the download. Nothing is
        // asked at launch, and nothing is asked while the user has the feature switched off
        // (specification section 33), which is what the stored preference decides.
        val downloadNotificationsEnabled by rememberDownloadNotificationsEnabled()
        val requestNotificationPermission = rememberDownloadNotificationRequest()

        // Compose-side fallback for the case where focus arrived before the first composition.
        // It still refuses to read unless the window is focused right now. The explicit extra is
        // never re-read here: `panelUrl` was already set from it in `onCreate`.
        LaunchedEffect(Unit) {
            clipboardReadListener = { url -> viewModel.showUrl(url) }
            if (!clipboardReadDone && hasWindowFocus()) {
                clipboardReadDone = true
                panelUrl = ClipboardUrlReader.read(this@QuickPanelActivity).url
            }
            viewModel.showUrl(panelUrl)
        }

        // "Open Downloads" leaves this panel and opens the real screen in the main app, rather than
        // reimplementing any of it here.
        LaunchedEffect(state.openDownloadsRequested) {
            if (state.openDownloadsRequested) {
                viewModel.consumeOpenDownloadsRequest()
                openDownloadsScreen(context)
                finish()
            }
        }

        QuickPanelContent(
            url = panelUrl,
            state = state,
            onSave = viewModel::save,
            onCleanUrl = viewModel::cleanUrl,
            onDownload = { formatId ->
                // Requested before the download is enqueued, and never awaited: the download is
                // independent of the answer, so the panel must not wait for the dialog.
                requestNotificationPermission(downloadNotificationsEnabled)
                viewModel.download(formatId)
            },
            onCancelDownload = viewModel::cancelDownload,
            onRetryDownload = viewModel::retryDownload,
            onDismissStatus = viewModel::dismissDownloadStatus,
            onOpenDownloads = viewModel::requestOpenDownloads,
            onDismiss = { finish() }
        )
    }

    private fun openDownloadsScreen(context: Context) {
        runCatching {
            startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(DownloadNavigation.EXTRA_OPEN_DOWNLOADS, true)
                }
            )
        }
    }

    companion object {

        /**
         * The intent that shows the panel for exactly [url], with no clipboard read.
         *
         * Used by the link options sheet, which already knows the link, so the feature is reachable
         * without the floating bubble and without the accessibility service.
         */
        fun intentFor(context: Context, url: String): Intent =
            Intent(context, QuickPanelActivity::class.java).apply {
                // The panel is launched from a bottom sheet inside the task; these flags keep it in
                // front of it instead of stacking a second copy.
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(DownloadNavigation.EXTRA_PANEL_URL, url)
            }
    }
}

/**
 * The panel body.
 *
 * Kept as a standalone composable so it can be driven directly by an instrumented test with a
 * fabricated [QuickPanelUiState], without an activity round trip.
 */
@Composable
fun QuickPanelContent(
    url: String?,
    state: QuickPanelUiState,
    onSave: () -> Unit,
    onCleanUrl: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onRetryDownload: () -> Unit,
    onDismissStatus: () -> Unit,
    onOpenDownloads: () -> Unit,
    onDismiss: () -> Unit
) {
    if (url.isNullOrBlank()) {
        QuickPanelNoUrlContent(onDismiss = onDismiss)
        return
    }

    val callbacks = rememberQuickPanelCallbacks(
        state = state.panel,
        onSave = onSave,
        onCleanUrl = onCleanUrl,
        onDownload = onDownload,
        onDismiss = onDismiss
    )

    QuickActionPanel(
        state = state.panel,
        callbacks = callbacks,
        isSaving = state.isSaving,
        saveResult = state.saveResult,
        downloadStatus = {
            Column(modifier = Modifier.fillMaxWidth()) {
                QuickPanelDownloadStatusCard(
                    status = state.download,
                    onCancel = onCancelDownload,
                    onRetry = onRetryDownload,
                    onDismissStatus = onDismissStatus,
                    onOpenDownloads = onOpenDownloads
                )

                // The reason a download (or a check) did not work. Rendered below the status card
                // so it never replaces any of the panel's working actions.
                state.message?.let { messageRes ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Text(
                            text = stringResource(messageRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    )
}

/** The "nothing to act on" panel: the same message the thin panel always showed, plus Close. */
@Composable
private fun QuickPanelNoUrlContent(onDismiss: () -> Unit) {
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.enhanced_quick_panel_no_url),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.enhanced_quick_panel_close))
            }
        }
    }
}

/**
 * The browser/app hand-off belongs to [QuickPanelActions.openUrl], which is called by
 * [rememberQuickPanelCallbacks]. This file therefore launches only two things itself: the main app
 * when the user asks for the Downloads screen, and nothing else.
 */
