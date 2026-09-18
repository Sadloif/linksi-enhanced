package com.linksi.app.enhanced.media.ytdlp

import android.content.Context
import android.util.Log
import com.linksi.app.enhanced.capability.RuntimeCapabilities
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whether the bundled site engine has been started yet, and how that went. */
sealed interface YtDlpInitStatus {

    /** Nothing has asked for the engine yet. This is the state at app start, on purpose. */
    data object NotStarted : YtDlpInitStatus

    /** The bundled Python, yt-dlp and FFmpeg are unpacked and usable. */
    data object Ready : YtDlpInitStatus

    /**
     * Initialisation was attempted and failed. Sticky for the lifetime of the process: every later
     * caller is told the same reason instead of re-running a multi-second unpack that already
     * failed, and instead of hammering a broken installation.
     */
    data class Failed(val reason: String) : YtDlpInitStatus
}

/**
 * Owns the one thing that is expensive and irreversible about the site engine: starting it.
 *
 * `YoutubeDL.getInstance().init(context)` is **not** allowed to run at app start. It unpacks an
 * entire CPython standard library (`libpython.zip.so`, ~11 MB) into `noBackupFilesDir`, copies the
 * ~3 MB yt-dlp payload out of the APK's raw resources and touches shared preferences - seconds of
 * disk work that the specification forbids any optional module from doing during startup. So the
 * work happens on the first *use*, on [Dispatchers.IO], once, behind a mutex with a double-checked
 * flag.
 *
 * Failure policy (specification section 26): an engine that cannot start disables site downloads
 * and nothing else. Every failure is recorded as [YtDlpInitStatus.Failed] and handed back as a
 * value; no exception from this class ever reaches a caller.
 *
 * [isUsable] is deliberately separate and deliberately cheap: it is called from
 * [com.linksi.app.enhanced.media.MediaExtractor.isAvailable], which is not a suspend function and
 * must not start anything, so it only reads the ABI report and stats two files.
 */
@Singleton
class YtDlpRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updater: YtDlpUpdater
) {

    private val initLock = Mutex()

    @Volatile
    private var status: YtDlpInitStatus = YtDlpInitStatus.NotStarted

    /** Memoised result of the native-library probe; null until it has been read once. */
    @Volatile
    private var nativeEnginePresent: Boolean? = null

    /**
     * True when this device could run the engine at all. Cheap, synchronous, side-effect free and
     * safe on any thread. Never initialises anything.
     */
    fun isUsable(capabilities: RuntimeCapabilities): Boolean =
        capabilities.supportsLocalMediaEngine && nativeLibrariesPresent()

    /**
     * True when the interpreter and its standard library are on disk for this device's ABI.
     *
     * `nativeLibraryDir` holds the libraries the installer extracted for *this* device, so two
     * `stat` calls are enough to answer honestly - no guessing an ABI, no reading the APK. This
     * matters because [com.linksi.app.enhanced.media.MediaExtractor.isAvailable] runs on every
     * registry lookup; anything more expensive would be paid on every analysis.
     */
    fun nativeLibrariesPresent(): Boolean {
        nativeEnginePresent?.let { return it }
        synchronized(this) {
            nativeEnginePresent?.let { return it }
            val present = runCatching {
                val directory = File(context.applicationInfo.nativeLibraryDir)
                YtDlpNativeLibraries.isRunnable(directory.list().orEmpty().toSet())
            }.getOrDefault(false)
            nativeEnginePresent = present
            return present
        }
    }

    /**
     * Starts the engine if it has not been started yet, and reports where that stands.
     *
     * Concurrent callers are serialised by [initLock], so the unpack happens exactly once. The
     * double-check outside the lock keeps the common case - already [YtDlpInitStatus.Ready] - free
     * of suspension.
     */
    suspend fun ensureReady(): YtDlpInitStatus {
        status.takeIf { it != YtDlpInitStatus.NotStarted }?.let { return it }

        return initLock.withLock {
            status.takeIf { it != YtDlpInitStatus.NotStarted }?.let { return@withLock it }

            val resolved = try {
                withContext(Dispatchers.IO) {
                    // Blocking by nature: `init` runs synchronously and has no async form.
                    YoutubeDL.getInstance().init(context.applicationContext)
                    // `YoutubeDL.init` unpacks CPython and yt-dlp and nothing else. The `:ffmpeg`
                    // artifact has its own entry point and its own payload, and must be initialised
                    // separately: without this call `packages/ffmpeg` never exists, so every
                    // postprocessing step - which is to say every merged download - fails with
                    // "ffmpeg not found" long after the app has looked healthy. Also idempotent.
                    FFmpeg.getInstance().init(context.applicationContext)
                    YtDlpInitStatus.Ready
                }
            } catch (cancelled: CancellationException) {
                // Cancellation is not an engine failure; the caller owns that decision.
                throw cancelled
            } catch (error: Throwable) {
                // Throwable, not Exception: a missing native library surfaces as an Error on some
                // devices, and the promise that nothing escapes this module has to hold anyway.
                Log.w(TAG, "the bundled yt-dlp engine could not be started", error)
                YtDlpInitStatus.Failed(error.message ?: error.javaClass.simpleName)
            }

            status = resolved

            // Site extractors break constantly, and the copy the wrapper ships as a raw resource is
            // whatever was current when this dependency was pinned. The refresh happens *here* -
            // after the engine is known to start, and only because something is already asking for a
            // real download - so it can never be startup work (specification section 26), and its
            // result is a value that cannot fail the download that triggered it.
            if (resolved is YtDlpInitStatus.Ready) {
                Log.i(TAG, "site engine ${updater.installedVersion() ?: "unknown"}; " +
                    "refresh: ${updater.refreshIfStale()}")
            }

            resolved
        }
    }

    /**
     * The version of the site engine currently on disk, by running it, or null when it cannot run.
     *
     * Exposed for the settings screen: the wrapper's own `versionName` reads a preference that only
     * its own updater writes, so it is null for a freshly installed app.
     *
     * This starts the engine if nothing has yet, because the version is read by *running* the engine
     * (`<python> <engine> --version`) and the wrapper refuses to run anything before
     * `YoutubeDL.init`. Without [ensureReady] the settings row reads "not reported yet" on a fresh
     * process and its Check button fails with `instance not initialized` - measured on the POCO on
     * 2026-09-18 (`TEST_REPORT.md` section 40). Initialising here is not startup work: this is only
     * reached when a user opens the screen and asks about the engine.
     *
     * A failed start yields null here rather than an error, so the row falls back to "not reported
     * yet"; [refreshEngine] is where the user asks for a real answer and gets the reason.
     */
    suspend fun engineVersion(): String? {
        if (ensureReady() !is YtDlpInitStatus.Ready) return null
        return runCatching { updater.installedVersion() }.getOrNull()
    }

    /**
     * Replaces the site engine with the published release, whatever the last check's age.
     *
     * Starts the engine first for the same reason [engineVersion] does, but reports the failure
     * instead of hiding it: this is a user-initiated action, so "it could not start" must reach the
     * screen rather than surface as a generic update error.
     */
    suspend fun refreshEngine(): YtDlpRefreshResult {
        when (val init = ensureReady()) {
            is YtDlpInitStatus.Failed -> return YtDlpRefreshResult.Failed(init.reason)
            YtDlpInitStatus.NotStarted ->
                return YtDlpRefreshResult.Failed("the site engine could not be started")
            YtDlpInitStatus.Ready -> Unit
        }
        return updater.refreshIfStale(force = true)
    }

    /** The application context the engine was started with. */
    internal val appContext: Context get() = context.applicationContext

    companion object {
        private const val TAG = "YtDlpRuntime"
    }
}

