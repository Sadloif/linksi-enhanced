package com.linksi.app.enhanced.download

import android.content.Context
import android.os.Environment
import com.linksi.app.enhanced.media.MediaError
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

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
 * A finished file is never used as a partial. Each open gets an operation-unique hidden `.part`
 * file, and the completed bytes are published by an atomic same-volume rename into a final name
 * reserved with `File.createNewFile()`. The sink deliberately reports no resumable bytes: without
 * a durable request identity, selecting an old partial after process death would be less safe than
 * restarting the transfer. Old partials are reclaimed conservatively when a new operation starts:
 * only the exact generated filename pattern is eligible, at most a small bounded number is removed,
 * and each candidate must be older than the stale threshold. Final files never match that pattern.
 */
class AppStorageSink(private val context: Context) : DownloadSink {

    override val kind: SinkKind = SinkKind.APP_STORAGE

    override suspend fun resumableBytes(displayName: String): Long = 0L

    override suspend fun open(
        displayName: String,
        mimeType: String?,
        append: Boolean
    ): SinkHandle {
        val directory = directory()

        if (append) {
            throw DownloadSinkException(
                MediaError.NO_STORAGE,
                "app storage does not support resuming an unowned partial file"
            )
        }

        val partial = try {
            newPartial(directory, displayName)
        } catch (error: Exception) {
            throw DownloadSinkException(
                MediaError.NO_STORAGE,
                "could not reserve an app-storage partial file",
                error
            )
        }

        val stream = try {
            FileOutputStream(partial, false)
        } catch (error: Exception) {
            runCatching { partial.delete() }
            throw DownloadSinkException(
                MediaError.NO_STORAGE,
                "could not open ${partial.name} for writing",
                error
            )
        }

        return Handle(directory, displayName, partial, stream)
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

    /** Creates a partial with a name that cannot be confused with a finished download. */
    private fun newPartial(directory: File, displayName: String): File {
        cleanupStalePartials(directory)

        val safeBase = displayName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_')
            .take(80)
            .ifEmpty { "download" }

        repeat(MAX_PARTIAL_RESERVATION_ATTEMPTS) {
            val candidate = File(
                directory,
                ".${safeBase}.linksi-${UUID.randomUUID()}.part"
            )
            if (candidate.createNewFile()) return candidate
        }
        throw IOException("could not reserve a unique app-storage partial file")
    }

    /**
     * Reclaims only abandoned files made by this sink. A strict name pattern is intentional: a
     * final download, a user file, and a partial from another tool must all be left untouched.
     */
    private fun cleanupStalePartials(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_PARTIAL_AGE_MILLIS
        runCatching {
            directory.listFiles()
                .orEmpty()
                .asSequence()
                .filter { file ->
                    file.isFile &&
                        PARTIAL_NAME_PATTERN.matches(file.name) &&
                        file.lastModified() > 0L &&
                        file.lastModified() <= cutoff
                }
                .take(MAX_STALE_PARTIAL_CLEANUPS)
                .forEach { file -> runCatching { file.delete() } }
        }
    }

    private class Handle(
        private val directory: File,
        private val displayName: String,
        private val partial: File,
        override val output: OutputStream
    ) : SinkHandle {

        /** The temporary location is useful for diagnostics before commit. */
        override val location: String = partial.absolutePath

        private var reservedTarget: File? = null
        private var committed = false

        override suspend fun commit(bytesWritten: Long): String {
            try {
                output.close()
            } catch (error: Exception) {
                throw DownloadSinkException(
                    MediaError.NO_STORAGE,
                    "could not close the app-storage partial file",
                    error
                )
            }

            if (!partial.isFile || partial.length() != bytesWritten) {
                throw DownloadSinkException(
                    MediaError.NO_STORAGE,
                    "the app-storage partial file length does not match the download"
                )
            }

            val target = try {
                reserveFinalTarget(directory, displayName)
            } catch (error: Exception) {
                throw DownloadSinkException(
                    MediaError.NO_STORAGE,
                    "could not reserve the final app-storage file name",
                    error
                )
            }
            reservedTarget = target

            try {
                atomicMove(partial, target)
            } catch (error: Exception) {
                runCatching { target.delete() }
                throw DownloadSinkException(
                    MediaError.NO_STORAGE,
                    "could not publish the completed app-storage file",
                    error
                )
            }

            if (!target.isFile || target.length() != bytesWritten) {
                runCatching { target.delete() }
                throw DownloadSinkException(
                    MediaError.NO_STORAGE,
                    "the published app-storage file could not be verified"
                )
            }

            committed = true
            return target.absolutePath
        }

        override suspend fun abort() {
            runCatching { output.close() }
            if (!committed) {
                runCatching { if (partial.exists()) partial.delete() }
                runCatching { reservedTarget?.let { if (it.exists()) it.delete() } }
            }
        }

        private companion object {
            const val MAX_FINAL_RESERVATION_ATTEMPTS = 100_000

            /** Reserves a final name with an atomic create, so list-then-open cannot race. */
            fun reserveFinalTarget(directory: File, displayName: String): File {
                repeat(MAX_FINAL_RESERVATION_ATTEMPTS) { attempt ->
                    val candidate = File(directory, collisionName(displayName, attempt))
                    if (candidate.createNewFile()) return candidate
                }
                throw IOException("could not reserve a unique final file name")
            }

            fun collisionName(displayName: String, attempt: Int): String {
                if (attempt == 0) return displayName
                val extension = displayName.substringAfterLast('.', "")
                val hasExtension =
                    extension.isNotEmpty() && extension.length <= 8 && displayName.contains('.')
                val base = if (hasExtension) {
                    displayName.dropLast(extension.length + 1)
                } else {
                    displayName
                }
                val suffix = if (hasExtension) ".${extension}" else ""
                return "$base ($attempt)$suffix"
            }

            /**
             * Same-volume rename only; never copy bytes into a name that could race. Android's
             * File.renameTo is retained only for filesystems that do not expose ATOMIC_MOVE.
             */
            fun atomicMove(source: File, target: File) {
                try {
                    Files.move(
                        source.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (unsupported: AtomicMoveNotSupportedException) {
                    if (!source.renameTo(target)) throw unsupported
                } catch (unsupported: UnsupportedOperationException) {
                    if (!source.renameTo(target)) throw unsupported
                }
            }
        }
    }

    private companion object {
        const val FALLBACK_DIRECTORY = "downloads"
        const val MAX_PARTIAL_RESERVATION_ATTEMPTS = 100_000
        const val MAX_STALE_PARTIAL_CLEANUPS = 32
        const val STALE_PARTIAL_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L

        private val PARTIAL_NAME_PATTERN = Regex(
            "^\\.[A-Za-z0-9._-]{1,80}\\.linksi-" +
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
                "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.part$"
        )
    }
}
