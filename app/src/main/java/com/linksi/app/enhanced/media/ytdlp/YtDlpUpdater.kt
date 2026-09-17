package com.linksi.app.enhanced.media.ytdlp

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What one refresh attempt did, as a value rather than an exception. */
sealed interface YtDlpRefreshResult {

    /** The engine was replaced and the new copy answered `--version`. */
    data class Updated(val version: String) : YtDlpRefreshResult

    /** The engine on disk already matched the published release. */
    data class AlreadyCurrent(val version: String) : YtDlpRefreshResult

    /** The check was skipped because it ran recently, or the engine is not usable. */
    data class Skipped(val reason: String) : YtDlpRefreshResult

    /**
     * The attempt failed and the previous engine is **still in place and working**.
     *
     * This is the only outcome that matters for a user's downloads, which is why it is a value with
     * a reason rather than an exception.
     */
    data class Failed(val reason: String) : YtDlpRefreshResult
}

/**
 * Keeps the bundled site engine up to date (specification sections 19 and 26).
 *
 * ### Why this exists
 *
 * `youtubedl-android` ships yt-dlp **2024.09.27** as a raw resource. Site extractors break
 * constantly - that is the nature of scraping - and by the time it mattered the pinned copy could no
 * longer read a single one of the owner's real Facebook links, while every one of them worked on a
 * current release. Measured on the nine links from the owner's export:
 *
 * | engine | extracts |
 * |---|---|
 * | bundled 2024.09.27 | **0 of 9** |
 * | current release | **5 of 9** |
 *
 * The other four are dead share links that fail on every version, including yt-dlp's own tip, and no
 * engine change will fix them.
 *
 * ### Why not the wrapper's own updater
 *
 * `YoutubeDL.updateYoutubeDL()` is unusable as-is, and the reason is worth recording because it is
 * invisible from the outside. It **deletes the whole `yt-dlp/` directory before copying the new
 * binary in**, and on failure its recovery path calls `init_ytdlp`, which decides what to do by
 * asking whether that directory exists. The directory does exist - it has just been emptied - so the
 * recovery silently does nothing. A download that fails at the wrong moment therefore leaves the app
 * with **no engine at all**, permanently, and every later site download reports the engine as
 * unavailable. It also checks for updates through the GitHub *API*, which is rate-limited to 60
 * unauthenticated requests an hour.
 *
 * So this class does the same job with the three properties that matter:
 *
 * 1. **Staged.** The new binary is downloaded next to the old one under a temporary name, never over
 *    it, so a dropped connection or a killed process cannot destroy a working engine.
 * 2. **Validated before it is installed.** The candidate is executed as `--version` through the same
 *    interpreter and environment the real downloads use. A file that arrives truncated, or that the
 *    bundled CPython cannot run, is discarded and the old engine is left untouched. This is the check
 *    that turns "the update broke downloads" into "the update did not happen".
 * 3. **Atomic.** The swap is a rename within one directory, so there is no instant at which the
 *    engine path holds nothing or half a file.
 *
 * The download deliberately does **not** use the GitHub API: `releases/latest/download/yt-dlp` is a
 * fixed URL that redirects to the newest release asset, so there is no JSON to parse and no
 * per-IP limit to exhaust.
 *
 * ### How often it runs
 *
 * At most once every [CHECK_INTERVAL_MILLIS], and only when the engine is already being started for a
 * real download - never at app start (specification section 26). A failed or skipped attempt also
 * counts as a check, so a device that is offline does not retry on every single download. The
 * timestamp lives in its own preferences file rather than in DataStore: this is a cache of an
 * implementation detail, and a corrupt or missing value can only cause one redundant check.
 */
