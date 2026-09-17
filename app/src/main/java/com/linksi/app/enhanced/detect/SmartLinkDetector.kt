package com.linksi.app.enhanced.detect

import android.content.Context
import android.os.SystemClock
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.linksi.app.enhanced.EnhancedPreferenceKeys
import com.linksi.app.enhanced.bubble.BubbleService
import com.linksi.app.enhanced.bubble.BubbleSettings
import com.linksi.app.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The optional smart-detection orchestrator (specification sections 11 and 12).
 *
 * It is the only place that decides "a likely copy happened, should anything be shown?", and it is
 * deliberately unable to do anything at all unless *both* the smart detection and the floating
 * bubble switches are on and the overlay permission is granted. Every entry point is safe to call
 * with the whole module switched off: it records a state and returns.
 *
 * Privacy: this class never sees the copied text, only the already-validated URL that
 * [LikelyCopyDetector] produced, and it never passes that URL on to the bubble - the bubble is a
 * bare "there may be a link" prompt, and the URL is only re-read from the clipboard later, by
 * [QuickPanelActivity], once the user has tapped and the app has focus (section 11.1).
 *
 * Battery: everything is event driven. There is no polling loop, no scheduled work and no
 * clipboard observer; the only timer is the bubble's own auto-dismiss delay, which lives in
 * [BubbleService] and is cancelled on teardown (sections 60 and 61).
 */
