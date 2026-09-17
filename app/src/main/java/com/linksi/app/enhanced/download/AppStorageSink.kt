package com.linksi.app.enhanced.download

import android.content.Context
import android.os.Environment
import com.linksi.app.enhanced.media.MediaError
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Writes into the app-specific external `Download/` directory
 * (`getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)`).
 *
 * Two reasons this exists (research brief sections 5.1 and 5.3):
 *  - on API 26-28 there is no `MediaStore.Downloads` collection, and this app deliberately does not
 *    request `WRITE_EXTERNAL_STORAGE` at runtime, so public downloads land here until the user
 *    exports them;
 *  - on every API level it is the one destination that needs no permission at all, which makes it
 *    the safe fallback when the external volume is unavailable.
 *
 * It is also the only sink that can resume honestly: the partial file has a real length on disk and
 * a plain `FileOutputStream(file, append = true)` continues exactly where the previous attempt
 * stopped, with no MediaStore bookkeeping to mis-trust.
 */
class AppStorageSink(private val context: Context) : DownloadSink {

    override val kind: SinkKind = SinkKind.APP_STORAGE

    override suspend fun resumableBytes(displayName: String): Long = runCatching {
        val existing = file(displayName)
        if (existing.isFile) existing.length().coerceAtLeast(0L) else 0L
    }.getOrDefault(0L)

    override suspend fun open(
        displayName: String,
        mimeType: String?,
        append: Boolean
    ): SinkHandle {
        val directory = directory()

        // An append reuses the exact file name the offset was measured from; anything else would
        // write the remaining bytes into a different file. A fresh download never overwrites an
        // existing one (specification 48).
        val target = if (append) {
            file(displayName)
        } else {
            uniqueTarget(directory, displayName)
        }

        val stream = try {
            FileOutputStream(target, append)
        } catch (error: Exception) {
            throw DownloadSinkException(
                MediaError.NO_STORAGE,
                "could not open ${target.name} for writing",
                error
            )
        }

        return Handle(target, stream)
    }

    /** The directory this sink writes to; created on demand. */
    private fun directory(): File {
        val external = runCatching {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()
        val directory = external ?: File(context.filesDir, FALLBACK_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw DownloadSinkException(
                MediaError.NO_STORAGE,
                "the app download directory could not be created"
            )
        }
        return directory
    }

    private fun file(displayName: String): File = File(directory(), displayName)

    private fun uniqueTarget(directory: File, displayName: String): File {
        val existing = directory.list()?.toSet().orEmpty()
        return File(directory, FilenameSanitizer.ensureUnique(displayName, existing))
    }

    private class Handle(
        private val target: File,
        override val output: OutputStream
    ) : SinkHandle {

        override val location: String = target.absolutePath

        override suspend fun commit(bytesWritten: Long): String = target.absolutePath

        override suspend fun abort() {
            runCatching { output.close() }
            runCatching { if (target.exists()) target.delete() }
        }
    }

    private companion object {
        const val FALLBACK_DIRECTORY = "downloads"
    }
}