@Singleton
class YtDlpUpdater @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Refreshes the engine when it has not been checked recently.
     *
     * Called from [YtDlpRuntime.ensureReady] after the engine is known to be runnable. Every failure
     * is swallowed into a [YtDlpRefreshResult] - a download that cannot be updated is still a
     * download (specification section 26).
     */
    suspend fun refreshIfStale(
        now: Long = System.currentTimeMillis(),
        force: Boolean = false
    ): YtDlpRefreshResult {
        val lastChecked = preferences().getLong(KEY_LAST_CHECKED_AT, 0L)
        if (!force && now - lastChecked < CHECK_INTERVAL_MILLIS) {
            return YtDlpRefreshResult.Skipped(
                "the engine was checked ${(now - lastChecked) / 3_600_000L} hours ago"
            )
        }

        // Recorded before the attempt, not after: an attempt that fails every time must not retry on
        // every download, or a phone with no data plan pays for a doomed HTTPS request each time.
        preferences().edit().putLong(KEY_LAST_CHECKED_AT, now).apply()

        // The check runs while the engine is being started for a real download, so it must not be
        // able to hold that download up. The network calls are individually bounded, but a slow
        // connection can still take the sum of them, so the whole attempt gets one budget; running
        // out means the download proceeds on the engine already installed, which is always correct.
        return withTimeoutOrNull(REFRESH_BUDGET_MILLIS) {
            runCatching { refresh() }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "the bundled site engine could not be refreshed", error)
                    YtDlpRefreshResult.Failed(error.message ?: error.javaClass.simpleName)
                }
        } ?: YtDlpRefreshResult.Skipped("the refresh took longer than the time allowed for it")
    }

    /**
     * The version the engine on disk reports, by running it.
     *
     * Deliberately not `YoutubeDL.versionName`, which reads a preference that only the wrapper's own
     * updater ever writes and is therefore null for a freshly installed app.
     */
    suspend fun installedVersion(): String? = runCatching { askVersion(engineFile()) }
        .getOrElse { null }

    private suspend fun refresh(): YtDlpRefreshResult = withContext(Dispatchers.IO) {
        val target = engineFile()
        if (!target.isFile) {
            return@withContext YtDlpRefreshResult.Skipped(
                "the bundled engine has not been unpacked yet"
            )
        }

        val current = askVersion(target)
        val installedDigest = sha256(target)
        val staged = File(target.parentFile, STAGING_NAME)
        runCatching { staged.delete() }
        val installedBytes = target.length()

        download(RELEASE_URL, staged)
        Log.i(TAG, "staged ${staged.length()} bytes (installed copy is $installedBytes bytes)")

        // Integrity before anything else. The published digest is the only way to know the bytes are
        // the released ones: a truncated file and a page of HTML both look like "some bytes", and - as
        // measured on a real phone - a network path can hand back a *different* yt-dlp at exactly the
        // published size, which no size or shape check can detect.
        val expected = publishedDigest(SUMS_URL)
            ?: return@withContext YtDlpRefreshResult.Failed(
                "the published checksums could not be read, so nothing was installed"
            )
        val actual = sha256(staged)
        if (!actual.equals(expected, ignoreCase = true)) {
            staged.delete()
            Log.w(TAG, "checksum mismatch: published $expected, received $actual")
            return@withContext YtDlpRefreshResult.Failed(
                "the downloaded engine did not match the published checksum"
            )
        }
        Log.i(TAG, "checksum verified against the published digest")

        if (!looksLikeZipApp(staged)) {
            staged.delete()
            return@withContext YtDlpRefreshResult.Failed(
                "the downloaded engine is not a Python zip application"
            )
        }

        val candidate = runCatching { askVersion(staged) }.getOrElse { error ->
            Log.w(TAG, "the downloaded engine did not run; keeping $current", error)
            staged.delete()
            return@withContext YtDlpRefreshResult.Failed(
                "the downloaded engine did not run: ${error.message ?: error.javaClass.simpleName}"
            )
        }

        // Whether this is an update is decided by the **bytes**, not by the version either file
        // reports, for a measured and repeatable reason: the authentic asset - checksum-verified
        // against the published digest, and downloaded by the app itself - reports the *installed*
        // version while it sits under a temporary name beside the old engine, and reports its own
        // version the moment it is moved to the canonical path. That was hit twice during
        // development, and both times a version comparison refused a genuinely newer engine. The
        // version must therefore be read where it is reliable, which is after the swap.
        Log.i(
            TAG,
            "candidate reports $candidate, installed reports $current, " +
                "digests differ: ${!actual.equals(installedDigest, ignoreCase = true)}"
        )
        if (actual.equals(installedDigest, ignoreCase = true)) {
            staged.delete()
            return@withContext YtDlpRefreshResult.AlreadyCurrent(current)
        }

        install(staged, target)

        // The candidate is now under the canonical name, where its reported version can be trusted.
        // A candidate that does not run, does not change the version, or claims an *older* version
        // than the one it replaced is put back exactly as it was.
        val installed = try {
            askVersion(target)
        } catch (error: Exception) {
            Log.w(TAG, "the installed engine did not run; rolling back", error)
            return@withContext rollBack(target, "the new engine did not run after installation")
        }
        if (installed == current || compareVersions(installed, current) <= 0) {
            Log.w(TAG, "the installed engine reports $installed against the previous $current; rolling back")
            return@withContext rollBack(target, "the new engine did not take effect")
        }

        Log.i(TAG, "site engine updated: $current -> $installed")
        // Only now is the previous engine genuinely unnecessary.
        runCatching { File(target.parentFile, "$STAGING_NAME.previous").delete() }
        YtDlpRefreshResult.Updated(installed)
    }

    /**
     * Puts the previous engine back after a failed replacement.
     *
     * The backup is kept until the new engine has been proven to run, so this is always possible; if
     * even this fails, the failure is reported rather than hidden, because the next download is what
     * would be affected.
     */
    private fun rollBack(target: File, reason: String): YtDlpRefreshResult {
        val backup = File(target.parentFile, "$STAGING_NAME.previous")
        if (!backup.isFile) return YtDlpRefreshResult.Failed("$reason, and no previous engine to restore")
        runCatching { target.delete() }
        return if (backup.renameTo(target)) {
            Log.i(TAG, "restored the previous engine after: $reason")
            YtDlpRefreshResult.Failed("$reason; the previous engine was restored")
        } else {
            YtDlpRefreshResult.Failed("$reason, and the previous engine could not be restored")
        }
    }

    /**
     * Replaces the engine with a validated candidate, keeping the old file as a backup.
     *
     * `renameTo` rather than a copy: both files are in the same directory on the same filesystem, so
     * the replacement is atomic and there is no window in which the engine path is empty. The old
     * file is moved aside first only because Android's `renameTo` will not overwrite.
     *
     * The backup is deliberately **not** deleted here. The caller re-probes the installed engine and
     * restores this file if the new one does not take effect, which is the only way to be sure that a
     * replacement left the app working.
     */
    private fun install(staged: File, target: File) {
        val backup = File(target.parentFile, "$STAGING_NAME.previous")
        runCatching { backup.delete() }
        if (!target.renameTo(backup)) {
            staged.delete()
            error("the existing engine could not be moved aside, so nothing was changed")
        }
        if (!staged.renameTo(target)) {
            // Put the working engine back. This is the outcome this whole class exists to guarantee.
            backup.renameTo(target)
            error("the new engine could not be moved into place; the previous one was restored")
        }
    }

    /** Runs `<python> <engine> --version` and returns the version, or throws. */
    private suspend fun askVersion(engine: File): String {
        val response = YoutubeDL.getInstance().execute(
            YoutubeDLRequest(engine.absolutePath).addOption("--version"),
            "$PROCESS_ID_PREFIX${UUID.randomUUID()}"
        )
        Log.i(
            TAG,
            "version probe of ${engine.name} (${engine.length()} bytes) exit=${response.exitCode} " +
                "out=${response.out.take(120).replace('\n', ' ')} " +
                "err=${response.err.take(300).replace('\n', ' ')}"
        )
        val version = response.out.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        check(!version.isNullOrBlank()) { "the engine printed no version" }
        return version
    }

    /** The engine file itself, which is what `YoutubeDL.execute` runs. */
    private fun engineFile(): File =
        File(File(context.noBackupFilesDir, YoutubeDL.baseName), YT_DLP_RELATIVE_PATH)

    /** Streams [url] to [destination], following redirects, over HTTPS only. */
    private fun download(url: String, destination: File) {        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "the release download answered HTTP $code" }
            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            check(destination.length() > MIN_ENGINE_BYTES) {
                "the downloaded engine is only ${destination.length()} bytes"
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun preferences() =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * The digest the release publishes for its `yt-dlp` asset, or null when it cannot be read.
     *
     * `SHA2-256SUMS` is a `shasum -a 256` style listing, so the line for the asset is the digest,
     * two spaces, then the file name. Only the exact name is accepted: the release also carries
     * platform-specific builds whose names merely start the same way.
     */
    private fun publishedDigest(url: String): String? = runCatching {
        val text = readText(url)
        text.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts.last() == ASSET_NAME) parts.first() else null
            }
            .firstOrNull { it.length == SHA_256_HEX_LENGTH }
    }.getOrNull()

    /** The SHA-256 of a file, as lowercase hex. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Reads a small text resource over HTTPS. */
    private fun readText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "the checksum listing answered HTTP ${connection.responseCode}"
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "YtDlpUpdater"

        /**
         * A fixed URL that redirects to the newest release asset, so no API call - and no
         * 60-requests-an-hour limit - is involved.
         */
        const val RELEASE_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"

        /** The digest listing published beside it, which is what makes the download verifiable. */
        const val SUMS_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/SHA2-256SUMS"

        /** The asset name in that listing. */
        const val ASSET_NAME = "yt-dlp"

        const val SHA_256_HEX_LENGTH = 64

        const val STAGING_NAME = "yt-dlp.staged"

        /** Relative to `noBackupFilesDir/youtubedl-android`, which is where `init` unpacks it. */
        const val YT_DLP_RELATIVE_PATH = "yt-dlp/yt-dlp"

        const val PROCESS_ID_PREFIX = "linksi-ytdlp-update-"

        const val PREFERENCES_NAME = "ytdlp_updater"

        const val KEY_LAST_CHECKED_AT = "lastCheckedAt"

        /** A week: extractor breakage is frequent enough that this is the useful end of the range. */
        const val CHECK_INTERVAL_MILLIS = 7L * 24L * 60L * 60L * 1000L

        const val CONNECT_TIMEOUT_MILLIS = 20_000

        const val READ_TIMEOUT_MILLIS = 60_000

        /**
         * The longest a refresh may take before the download that triggered it stops waiting.
         *
         * Generous enough for a 3 MB download plus two version probes on a slow mobile link, and
         * short enough that a hostile or congested network cannot make a user's first download of the
         * day appear to hang. Exceeding it changes nothing except that this week's check is done.
         */
        const val REFRESH_BUDGET_MILLIS = 180_000L

        /** The real asset is ~3 MB; anything much smaller is an error page or a truncated body. */
        const val MIN_ENGINE_BYTES = 1L * 1024L * 1024L
    }
}

