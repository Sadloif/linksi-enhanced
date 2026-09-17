package com.linksi.app.utils

/**
 * Deterministic, dependency-free URL cleaner for Linksi Enhanced.
 *
 * Design rules (see LINKSI_ENHANCED_REVISED_SPEC.md section 9):
 *
 *  1. Never lowercase the whole URL. Only the scheme is case folded. The authority,
 *     path, query values and fragment are preserved byte for byte, including percent
 *     encoding and mixed case.
 *  2. Only *known* tracking parameters are removed. Unknown parameters are kept even
 *     when they look unnecessary.
 *  3. Parsing failures never mutate the input. The caller receives [UrlCleaner.Result.Invalid]
 *     and can fall back to [cleanOrSelf] so that a cleaner failure can never lose a link.
 *  4. No `android.*` imports, so the whole component is unit testable on the plain JVM.
 *
 * The cleaner deliberately does NOT:
 *  - upgrade `http` to `https` (that is [normalizeUrl]'s existing job),
 *  - add a missing scheme (an input without `http://`/`https://` is reported invalid),
 *  - remove fragments (they are preserved unless [UrlCleaner.Options.removeFragment] is set),
 *  - guess at unknown parameters.
 */
object UrlCleaner {

    /** Tracking parameters that are removed regardless of the host. Must stay centralized. */
    val TRACKING_PARAMETERS: Set<String> = setOf(
        // Campaign parameters
        "utm_source",
        "utm_medium",
        "utm_campaign",
        "utm_term",
        "utm_content",
        "utm_id",
        // Ad click identifiers
        "fbclid",
        "gclid",
        "dclid",
        "msclkid",
        "yclid",
        // Social / mail tracking
        "igshid",
        "mc_cid",
        "mc_eid",
        // Google Analytics cross domain
        "_ga",
        "_gl"
    )

    /** Whole families of tracking parameters, matched by prefix against the (case folded) key. */
    val TRACKING_PARAMETER_PREFIXES: Set<String> = setOf("utm_")

    /**
     * Facebook-only parameters that are not required to identify the content.
     * Applied only when the URL host is Facebook, because these names are generic
     * enough that other sites may use them meaningfully.
     */
    val FACEBOOK_TRACKING_PARAMETERS: Set<String> = setOf(
        "referral_source",
        "surface_type",
        "in_reels_tab_context"
    )

    /** Hosts that the Facebook specific rules apply to (compared case insensitively). */
    private val FACEBOOK_HOSTS: Set<String> = setOf("facebook.com", "fb.watch", "fb.com", "fb.gg")

    /**
     * @param removeTrackingParameters remove known tracking parameters (spec 9.3 / 9.4).
     * @param removeEmptyParameters    also drop `key=` parameters that carry no value. Off by
     *                                 default: an empty value can be meaningful on some sites.
     * @param removeFragment           drop the `#fragment`. Off by default because fragments are
     *                                 frequently used by single page applications to identify content.
     */
    data class Options(
        val removeTrackingParameters: Boolean = true,
        val removeEmptyParameters: Boolean = false,
        val removeFragment: Boolean = false
    )

    /** Why a URL could not be cleaned. The original string is never modified in these cases. */
    enum class Reason {
        BLANK,
        MISSING_SCHEME,
        UNSUPPORTED_SCHEME,
        MISSING_HOST,
        MALFORMED
    }

    sealed interface Result {

        /**
         * The URL was parsed. [cleanedUrl] equals [originalUrl] when nothing needed removing
         * (apart from the always applied one-character normalisations listed on [UrlCleaner]).
         */
        data class Cleaned(
            val originalUrl: String,
            val cleanedUrl: String,
            val removedParameters: List<String>
        ) : Result {
            val changed: Boolean get() = cleanedUrl != originalUrl
        }

        /** The URL was rejected. The caller must keep [originalUrl] untouched. */
        data class Invalid(
            val originalUrl: String,
            val reason: Reason
        ) : Result
    }

    /**
     * Clean [url]. Never throws and never mutates the input string.
     *
     * Safe normalisations that are always applied, mirroring the behaviour the app already
     * has in [normalizeUrl] and the Facebook example required by the specification:
     *  - surrounding whitespace is trimmed,
     *  - the scheme is case folded (`HTTPS://` becomes `https://`),
     *  - one trailing `/` is removed from the path (`/reel/123/` becomes `/reel/123`),
     *  - empty query segments are dropped (`?a=1&&b=2&` becomes `?a=1&b=2`),
     *  - a `?` with no remaining parameters is dropped.
     */
    fun clean(url: String, options: Options = Options()): Result {
        val original = url
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return Result.Invalid(original, Reason.BLANK)

        // ── scheme ────────────────────────────────────────────────────────────────
        val schemeSeparator = trimmed.indexOf("://")
        if (schemeSeparator <= 0) {
            // Distinguish "a real scheme we do not support" (mailto:, ftp:, javascript:) from
            // "no scheme at all" (example.com/path), because the two need different messages.
            if (!hasExplicitScheme(trimmed)) return Result.Invalid(original, Reason.MISSING_SCHEME)
            val scheme = trimmed.substringBefore(':').lowercase()
            return if (scheme == "http" || scheme == "https") {
                // e.g. "http:/example.com" - a supported scheme with a broken separator.
                Result.Invalid(original, Reason.MALFORMED)
            } else {
                Result.Invalid(original, Reason.UNSUPPORTED_SCHEME)
            }
        }

        val rawScheme = trimmed.substring(0, schemeSeparator)
        if (rawScheme.isEmpty() || !rawScheme[0].isLetter() || !rawScheme.all(::isSchemeCharacter)) {
            return Result.Invalid(original, Reason.MISSING_SCHEME)
        }

        val scheme = rawScheme.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.Invalid(original, Reason.UNSUPPORTED_SCHEME)
        }

