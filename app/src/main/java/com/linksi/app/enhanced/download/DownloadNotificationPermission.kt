package com.linksi.app.enhanced.download

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.linksi.app.enhanced.EnhancedFeatureDefaults
import com.linksi.app.enhanced.EnhancedPreferenceKeys
import com.linksi.app.utils.dataStore
import kotlinx.coroutines.flow.first

/**
 * The runtime `POST_NOTIFICATIONS` request for the optional download notifications (specification
 * sections 23, 33 and 56).
 *
 * The permission is declared in the manifest but, from Android 13 (API 33), only the user can grant
 * it. Nothing else in the app asks for it, and [DownloadNotifications] *deliberately* treats a
 * missing permission as "drop the notification and keep the download" rather than as an error, so
 * without this class the feature fails silently: progress and completion notices simply never
 * appear (open item 6 of the handover, upstream's gap).
 *
 * ### When it is asked for
 *
 * Specification section 33's feature-based permission rule shapes the whole design: an optional
 * module must not do startup work, and a permission is asked for **in context**, at the moment the
 * user turns the matching feature on. There are therefore exactly two triggers, and neither of them
 * is app launch:
 *
 *  - the Enhanced Features settings switch, the instant the user enables *Download notifications*
 *    ([com.linksi.app.ui.screens.EnhancedSettingsScreen]);
 *  - the quick panel, immediately before the first download it enqueues
 *    ([com.linksi.app.enhanced.ui.QuickPanelActivity]).
 *
 * Both paths consult [shouldRequest], so both are idempotent: an already-granted permission and a
 * dialog already shown in this screen visit are each a refusal to ask again, and a switch that is
 * off never triggers a request at all. A retry from the Downloads screen is deliberately *not* a
 * trigger - re-prompting a user who has already answered is nagging, not context.
 *
 * ### Why the request lives in the UI and not in the worker
 *
 * The download itself runs in [DownloadWorker], and a worker has no activity result registry, so it
 * cannot request anything even in principle. The request therefore happens where the download is
 * **enqueued from the UI**, and the worker tolerates the permission being absent exactly as it
 * always did: it posts best effort and can never fail a download over a notification. The answer to
 * the dialog is ignored for the same reason - a denial must not change the download's behaviour.
 *
 * ### Android 12 and older
 *
 * `POST_NOTIFICATIONS` does not exist below API 33, and `checkSelfPermission` for a permission that
 * was never declared in that release is not a meaningful question. [isGranted] therefore answers
 * `true` without asking the platform at all, which is what keeps [shouldRequest] silent on those
 * releases and keeps posting unrestricted, as it always was.
 */
object DownloadNotificationPermission {

    /**
     * The "should the dialog be shown now?" decision, kept free of Android types so it is unit
     * tested on the plain JVM. Every input is a fact the caller has already established.
     *
     * @param sdkInt the device API level. The permission only exists from
     *   [Build.VERSION_CODES.TIRAMISU]; below that there is nothing to ask for.
     * @param enabled whether the user has the optional download-notifications feature switched on.
     *   A permission belonging to a feature that is off must never be requested (section 33).
     * @param granted whether the permission is already held.
     * @param alreadyAsked whether the dialog has already been shown during this screen visit, so a
     *   second tap of the same switch cannot stack a second dialog.
     */
    fun shouldRequest(
        sdkInt: Int,
        enabled: Boolean,
        granted: Boolean,
        alreadyAsked: Boolean
    ): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU && enabled && !granted && !alreadyAsked

    /** True when the permission is held - and always true below API 33, where it does not exist. */
    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The persisted *Download notifications* switch, defaulted to the module default rather than to
     * a literal so this decision and the settings screen cannot drift apart.
     *
     * An unreadable DataStore falls back to that default too: a broken preference read must not be
     * able to turn a context-sensitive prompt into permanent silence.
     */
    suspend fun isEnabled(context: Context): Boolean =
        runCatching {
            context.dataStore.data.first()[
                booleanPreferencesKey(EnhancedPreferenceKeys.DOWNLOAD_NOTIFICATIONS)
            ]
        }.getOrNull() ?: EnhancedFeatureDefaults.DOWNLOAD_NOTIFICATIONS
}

/**
 * A one-shot `POST_NOTIFICATIONS` request for the two UI triggers above.
 *
 * The returned lambda takes the *current* value of the feature switch instead of reading it, which
 * is what makes both callers honest: the settings screen has just flipped the switch and knows the
 * new value, and the quick panel reads the stored value once. Neither has to race the DataStore
 * write that is still in flight.
 *
 * The dialog's answer is deliberately discarded. The permission's only consumer is
 * [DownloadNotifications], which re-reads it on every post and drops the post rather than failing a
 * download, so there is nothing to report and nothing to change: a denial leaves the downloader
 * working exactly as before.
 *
 * The once-per-visit guard is intentionally in-memory rather than persisted. The platform already
 * limits the dialog itself (after two denials it stops showing it), so this only has to cover the
 * double tap; persisting a "we asked" flag would risk never asking again after a reinstall or a
 * settings reset, which is the opposite of the section 56 granted/denied/revoked matrix.
 */
@Composable
fun rememberDownloadNotificationRequest(): (enabled: Boolean) -> Unit {
    val context = LocalContext.current

    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Ignored on purpose: see the note above.
    }

    return { enabled ->
        val shouldAsk = DownloadNotificationPermission.shouldRequest(
            sdkInt = Build.VERSION.SDK_INT,
            enabled = enabled,
            granted = DownloadNotificationPermission.isGranted(context),
            alreadyAsked = asked
        )

        // The second test is not redundant. The permission *name* is a constant of API 33, so the
        // platform level has to be visible right next to the `launch` call for lint's flow-sensitive
        // API check; `shouldRequest` compiles the same rule but hides it behind a function call.
        if (shouldAsk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * The persisted *Download notifications* switch as Compose state, so the quick panel can honour the
 * user's choice without the download module reaching into the settings view-model.
 *
 * The first frame uses the module default, which is safe: a download can only start from a tap,
 * long after the read has landed.
 */
@Composable
fun rememberDownloadNotificationsEnabled(): State<Boolean> {
    val context = LocalContext.current
    return produceState(
        initialValue = EnhancedFeatureDefaults.DOWNLOAD_NOTIFICATIONS,
        context
    ) {
        value = DownloadNotificationPermission.isEnabled(context)
    }
}