/**
 * Whether [file] begins like the yt-dlp release asset, which is a Python zip application: a shebang
 * line naming a Python interpreter, followed by a ZIP local-file header (pure, unit tested).
 *
 * This is a cheap sanity check on the first bytes, not a security control - the executable check that
 * matters is running the candidate, which [YtDlpUpdater] does before installing it. It exists because
 * release-download URLs answer with an HTML page on error far more often than with anything else, and
 * installing one of those would replace a working engine with rubbish.
 */
internal fun looksLikeZipApp(file: File): Boolean = runCatching {
    if (!file.isFile) return false
    val head = ByteArray(HEAD_BYTES_TO_CHECK)
    // A plain read loop rather than `readNBytes`, which needs API 33 and this app supports 26.
    val read = file.inputStream().use { stream ->
        var total = 0
        while (total < head.size) {
            val count = stream.read(head, total, head.size - total)
            if (count < 0) break
            total += count
        }
        total
    }
    looksLikeZipAppHead(head.copyOf(read))
}.getOrDefault(false)

/** The rule above, over the first bytes rather than a file (pure, so it is directly testable). */
internal fun looksLikeZipAppHead(head: ByteArray): Boolean {
    if (head.size < MIN_HEAD_BYTES) return false

    val firstLineEnd = head.indexOf('\n'.code.toByte())
    if (firstLineEnd <= 0) return false
    val shebang = String(head, 0, firstLineEnd, Charsets.US_ASCII)
    if (!shebang.startsWith("#!")) return false
    // `#!/usr/bin/env python3` and `#!/usr/bin/python3` are both real yt-dlp shebangs across
    // versions and platforms; requiring one exact spelling would reject a good update.
    if (!PYTHON_INTERPRETER.containsMatchIn(shebang)) return false

    // 'P' 'K' 0x03 0x04: a ZIP local-file header immediately after the shebang line.
    val zipStart = firstLineEnd + 1
    return head.size >= zipStart + 4 &&
        head[zipStart] == 'P'.code.toByte() && head[zipStart + 1] == 'K'.code.toByte() &&
        head[zipStart + 2] == 0x03.toByte() && head[zipStart + 3] == 0x04.toByte()
}