/**
 * The native payload `youtubedl-android` needs on disk before it can do anything (pure JVM, so the
 * naming rule is unit tested rather than discovered on a device).
 *
 * The names are those of the 0.17.3 artifacts, read out of the published AARs:
 *
 * | file | what it is |
 * |---|---|
 * | `libpython.so` | the CPython **executable**, despite the `.so` suffix - it is spawned directly |
 * | `libpython.zip.so` | the CPython standard library, unzipped into `noBackupFilesDir` on init |
 * | `libffmpeg.so` | FFmpeg, needed before a video and an audio stream can be merged |
 * | `libffmpeg.zip.so` | the FFmpeg payload FFmpeg is linked against |
 * | `libffprobe.so` | the probe used to verify a merged file |
 *
 * Only the two Python entries gate [isRunnable]: without them there is no extractor at all. FFmpeg
 * is reported separately by [isMuxingCapable], because a device that cannot merge still benefits
 * from being able to download a combined stream.
 */
object YtDlpNativeLibraries {

    const val PYTHON_EXECUTABLE = "libpython.so"

    const val PYTHON_STDLIB_ARCHIVE = "libpython.zip.so"

    const val FFMPEG_EXECUTABLE = "libffmpeg.so"

    const val FFMPEG_ARCHIVE = "libffmpeg.zip.so"

    const val FFPROBE_EXECUTABLE = "libffprobe.so"

    /** The entries every device needs before the engine can extract anything at all. */
    val REQUIRED: List<String> = listOf(PYTHON_EXECUTABLE, PYTHON_STDLIB_ARCHIVE)

    /** Which of [REQUIRED] the given directory listing does not contain. */
    fun missingFrom(present: Set<String>): List<String> = REQUIRED.filterNot { it in present }

    /** True when the interpreter and its standard library are both present. */
    fun isRunnable(present: Set<String>): Boolean = missingFrom(present).isEmpty()

    /** True when the payload includes FFmpeg, without which separate streams cannot be merged. */
    fun isMuxingCapable(present: Set<String>): Boolean =
        FFMPEG_EXECUTABLE in present || FFMPEG_ARCHIVE in present
}
