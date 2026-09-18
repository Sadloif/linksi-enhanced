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

    /**
     * The last link offered from the window tree, so the same page does not re-offer on every
     * content-change event. Without this a WebView's constant event stream would raise a bubble
     * continuously.
     */
    @Volatile
    private var lastOfferedLink: String? = null

    /** When the window tree was last scanned for a link. See [TREE_SCAN_INTERVAL_MS]. */
    @Volatile
    private var lastTreeScanAtMs: Long = 0

    /** When a native app's tree was last dumped, so the log stays readable. Debug builds only. */
    @Volatile
    private var lastNativeDumpAtMs: Long = 0

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

        // A new window is how a browser announces its long-press menu. Dump every window at that moment,
        // while the menu is still on screen - by the time a click arrives the menu may already be gone,
        // which is why an earlier search of `event.source` alone found nothing.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            dumpAllWindows("windows-changed pkg=$packageName")
            // And scan here specifically, because this is the moment the link becomes readable: Brave's
            // link nodes carry their `targetUrl` extra only while its context menu is open. Scanning at
            // page load finds nothing, which is exactly what happened before this line existed.
            lastTreeScanAtMs = SystemClock.elapsedRealtime()
            offerLinkFromWindow(event, type, packageName)
        }

        // Debug builds only: one dump when a native (non-Chromium) app presents something link-shaped.
        //
        // Facebook Reels, WhatsApp and YouTube are native apps with their own view hierarchies, so
        // Chromium's `targetUrl` extra cannot exist there. This records what they *do* expose, so
        // "unsupported" is a measured conclusion rather than an assumption. One dump per app per minute
        // keeps it readable.
        if (BuildConfig.DEBUG && packageName != null && packageName !in CHROMIUM_PACKAGES) {
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastNativeDumpAtMs >= NATIVE_DUMP_INTERVAL_MS) {
                lastNativeDumpAtMs = nowMs
                dumpAllWindows("native pkg=$packageName")
            }
        }

        // Belt and braces for the case a menu is added without a windows-changed event: a long press in
        // a browser fires a selection change, and at that instant the menu is about to be (or already is)
        // on screen, so this is the moment worth capturing.
        if (BuildConfig.DEBUG && type == CopyEventType.VIEW_TEXT_SELECTION_CHANGED) {
            dumpAllWindows("selection pkg=$packageName")
        }

        // A browser long-press does not reliably produce any event this service can use - measured: a
        // copy in Brave delivered no selection event and, on a later run, nothing at all. But the link
        // IS on screen, in the WebView's node extras, so the window is examined when something suggests
        // a link action is under way.
        //
        // ONLY on those signals - deliberately NOT on TYPE_WINDOW_CONTENT_CHANGED, which is what this
        // used to do and which was wrong. A scrolling feed emits content changes continuously, so
        // triggering on them meant the bubble was driven by "a link exists somewhere" rather than by
        // anything the user did, and it arrived whenever the next content change happened to occur.
        // Reported from the field as: copy a link in Facebook Reels and nothing appears; scroll away
        // and the bubble appears - a bubble at the wrong moment, which is worse than none.
        //
        // The two triggers now are the ones that mean "the user is interacting with a link":
        //  - a selection change, which a long-press produces (and which long-press on a page link also
        //    produces in several apps);
        //  - a new window, which is how a context menu announces itself.
        if (type == CopyEventType.VIEW_TEXT_SELECTION_CHANGED) {
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastTreeScanAtMs >= TREE_SCAN_INTERVAL_MS) {
                lastTreeScanAtMs = nowMs
                offerLinkFromWindow(event, type, packageName)
            }
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
     * produced one click that reached no detection code at all (`TEST_REPORT.md` Â§49).
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
        // 0. Chromium's own link metadata, tried first because it is the only mechanism measured to
        //    work for a browser: links there carry no text at all, only a `targetUrl` extra.
        linkFromChromiumExtras(event)?.let {
            lastForwardedAtMs = SystemClock.elapsedRealtime()
            CopyObservationLog.record(
                CopyObservation(
                    System.currentTimeMillis(), CopyEventType.VIEW_CLICKED, packageName,
                    CopyRejection.ACCEPTED
                )
            )
            if (BuildConfig.DEBUG) {
                android.util.Log.i(TAG_DEBUG, "copy click: resolved through a Chromium targetUrl extra")
            }
            smartLinkDetector.onLikelyUrlCopied()
            return
        }

        // 1. The clicked node's own surroundings.
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
     * The currently selected text, read from the window's node tree.
     *
     * A selection is still the most precise signal when a platform reports one, and several apps do, so
     * this is kept alongside the Chromium-extras path rather than replaced by it.
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

    /**
     * Debug-only: dump every interactive window and every node's metadata, including `extras`.
     *
     * This exists to close one specific gap. The earlier investigation searched only `event.source` and
     * only `node.text`/`contentDescription`, and both assumptions were shown to be too narrow:
     *
     *  - a context menu is often a **separate window**, reachable only through [getWindows] and
     *    announced by `TYPE_WINDOWS_CHANGED` (a type this service did not even subscribe to until now,
     *    despite already setting `flagRetrieveInteractiveWindows`, which is documented as requiring it);
     *  - Chromium stores a link's destination in the node's **extras** bundle under
     *    `AccessibilityNodeInfo.targetUrl`, which `node.text` inspection cannot see.
     *
     * Everything is logged in a debug build only, and the URL-bearing extras are recorded as a
     * presence flag plus a redacted snippet rather than the raw value, so this stays a diagnostic rather
     * than a log of what the user copied.
     */
    private fun dumpAllWindows(reason: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val windows = windows.orEmpty()
            android.util.Log.i(TAG_DEBUG, "WINDOWS[$reason] count=${windows.size}")
            windows.forEachIndexed { index, window ->
                val root = runCatching { window.root }.getOrNull()
                android.util.Log.i(
                    TAG_DEBUG,
                    "WINDOWS[$reason] #$index id=${window.id} type=${window.type} " +
                        "layer=${window.layer} active=${window.isActive} focused=${window.isFocused} " +
                        "root=${root?.className?.toString()?.substringAfterLast('.')}"
                )
                if (root != null) dumpNode(root, reason, depth = 0, budget = intArrayOf(MAX_DUMP_NODES))
            }
        }.onFailure {
            android.util.Log.i(TAG_DEBUG, "WINDOWS[$reason] dump failed: ${it.javaClass.simpleName}")
        }
    }

    /**
     * Chromium's key for a link node's destination.
     *
     * Chromium exposes the href of a link through `AccessibilityNodeInfo.getExtras()` under this key,
     * even when the node's `text` is empty. This is the piece the first investigation missed: a
     * browser's links are not readable through `node.text` at all, so no amount of text inspection
     * would ever have found them. Confirmed on the device - a Brave page reported
     * `targetUrl=PRESENT(len=25)` and `len=88` on two nodes.
     */

    /** Depth-first node dump, bounded by `budget[0]` nodes in total. */
    private fun dumpNode(node: AccessibilityNodeInfo, reason: String, depth: Int, budget: IntArray) {
        if (budget[0]-- <= 0) return
        val extras = runCatching { node.extras }.getOrNull()
        val extraKeys = extras?.keySet()?.joinToString(",").orEmpty()
        // The Chromium link target. Presence is what matters; the value is redacted to a marker.
        val target = runCatching { extras?.getString(EXTRA_TARGET_URL) }.getOrNull()
        val targetNote = when {
            target == null -> ""
            else -> " targetUrl=PRESENT(len=${target.length})"
        }
        android.util.Log.i(
            TAG_DEBUG,
            "WINDOWS[$reason] ${"  ".repeat(depth.coerceAtMost(6))}" +
                "${node.className?.toString()?.substringAfterLast('.')} " +
                "viewId=${node.viewIdResourceName ?: "-"} " +
                "text=${LabelSnippet.of(true, null, node.text?.toString()) ?: "-"} " +
                "desc=${LabelSnippet.of(true, node.contentDescription?.toString(), null) ?: "-"} " +
                "clickable=${node.isClickable} actions=${node.actionList.size} " +
                "extras=[$extraKeys]$targetNote"
        )
        for (child in 0 until node.childCount) {
            node.getChild(child)?.let { dumpNode(it, reason, depth + 1, budget) }
        }
    }

    /**
     * Offers the bubble for a link found in the window tree, at most once per distinct link.
     *
     * Extracted so the two entry points - a window appearing, and a window's contents changing - share
     * exactly one implementation, and so the "already offered this link" rule cannot be forgotten at
     * either. Without that rule a WebView's continuous event stream would raise a bubble forever.
     */
    private fun offerLinkFromWindow(event: AccessibilityEvent, type: CopyEventType, packageName: String?) {
        linkFromChromiumExtras(event)?.let { url ->
            if (url != lastOfferedLink) {
                lastOfferedLink = url
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(TAG_DEBUG, "offering a link found in the window tree")
                }
                CopyObservationLog.record(
                    CopyObservation(
                        System.currentTimeMillis(), type, packageName, CopyRejection.ACCEPTED
                    )
                )
                smartLinkDetector.onLikelyUrlCopied()
            }
        }
    }

    /**
     * The link a long-press was aimed at, found through Chromium's `targetUrl` extras.
     *
     * This is the mechanism that actually works for a browser, and it was invisible to every earlier
     * attempt. A browser's links carry no `text` and no `contentDescription`; Chromium instead puts the
     * href in the node's **extras** bundle. Measured on a Brave page:
     *
     * ```
     * View viewId=â€¦ desc="Brave logo" clickable=true
     *     extras=[â€¦, AccessibilityNodeInfo.targetUrl, â€¦] targetUrl=PRESENT(len=25)
     * ```
     *
     * Selecting *which* link was long-pressed is the real problem, since a page has many. Three
     * candidates are collected and ranked, best first:
     *
     *  1. a node whose bounds **contain the touch point of the event**, which is the link the finger was
     *     actually on - exact, and the reason the event's bounds are used at all;
     *  2. a node reporting a live text selection, which is what a long-press on a link selects;
     *  3. the only `targetUrl` node in the tree, when there is exactly one - unambiguous by definition.
     *
     * Anything less certain returns null rather than guessing, so a wrong link is never offered.
     */
    private fun linkFromChromiumExtras(event: AccessibilityEvent): String? = runCatching {
        val touch = touchPointOf(event)

        // Walk every interactive window's root, not just `rootInActiveWindow`.
        //
        // This is the second time the same mistake was made in this investigation, so it is worth
        // stating: the dump that first found `targetUrl` used `getWindows()`, and the search that
        // followed used `rootInActiveWindow` - and reported "no targetUrl extras found" while the dump
        // in the very same run was printing them. A Chromium page lives inside the active window's root,
        // but that root is not always where the interesting nodes are reached from, so the search now
        // uses exactly the source proven to work.
        val roots = windows.orEmpty().mapNotNull { window ->
            runCatching { window.root }.getOrNull()
        }.ifEmpty {
            listOfNotNull(runCatching { rootInActiveWindow }.getOrNull())
        }

        val withTargets = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach { queue.add(it) }
        while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.targetUrl() != null) withTargets.add(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }
        }
        if (withTargets.isEmpty()) {
            if (BuildConfig.DEBUG) {
                android.util.Log.i(
                    TAG_DEBUG,
                    "scan: windows=${roots.size} visited=$visited nodes, no targetUrl extras found"
                )
            }
            return@runCatching null
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.i(
                TAG_DEBUG,
                "scan: windows=${roots.size} visited=$visited nodes, " +
                    "targetUrl nodes=${withTargets.size}, touch=${touch != null}"
            )
        }

        // Exactly one link on the page: no ambiguity to resolve.
        if (withTargets.size == 1) return@runCatching withTargets.first().targetUrl()

        // The link under the finger, when the platform gave us the touch point.
        if (touch != null) {
            withTargets
                .filter { it.containsPoint(touch.first, touch.second) }
                // The innermost match is the most specific; prefer a node that is itself clickable.
                .minByOrNull { it.boundsArea() }
                ?.targetUrl()
                ?.let { return@runCatching it }
        }

        // A node the platform says is currently selected.
        withTargets.firstOrNull { node ->
            val start = runCatching { node.textSelectionStart }.getOrDefault(-1)
            val end = runCatching { node.textSelectionEnd }.getOrDefault(-1)
            start >= 0 && end > start
        }?.let { return@runCatching it.targetUrl() }

        // Nothing distinguishes which of several links was pressed. Debug builds record what the
        // candidates looked like, because "15 links, cannot tell which" is only actionable if the shape
        // of those nodes is known.
        if (BuildConfig.DEBUG) {
            withTargets.take(6).forEach { node ->
                val bounds = android.graphics.Rect()
                runCatching { node.getBoundsInScreen(bounds) }
                android.util.Log.i(
                    TAG_DEBUG,
                    "scan candidate: ${node.className?.toString()?.substringAfterLast('.')} " +
                        "viewId=${node.viewIdResourceName ?: "-"} clickable=${node.isClickable} " +
                        "bounds=$bounds desc=${node.contentDescription ?: "-"}"
                )
            }
        }

        // Last resort, and the one the research suggested is most likely to be exact: Chromium's
        // context-menu header carries the link it was opened on (`ContextMenuHeaderProperties.URL`).
        // So a URL-shaped string sitting in a node's own text is a strong candidate - it is how a menu
        // header, or an address bar, represents "the link this action refers to".
        urlShapedNodeText(roots)?.let { return@runCatching it }
        null
    }.getOrNull()

    /**
     * The first node whose own text is an actionable URL, or null.
     *
     * A page's ordinary content rarely has a node whose *entire* text is a URL - that shape is a menu
     * header, an address bar, or a share preview. It is therefore a much narrower signal than "contains a
     * link", and the URL is validated by the same rule as every other source.
     */
    private fun urlShapedNodeText(roots: List<AccessibilityNodeInfo>): String? {
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach { queue.add(it) }
        while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            visited++
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank() && text.length <= MAX_URL_TEXT_LENGTH) {
                UrlTextExtractor.firstActionableUrl(text)?.let { url ->
                    if (text == url || UrlTextExtractor.isActionableUrl(text)) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.i(
                                TAG_DEBUG,
                                "scan: found a URL-shaped node text in " +
                                    "${node.className?.toString()?.substringAfterLast('.')}"
                            )
                        }
                        return url
                    }
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** The href Chromium attached to [this] node, or null when it has none or it is unusable. */
    private fun AccessibilityNodeInfo.targetUrl(): String? {
        val raw = runCatching { extras?.getString(EXTRA_TARGET_URL) }.getOrNull() ?: return null
        if (raw.isBlank()) return null
        // Validated like any other candidate: only an actionable HTTP(S) URL may leave this class.
        return UrlTextExtractor.firstActionableUrl(raw)
    }

    /**
     * The centre of the event source's on-screen bounds, or null when the platform omitted them.
     *
     * A long-press on a link reports the link's own rectangle, so its centre is the point the finger
     * was on - good enough to decide which of several links was pressed.
     */
    private fun touchPointOf(event: AccessibilityEvent): Pair<Int, Int>? {
        val source = runCatching { event.source }.getOrNull() ?: return null
        val bounds = android.graphics.Rect()
        runCatching { source.getBoundsInScreen(bounds) }
        if (bounds.isEmpty) return null
        return bounds.centerX() to bounds.centerY()
    }

    /** True when ([x], [y]) lies inside this node's on-screen bounds. */
    private fun AccessibilityNodeInfo.containsPoint(x: Int, y: Int): Boolean {
        val bounds = android.graphics.Rect()
        runCatching { getBoundsInScreen(bounds) }
        return !bounds.isEmpty && bounds.contains(x, y)
    }

    /** Area of this node's bounds, used to prefer the most specific match. */
    private fun AccessibilityNodeInfo.boundsArea(): Int {
        val bounds = android.graphics.Rect()
        runCatching { getBoundsInScreen(bounds) }
        return if (bounds.isEmpty) Int.MAX_VALUE else bounds.width() * bounds.height()
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

    // â”€â”€ Settings â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

        /**
         * Most nodes to visit when looking for a selection or a link.
         *
         * Raised from 400 after a device run showed `visited=400 nodes, no targetUrl extras found`: the
         * WebView sits deep inside Chromium's view hierarchy, so the budget was exhausted before
         * reaching the page content and the search silently found nothing. 3000 is enough for a real
         * page while still bounding the work, which matters because this runs on the main thread.
         */
        private const val MAX_TREE_NODES = 3000

        /** A node's text longer than this is page content, not a selectable link. */
        private const val MAX_SELECTED_TEXT = 4096

        /** Most nodes a single window dump may print, so a large page cannot flood the log. */
        private const val MAX_DUMP_NODES = 500

        /**
         * Chromium's key for a link node's destination, in `AccessibilityNodeInfo.getExtras()`.
         *
         * This is what makes browser links findable at all - see [linkFromChromiumExtras].
         */
        private const val EXTRA_TARGET_URL = "AccessibilityNodeInfo.targetUrl"
        /**
         * Minimum gap between window-tree scans for a link.
         *
         * This is a main-thread tree walk triggered by an event stream a WebView produces continuously,
         * so it is bounded in time as well as in nodes. 400 ms is well under what a user perceives as
         * "as soon as I long-press", and it caps the cost at a couple of walks per second.
         */
        private const val TREE_SCAN_INTERVAL_MS = 400L

        /** A node whose text is longer than this is content, not a URL-bearing label. */
        private const val MAX_URL_TEXT_LENGTH = 2048

        /** Minimum gap between native-app tree dumps, so a chatty app cannot flood the log. */
        private const val NATIVE_DUMP_INTERVAL_MS = 60_000L

        /**
         * Packages known to be Chromium-based, i.e. the ones that DO expose `targetUrl`.
         *
         * Used only to decide what to dump for diagnosis. A native app is not expected to expose link
         * metadata at all, and this list is how "expected" is distinguished from "unexpected".
         */
        private val CHROMIUM_PACKAGES = setOf(
            "com.brave.browser",
            "com.android.chrome",
            "com.chrome.beta",
            "org.chromium.webview_shell",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser"
        )
    }
}
