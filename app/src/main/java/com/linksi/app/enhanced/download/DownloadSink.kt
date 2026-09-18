package com.linksi.app.enhanced.download

import android.content.Context
import android.os.Build
import com.linksi.app.enhanced.media.MediaError
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which concrete storage target a download is written to (specification section 22, research
 * brief section 5.4).
 *
 * The kind - not the destination enum - is what the download engine branches on, so the storage
 * decision is made in exactly one place and can be unit tested on the plain JVM.
 */
enum class SinkKind(
    /** True when the finished file is visible to the user in the system Downloads collection. */
    val isPubliclyVisible: Boolean
) {
    /** Public `Download/` collection through `MediaStore.Downloads` (API 29+). */
    MEDIA_STORE_DOWNLOADS(isPubliclyVisible = true),

    /** App-specific external files directory: no permission, removed on uninstall, invisible. */
    APP_STORAGE(isPubliclyVisible = false),

    /** A folder the user picked through the Storage Access Framework. */
    USER_SELECTED_SAF(isPubliclyVisible = true)
}

/**
 * Decides where a download goes for a given destination and API level (specification section 22).
 *
 * Pure JVM: no Android types, no device state, so every branch is unit tested.
 *
 * The rules implement the research brief's decision ladder (section 5.4):
 *  - `MediaStore.Downloads` exists from API 29 and needs **no** storage permission, so public
 *    downloads use it.
 *  - Below API 29 there is no Downloads collection, and this app deliberately does not ask for
 *    `WRITE_EXTERNAL_STORAGE` at runtime. Those downloads are written into app-specific storage,
 *    which needs no permission at any API level; they become visible only if the user later
 *    exports them.
 *  - `USER_SELECTED` always uses the Storage Access Framework, which exists at every supported
 *    API level; where the file lands is the user's choice, not the platform's.
 */
object DownloadSinkPolicy {

    /** First API level with a `MediaStore.Downloads` collection. */
    const val MEDIA_STORE_DOWNLOADS_MIN_SDK = 29

    /** First API level with scoped storage. */
    const val SCOPED_STORAGE_MIN_SDK = 29

    fun forSdk(destination: DownloadDestination, sdkInt: Int): SinkKind = when (destination) {
        DownloadDestination.USER_SELECTED -> SinkKind.USER_SELECTED_SAF

        DownloadDestination.APP_STORAGE -> SinkKind.APP_STORAGE

        DownloadDestination.PUBLIC_DOWNLOADS ->
            if (sdkInt >= MEDIA_STORE_DOWNLOADS_MIN_SDK) {
                SinkKind.MEDIA_STORE_DOWNLOADS
            } else {
                SinkKind.APP_STORAGE
            }
    }

    /**
     * Always false, and that is the point: Linksi never requests `WRITE_EXTERNAL_STORAGE` at
     * runtime. On API 26-28 a public download is written app-privately instead (decision ladder
     * option (b)), so no destination ever needs the legacy permission. The manifest still declares
     * it with `maxSdkVersion="28"` for older installs, but nothing in this module asks for it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun requiresLegacyWritePermission(destination: DownloadDestination, sdkInt: Int): Boolean = false

    /** True when files written for [destination] are visible to other apps on [sdkInt]. */
    fun isPubliclyVisible(destination: DownloadDestination, sdkInt: Int): Boolean =
        forSdk(destination, sdkInt).isPubliclyVisible
}

/**
 * Somewhere bytes can be written (specification section 22).
 *
 * Implementations must never throw a platform exception type at the caller: they wrap failures in
 * [DownloadSinkException] so the download engine can turn them into `DownloadState.Failed` without
 * leaking `SecurityException`, `FileNotFoundException` or MediaStore surprises into the UI
 * (specification section 26).
 */
interface DownloadSink {

    val kind: SinkKind

    /**
     * Bytes already stored for [displayName] that this sink could append to, or 0 when the sink
     * cannot resume.
     *
     * Returning 0 is always safe - it means "start from the first byte". A sink must not infer
     * that a user-visible final file is a partial download: only an operation-owned partial file
     * may be reported here. The built-in sinks currently return 0, so a process restart starts a
     * fresh, independently published file rather than risking an append into unrelated bytes.
     */
    suspend fun resumableBytes(displayName: String): Long = 0L

    /**
     * Opens [displayName] for writing.
     *
     * @param append true only when [resumableBytes] returned a positive offset *and* the server
     *   agreed to continue from exactly that byte with a `206 Partial Content` response. A caller
     *   must not pass true after a zero-byte response from [resumableBytes].
     */
    suspend fun open(displayName: String, mimeType: String?, append: Boolean): SinkHandle
}

/**
 * A sink that is open for writing.
 *
 * The engine writes to [output], then either [commit]s (publishing the file) or [abort]s
 * (deleting the partial file). Neither may throw.
 */
interface SinkHandle {

    /** `content://` URI or absolute path, depending on the sink. Human readable, never parsed. */
    val location: String

    val output: OutputStream

    /** Publishes the finished file and returns its final location. */
    suspend fun commit(bytesWritten: Long): String

    /** Deletes whatever was written and releases the stream. Must not throw. */
    suspend fun abort()
}

/**
 * A storage failure the user can act on. Carries the [MediaError] the UI should show instead of a
 * technical message, which stays in the log.
 */
class DownloadSinkException(
    val error: MediaError,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Builds the sink for a destination on this device.
 *
 * Note on `USER_SELECTED`: the policy decides correctly that it maps to the Storage Access
 * Framework, but a SAF write needs the *tree URI* the user granted, and `DownloadRequest` - a
 * frozen contract shared with the UI and the resolver - has no field to carry one. Rather than
 * guessing a location, this factory reports "no storage" with an actionable detail. Wiring a
 * picker through would mean adding the URI to `DownloadRequest` or as a separate input-data key,
 * which is deliberately left to the screen that owns the picker.
 */
@Singleton
class DownloadSinkFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun forDestination(destination: DownloadDestination): DownloadSink? =
        when (DownloadSinkPolicy.forSdk(destination, Build.VERSION.SDK_INT)) {
            SinkKind.MEDIA_STORE_DOWNLOADS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStoreSink(context)
                } else {
                    // Unreachable through the policy, kept so the branch is explicit and Lint sees
                    // a real version check around the API 29 call.
                    AppStorageSink(context)
                }

            SinkKind.APP_STORAGE -> AppStorageSink(context)

            SinkKind.USER_SELECTED_SAF -> null
        }

    /** Why [forDestination] returned null, for the failure detail shown to the user. */
    fun unavailableDetail(destination: DownloadDestination): String? = when (destination) {
        DownloadDestination.USER_SELECTED -> USER_SELECTED_DETAIL
        else -> null
    }

    private companion object {
        const val USER_SELECTED_DETAIL =
            "No Storage Access Framework folder has been granted for this download"
    }
}
