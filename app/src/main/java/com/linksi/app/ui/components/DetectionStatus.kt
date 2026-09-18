package com.linksi.app.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linksi.app.enhanced.detect.CopyObservation
import com.linksi.app.enhanced.detect.CopyObservationLog
import com.linksi.app.enhanced.detect.CopyRejection
import com.linksi.app.enhanced.detect.SmartDetectionState
import com.linksi.app.enhanced.detect.SmartLinkDetector
import com.linksi.app.enhanced.service.LinksiAccessibilityService
import kotlinx.coroutines.delay

/**
 * Everything needed to answer "why is nothing happening?" for the detection feature.
 *
 * ## Why this exists
 *
 * A user reported that link-copy detection and the floating bubble did nothing on their device even
 * with every permission they could find granted. There was **no way to investigate it**: the
 * accessibility service intentionally contains no `Log` call, by a documented privacy commitment, and
 * the screen showed nothing but three switches. So the failure was silent by construction.
 *
 * This row reads the platform's real state - is the service actually bound, is the overlay actually
 * permitted, are notifications permitted - and combines it with the detector's own last decision, so
 * the answer is on the screen rather than in a log the app refuses to write.
 *
 * ## What it deliberately does not do
 *
 * It does not read the clipboard, does not report *what* was copied, and does not touch the service's
 * no-logging promise. It reports only whether each gate is open, and when a copy was last seen -
 * never any content.
 */
private data class DetectionDiagnostics(
    /** True when the OS reports our accessibility service among the enabled ones. */
    val accessibilityServiceEnabled: Boolean,
    /** True when the overlay permission is granted. */
    val overlayGranted: Boolean,
    /** True when POST_NOTIFICATIONS is granted; the bubble's foreground service needs it on 13+. */
    val notificationsGranted: Boolean,
    /** The smart-detection switch. */
    val smartDetectionOn: Boolean,
    /** The floating-bubble switch. */
    val bubbleOn: Boolean,
    /** The accessibility-assistance switch. */
    val accessibilityAssistanceOn: Boolean,
    /** Wall-clock time of the last copy the service observed, or null for none. */
    val lastCopyAt: Long?,
    /** The detector's most recent decision, if it has made one this session. */
    val state: SmartDetectionState?
)