        // ── split into authority / path / query / fragment, preserving raw text ───
        var remainder = trimmed.substring(schemeSeparator + 3)

        var fragment: String? = null
        val hashIndex = remainder.indexOf('#')
        if (hashIndex >= 0) {
            fragment = remainder.substring(hashIndex + 1)
            remainder = remainder.substring(0, hashIndex)
        }

        var query: String? = null
        val queryIndex = remainder.indexOf('?')
        if (queryIndex >= 0) {
            query = remainder.substring(queryIndex + 1)
            remainder = remainder.substring(0, queryIndex)
        }

        val slashIndex = remainder.indexOf('/')
        val authority = if (slashIndex >= 0) remainder.substring(0, slashIndex) else remainder
        var path = if (slashIndex >= 0) remainder.substring(slashIndex) else ""

        // ── validation (never destructive: we bail out instead of rewriting) ──────
        if (authority.isEmpty()) return Result.Invalid(original, Reason.MISSING_HOST)
        if (authority.any { it.isWhitespace() || it.isISOControl() }) {
            return Result.Invalid(original, Reason.MALFORMED)
        }

        val host = hostOf(authority)
        if (host.isEmpty()) return Result.Invalid(original, Reason.MISSING_HOST)

        val isLocalhost = host.equals("localhost", ignoreCase = true)
        if (!isLocalhost && !host.contains('.') && !host.contains(':')) {
            // Same acceptance rule the app already applies in isValidUrl().
            return Result.Invalid(original, Reason.MALFORMED)
        }

        // ── query parameter filtering ─────────────────────────────────────────────
        val removed = mutableListOf<String>()
        var cleanedQuery: String? = query
        if (query != null) {
            val kept = mutableListOf<String>()
            val facebook = isFacebookHost(host)
            for (segment in query.split('&')) {
                if (segment.isEmpty()) continue // drop empty segments such as "&&" or a trailing "&"

                val key = segment.substringBefore('=')
                val hasValue = segment.contains('=')
                val value = if (hasValue) segment.substringAfter('=') else null
                val matchKey = decodePercent(key).lowercase()

                val drop = (options.removeTrackingParameters && isTrackingParameter(matchKey, facebook)) ||
                    (options.removeEmptyParameters && hasValue && value!!.isEmpty())

                if (drop) {
                    removed += matchKey
                } else {
                    kept += segment
                }
            }
            cleanedQuery = if (kept.isEmpty()) null else kept.joinToString("&")
        }

        // ── reassemble, preserving everything else exactly as received ────────────
        if (path.endsWith("/")) path = path.dropLast(1)

        val builder = StringBuilder(trimmed.length)
        builder.append(scheme).append("://").append(authority).append(path)
        if (cleanedQuery != null) builder.append('?').append(cleanedQuery)
        if (fragment != null && !options.removeFragment) builder.append('#').append(fragment)

        return Result.Cleaned(original, builder.toString(), removed)
    }

    /** Clean [url], returning it unchanged when it cannot be parsed. Failure safe by design. */
    fun cleanOrSelf(url: String, options: Options = Options()): String =
        when (val result = clean(url, options)) {
            is Result.Cleaned -> result.cleanedUrl
            is Result.Invalid -> result.originalUrl
        }

    /** Clean [url], returning `null` when it cannot be parsed. */
    fun cleanOrNull(url: String, options: Options = Options()): String? =
        (clean(url, options) as? Result.Cleaned)?.cleanedUrl

    /** True when [key] (already percent decoded and case folded) is a known tracking parameter. */
    fun isTrackingParameter(key: String, isFacebookHost: Boolean = false): Boolean {
        if (key.isEmpty()) return false
        if (key in TRACKING_PARAMETERS) return true
        if (TRACKING_PARAMETER_PREFIXES.any { key.startsWith(it) }) return true
        return isFacebookHost && key in FACEBOOK_TRACKING_PARAMETERS
    }

    /** True when [host] belongs to Facebook, ignoring case and a leading `www.`. */
    fun isFacebookHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized in FACEBOOK_HOSTS) return true
        return FACEBOOK_HOSTS.any { normalized.endsWith(".$it") }
    }

    /** True when the character is legal inside a URL scheme. */
    private fun isSchemeCharacter(c: Char): Boolean =
        c.isLetterOrDigit() || c == '+' || c == '-' || c == '.'

    /**
     * True when [value] starts with a plausible `scheme:` prefix even without the `//`.
     *
     * Digits and dots are deliberately excluded from the prefix test so that a schemeless
     * `host.example:8080/path` is still reported as [Reason.MISSING_SCHEME] rather than being
     * mistaken for an unsupported scheme.
     */
    private fun hasExplicitScheme(value: String): Boolean {
        val colon = value.indexOf(':')
        if (colon <= 0) return false
        val prefix = value.substring(0, colon)
        if (!prefix[0].isLetter()) return false
        return prefix.all { it.isLetter() || it == '+' || it == '-' }
    }

    /** Extracts the host from an authority, dropping any user info and port. */
    private fun hostOf(authority: String): String {
        val withoutUserInfo = authority.substringAfterLast('@')
        if (withoutUserInfo.startsWith("[")) {
            val end = withoutUserInfo.indexOf(']')
            return if (end > 0) withoutUserInfo.substring(0, end + 1) else withoutUserInfo
        }
        return withoutUserInfo.substringBefore(':')
    }

    /**
     * Minimal percent decoder used only to compare parameter names. Unknown or malformed
     * escapes are returned verbatim, which makes the comparison fail closed (parameter kept).
     */
    private fun decodePercent(value: String): String {
        if (!value.contains('%')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
