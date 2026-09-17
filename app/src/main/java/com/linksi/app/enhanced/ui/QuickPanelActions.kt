package com.linksi.app.enhanced.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.linksi.app.R

/**
 * The self contained panel actions (specification sections 9.5 and 13).
 *
 * Everything here uses only Android framework APIs plus `androidx.core.net.toUri`, so the module
 * adds no dependency and needs no permission. Every function returns a boolean "did anything
 * happen" instead of throwing: a device without a browser or without a share target must degrade
 * to nothing rather than crash the sheet, and the caller may use `false` to show a message.
 */
object QuickPanelActions {

    /** Plain text MIME type used by the share sheet. */
    const val TEXT_MIME_TYPE = "text/plain"

    /** Copies [text] to the clipboard, returning false when the service is unavailable or blank. */
    fun copyToClipboard(context: Context, text: String, label: String = "url"): Boolean {
        if (text.isBlank()) return false
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (e: RuntimeException) {
            // Some OEM builds throw when the clipboard is locked; never let that reach the UI.
            false
        }
    }

    /**
     * Opens the system share sheet for [text] (specification 9.5: "Share cleaned URL").
     *
     * [title] is prefixed when it is not blank, matching the behaviour of the existing options
     * sheet. [chooserTitle] comes from the caller so this object stays free of resource lookups.
     */
    fun shareText(
        context: Context,
        text: String,
        title: String = "",
        chooserTitle: String? = null
    ): Boolean {
        if (text.isBlank()) return false
        val payload = if (title.isBlank()) text else "$title\n$text"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = TEXT_MIME_TYPE
            putExtra(Intent.EXTRA_TEXT, payload)
            if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return startChooser(context, send, chooserTitle)
    }

    /** Opens [url] in whatever app handles it. Returns false when nothing can. */
    fun openUrl(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val view = Intent(Intent.ACTION_VIEW, url.toUri())
        return try {
            context.startActivity(view)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun startChooser(context: Context, intent: Intent, chooserTitle: String?): Boolean {
        val chooser = if (chooserTitle.isNullOrBlank()) {
            Intent.createChooser(intent, null)
        } else {
            Intent.createChooser(intent, chooserTitle)
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }
}

/**
 * The integration point between [QuickActionPanel] and [QuickPanelActions].
 *
 * Every action this module can perform by itself is wired here - cleaning, copying, sharing and
 * opening - so a caller only has to supply what only the host knows:
 *
 *  - [onSave] / [onFolder] / [onTags] / [onNotes]: the host's own link management actions;
 *  - [onDownload]: the download engine, which belongs to another module. The panel is
 *    presentational for downloads and hands over the chosen format id, or an empty string when
 *    the link is itself the file (an image or a direct file, with no quality to choose);
 *  - [onCleanUrl]: what "Clean URL" should do, for example re-save the link with the cleaned
 *    address. Copying and sharing the cleaned URL need no host code at all.
 *
 * `onCopyCleanUrl` and `onShareCleanUrl` are only invoked after a successful clipboard or share
 * hand-off, so a device with no share target leaves the URL on screen instead of losing it.
 */
@Composable
fun rememberQuickPanelCallbacks(
    state: QuickPanelState,
    onSave: () -> Unit,
    onFolder: () -> Unit = {},
    onTags: () -> Unit = {},
    onNotes: () -> Unit = {},
    onCleanUrl: (String) -> Unit = {},
    onDownload: (String) -> Unit = {},
    onDismiss: () -> Unit = {}
): QuickPanelCallbacks {
    val context = LocalContext.current
    val shareVia = stringResource(R.string.quick_panel_share_via)

    return QuickPanelCallbacks(
        onCleanUrl = onCleanUrl,
        onCopyCleanUrl = { QuickPanelActions.copyToClipboard(context, state.cleanedUrl) },
        onShareCleanUrl = {
            QuickPanelActions.shareText(
                context = context,
                text = state.cleanedUrl,
                title = state.title,
                chooserTitle = shareVia
            )
        },
        onSave = onSave,
        onFolder = onFolder,
        onTags = onTags,
        onNotes = onNotes,
        onOpen = { QuickPanelActions.openUrl(context, state.url) },
        onShare = {
            QuickPanelActions.shareText(
                context = context,
                text = state.url,
                title = state.title,
                chooserTitle = shareVia
            )
        },
        onCopy = { QuickPanelActions.copyToClipboard(context, state.url) },
        onDownload = onDownload,
        onDismiss = onDismiss
    )
}
