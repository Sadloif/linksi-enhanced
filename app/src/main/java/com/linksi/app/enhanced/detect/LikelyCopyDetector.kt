package com.linksi.app.enhanced.detect

/**
 * Decides whether an accessibility event looks like a "copy link" action (specification sections
 * 11.2, 11.3 and 57).
 *
 * This class is deliberately **pure**: every input is injected, there is no `android.*` import and
 * no reference to `AccessibilityNodeInfo`. The service layer is responsible for the one cheap read
 * of the event and the node's properties, then hands the values here. That keeps the whole
 * heuristic unit testable on the plain JVM and keeps the accessibility callback O(1) and
 * allocation-light (specification sections 60 and 61).
 *
 * The detector never receives, returns, logs or stores anything except a verdict. The matched URL
 * is handed back only so the caller can act on it, and only when it is an actionable HTTP(S) URL;
 * everything else is discarded at this boundary.
 */
object LikelyCopyDetector {

    // ── Copy-related signals ──────────────────────────────────────────────────

    /** Words that suggest the control the user touched was about copying or linking. */
    private val COPY_SIGNAL_WORDS = listOf("copy", "link")

    /**
     * Labels that are treated as a copy signal on their own, in a lower-case form.
     *
     * These are the labels Android's own text selection toolbar uses, and they are the single most
     * common way a "copy link" reaches the clipboard. They are listed explicitly so a future
     * translation or substring change does not silently disable detection.
     */
    private val EXPLICIT_COPY_LABELS = setOf(
        "copy", "copied", "copy link", "copy link address", "copy url",
        "copy address", "copy to clipboard", "copy to clipboard url",
        "copy link location", "share link", "copy shortcut"
    )

    /**
     * Decides whether [input] is a likely copy action.
     *
     * Order of the checks matters only for the reason that is reported; every rejection is equally
     * final and equally privacy-preserving. [ignoredPackages] entries match either exactly
     * (`com.example.bank`) or as a package prefix (`com.example.` excludes every `com.example.*`
     * package); an empty list disables only that rule (section 11.3.10).
     *
     * [recentSelection] is the URL from a very recent text selection, and it is what makes the most
     * common real copy work. When a user selects a link and taps "Copy" in the selection toolbar, the
     * platform delivers two separate events: a selection change carrying the link, then a click on the
     * "Copy" button carrying **no URL at all**. Judged alone the click has a copy label but nothing to
     * copy, so it was rejected - which is why copying in a browser or a chat app did nothing while
     * pasting inside Linksi (a different, URL-carrying path) worked.
     */
    fun detect(
        input: CopyEventInput,
        ignoredPackages: Collection<String> = emptyList(),
        recentSelection: RecentSelection? = null
    ): CopyDetection {
        if (input.isPassword || input.isSensitiveField) return CopyDetection.PASSWORD_FIELD
        if (isIgnoredPackage(input.packageName, ignoredPackages)) return CopyDetection.IGNORED_PACKAGE

        val copySignal = hasCopySignal(input) || looksLikeSelectionToolbarAction(input)
        if (!copySignal) return CopyDetection.NO_COPY_SIGNAL

        // Prefer what the event itself carries; fall back to the selection the Copy button refers to.
        val source = urlSource(input)
            ?: recentSelection?.takeIf { it.isFresh() }?.url
            ?: return CopyDetection.NO_URL
        val url = source.firstActionableUrl() ?: return CopyDetection.NO_URL

        return CopyDetection.Detected(
            url = url,
            textWasUrl = source.trim() == url
        )
    }

    /**
     * True when the ignore list excludes [packageName].
     *
     * Matching is done on package boundaries so that ignoring `com.bank.app` does not accidentally
     * also ignore `com.bank.application.other`, while ignoring `com.bank.` (with the trailing dot)
     * does exclude the whole family. A blank package name is never ignorable - the ignore list must
     * not be able to silently disable all detection.
     */
    fun isIgnoredPackage(packageName: String?, ignoredPackages: Collection<String>): Boolean {
        val candidate = packageName?.trim().orEmpty()
        if (candidate.isEmpty()) return false
        for (rawEntry in ignoredPackages) {
            val entry = rawEntry.trim()
            if (entry.isEmpty()) continue
            if (candidate.equals(entry, ignoreCase = true)) return true
            if (entry.endsWith(".") && candidate.startsWith(entry, ignoreCase = true)) return true
            if (candidate.startsWith(entry, ignoreCase = true) &&
                candidate.length > entry.length &&
                candidate[entry.length] == '.'
            ) {
                return true
            }
        }
        return false
    }

