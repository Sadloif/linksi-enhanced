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
 * this environment, and getting it wrong corrupts a file silently, so downloads into MediaStore
 * restart from zero.
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

        return Handle(resolver, uri, displayName, stream)
    }

    private fun discard(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    private class Handle(
        private val resolver: ContentResolver,
        private val uri: Uri,
        /** What this entry asked MediaStore to call the file, before any collision suffix. */
        private val displayName: String,
        override val output: OutputStream
    ) : SinkHandle {

        /**
         * When this handle was created, used to prove a file on disk belongs to *this* operation.
         *
         * A file of the right name and size that was already there is another operation's work; one
         * written after we started is ours. Measured on the POCO, three same-named files of identical
         * size can sit in Downloads at once, so this bound is what makes the fallback safe.
         */
        private val openedAt: Long = System.currentTimeMillis()

        override val location: String = uri.toString()

        override suspend fun commit(bytesWritten: Long): String {
            try {
                output.close()
            } catch (error: Exception) {
                throw DownloadSinkException(
                    MediaError.NO_STORAGE,
                    "the new Downloads entry could not be closed",
                    error
                )
            }

            publish(bytesWritten)?.let { return it }

            // The row did not verify. Before reporting failure, ask the only question that matters to
            // the user: is the finished file actually on disk?
            //
            // This fallback was briefly removed during a refactor, and the reason is worth recording.
            // The concern was real: an earlier version matched *any* Downloads row of the same name and
            // size, so it could hand back a different operation's file. But dropping the fallback
            // entirely traded one wrong answer for a worse one - a device run showed a complete
            // download reported as `NO_STORAGE` ("Not enough storage is available") with its bytes
            // sitting intact on disk. A false failure for a file the user has is the defect this sink
            // has now been fixed for twice.
            //
            // So the fallback is restored and bounded by three independent tests: this handle's own
            // requested name (or the provider's collision suffix of it), the exact committed size, and
            // a modification time inside this handle's own write window. Measured on the POCO, three
            // same-named files of identical size can coexist in Downloads, so no single one of those
            // tests is sufficient - it is the conjunction that identifies our file.
            val onDisk = finishedFileOnDisk(displayName, bytesWritten, openedAt)
            if (onDisk != null) {
                Log.i(TAG, "publishing $uri failed but the finished file is on disk at $onDisk")
                runCatching { resolver.delete(uri, null, null) }
                return onDisk.absolutePath
            }

            // Nothing of ours is on disk: the only row we may clean up is the one this handle created.
            runCatching { resolver.delete(uri, null, null) }
            throw DownloadSinkException(
                MediaError.NO_STORAGE,
                "the finished download could not be published"
            )
        }

        /** Flips `IS_PENDING` to 0 and resolves this row's actual provider-assigned identity. */
        private fun publish(bytesWritten: Long): String? {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            val updated = runCatching { resolver.update(uri, values, null, null) }.getOrElse { error ->
                Log.w(TAG, "publishing $uri threw", error)
                0
            }
            if (updated <= 0) {
                // A zero update count is not proof of failure: some providers complete the move
                // while reporting zero. The current URI is the only trustworthy follow-up.
                Log.w(TAG, "publishing $uri returned update count $updated; checking the row")
            }
            return currentPublishedIdentity(bytesWritten)
        }

        /**
         * Reads only this handle's URI, including the provider's actual collision-suffixed name.
         *
         * A pending row or a row whose recorded size differs from the committed byte count is not
         * proof of publication.
         */
        private fun currentPublishedIdentity(bytesWritten: Long): String? = runCatching {
            resolver.query(
                uri,
                arrayOf(
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.IS_PENDING,
                    MediaStore.MediaColumns.SIZE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val actualName = cursor.getString(0)
                val pending = cursor.getInt(1)
                val actualSize = if (cursor.isNull(2)) null else cursor.getLong(2)
                if (pending == 0 && actualSize == bytesWritten) {
                    Log.i(TAG, "published $uri as $actualName ($actualSize bytes)")
                    uri.toString()
                } else {
                    null
                }
            }
        }.getOrNull()

        /**
         * The finished file on disk for this handle's download, or null when nothing here is ours.
         *
         * `DATA` is **not** a queryable column on this ROM - a device run failed with
         * `IllegalArgumentException: Invalid column data` - so the path has to be derived rather than
         * looked up. That makes the bounds below load-bearing rather than defensive:
         *
         *  - the name must be the one this handle asked for, or the provider's collision-suffixed form
         *    of it (`clip (1).mp4`), because MediaStore renames on collision;
         *  - the file must be exactly [bytesWritten] long, so a partial file cannot be reported as
         *    complete;
         *  - it must have been modified at or after [openedAt], so a pre-existing file is not claimed.
         *
         * The size bound also enforces the guard the row check above makes: a collision-suffixed file
         * created by an *earlier* operation of the same byte count is rejected by the time bound.
         */
        private fun finishedFileOnDisk(
            requestedName: String,
            bytesWritten: Long,
            openedAt: Long
        ): File? = runCatching {
            val directory = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val candidates = listOf(requestedName) + collisionSuffixedNames(requestedName)
            candidates
                .map { File(directory, it) }
                .firstOrNull { file ->
                    file.isFile &&
                        file.length() == bytesWritten &&
                        file.lastModified() >= openedAt
                }
        }.getOrNull()

        /**
         * The names a provider may substitute for [name] on a collision, up to a small bound.
         *
         * MediaStore uses the platform's `name (n).ext` convention. Ten is enough for any realistic
         * directory; beyond that the fallback simply declines and the user gets an honest failure
         * rather than a guess.
         */
        private fun collisionSuffixedNames(name: String): List<String> {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val extension = if (dot > 0) name.substring(dot) else ""
            return (1..SUFFIX_LIMIT).map { "$stem ($it)$extension" }
        }

        override suspend fun abort() {
            runCatching { output.close() }
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    private companion object {
        const val TAG = "MediaStoreSink"

        /** How many `name (n).ext` variants to consider before declining to guess. */
        const val SUFFIX_LIMIT = 10
    }
}
