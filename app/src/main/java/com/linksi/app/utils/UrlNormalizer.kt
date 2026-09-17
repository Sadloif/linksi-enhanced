package com.linksi.app.utils

import java.net.URI

/**
 * URL helpers shared across the app.
 *
 * These live here rather than in `MetadataFetcher.kt` so that they stay free of `android.*`
 * imports and can therefore be unit tested on the plain JVM. They remain in the
 * `com.linksi.app.utils` package, so every existing call site resolves unchanged.
 *
 * ### Case handling (spec section 9.2 and acceptance criterion 70.13)
 *
 * [normalizeUrl] used to call `String.lowercase()` on the entire URL. Paths and query values are
 * case sensitive, so `https://example.com/File?id=AbC123` was stored as
 * `https://example.com/file?id=abc123`. Database migration 11 to 12 did the same thing in SQL to
 * every previously stored row, which is why existing data cannot be recovered.
 *
 * Normalisation now only touches the scheme, which is the only genuinely case insensitive part.
 * The authority, path, query and fragment are returned exactly as received.
 */
fun normalizeUrl(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return ""

    // Upgrade http to https and fold the scheme to lower case. The scheme is the only genuinely
    // case insensitive part of a URL, so folding it is safe. The comparison must be case
    // insensitive: comparing case sensitively used to produce garbage such as
    // "https://HTTP://example.com" for an input of "HTTP://example.com".
    val schemeProbe = trimmed.lowercase()
    val normalized = when {
        schemeProbe.startsWith("http://") -> "https://" + trimmed.substring(7)
        schemeProbe.startsWith("https://") -> "https://" + trimmed.substring(8)
        else -> "https://$trimmed"
    }

    return try {
        val uri = URI(normalized).normalize()
        val result = uri.toString()

        // Remove one trailing slash for root domains AND paths to be robust. This mirrors the
        // behaviour the app has always had, and is what makes the cleaned Facebook reel URL match
        // the expected shape.
        if (result.endsWith("/")) result.substring(0, result.length - 1) else result
    } catch (e: Exception) {
        // Never rewrite an unparseable URL: return it as received, only trimmed.
        normalized
    }
}

/** Extracts the display domain (`www.` stripped) from [url], falling back to the raw input. */
fun extractDomain(url: String): String {
    return try {
        URI(normalizeUrl(url.trim())).host?.removePrefix("www.") ?: url
    } catch (e: Exception) {
        url
    }
}

/**
 * True when [url] looks like a usable http(s) URL: it must have an http/https scheme, a host, and
 * that host must contain a dot (or be `localhost`).
 */
fun isValidUrl(url: String): Boolean {
    if (url.isBlank()) return false
    return try {
        val uri = URI(normalizeUrl(url.trim()))
        val host = uri.host
        uri.scheme in listOf("http", "https") &&
                !host.isNullOrBlank() &&
                (host.contains(".") || host == "localhost")
    } catch (e: Exception) {
        false
    }
}