    /** Parses the newline/comma separated value stored under `A11Y_IGNORED_PACKAGES`. */
    fun parseIgnoreList(raw: String?): List<String> =
        raw.orEmpty()
            .split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** Serialises the ignore list back into the stored form. */
    fun serializeIgnoreList(packages: Collection<String>): String =
        packages.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")

    /** True when the [CopyEventType.VIEW_TEXT_SELECTION_CHANGED] burst looks like a copy action. */
    private fun looksLikeSelectionToolbarAction(input: CopyEventInput): Boolean {
        if (input.eventType != CopyEventType.VIEW_TEXT_SELECTION_CHANGED) return false
        // A selection change alone is not enough: the user may simply be highlighting text. The
        // text itself must look like a URL, which is also what makes the false positive rate low.
        return UrlTextExtractor.isActionableUrl(input.text) &&
            input.text != null &&
            input.text.length <= MAX_SELECTION_LENGTH
    }

    /**
     * True when a click landed on a copy-ish **action** while the event also carries a URL.
     *
     * This is the signal that was missing in the field. On a real device the common external copy is
     * the browser's "Copy link address", and browsers vary in what they expose to accessibility for
     * that menu item: the node's own text is often the *link* rather than the words "Copy link", so
     * requiring a copy label on the node's text - which the original rule did - rejected the most
     * common real copy, on a device where the in-app paste path worked fine.
     *
     * Narrow in three places, each of which a test pins:
     *
     *  - the event must be a **click**;
     *  - the **control name** - the content description, or a short text - must carry a copy word, so
     *    a click on a link, and typing a URL into a search box, are still ignored;
     *  - and an actionable URL must be present **somewhere in the event**, which is where the link
     *    itself lives when the browser names the menu item after it.
     *
     * To insist that the URL be "linked", not merely a bare host, an actionable URL must be
     * *present*; [CopyDetection.Detected] is only returned when one can be extracted.
     */
    private fun clickLabelledAsACopyAction(input: CopyEventInput): Boolean {
        if (input.eventType != CopyEventType.VIEW_CLICKED) return false
        if (!hasExplicitCopyWord(input.contentDescription) && !hasExplicitCopyWord(input.text)) return false
        return urlSource(input) != null
    }

    /**
     * True when [value] names a copy-ish action.
     *
     * Bounded by [MAX_COPY_LABEL_LENGTH] so a paragraph that merely contains the word "link" is prose
     * rather than a control name, which is what stops odd prose from arming the bubble.
     */
    private fun hasExplicitCopyWord(value: String?): Boolean {
        val trimmed = value?.trim()?.lowercase() ?: return false
        if (trimmed.isEmpty() || trimmed.length > MAX_COPY_LABEL_LENGTH) return false
        if (matchesExplicitCopyLabel(trimmed)) return true
        return COPY_SIGNAL_WORDS.any { it in trimmed }
    }

    /**
     * True when the node carries an explicit copy signal.
     *
     * An exact copy label is unambiguous whichever field carries it - "copy link address" is a control
     * name, never page copy - so that case is accepted directly.
     *
     * Everything weaker than that can appear in ordinary page content, so it only counts on a
     * **click**. Without that restriction a content-changed or window-state-changed event carrying a
     * URL would arm the bubble simply because the page happened to mention "copy" or "link" somewhere:
     * navigation is not a copy. This is what `theNewSignalAppliesOnlyToClicks` pins.
     */
    private fun hasCopySignal(input: CopyEventInput): Boolean {
        val description = input.contentDescription?.lowercase().orEmpty()
        val text = input.text?.lowercase().orEmpty()
        if (matchesExplicitCopyLabel(description) || matchesExplicitCopyLabel(text)) return true

        if (input.eventType != CopyEventType.VIEW_CLICKED) return false

        return COPY_SIGNAL_WORDS.any { it in description } || clickLabelledAsACopyAction(input)
    }

    private fun matchesExplicitCopyLabel(value: String): Boolean {
        if (value.isEmpty()) return false
        val trimmed = value.trim()
        if (trimmed in EXPLICIT_COPY_LABELS) return true
        return EXPLICIT_COPY_LABELS.any { it.contains(' ') && it in trimmed }
    }

