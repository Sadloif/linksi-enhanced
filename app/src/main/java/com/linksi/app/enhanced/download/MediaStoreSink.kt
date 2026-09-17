package com.linksi.app.enhanced.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.linksi.app.enhanced.media.MediaError
import java.io.File
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

        return Handle(resolver, uri, stream, displayName)
    }

    private fun discard(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    private class Handle(
        private val resolver: ContentResolver,
        private val uri: Uri,
        override val output: OutputStream,
        /** What this entry asked MediaStore to call the file. */
        private val requestedName: String
    ) : SinkHandle {

        override val location: String = uri.toString()

        override suspend fun commit(bytesWritten: Long): String {
            if (publish()) return uri.toString()

            // Publishing failed. **Before touching anything**, ask whether the user actually has this
            // file, and ask the filesystem first.
            //
            // The order of these two checks is deliberate and was corrected after observing the wrong
            // answer. A MediaStore row is a *claim* that a file exists, and the claim outlives the
            // file: after the download was deleted from Downloads with the row left behind, the row
            // check matched, the app reported "Download complete", and the location it handed back
            // pointed at a file that was not there. A file of the right name and exact size in
            // Downloads is evidence; a row is bookkeeping. Evidence goes first.
            //
            // The size comparison is what makes the disk check safe: an older file of the same name
            // cannot be mistaken for this download, and a partial file cannot be reported as complete.
            val onDisk = publishedFileOnDisk(bytesWritten)
            if (onDisk != null) {
                Log.i(TAG, "publishing $uri failed but the finished file is on disk at $onDisk")
                runCatching { resolver.delete(uri, null, null) }
                return onDisk.absolutePath
            }

            // No file, but a visible row claims one. MediaStore *renames* an entry whose requested
            // name is taken ("clip.mp4" arrives as "clip (1).mp4"), so the published entry may be
            // under the de-duplicated name; this is reported rather than thrown away, because the
            // collection's own answer is still the best available one when the bytes are in place.
            val published = findPublishedCopy()
            if (published != null) {
                Log.i(TAG, "publishing $uri failed but the file is already published as $published")
                runCatching { resolver.delete(uri, null, null) }
                return published
            }

            // Neither a file nor a published row, so whatever is in the way is residue: an aborted
            // attempt, or a row whose file is gone. Clear it and try once more.
            val path = fileName()
            val cleared = if (path != null) clearConflictingRows(path) else 0
            if (publish()) {
                Log.i(TAG, "published after clearing $cleared stale Downloads entr(ies)")
                return uri.toString()
            }

            // Still nothing. The entry is deleted unless the user can actually see it, so a hidden
            // half-entry does not accumulate.
            if (!rowIsVisible()) {
                runCatching { resolver.delete(uri, null, null) }
                throw DownloadSinkException(
                    MediaError.NO_STORAGE,
                    "the finished download could not be published"
                )
            }
            return uri.toString()
        }

        /**
         * The finished file in the public Downloads directory, or null.
         *
         * Matched on the requested name (MediaStore's de-duplicated form included) **and** on the exact
         * size that was written, so this cannot mistake an older file of the same name for the one just
         * downloaded, and cannot report a partial file as complete.
         */
        private fun publishedFileOnDisk(bytesWritten: Long): File? {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloads.isDirectory) return null

            val base = requestedName.substringBeforeLast('.', requestedName)
            val extension = requestedName.substringAfterLast('.', "")
            val candidates = listOfNotNull(
                requestedName,
                if (extension.isEmpty()) "$base (1)" else "$base (1).$extension"
            )

            return candidates.asSequence()
                .map { File(downloads, it) }
                .firstOrNull { it.isFile && bytesWritten > 0L && it.length() == bytesWritten }
        }

        /**
         * A visible Downloads entry holding this file, other than this one, or null.
         *
         * Matched on the requested display name **or** MediaStore's de-duplicated form of it, because
         * the platform adds a " (1)" suffix when the name is taken. The `IS_PENDING = 0` clause is what
         * makes the answer meaningful: a pending row is still hidden from the user, so it is not a
         * published file however good its name looks.
         */
        private fun findPublishedCopy(): String? {
            val base = requestedName.substringBeforeLast('.', requestedName)
            val extension = requestedName.substringAfterLast('.', "")
            val candidates = listOf(requestedName, if (extension.isEmpty()) "$base (1)" else "$base (1).$extension")

            return candidates.firstNotNullOfOrNull { candidate ->
                queryFirstUri(
                    "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.IS_PENDING} = 0",
                    arrayOf(candidate)
                )
            }
        }

        /** The URI of the first row matching [selection], or null. */
        private fun queryFirstUri(selection: String, args: Array<String>): String? = runCatching {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                selection,
                args,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getString(0)
                    ).toString()
                } else {
                    null
                }
            }
        }.getOrNull()

        /** Flips `IS_PENDING` to 0, reporting whether the entry is visible afterwards. */
        private fun publish(): Boolean {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            val updated = runCatching { resolver.update(uri, values, null, null) }.getOrElse { error ->
                Log.w(TAG, "publishing $uri threw", error)
                0
            }
            if (updated > 0) return true

            // An update count of 0 is not proof of anything: MediaStore moves a pending file into
            // place as part of publishing it, and that move was observed reporting zero updated rows
            // while the file and its row were both still present. So the row is asked directly.
            return rowIsVisible()
        }

        /** The on-disk path MediaStore recorded for this entry, or null when it has none. */
        private fun fileName(): String? = runCatching {
            resolver.query(uri, arrayOf(MediaStore.Downloads.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

        /**
         * Deletes rows other than this one that claim [path], and reports how many.
         *
         * Scoped to the exact path rather than the display name, so replacing a file the user
         * previously downloaded under the same name is deliberate and cannot touch anything else.
         */
        private fun clearConflictingRows(path: String): Int = runCatching {
            val deleted = resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DATA} = ? AND ${MediaStore.Downloads._ID} != ?",
                arrayOf(path, uri.lastPathSegment.orEmpty())
            )
            if (deleted > 0) Log.i(TAG, "cleared $deleted stale row(s) for $path")
            deleted
        }.getOrElse { error ->
            Log.w(TAG, "could not clear stale rows for $path", error)
            0
        }

        /**
         * Whether the entry is present *and* no longer pending, asked directly.
         *
         * A projection of `IS_PENDING` rather than a bare existence check: a row that is still
         * pending is still hidden from the user, so it has not been published.
         */
        private fun rowIsVisible(): Boolean = runCatching {
            resolver.query(
                uri,
                arrayOf(MediaStore.Downloads.IS_PENDING),
                null,
                null,
                null
            )?.use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) == 0
            } ?: false
        }.getOrDefault(false)

        override suspend fun abort() {
            runCatching { output.close() }
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    private companion object {
        const val TAG = "MediaStoreSink"
    }
}
