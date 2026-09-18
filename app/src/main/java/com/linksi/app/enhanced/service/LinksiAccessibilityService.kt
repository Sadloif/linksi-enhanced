package com.linksi.app.enhanced.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.linksi.app.enhanced.EnhancedPreferenceKeys
import com.linksi.app.enhanced.detect.CopyDetection
import com.linksi.app.enhanced.detect.CopyEventInput
import com.linksi.app.enhanced.detect.CopyEventType
import com.linksi.app.enhanced.detect.LikelyCopyDetector
import com.linksi.app.enhanced.detect.SmartLinkDetector
import com.linksi.app.enhanced.detect.UrlTextExtractor
import com.linksi.app.utils.dataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The optional accessibility assistance service (specification sections 11.2 and 11.3).
 *
 * It does exactly one thing: it watches for events that *look* like a copy of a link and tells
 * [SmartLinkDetector]. It is observation only - it never performs an action on the user's behalf,
 * never reads the clipboard, never takes a screenshot and never traverses the tree beyond the
 * single node the event itself points at.
 *
 * Privacy rules implemented here (section 11.3, section 57, section 73.15):
 *  - nothing is ever logged: this class contains no `Log` call at all,
 *  - password and sensitive nodes are rejected before their text is read,
 *  - the ignore list is honoured before any node is inspected,
 *  - only an actionable HTTP(S) URL can leave the detector, and this service does not even see it,
 *  - no content is stored, uploaded or retained beyond the current callback.
 *
 * Battery and performance (sections 60 and 61): the service is event driven from the platform, with
 * `notificationTimeout` throttling in `accessibility_service_config.xml`. Every callback first
 * checks the cached enable flag and a minimum interval, and returns immediately when there is
 * nothing to do. There is no polling, no timer and no wakelock anywhere in this class.
 */