@Singleton
class SmartLinkDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<SmartDetectionState>(SmartDetectionState.Idle)

    /** Observable status, for a settings or diagnostics screen. */
    val state: StateFlow<SmartDetectionState> = _state.asStateFlow()

    private var lastActionAtElapsedMs: Long = Long.MIN_VALUE

    /**
     * Called by [com.linksi.app.enhanced.service.LinksiAccessibilityService] when it has already
     * decided that a copy of an actionable URL probably happened.
     *
     * Never throws and never blocks the caller: the accessibility callback runs on the main thread,
     * so all work is handed to [scope] and the platform calls are wrapped.
     */
    fun onLikelyUrlCopied() {
        scope.launch {
            runCatching { handleLikelyCopy() }.onFailure {
                _state.value = SmartDetectionState.Disabled(SmartDetectionState.Reason.ERROR)
            }
        }
    }

    /** Clears the current status, for example when the user dismisses a diagnostic row. */
    fun reset() {
        _state.value = SmartDetectionState.Idle
    }

    /**
     * Reads the persisted switches and returns null when the bubble must not be shown.
     *
     * The returned [SmartDetectionState.Reason] explains *why* nothing happened, which is exactly
     * what the settings screen needs to show a truthful status instead of a silent nothing. One
     * snapshot serves both the gate and the bubble's own settings, so a single copy costs one read.
     */
    private suspend fun resolveRun(): RunDecision {
        val prefs = runCatching { context.dataStore.data.first() }.getOrNull()
            ?: return RunDecision.Disabled(SmartDetectionState.Reason.ERROR)

        val smartEnabled = prefs[booleanPreferencesKey(EnhancedPreferenceKeys.SMART_LINK_DETECTION)]
            ?: false
        val bubbleEnabled = prefs[booleanPreferencesKey(EnhancedPreferenceKeys.FLOATING_BUBBLE)]
            ?: false

        if (!smartEnabled) return RunDecision.Disabled(SmartDetectionState.Reason.SMART_DETECTION_OFF)
        if (!bubbleEnabled) return RunDecision.Disabled(SmartDetectionState.Reason.BUBBLE_OFF)
        if (!BubbleService.canDrawOverlays(context)) {
            return RunDecision.Disabled(SmartDetectionState.Reason.OVERLAY_PERMISSION_MISSING)
        }

        return RunDecision.Allowed(
            BubbleSettings(
                size = prefs[stringPreferencesKey(EnhancedPreferenceKeys.BUBBLE_SIZE)],
                position = prefs[stringPreferencesKey(EnhancedPreferenceKeys.BUBBLE_POSITION)],
                autoDismissSeconds = prefs[intPreferencesKey(EnhancedPreferenceKeys.BUBBLE_AUTO_DISMISS)]
                    ?: DEFAULT_AUTO_DISMISS_SECONDS
            )
        )
    }

    private suspend fun handleLikelyCopy() {
        when (val decision = resolveRun()) {
            is RunDecision.Disabled -> {
                _state.value = SmartDetectionState.Disabled(decision.reason)
                return
            }

            is RunDecision.Allowed -> {
                if (isRateLimited()) {
                    _state.value = SmartDetectionState.Disabled(SmartDetectionState.Reason.RATE_LIMITED)
                    return
                }

                lastActionAtElapsedMs = now()
                _state.value = SmartDetectionState.BubbleRequested
                _state.value = if (BubbleService.show(context, decision.settings)) {
                    SmartDetectionState.BubbleShown
                } else {
                    SmartDetectionState.Disabled(SmartDetectionState.Reason.OVERLAY_PERMISSION_MISSING)
                }
            }
        }
    }

    /** True when a bubble was requested too recently; see [MIN_INTERVAL_BETWEEN_BUBBLES_MS]. */
    private fun isRateLimited(): Boolean {
        val last = lastActionAtElapsedMs
        if (last == Long.MIN_VALUE) return false
        return now() - last < MIN_INTERVAL_BETWEEN_BUBBLES_MS
    }

    /** Monotonic clock: a wall-clock change must not be able to defeat the rate limit. */
    private fun now(): Long = SystemClock.elapsedRealtime()

    /** Persists one of the optional switches. Used by the settings UI, never at app start. */
    suspend fun setSmartDetectionEnabled(enabled: Boolean) =
        context.dataStore.edit { it[booleanPreferencesKey(EnhancedPreferenceKeys.SMART_LINK_DETECTION)] = enabled }

    suspend fun setFloatingBubbleEnabled(enabled: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey(EnhancedPreferenceKeys.FLOATING_BUBBLE)] = enabled }
        if (!enabled) stopBubble()
    }

    suspend fun setAccessibilityAssistanceEnabled(enabled: Boolean) =
        context.dataStore.edit { it[booleanPreferencesKey(EnhancedPreferenceKeys.ACCESSIBILITY_ASSISTANCE)] = enabled }

    /** Stops the bubble immediately; safe when it is not running. */
    fun stopBubble() {
        BubbleService.stop(context)
        _state.value = SmartDetectionState.Idle
    }

    companion object {
        /**
         * At most one bubble per this interval (specification section 11: "debounced/rate-limited").
         * The accessibility service can deliver a burst of selection and click events for a single
         * user action; without this, one copy could stack several bubbles.
         */
        const val MIN_INTERVAL_BETWEEN_BUBBLES_MS = 4_000L

        private const val DEFAULT_AUTO_DISMISS_SECONDS = 10
    }
}

/** The outcome of reading the persisted switches: either a reason to do nothing, or the settings. */
private sealed interface RunDecision {
    data class Allowed(val settings: BubbleSettings) : RunDecision
    data class Disabled(val reason: SmartDetectionState.Reason) : RunDecision
}

/**
 * What the detector is currently doing, for a status row in settings (specification section 12.3 is
 * explicitly optional; this is only a status, never a promise).
 */
sealed interface SmartDetectionState {

    data object Idle : SmartDetectionState

    /** A likely copy was seen and the bubble is about to be requested. */
    data object BubbleRequested : SmartDetectionState

    /** The bubble is on screen, waiting for the user to tap it. */
    data object BubbleShown : SmartDetectionState

    /** Nothing happened, and why. Never an error the user must act on. */
    data class Disabled(val reason: Reason) : SmartDetectionState

    /** Reasons nothing was shown. Every one of these is a normal, supported configuration. */
    enum class Reason {
        SMART_DETECTION_OFF,
        BUBBLE_OFF,
        OVERLAY_PERMISSION_MISSING,
        RATE_LIMITED,
        ACCESSIBILITY_OFF,
        ERROR
    }
}