/** One labelled line: a tick or a cross, then what it means. */
@Composable
private fun DiagnosticRow(label: String, ok: Boolean, hint: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = if (ok) Color(0xFF22C55E) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The status block. Renders its own heading and explanation; the caller places it in a card.
 *
 * [detector] is nullable so a caller that cannot reach the graph still gets the platform half of the
 * answer rather than nothing.
 */
@Composable
fun DetectionStatusBlock(
    detector: SmartLinkDetector?,
    smartDetectionOn: Boolean,
    bubbleOn: Boolean,
    accessibilityAssistanceOn: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    val context = LocalContext.current

    // Re-read the platform state when the screen appears. It is deliberately not polled: a user
    // who changes a system setting comes back, and this re-reads on return.
    var diagnostics by remember { mutableStateOf(readDetectionDiagnostics(context, smartDetectionOn, bubbleOn, accessibilityAssistanceOn)) }

    LaunchedEffect(Unit) {
        while (true) {
            diagnostics = readDetectionDiagnostics(context, smartDetectionOn, bubbleOn, accessibilityAssistanceOn)
            delay(2_000)
        }
    }

    // Live detector state, which is what distinguishes "declined" from "never saw a copy".
    LaunchedEffect(detector) {
        detector?.state?.collect { state ->
            diagnostics = diagnostics.copy(state = state)
        }
    }

    // The last-observed copy time is persisted, so it survives a restart and tells the user that
    // detection fired at all even if the bubble was then declined.
    LaunchedEffect(detector) {
        while (true) {
            val seen = runCatching { detector?.lastCopyDetectedAt() }.getOrNull()
            diagnostics = diagnostics.copy(lastCopyAt = seen)
            delay(2_000)
        }
    }

    val serviceMissing = !diagnostics.accessibilityServiceEnabled
    val overlayMissing = !diagnostics.overlayGranted

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Link detection status",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Why the bubble does or does not appear. Nothing here records what you copy.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        DiagnosticRow(
            label = if (diagnostics.accessibilityServiceEnabled) {
                "Accessibility service is connected"
            } else {
                "Accessibility service is NOT connected"
            },
            ok = diagnostics.accessibilityServiceEnabled,
            hint = if (serviceMissing) {
                "Linksi cannot see copy actions without it. Some phones also need Linksi's battery use set to Unrestricted."
            } else null
        )
        DiagnosticRow(
            label = if (diagnostics.overlayGranted) "Display over other apps is allowed" else "Display over other apps is NOT allowed",
            ok = diagnostics.overlayGranted,
            hint = if (overlayMissing) "The bubble cannot be drawn without it." else null
        )
        DiagnosticRow(
            label = if (diagnostics.notificationsGranted) "Notifications are allowed" else "Notifications are NOT allowed",
            ok = diagnostics.notificationsGranted,
            hint = if (!diagnostics.notificationsGranted) {
                "The bubble runs as a foreground service, which shows a notification. Denying it can stop the bubble on Android 13+."
            } else null
        )
        DiagnosticRow("Smart link detection is on", diagnostics.smartDetectionOn)
        DiagnosticRow("Floating bubble is on", diagnostics.bubbleOn)
        DiagnosticRow("Accessibility assistance is on", diagnostics.accessibilityAssistanceOn)

        Spacer(Modifier.height(8.dp))

        // The single most useful line: it separates "the service never saw the copy" from "it saw it
        // and declined". Both look identical to a user otherwise.
        Text(
            text = lastCopyText(diagnostics.lastCopyAt, diagnostics.state),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        // ── Recent events, as content-free codes ──────────────────────────────
        // This is the part that answers "I copied a link in Chrome and nothing happened": it lists
        // what the service actually judged, and which rule rejected it. No text and no URL is shown,
        // because none is retained - only the event kind, the app it came from, and the verdict.
        val observations by CopyObservationLog.recent.collectAsStateWithLifecycle()
        Spacer(Modifier.height(10.dp))
        if (observations.isNotEmpty()) {
            Text(
                text = "Recent copy events seen",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            observations.reversed().forEach { observation ->
                Text(
                    text = describeObservation(observation),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (observation.rejection == CopyRejection.ACCEPTED) {
                        Color(0xFF22C55E)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            TextButton(onClick = { CopyObservationLog.clear() }) { Text("Clear this list") }
        } else {
            Text(
                text = "No accessibility events have been seen yet. If you have copied a link since " +
                    "switching this on, the service is not receiving events at all.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (serviceMissing) {
            TextButton(onClick = onOpenAccessibilitySettings) { Text("Open accessibility settings") }
        }
        if (overlayMissing) {
            TextButton(onClick = onOpenOverlaySettings) { Text("Open overlay permission") }
        }
    }
}

/**
 * One observed event as a readable line: when, what kind, from which app, and the verdict.
 *
 * Separate from the composable so the wording can be tested without a device.
 */
internal fun describeObservation(
    observation: CopyObservation,
    now: Long = System.currentTimeMillis()
): String {
    val ago = DateUtils.getRelativeTimeSpanString(
        observation.atMs,
        now,
        DateUtils.SECOND_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    )
    val app = observation.packageName?.substringAfterLast('.') ?: "unknown app"
    val verdict = when (observation.rejection) {
        CopyRejection.ACCEPTED -> "accepted - bubble requested"
        CopyRejection.NO_COPY_SIGNAL -> "ignored: no copy/link label and not a URL selection"
        CopyRejection.NO_URL -> "copy seen but no usable link in the event"
        CopyRejection.PASSWORD_OR_SENSITIVE -> "refused: password or sensitive field"
        CopyRejection.IGNORED_PACKAGE -> "refused: app is on your ignore list"
        CopyRejection.NO_TEXT -> "ignored: the event carried no text"
        CopyRejection.SELECTION_REMEMBERED -> "link selected - waiting for the Copy tap"
        CopyRejection.SELECTION_WITHOUT_A_LINK -> "text selected but the selection held no link"
    }
    // In a debug build the label the control carried is shown, because "no copy signal" without
    // knowing the label is not actionable. It is always null in a release build.
    val label = observation.labelSnippet?.takeIf { it.isNotBlank() }?.let { "  [label: $it]" } ?: ""
    return "$ago  $app  ${observation.eventType.name.lowercase()}  ->  $verdict$label"
}

/**
 * The sentence shown for the last-observed copy.
 *
 * Kept separate from the composable so the wording - which is the whole point of the feature - can be
 * reasoned about and tested without a device.
 */
internal fun lastCopyText(lastCopyAt: Long?, state: SmartDetectionState?, now: Long = System.currentTimeMillis()): String {
    if (lastCopyAt == null) {
        return "No link copy has been detected yet. Copy a link in another app, then come back - " +
            "if this still says none, the accessibility service is not receiving events."
    }

    val ago = DateUtils.getRelativeTimeSpanString(
        lastCopyAt,
        now,
        DateUtils.SECOND_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    )
    val verdict = when (state) {
        is SmartDetectionState.BubbleShown -> "the bubble was shown"
        is SmartDetectionState.BubbleRequested -> "the bubble was requested"
        is SmartDetectionState.Disabled -> when (state.reason) {
            SmartDetectionState.Reason.SMART_DETECTION_OFF -> "smart link detection is switched off"
            SmartDetectionState.Reason.BUBBLE_OFF -> "the floating bubble is switched off"
            SmartDetectionState.Reason.OVERLAY_PERMISSION_MISSING -> "the overlay permission is missing"
            SmartDetectionState.Reason.RATE_LIMITED -> "it was within the 4 second rate limit"
            SmartDetectionState.Reason.ACCESSIBILITY_OFF -> "accessibility assistance is switched off"
            SmartDetectionState.Reason.ERROR -> "a settings read failed"
        }
        else -> "the outcome was not recorded"
    }
    return "Last link copy detected $ago - $verdict."
}

/** Reads the platform's real permission and service state. Safe on any thread; called from Compose. */
private fun readDetectionDiagnostics(
    context: Context,
    smartDetectionOn: Boolean,
    bubbleOn: Boolean,
    accessibilityAssistanceOn: Boolean
): DetectionDiagnostics {
    val accessibilityEnabled = runCatching {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        // `getEnabledAccessibilityServiceList` returns the services the system has actually **bound**,
        // and each id is spelled `pkg/.ClassName` or `pkg/full.ClassName` depending on the platform.
        // Matching on the simple class name avoids this reporting "not connected" for a service that
        // is demonstrably delivering events - which it did, on a real device, and made the whole
        // status block untrustworthy at exactly the moment it was needed.
        val simpleName = LinksiAccessibilityService::class.java.simpleName
        manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfoAll)
            ?.any { info ->
                val id = info.id.orEmpty()
                id.startsWith(context.packageName) && id.contains(simpleName)
            } == true
    }.getOrDefault(false)

    val overlayGranted = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    } else {
        true
    }

    return DetectionDiagnostics(
        accessibilityServiceEnabled = accessibilityEnabled,
        overlayGranted = overlayGranted,
        notificationsGranted = notificationsGranted,
        smartDetectionOn = smartDetectionOn,
        bubbleOn = bubbleOn,
        accessibilityAssistanceOn = accessibilityAssistanceOn,
        lastCopyAt = null,
        state = null
    )
}

/** `AccessibilityServiceInfo.FEEDBACK_ALL_MASK` and `FLAG_DEFAULT` combined, named for readability. */
private const val AccessibilityServiceInfoAll = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