    /**
     * The text to search for a URL: the node's text first, then its content description.
     *
     * Content descriptions are only consulted when the text holds nothing, so a node whose
     * description is unrelated prose cannot shadow a genuine URL in its text.
     */
    private fun urlSource(input: CopyEventInput): String? {
        val text = input.text?.takeIf { it.isNotBlank() }
        if (text != null && UrlTextExtractor.containsHttpUrl(text)) return text
        val description = input.contentDescription?.takeIf { it.isNotBlank() }
        if (description != null && UrlTextExtractor.containsHttpUrl(description)) return description
        return null
    }

    private fun String.firstActionableUrl(): String? =
        UrlTextExtractor.firstActionableUrl(this)

    /** Selections longer than this are treated as a document, not as "the user copied this link". */
    private const val MAX_SELECTION_LENGTH = 4096

    /**
     * A node label longer than this is prose, not a control name.
     *
     * Keeps [hasExplicitCopyWord] from accepting a paragraph that happens to contain the word "link".
     */
    private const val MAX_COPY_LABEL_LENGTH = 120

    /**
     * [detect], plus the reason for its verdict.
     *
     * Kept as a separate entry point so the phone-hot [detect] path is unchanged and the extra value is
     * only produced where someone is actually going to look at it. Returns the same [CopyDetection] the
     * other overload would, so the two can never disagree.
     */
    fun detectWithReason(
        input: CopyEventInput?,
        ignoredPackages: Collection<String> = emptyList(),
        recentSelection: RecentSelection? = null
    ): Pair<CopyDetection, CopyRejection> {
        if (input == null) return CopyDetection.NO_COPY_SIGNAL to CopyRejection.NO_TEXT

        if (input.isPassword || input.isSensitiveField) {
            return CopyDetection.PASSWORD_FIELD to CopyRejection.PASSWORD_OR_SENSITIVE
        }
        if (isIgnoredPackage(input.packageName, ignoredPackages)) {
            return CopyDetection.IGNORED_PACKAGE to CopyRejection.IGNORED_PACKAGE
        }
        val copySignal = hasCopySignal(input) || looksLikeSelectionToolbarAction(input)
        if (!copySignal) return CopyDetection.NO_COPY_SIGNAL to CopyRejection.NO_COPY_SIGNAL

        val source = urlSource(input)
            ?: recentSelection?.takeIf { it.isFresh() }?.url
            ?: return CopyDetection.NO_URL to CopyRejection.NO_URL
        val url = source.firstActionableUrl() ?: return CopyDetection.NO_URL to CopyRejection.NO_URL

        return CopyDetection.Detected(url = url, textWasUrl = source.trim() == url) to
            CopyRejection.ACCEPTED
    }
}

/**
 * The accessibility event types this module reacts to, mirrored from the platform constants so the
 * heuristic stays free of `android.*`.
 */
enum class CopyEventType {
    VIEW_CLICKED,
    VIEW_TEXT_SELECTION_CHANGED,
    VIEW_TEXT_CHANGED,
    WINDOW_CONTENT_CHANGED,
    WINDOW_STATE_CHANGED,
    OTHER
}

/**
 * Why one accessibility event was or was not accepted, as a code with no content.
 *
 * This exists so the *reason* a real copy was ignored can be shown to the user without recording the
 * copied text. Nothing here carries the event's text - only which rule decided, plus the event type and
 * package. That is what makes a field failure diagnosable on a device whose accessibility service
 * deliberately keeps no log at all.
 */
enum class CopyRejection {
    /** A likely copy of an actionable URL; a bubble was requested. */
    ACCEPTED,
    /** A password or otherwise sensitive node, refused before its text was read. */
    PASSWORD_OR_SENSITIVE,
    /** The package is on the user's ignore list. */
    IGNORED_PACKAGE,
    /** Nothing about the event suggested a copy: no "copy"/"link" label, and not a URL selection. */
    NO_COPY_SIGNAL,
    /** A copy was likely, but the event carried no actionable HTTP(S) URL. */
    NO_URL,
    /** The node had no text or label at all, so there was nothing to judge. */
    NO_TEXT,
    /** A text selection contained a link and was remembered for a following Copy tap. */
    SELECTION_REMEMBERED,
    /** A text selection happened but held no usable link, so there is nothing a Copy could refer to. */
    SELECTION_WITHOUT_A_LINK,
}

