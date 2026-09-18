package com.linksi.app.enhanced.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.linksi.app.enhanced.EnhancedPreferenceKeys
import com.linksi.app.BuildConfig
import com.linksi.app.enhanced.detect.ClipboardUrlReader
import com.linksi.app.enhanced.detect.CopyDetection
import com.linksi.app.enhanced.detect.CopyEventInput
import com.linksi.app.enhanced.detect.CopyEventType
import com.linksi.app.enhanced.detect.CopyObservation
import com.linksi.app.enhanced.detect.CopyObservationLog
import com.linksi.app.enhanced.detect.CopyRejection
import com.linksi.app.enhanced.detect.LabelSnippet
import com.linksi.app.enhanced.detect.LikelyCopyDetector
import com.linksi.app.enhanced.detect.RecentSelection
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

    /**
     * The link from the most recent text selection, so a following "Copy" tap can be resolved.
     *
     * `@Volatile` because it is written on the main thread and read on the same thread, but the field
     * must not be hoisted across the coroutine boundary that the detector may run behind.
     */
    @Volatile
    private var recentSelection: RecentSelection? = null

    /** When the last text selection with no link in it happened, for the clipboard fallback. */
    @Volatile
    private var selectionAtMs: Long = 0

    /**
     * What the clipboard held when the last selection began.
     *
     * The clipboard fallback compares against this so that a link copied *earlier* - which is still
     * sitting on the clipboard - cannot be reported as the copy the user just made.
     */
    @Volatile
    private var clipboardBeforeCopy: String? = null

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

        // One-time, debug-only probe of whether this service may read the clipboard while it is not
        // the foreground app.
        //
        // The clipboard fallback depends on that permission, and Android 10+ restricts background
        // clipboard reads without guaranteeing an accessibility service an exemption. Rather than
        // assume either way, the fact is established once at connect and logged in a debug build. No
        // clip content is logged - only whether a read was permitted and whether it held a URL.
        if (BuildConfig.DEBUG) {
            val probe = runCatching { ClipboardUrlReader.readFromService(this) }.getOrNull()
            android.util.Log.i(
                TAG_DEBUG,
                "clipboard probe at connect: readable=${probe?.readable == true} " +
                    "foundUrl=${probe?.url != null}"
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Every decision is recorded as a content-free code, so "why did my copy do nothing?" has an
        // answer on the device. This stores no text, no URL and nothing from the clipboard - see
        // CopyObservationLog. The service still writes nothing to logcat.
        val packageName = event.packageName?.toString()
        val type = eventTypeOf(event.eventType)

        // Debug builds only: log the arrival of the two event kinds a copy needs, BEFORE anything can
        // filter them out. This answers "does the platform deliver a selection or a click at all for
        // this gesture?" independently of every rule, rate limit and early return in this class - the
        // question that kept being confused with "is our rule too strict".
        if (BuildConfig.DEBUG &&
            (type == CopyEventType.VIEW_TEXT_SELECTION_CHANGED || type == CopyEventType.VIEW_CLICKED)
        ) {
            android.util.Log.i(TAG_DEBUG, "arrived: $type pkg=$packageName")
        }

        // 1. Cheapest possible rejection first: the whole feature is off.
        if (!isEnabled()) {
            CopyObservationLog.record(
                CopyObservation(
                    System.currentTimeMillis(), type, packageName, CopyRejection.NO_COPY_SIGNAL
                )
            )
            return
        }

        // A selection change is handled FIRST, before the rate limit.
        //
        // It used to be handled after, which made the whole copy path undiagnosable: a burst of
        // window-content events from a browser would consume the interval, and the one selection that
        // mattered was then dropped so early that it never appeared in the observation log at all.
        // Recording it here means "did the platform even tell us about the selection?" always has an
        // answer. Remembering it is cheap - one field, no I/O.
        if (type == CopyEventType.VIEW_TEXT_SELECTION_CHANGED) {
            // Read the minimum from the event, exactly as the detector path does.
            val selectionInput = readEvent(event)
            val selected = UrlTextExtractor.firstActionableUrl(selectionInput?.text)
                ?: UrlTextExtractor.firstActionableUrl(selectionInput?.contentDescription)
                ?: selectedTextFromWindow()

            // The clipboard as it stands *before* this interaction, so a clip that has not changed
            // cannot be mistaken for a fresh copy. Read best-effort: the platform may refuse a
            // background read, in which case this stays null and the comparison simply never matches.
            clipboardBeforeCopy = runCatching {
                ClipboardUrlReader.readFromService(this).url
            }.getOrNull()

            if (selected != null) {
                recentSelection = RecentSelection(selected, System.currentTimeMillis())
                CopyObservationLog.record(
                    CopyObservation(
                        System.currentTimeMillis(), type, packageName, CopyRejection.SELECTION_REMEMBERED
                    )
                )
            } else {
                // Brave, WhatsApp and other WebView apps land here: they deliver the selection and the
                // click but put no text in either, so the URL can only come from the clipboard.
                selectionAtMs = System.currentTimeMillis()
                CopyObservationLog.record(
                    CopyObservation(
                        System.currentTimeMillis(), type, packageName,
                        CopyRejection.SELECTION_WITHOUT_A_LINK
                    )
                )
            }
        }

        // 2. A copy-shaped interaction is handled BEFORE the burst filter.
        //
        // This ordering is the whole reason copying did not work in a browser. A WebView emits a
        // continuous stream of window-content events, so the 1.5 s interval was almost always already
        // "spent" by the time the user's click on Copy arrived - and that click was discarded at this
        // very line, before anything could look at it. Measured on the OPPO: three copies in Brave
        // produced a VIEW_CLICKED at 13:03:04.184 that never reached a single line of detection code.
        //
        // A click is rare and cheap, so it is never rate-limited; the interval still throttles the
        // heuristic path below, which is what it was for.
        if (type == CopyEventType.VIEW_CLICKED) {
            handleCopyClick(packageName, event)
        }

        // 3. A burst of events for one user action must not queue up work.
        val now = SystemClock.elapsedRealtime()
        if (now - lastForwardedAtMs < MIN_DETECTION_INTERVAL_MS) return

        // Refuse an ignored app before asking the event for its source node or materialising any of
        // its text. Checking only after readEvent() would make the ignore list a UI filter rather
        // than the privacy boundary promised to the user.
        if (LikelyCopyDetector.isIgnoredPackage(packageName, cachedIgnoredPackages)) {
            CopyObservationLog.record(
                CopyObservation(
                    System.currentTimeMillis(), type, packageName, CopyRejection.IGNORED_PACKAGE
                )
            )
            return
        }

        // 3. Pure heuristic, no I/O. The "with reason" form returns exactly the same CopyDetection the
        //    plain form would, and additionally says which rule decided.
        val input = readEvent(event)
        if (input == null) {
            CopyObservationLog.record(
                CopyObservation(System.currentTimeMillis(), type, packageName, CopyRejection.NO_TEXT)
            )
            return
        }
        val (detection, rejection) = LikelyCopyDetector.detectWithReason(
            input = input,
            ignoredPackages = cachedIgnoredPackages,
            recentSelection = recentSelection
        )
        CopyObservationLog.record(
            CopyObservation(
                atMs = System.currentTimeMillis(),
                eventType = type,
                packageName = packageName,
                rejection = rejection,
                // Debug builds only: the label the control actually carried. See LabelSnippet.
                labelSnippet = LabelSnippet.of(
                    enabled = BuildConfig.DEBUG,
                    description = input.contentDescription,
                    text = input.text
                )
            )
        )
        if (detection is CopyDetection.Detected) {
            // Consume it: one selection may explain at most one copy, so a later unrelated click cannot
            // reuse it.
            recentSelection = null
            lastForwardedAtMs = now
            // The URL itself is deliberately not passed on: the bubble only signals "a link may be
            // there", and the clipboard is read later from the foreground activity (section 11.1).
            smartLinkDetector.onLikelyUrlCopied()
            return
        }
    }

    /**
     * Handles a click, which is the only event a copy affordance reliably produces.
     *
     * Called before the burst filter, because in a WebView the interval is almost always already spent
     * by the time the user's Copy tap arrives - which is exactly how three copies on a real device
     * produced one click that reached no detection code at all (`TEST_REPORT.md` §49).
     *
     * Two things are attempted, in order of how much they know:
     *
     *  1. **A remembered selection.** If the browser delivered the link inside an earlier selection
     *     event, the click resolves against it. This is the precise path and it shows a bubble only
     *     when a real link was selected.
     *  2. **The clipboard fallback.** Brave and WhatsApp put *no text* in either event, so the URL only
     *     exists on the clipboard. It is accepted only when it has changed to an actionable URL since
     *     the selection began, so a link copied earlier cannot be reported as a fresh copy.
     */
    private fun handleCopyClick(packageName: String?, event: AccessibilityEvent) {
        // 0. The clicked node's own surroundings, tried first because it is the only channel a real
        //    device has left. Measured on the OPPO, a copy in Brave delivers exactly one event:
        //      arrived: VIEW_CLICKED pkg=com.brave.browser
        //    No selection change, no text, and the clipboard is refused to a background reader. What
        //    the click *does* have is a node, and the context menu the user tapped lives in that node's
        //    tree - including, often, the link the menu item refers to.
        clickContextUrl(event)?.let { url ->
            lastForwardedAtMs = SystemClock.elapsedRealtime()
            CopyObservationLog.record(
                CopyObservation(
                    System.currentTimeMillis(), CopyEventType.VIEW_CLICKED, packageName,
                    CopyRejection.ACCEPTED
                )
            )
            // A real link was found in the tree, so this is a copy-shaped click; the URL itself is not
            // passed on, exactly as for the other paths.
            if (BuildConfig.DEBUG) {
                android.util.Log.i(TAG_DEBUG, "copy click: resolved from the node tree")
            }
            smartLinkDetector.onLikelyUrlCopied()
            return
        }

        // 1. A link the platform already told us about.
        val remembered = recentSelection?.takeIf { it.isFresh() }
        if (remembered != null) {
            recentSelection = null
            lastForwardedAtMs = SystemClock.elapsedRealtime()
            smartLinkDetector.onLikelyUrlCopied()
            return
        }

        // 2. The clipboard, compared against what it held before this interaction.
        if (packageName.isNullOrBlank()) return
        if (System.currentTimeMillis() - selectionAtMs !in 1..COPY_FALLBACK_WINDOW_MS) return

        val afterCopy = runCatching { ClipboardUrlReader.readFromService(this) }.getOrNull()
        val copied = afterCopy?.url
        val changed = copied != null && copied != clipboardBeforeCopy

        // Debug builds only, and deliberately content-free: it says whether the platform permitted a
        // background clipboard read at all, which is the one fact needed to know whether this fallback
        // can work on a device. No URL and no clip text is ever logged.
        if (BuildConfig.DEBUG) {
            android.util.Log.i(
                TAG_DEBUG,
                "copy click: readable=${afterCopy?.readable == true} foundUrl=${copied != null} changed=$changed"
            )
        }

        if (changed) {
            CopyObservationLog.record(
                CopyObservation(
                    System.currentTimeMillis(), CopyEventType.VIEW_CLICKED, packageName,
                    CopyRejection.ACCEPTED
                )
            )
            selectionAtMs = 0
            clipboardBeforeCopy = null
            lastForwardedAtMs = SystemClock.elapsedRealtime()
            smartLinkDetector.onLikelyUrlCopied()
        }
    }

    /**
     * A link found in the node tree around a click, or null.
     *
     * This is the last channel, and on a real device it is the only one a copy in Brave leaves open.
     * The click carries no text and no selection was reported, but the context menu the user tapped is
     * part of the tree, and its items frequently carry the link - either as their own text
     * ("example.com/path") or in their content description.
     *
     * The search is deliberately shaped around that menu rather than sweeping the page:
     *
     *  1. the clicked node, and its ancestors up to [MAX_ANCESTOR_HOPS] - because the action that was
     *     tapped is usually a leaf inside a menu container;
     *  2. then each of those nodes' subtrees, breadth-first, bounded by [MAX_TREE_NODES].
     *
     * A plain page link would be found by this too, which is why an ancestor must *also* look like a
     * copy affordance before the link is accepted: a copy label ("Copy", "Copy link address") on any
     * node in the same subtree. That keeps an ordinary tap on a link from raising a bubble, which a
     * test pins.
     *
     * Everything is wrapped and bounded: a null or partial tree simply yields null, leaving detection
     * exactly as it was before this method existed.
     */
    private fun clickContextUrl(event: AccessibilityEvent): String? = runCatching {
        val clicked = event.source ?: return@runCatching null

        // The chain: the clicked node and its ancestors, which is where a menu container lives.
        val chain = mutableListOf<AccessibilityNodeInfo>()
        var current: AccessibilityNodeInfo? = clicked
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            chain.add(current)
            current = current.parent
            hops++
        }

        for (anchors in chain) {
            if (!subtreeLooksLikeACopyAction(anchors)) continue
            subtreeUrl(anchors)?.let { return@runCatching it }
        }
        null
    }.getOrNull()

    /** True when any node in [root]'s subtree carries a copy-ish label. Bounded by [MAX_TREE_NODES]. */
    private fun subtreeLooksLikeACopyAction(root: AccessibilityNodeInfo): Boolean {
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            visited++
            if (looksLikeACopyLabel(node.text?.toString())) return true
            if (looksLikeACopyLabel(node.contentDescription?.toString())) return true
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }
        }
        return false
    }

    /** The first actionable URL in [root]'s subtree, or null. Bounded by [MAX_TREE_NODES]. */
    private fun subtreeUrl(root: AccessibilityNodeInfo): String? {
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            visited++
            UrlTextExtractor.firstActionableUrl(node.text?.toString())?.let { return it }
            UrlTextExtractor.firstActionableUrl(node.contentDescription?.toString())?.let { return it }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** True when [value] names a copy or link action rather than ordinary content. */
    private fun looksLikeACopyLabel(value: String?): Boolean {
        val text = value?.trim()?.lowercase() ?: return false
        if (text.isEmpty() || text.length > MAX_COPY_LABEL_LENGTH) return false
        return COPY_ACTION_WORDS.any { it in text }
    }

    /**
     * The currently selected text, read from the window's node tree.
     *
     * Kept alongside [clickContextUrl] because a selection is still the most precise signal when a
     * platform does report one; several apps do.
     */
    private fun selectedTextFromWindow(): String? = runCatching {
        val root = rootInActiveWindow ?: return@runCatching null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            visited++

            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            val text = node.text?.toString()
            if (start >= 0 && end > start && text != null && end <= text.length) {
                val selected = text.substring(start, end)
                UrlTextExtractor.firstActionableUrl(selected)?.let { return@runCatching it }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child ->
                    if (child.text != null && child.text.length > MAX_SELECTED_TEXT) {
                        // Too large to be a link; skip without descending into it.
                    } else {
                        queue.add(child)
                    }
                }
            }
        }
        null
    }.getOrNull()

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

        /**
         * How long after a selection a click may still be the user's Copy.
         *
         * Long enough to cover a considered tap on the toolbar, short enough that an unrelated click
         * later in the same app cannot reach the clipboard fallback.
         */
        private const val COPY_FALLBACK_WINDOW_MS = 3_000L

        /** Debug-only log tag for the clipboard fallback; a release build never writes it. */
        private const val TAG_DEBUG = "LinksiDetect"

        /** Most nodes to visit when looking for a selection. A page tree can be very large. */
        private const val MAX_TREE_NODES = 400

        /** A node's text longer than this is page content, not a selectable link. */
        private const val MAX_SELECTED_TEXT = 4096

        /** How far up from a clicked node to look for the menu that contains it. */
        private const val MAX_ANCESTOR_HOPS = 6

        /** A label longer than this is prose, not a control name. */
        private const val MAX_COPY_LABEL_LENGTH = 120

        /** Words that mark a node as a copy affordance rather than content. */
        private val COPY_ACTION_WORDS = listOf("copy", "link", "clipboard")
    }
}
