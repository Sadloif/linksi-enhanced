package com.linksi.app.enhanced.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.linksi.app.enhanced.media.MediaError
import java.io.OutputStream

/**
 * Writes into the public `Download/` collection through `MediaStore.Downloads`
 * (research brief sections 5.1 and 5.2), which is where the user actually looks for a download.
 *
 * Why this is the right sink on API 29+:
 *  - it needs **no** storage permission for files the app owns, so the manifest's only storage
 *    permission stays capped at `maxSdkVersion="28"`;
 *  - `IS_PENDING = 1` keeps the row invisible to other apps (and out of the Downloads app) until
 *    the bytes are complete, and [SinkHandle.commit] flips it to 0. A cancelled or failed download
 *    deletes the row in [SinkHandle.abort] instead of leaving a 0 byte entry behind.
 *
 * **Resume limitation, stated rather than half-implemented:** [resumableBytes] always returns 0.
 * Continuing a MediaStore write means locating the previous `IS_PENDING` row, trusting its
 * reported `SIZE`, and appending through `openOutputStream(uri, "wa")`. That is not verifiable in
 * this environment, and getting it wrong corrupts a file silently, so the research brief's
 * IS_PENDING pattern is implemented exactly and downloads into MediaStore restart from zero. The
 * resume path is implemented where it can be correct - see [AppStorageSink] - and a process kill
 * during either is what the byte-range machinery exists for.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreSink(private val context: Context) : DownloadSink {

    override val kind: SinkKind = SinkKind.MEDIA_STORE_DOWNLOADS

    override suspend fun resumableBytes(displayName: String): Long = 0L

    override suspend fun open(
        displayName: String,
        mimeType: String?,
        append: Boolean
    ): SinkHandle {
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            if (!mimeType.isNullOrBlank()) {
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
            }
            // Never write DATA: DISPLAY_NAME plus RELATIVE_PATH is the supported pair.
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        // EXTERNAL_CONTENT_URI is the primary volume's Downloads collection; only the primary
        // volume may be modified, and it is the one the user sees.
        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (error: Exception) {
            throw DownloadSinkException(
                MediaError.NO_STORAGE,
                "the Downloads collection refused a new entry",
                error
            )
        } ?: throw DownloadSinkException(
            MediaError.NO_STORAGE,
            "the Downloads collection returned no URI"
        )

        val stream = try {
            resolver.openOutputStream(uri)
        } catch (error: Exception) {
            discard(resolver, uri)
            throw DownloadSinkException(
                MediaError.NO_STORAGE,
                "the new Downloads entry could not be opened for writing",
                error
            )
        } ?: run {
            discard(resolver, uri)
            throw DownloadSinkException(
                MediaError.NO_STORAGE,
                "the new Downloads entry has no output stream"
            )
        }

        return Handle(resolver, uri, stream)
    }

    private fun discard(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    private class Handle(
        private val resolver: ContentResolver,
        private val uri: Uri,
        override val output: OutputStream
    ) : SinkHandle {

        override val location: String = uri.toString()

        override suspend fun commit(bytesWritten: Long): String {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            val updated = try {
                resolver.update(uri, values, null, null)
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw DownloadSinkException(
                    MediaError.NO_STORAGE,
                    "the finished download could not be published",
                    error
                )
            }

            if (updated <= 0) {
                // The row disappeared (user cleared it, storage unmounted): publishing "succeeded"
                // would hand the UI a URI that no longer resolves.
                runCatching { resolver.delete(uri, null, null) }
                throw DownloadSinkException(
                    MediaError.NO_STORAGE,
                    "the finished download is no longer in the Downloads collection"
                )
            }
            return uri.toString()
        }

        override suspend fun abort() {
            runCatching { output.close() }
            runCatching { resolver.delete(uri, null, null) }
        }
    }
}