@AndroidEntryPoint
class LinksiAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var smartLinkDetector: SmartLinkDetector

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Cached switches. They are refreshed lazily and at most once per [SETTINGS_TTL_MS] so the
     * callback never touches DataStore on the main thread.
     */
    @Volatile
    private var cachedSmartDetectionEnabled: Boolean = false

    @Volatile
    private var cachedAccessibilityAssistanceEnabled: Boolean = false

    @Volatile
    private var cachedIgnoredPackages: List<String> = emptyList()

    @Volatile
    private var settingsLoadedAtMs: Long = 0

    /** Last time a positive detection was forwarded, for the cheap per-service rate limit. */
    @Volatile
    private var lastForwardedAtMs: Long = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The configuration lives in res/xml/accessibility_service_config.xml. An OEM or a user can
        // change the flags at runtime, so re-apply the one that matters for this feature: key-event
        // filtering must never be on, because this service has no business seeing keystrokes.
        runCatching {
            val info = serviceInfo ?: return@runCatching
            info.flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
            serviceInfo = info
        }
        refreshSettings(force = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. Cheapest possible rejection first: the whole feature is off.
        if (!isEnabled()) return

        // 2. A burst of events for one user action must not queue up work.
        val now = SystemClock.elapsedRealtime()
        if (now - lastForwardedAtMs < MIN_DETECTION_INTERVAL_MS) return

        // Refuse an ignored app before asking the event for its source node or materialising any of
        // its text. Checking only after readEvent() would make the ignore list a UI filter rather
        // than the privacy boundary promised to the user.
        val packageName = event.packageName?.toString()
        if (LikelyCopyDetector.isIgnoredPackage(packageName, cachedIgnoredPackages)) return

        val input = readEvent(event) ?: return

        // 3. Pure heuristic, no I/O. It repeats the package check defensively for non-service callers.
        val detection = LikelyCopyDetector.detect(input, cachedIgnoredPackages)
        if (detection !is CopyDetection.Detected) return

        lastForwardedAtMs = now
        // The URL itself is deliberately not passed on: the bubble only signals "a link may be
        // there", and the clipboard is read later from the foreground activity (section 11.1).
        smartLinkDetector.onLikelyUrlCopied()
    }

    override fun onInterrupt() {
        // Nothing to interrupt: this service holds no resources and no ongoing work.
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Reads the *minimum* needed from the event: its type, the text and content description of the
     * single node it points at, and whether that node is a password or otherwise sensitive field.
     *
     * Returns null when there is nothing safe to look at. The node is never recycled here because
     * the event owns it; it is not retained past this call either.
     *
     * Cost control matters here: the whole method runs on the main thread. The password and
     * sensitivity checks are O(1) node flags and run first, so a password field is refused without
     * ever materialising its text as a string. Every text candidate is then bounded to
     * [MAX_EVENT_TEXT_LENGTH] characters before it is handed to the heuristic, where a regex runs
     * over it (specification sections 60 and 61).
     */
    private fun readEvent(event: AccessibilityEvent): CopyEventInput? {
        val packageName = event.packageName?.toString()
        val type = eventTypeOf(event.eventType)

        val source = runCatching { event.source }.getOrNull()

        // Cheap O(1) refusals first, before any text is read from the node.
        if (source != null && isPasswordNode(source)) {
            return CopyEventInput(eventType = type, isPassword = true, packageName = packageName)
        }
        if (source != null && isSensitiveNode(source)) {
            return CopyEventInput(eventType = type, isSensitiveField = true, packageName = packageName)
        }

        val eventText = bounded(event.text?.joinToString(separator = " "))
        // `source.text` is read once: on a browser WebView it is the whole page, so calling it
        // twice would allocate that string twice.
        val nodeTextRaw = runCatching { source?.text }.getOrNull()
        val nodeTextLength = nodeTextRaw?.length ?: -1
        val nodeText = bounded(nodeTextRaw?.toString())

        val text = pickText(eventText, nodeText, nodeTextLength)

        val description = runCatching { source?.contentDescription?.toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: event.contentDescription?.toString()?.takeIf { it.isNotBlank() }

        // Nothing to look at and no label: not worth any further work.
        if (text.isNullOrBlank() && description.isNullOrBlank()) return null

        return CopyEventInput(
            eventType = type,
            text = text,
            contentDescription = description,
            isPassword = false,
            isSensitiveField = false,
            packageName = packageName
        )
    }

    /**
     * Chooses between the event's own text and the node's text.
     *
     * The node's text is preferred when it contains a URL, because that is the only case where the
     * answer differs and the node is authoritative. Otherwise the event text is used when the node
     * text is absent or implausibly long (a browser's WebView hands back the entire page as one
     * string, which is neither useful nor cheap); when the node text is short, it wins because it is
     * the label of the control the user actually touched.
     */
    private fun pickText(
        eventText: String?,
        nodeText: String?,
        nodeTextLength: Int
    ): String? {
        if (nodeText != null && UrlTextExtractor.containsHttpUrl(nodeText)) return nodeText
        if (!eventText.isNullOrBlank()) return eventText
        if (nodeText.isNullOrBlank()) return null
        if (nodeTextLength >= 0 && nodeTextLength > MAX_EVENT_TEXT_LENGTH) return null
        return nodeText
    }

    /** Bounds a candidate string so a single event cannot make the heuristic scan a whole document. */
    private fun bounded(value: String?): String? {
        if (value == null) return null
        if (value.isBlank()) return null
        return if (value.length <= MAX_EVENT_TEXT_LENGTH) value else value.take(MAX_EVENT_TEXT_LENGTH)
    }

    /**
     * True when the node is (or is inside) a password field.
     *
     * Android 16 marks whole screens as accessibility-data-sensitive for login and payment flows;
     * such nodes are normally invisible to a service that does not declare itself an accessibility
     * tool, but if one does arrive it is refused here too (research note section 7.2).
     */
    private fun isPasswordNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        if (node.isEditable && node.isPassword) return true
        val className = node.className?.toString().orEmpty()
        return className.contains("EditText") && node.isPassword
    }

    /**
     * Android 16 adds `ACCESSIBILITY_DATA_SENSITIVE_YES` to views in login and payment flows; a
     * service that is not a declared accessibility tool is normally never shown them at all. The
     * check is version gated so the same code compiles and runs on every supported Android version,
     * and it is wrapped defensively because the runtime may be older than the compile SDK.
     */
    private fun isSensitiveNode(node: AccessibilityNodeInfo): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 36) return false
        return runCatching { node.isAccessibilityDataSensitive }.getOrDefault(false)
    }

    private fun eventTypeOf(eventType: Int): CopyEventType = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> CopyEventType.VIEW_CLICKED
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> CopyEventType.VIEW_TEXT_SELECTION_CHANGED
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> CopyEventType.VIEW_TEXT_CHANGED
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> CopyEventType.WINDOW_CONTENT_CHANGED
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> CopyEventType.WINDOW_STATE_CHANGED
        else -> CopyEventType.OTHER
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private fun isEnabled(): Boolean {
        if (SystemClock.elapsedRealtime() - settingsLoadedAtMs > SETTINGS_TTL_MS) {
            refreshSettings(force = false)
        }
        return cachedSmartDetectionEnabled && cachedAccessibilityAssistanceEnabled
    }

    /**
     * Refreshes the cached switches off the main thread.
     *
     * [force] is used from `onServiceConnected`; the periodic refresh is what makes a user turning
     * the switch off take effect without restarting the service, at the cost of at most one
     * DataStore read per [SETTINGS_TTL_MS].
     */
    private fun refreshSettings(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - settingsLoadedAtMs <= SETTINGS_TTL_MS) return
        // Claim the slot immediately so a burst of events does not launch a read each.
        settingsLoadedAtMs = now
        val appContext = applicationContext ?: return
        scope.launch {
            runCatching {
                val prefs = appContext.dataStore.data.first()
                cachedSmartDetectionEnabled =
                    prefs[booleanPreferencesKey(EnhancedPreferenceKeys.SMART_LINK_DETECTION)] ?: false
                cachedAccessibilityAssistanceEnabled =
                    prefs[booleanPreferencesKey(EnhancedPreferenceKeys.ACCESSIBILITY_ASSISTANCE)] ?: false
                cachedIgnoredPackages = LikelyCopyDetector.parseIgnoreList(
                    prefs[stringPreferencesKey(EnhancedPreferenceKeys.A11Y_IGNORED_PACKAGES)]
                )
            }.onFailure {
                // A failed read must fail closed: no detection rather than unexpected detection.
                cachedSmartDetectionEnabled = false
                cachedAccessibilityAssistanceEnabled = false
                cachedIgnoredPackages = emptyList()
            }
        }
    }

    companion object {
        /** At most one detection forwarded per this interval, per service instance. */
        private const val MIN_DETECTION_INTERVAL_MS = 1_500L

        /** How long the cached switches are trusted before they are read again. */
        private const val SETTINGS_TTL_MS = 5_000L

        /**
         * Hard cap on any single text candidate handed to the heuristic. A screen reader style
         * WebView can report an entire page as one node's text; scanning that on the main thread for
         * every event would be exactly the kind of high-frequency work sections 60 and 61 forbid.
         */
        private const val MAX_EVENT_TEXT_LENGTH = 4096
    }
}