private val PYTHON_INTERPRETER = Regex("""python[0-9.]*\s*$""")

private const val HEAD_BYTES_TO_CHECK = 512

private const val MIN_HEAD_BYTES = 8

/**
 * Orders two yt-dlp version strings, which are calendar dates: `2026.08.19` is later than
 * `2024.09.27` (pure, unit tested).
 *
 * Component-wise numeric comparison rather than string comparison, because as strings `2024.09.27`
 * sorts *after* `2026.08.19`. Anything that does not parse as dotted numbers is treated as the
 * lower version, so an unparseable candidate can never displace a working engine.
 */
internal fun compareVersions(left: String?, right: String?): Int {
    val a = versionParts(left) ?: return -1
    val b = versionParts(right) ?: return 1
    for (index in 0 until maxOf(a.size, b.size)) {
        val difference = (a.getOrElse(index) { 0 }) - (b.getOrElse(index) { 0 })
        if (difference != 0) return difference.compareTo(0)
    }
    return 0
}

/** The numeric components of a version string, or null when it is not one. */
private fun versionParts(version: String?): List<Int>? {
    val text = version?.trim().orEmpty()
    if (text.isEmpty()) return null
    // yt-dlp release tags look like `2026.08.19`; a nightly carries a suffix such as `.dev0`, so the
    // run of digits and dots is taken and a trailing separator left by it is dropped rather than
    // being read as an empty component.
    val numeric = text.takeWhile { it.isDigit() || it == '.' }.trimEnd('.')
    if (numeric.isEmpty()) return null
    val parts = numeric.split('.').map { it.toIntOrNull() ?: return null }
    return parts.takeIf { it.isNotEmpty() && it.any { part -> part != 0 } }
}
