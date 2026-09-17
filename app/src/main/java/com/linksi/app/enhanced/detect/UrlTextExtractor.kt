package com.linksi.app.enhanced.detect

/**
 * Pulls HTTP(S) URLs out of arbitrary shared or copied text (specification sections 10, 11 and 57).
 *
 * This is the privacy boundary: text that does not contain an HTTP(S) URL is discarded immediately
 * and is never stored, logged or uploaded. It also fixes a baseline defect - `ShareReceiverActivity`
 * currently falls back to saving the *entire* shared text when its regex finds nothing.
 *
 * Pure JVM, no Android dependency, so the behaviour is unit tested directly.
 */
object UrlTextExtractor {

    private val HTTP_URL_REGEX = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    /** Punctuation that a URL is very unlikely to end with when it appears in prose. */
    private const val ALWAYS_TRAILING = ".,;:!\"'"

    /** Bracket pairs that are only stripped when unbalanced, so `...?q=(a)` survives intact. */
    private val BRACKETS = mapOf(')' to '(', ']' to '[', '}' to '{')

    /** The first HTTP(S) URL in [text], or null. Never returns partial trailing punctuation. */
    fun firstHttpUrl(text: String?): String? = allHttpUrls(text).firstOrNull()

    /** Every HTTP(S) URL in [text], de-duplicated while preserving order. */
    fun allHttpUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val found = mutableListOf<String>()
        for (match in HTTP_URL_REGEX.findAll(text)) {
            val candidate = stripTrailingPunctuation(match.value)
            if (candidate.length > "https://".length && candidate !in found) {
                found += candidate
            }
        }
        return found
    }

    fun containsHttpUrl(text: String?): Boolean = firstHttpUrl(text) != null

    /**
     * Removes trailing punctuation that belongs to the surrounding sentence rather than the URL.
     * Closing brackets are only removed when they are unbalanced inside the candidate.
     */
    fun stripTrailingPunctuation(candidate: String): String {
        var value = candidate
        var changed = true
        while (changed && value.isNotEmpty()) {
            changed = false
            val last = value.last()

            if (ALWAYS_TRAILING.indexOf(last) >= 0) {
                value = value.dropLast(1)
                changed = true
                continue
            }

            val opener = BRACKETS[last]
            if (opener != null) {
                val open = value.count { it == opener }
                val close = value.count { it == last }
                if (close > open) {
                    value = value.dropLast(1)
                    changed = true
                }
            }
        }
        return value
    }

    /**
     * True when [text] looks like a URL the app can act on: an HTTP(S) URL that also has a host
     * containing a dot (or localhost). Used before showing the bubble so plain text never triggers
     * an action.
     */
    fun isActionableUrl(text: String?): Boolean {
        val url = firstHttpUrl(text) ?: return false
        return hasPlausibleHost(url)
    }

    private fun hasPlausibleHost(url: String): Boolean {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return false
        var authority = afterScheme.substringBefore('/')
        authority = authority.substringAfterLast('@')
        val host = if (authority.startsWith("[")) {
            authority.substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
        if (host.isEmpty()) return false
        return host.contains('.') || host.equals("localhost", ignoreCase = true)
    }
}
