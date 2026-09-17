package com.linksi.app.enhanced.download

/**
 * Safe file naming for downloads (testing plan section 48).
 *
 * Produces names that are valid on Android and pleasant to look at: no path separators, no
 * control characters, no accidental overwrite, and an extension that matches the chosen format.
 * Pure JVM, unit testable.
 */
object FilenameSanitizer {

    const val DEFAULT_BASE = "linksi-download"

    private val ILLEGAL_CHARS = charArrayOf(
        '/', '\\', ':', '*', '?', '"', '<', '>', '|',
        '\u0000', '\n', '\r', '\t'
    )

    /** Names that are problematic on FAT/exFAT volumes some phones still mount. */
    private val RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    private val EXTENSION_BY_MIME: Map<String, String> = mapOf(
        "video/mp4" to "mp4",
        "video/quicktime" to "mov",
        "video/webm" to "webm",
        "video/x-matroska" to "mkv",
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/aac" to "aac",
        "audio/ogg" to "ogg",
        "audio/opus" to "opus",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/flac" to "flac",
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/avif" to "avif",
        "image/svg+xml" to "svg",
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "text/plain" to "txt",
        "text/csv" to "csv",
        "application/json" to "json",
        "application/epub+zip" to "epub"
    )

    /**
     * Sanitises [rawName] (a *base* name, without extension) and appends [extension].
     *
     * Guarantees: never blank, never contains an illegal character, never ends in a dot or space,
     * never longer than [maxLength], and never a reserved device name.
     */
    fun sanitize(
        rawName: String?,
        extension: String?,
        fallbackBase: String = DEFAULT_BASE,
        maxLength: Int = 120
    ): String {
        val cleanExtension = extension
            ?.trim()
            ?.trimStart('.')
            ?.lowercase()
            ?.filter { it.isLetterOrDigit() }
            ?.take(8)
            ?.takeIf { it.isNotEmpty() }

        var base = rawName.orEmpty()

        // If the caller passed a name that already carries the extension, do not double it.
        if (cleanExtension != null && base.lowercase().endsWith(".$cleanExtension")) {
            base = base.dropLast(cleanExtension.length + 1)
        }

        // Whitespace is collapsed *before* the illegal characters are replaced, so that a name
        // containing a tab or a newline collapses to a single space instead of gaining an
        // underscore: "my \t clip" must become "my clip", not "my _ clip".
        base = base
            .replace(Regex("\\s+"), " ")
            .trim()
            .map { if (it in ILLEGAL_CHARS) '_' else it }
            .joinToString("")
            .trim('.', ' ', '_')

        if (base.isEmpty()) base = fallbackBase

        if (base.uppercase() in RESERVED_NAMES) base = "_$base"

        val reservedForExtension = if (cleanExtension == null) 0 else cleanExtension.length + 1
        val maxBaseLength = (maxLength - reservedForExtension).coerceAtLeast(1)
        if (base.length > maxBaseLength) {
            base = base.substring(0, maxBaseLength).trimEnd('.', ' ')
            if (base.isEmpty()) base = fallbackBase.take(maxBaseLength)
        }

        return if (cleanExtension == null) base else "$base.$cleanExtension"
    }

    /**
     * Returns [desired] when it is free, otherwise inserts a counter before the extension:
     * `clip.mp4`, `clip (1).mp4`, `clip (2).mp4`. Comparison is case sensitive, matching
     * the file systems Android actually uses.
     */
    fun ensureUnique(desired: String, existing: Set<String>, maxAttempts: Int = 999): String {
        if (desired !in existing) return desired

        val extension = desired.substringAfterLast('.', missingDelimiterValue = "")
        val hasExtension = extension.isNotEmpty() && extension.length <= 8 && desired.contains('.')
        val base = if (hasExtension) desired.dropLast(extension.length + 1) else desired
        val suffix = if (hasExtension) ".$extension" else ""

        for (index in 1..maxAttempts) {
            val candidate = "$base ($index)$suffix"
            if (candidate !in existing) return candidate
        }
        // Extremely unlikely: fall back to a timestamp-free counter beyond the attempt limit.
        return "$base (${maxAttempts + 1})$suffix"
    }

    /** Maps a Content-Type to a file extension, ignoring parameters such as `; charset=utf-8`. */
    fun extensionForMimeType(mimeType: String?): String? {
        val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        if (normalized.isEmpty()) return null
        EXTENSION_BY_MIME[normalized]?.let { return it }
        // Fall back to the subtype when it looks like a usable extension (for example video/x-flv).
        val subtype = normalized.substringAfter('/', "")
        return subtype
            .filter { it.isLetterOrDigit() }
            .take(8)
            .takeIf { it.isNotEmpty() && normalized.contains('/') }
    }

    /** True when [name] would survive [sanitize] unchanged. */
    fun isSafe(name: String): Boolean =
        name.isNotEmpty() &&
            name.none { it in ILLEGAL_CHARS } &&
            name == name.trim() &&
            !name.startsWith(".") &&
            !name.endsWith(".") &&
            name.uppercase().substringBefore('.') !in RESERVED_NAMES
}