/**
 * A short, safe excerpt of an event's own label, for a debug build only.
 *
 * Deliberately narrow, because it is the one thing here that carries any text:
 *
 *  - **debug builds only**: the caller passes `BuildConfig.DEBUG`, so a release build always reports
 *    null and the invariant "a release build retains no content" continues to hold;
 *  - it is a node's **label or content description**, never `event.text` (which on a WebView is the
 *    whole page) and never anything from the clipboard;
 *  - bounded to [MAX_LABEL_SNIPPET], and a URL in it is replaced before display, so a diagnostic
 *    cannot become a record of what was copied.
 */
object LabelSnippet {

    const val MAX_LABEL_SNIPPET = 48

    /**
     * Returns a bounded, URL-free excerpt of [description] or [text], or null when there is nothing to
     * show or [enabled] is false.
     */
    fun of(enabled: Boolean, description: String?, text: String?): String? {
        if (!enabled) return null
        val raw = description?.trim()?.takeIf { it.isNotEmpty() }
            ?: text?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        // Drop anything URL-shaped rather than showing it. The label is what is being diagnosed; a URL
        // in it adds nothing and is the one thing worth not keeping.
        val withoutUrls = URL_LIKE.replace(raw, "<url>")
        return withoutUrls.take(MAX_LABEL_SNIPPET)
    }

    private val URL_LIKE = Regex("""\b(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
}

/**
 * One observed event, reduced to what is safe and useful to show: when, what kind, from which app, and
 * the verdict.
 *
 * [labelSnippet] is populated **only in a debug build** and only for an event that reached the
 * heuristic but produced no copy signal - the case where the answer is otherwise unknowable. It exists
 * because the field failure was "I copy a link and nothing happens": the verdict alone says a signal
 * was missing, but not which label the control actually carried, so there was no way to decide whether
 * the rule or the platform was at fault. It is a bounded number of characters of a node's own label,
 * never a URL and never clipboard content, and it is always empty in a release build.
 */
data class CopyObservation(
    val atMs: Long,
    val eventType: CopyEventType,
    val packageName: String?,
    val rejection: CopyRejection,
    val labelSnippet: String? = null
)

/**
 * The URL from a very recent text selection, so a following "Copy" click can be resolved.
 *
 * Holding this at all is a deliberate, bounded exception to the rule that nothing is retained. The
 * bounds are what make it acceptable:
 *
 *  - it is one URL, not a history;
 *  - it lives for [MAX_AGE_MS] milliseconds and is discarded on use, so it cannot outlive the copy it
 *    belongs to;
 *  - it is in memory only and is never written anywhere;
 *  - it holds only what the user had already selected on screen, which the platform had already
 *    handed to this service in the selection event.
 *
 * Without it, the most common copy in the world - select a link, tap Copy - is invisible, because that
 * click carries no URL.
 */
data class RecentSelection(val url: String, val atMs: Long) {

    /** True while this selection is still plausibly the subject of a copy click. */
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean = now - atMs in 0..MAX_AGE_MS

    companion object {
        /** How long a selection stays eligible. Long enough for a considered tap, short enough to expire. */
        const val MAX_AGE_MS = 5_000L
    }
}

/**
 * Everything the heuristic is allowed to know about one accessibility event.
 *
 * `isSensitiveField` is supplied by the platform layer for Android 16's `accessibilityDataSensitive`
 * views (which are normally invisible anyway); it exists so a caller that does receive such a node
 * can refuse to look at it.
 */
data class CopyEventInput(
    val eventType: CopyEventType,
    val text: String? = null,
    val contentDescription: String? = null,
    val isPassword: Boolean = false,
    val isSensitiveField: Boolean = false,
    val packageName: String? = null
)

/** The verdict. Only [Detected] carries a value, and only ever an actionable HTTP(S) URL. */
sealed interface CopyDetection {

    /** A likely copy of an actionable link. [url] is the only data that leaves this module. */
    data class Detected(val url: String, val textWasUrl: Boolean) : CopyDetection

    /** The node was a password field: never inspected, never reported (section 11.3.2). */
    data object PASSWORD_FIELD : CopyDetection

    /** The foreground package is on the user's ignore list (section 11.3.10). */
    data object IGNORED_PACKAGE : CopyDetection

    /** Nothing about the event suggested a copy action. */
    data object NO_COPY_SIGNAL : CopyDetection

    /** A copy action was likely, but no actionable HTTP(S) URL was present: discard (section 57). */
    data object NO_URL : CopyDetection
}
