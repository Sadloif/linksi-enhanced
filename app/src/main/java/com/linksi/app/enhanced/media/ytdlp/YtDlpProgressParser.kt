package com.linksi.app.enhanced.media.ytdlp

/**
 * Reads one yt-dlp progress line (pure JVM, unit tested).
 *
 * yt-dlp is asked for `--newline`, so every progress update arrives as its own line instead of
 * overwriting the previous one with a carriage return. The default shape is:
 *
 * ```
 * [download]  45.2% of   12.34MiB at    1.23MiB/s ETA 00:05
 * [download]  12.3% of ~  1.23MiB at  456.78KiB/s ETA 00:02 (frag 3/10)
 * ```
 *
 * The library's own callback already extracts the percentage, but its regular expression demands a
 * literal `ETA mm:ss` and one decimal digit, so it reports "unknown" for a fragment download, for a
 * finished stream and for anything yt-dlp words differently. Sizes and speed are not exposed at all,
 * and `bytesDownloaded`/`totalBytes` are what [com.linksi.app.enhanced.download.DownloadState]
 * actually needs. This parser is therefore the source of the numbers, and the callback's percentage
 * is only a fallback for a line this does not recognise.
 *
 * A line with no percentage - a live stream, a `[download] Destination: ...` line, any of yt-dlp's
 * chatter - yields null, which the caller reads as "no update from this line".
 */
object YtDlpProgressParser {

    /** Everything yt-dlp can read from one progress line, or null when it is not one. */
    data class Progress(
        /** Percentage complete, 0..100. */
        val percent: Float,
        /** Bytes completed, derived from [percent] and [totalBytes] when the total is known. */
        val downloadedBytes: Long?,
        /** Total size, or null when yt-dlp is still estimating it. */
        val totalBytes: Long?,
        /** Current speed in bytes per second, or null when the line does not state one. */
        val bytesPerSecond: Long?
    )

    /** Every progress line yt-dlp writes starts with this, so ordinary output never matches. */
    private const val PREFIX = "[download]"

    private val PERCENT = Regex("""(\d+(?:\.\d+)?)%""")

    /** `of 12.34MiB`, and the estimate form `of ~12.34MiB`. */
    private val TOTAL = Regex("""\bof\s+~?\s*(\d+(?:\.\d+)?)\s*([KMGT]?i?B)\b""")

    /** `at 1.23MiB/s`. */
    private val SPEED = Regex("""\bat\s+(\d+(?:\.\d+)?)\s*([KMGT]?i?B)/s""")

    fun parse(line: String?): Progress? {
        val text = line?.trim().orEmpty()
        if (!text.startsWith(PREFIX)) return null

        val percent = PERCENT.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?: return null
        if (!percent.isFinite()) return null

        val total = TOTAL.find(text)?.let { size(it.groupValues[1], it.groupValues[2]) }
        val speed = SPEED.find(text)?.let { size(it.groupValues[1], it.groupValues[2]) }
        val downloaded = total?.let { bytes -> (bytes * (percent / 100.0)).toLong() }

        return Progress(
            percent = percent,
            downloadedBytes = downloaded,
            totalBytes = total,
            bytesPerSecond = speed
        )
    }

    /**
     * `12.34` + `MiB` -> bytes.
     *
     * Both families are accepted, because yt-dlp uses binary units (`MiB`, 1024) for the sizes it
     * measures and either family for the speeds it reports. An unknown unit, a non-number or a
     * negative value is null rather than a guess.
     */
    fun size(value: String, unit: String): Long? {
        val magnitude = value.toDoubleOrNull() ?: return null
        if (!magnitude.isFinite() || magnitude < 0.0) return null

        val multiplier = when (unit.uppercase()) {
            "B" -> 1L
            "KIB" -> 1024L
            "MIB" -> 1024L * 1024L
            "GIB" -> 1024L * 1024L * 1024L
            "TIB" -> 1024L * 1024L * 1024L * 1024L
            "KB" -> 1000L
            "MB" -> 1000L * 1000L
            "GB" -> 1000L * 1000L * 1000L
            "TB" -> 1000L * 1000L * 1000L * 1000L
            else -> return null
        }

        // A Double beyond Long's range saturates rather than wrapping, which is the safe direction
        // for a progress bar: it can only ever read "as large as possible".
        return (magnitude * multiplier).toLong().takeIf { it >= 0L }
    }
}
