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
     */
    fun detect(input: CopyEventInput, ignoredPackages: Collection<String> = emptyList()): CopyDetection {
        if (input.isPassword || input.isSensitiveField) return CopyDetection.PASSWORD_FIELD
        if (isIgnoredPackage(input.packageName, ignoredPackages)) return CopyDetection.IGNORED_PACKAGE

        val copySignal = hasCopySignal(input) || looksLikeSelectionToolbarAction(input)
        if (!copySignal) return CopyDetection.NO_COPY_SIGNAL

        val source = urlSource(input) ?: return CopyDetection.NO_URL
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

    /** True when the node's own label or text carries an explicit copy or link word. */
    private fun hasCopySignal(input: CopyEventInput): Boolean {
        val text = input.text?.lowercase().orEmpty()
        val description = input.contentDescription?.lowercase().orEmpty()
        if (matchesExplicitCopyLabel(text) || matchesExplicitCopyLabel(description)) return true
        return COPY_SIGNAL_WORDS.any { it in text || it in description }
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
        UrlTextExtractor.firstHttpUrl(this)?.takeIf { UrlTextExtractor.isActionableUrl(it) }

    /** Selections longer than this are treated as a document, not as "the user copied this link". */
    private const val MAX_SELECTION_LENGTH = 4096
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
