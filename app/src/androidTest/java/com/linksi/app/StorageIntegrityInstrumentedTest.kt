package com.linksi.app

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linksi.app.enhanced.download.AppStorageSink
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Regression coverage for app-storage ownership, publication, and stale-part cleanup. */
@RunWith(AndroidJUnit4::class)
class StorageIntegrityInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val artifacts = mutableListOf<File>()

    @After
    fun tearDown() {
        artifacts.asReversed().forEach { file -> runCatching { file.delete() } }
        storageDirectory().listFiles()
            .orEmpty()
            .filter {
                it.name.startsWith("linksi-storage-integrity-") ||
                    (it.name.startsWith(".linksi-storage-integrity-") && it.name.endsWith(".part"))
            }
            .forEach { runCatching { it.delete() } }
    }

    @Test
    fun anExistingSameSizeFinalFileIsNotReportedAsResumable() {
        val name = uniqueName()
        val existing = File(storageDirectory(), name)
        val oldBytes = ByteArray(257) { 0x11 }
        existing.writeBytes(oldBytes)
        artifacts += existing

        val sink = AppStorageSink(context)
        assertEquals(
            "a final file is not an owned partial",
            0L,
            runBlocking { sink.resumableBytes(name) }
        )

        val freshBytes = ByteArray(oldBytes.size) { 0x7a }
        val handle = runBlocking { sink.open(name, "application/octet-stream", append = false) }
        val partial = File(handle.location)
        try {
            handle.output.use { it.write(freshBytes) }
            val location = runBlocking { handle.commit(freshBytes.size.toLong()) }
            val published = File(location)
            artifacts += published

            assertNotEquals("the existing final path must not be reused", existing.absolutePath, location)
            assertArrayEquals(oldBytes, existing.readBytes())
            assertArrayEquals(freshBytes, published.readBytes())
            assertFalse("the temporary partial must be atomically moved", partial.exists())
        } finally {
            runBlocking { handle.abort() }
        }
    }

    @Test
    fun sameNameCommitsReserveDistinctFinalNamesUnderConcurrency() {
        val name = uniqueName()
        val payloads = listOf(
            ByteArray(513) { 0x21 },
            ByteArray(513) { 0x6c }
        )
        val sink = AppStorageSink(context)
        val handles = runBlocking {
            listOf(
                async { sink.open(name, "application/octet-stream", append = false) },
                async { sink.open(name, "application/octet-stream", append = false) }
            ).awaitAll()
        }

        val locations = try {
            runBlocking {
                coroutineScope {
                    handles.mapIndexed { index, handle ->
                        async(Dispatchers.IO) {
                            handle.output.use { it.write(payloads[index]) }
                            handle.commit(payloads[index].size.toLong())
                        }
                    }.awaitAll()
                }
            }
        } finally {
            runBlocking { handles.forEach { it.abort() } }
        }

        assertEquals("both operations need their own final name", 2, locations.toSet().size)
        locations.forEachIndexed { index, location ->
            val file = File(location)
            artifacts += file
            assertArrayEquals(payloads[index], file.readBytes())
        }
    }

    @Test
    fun staleGeneratedPartialsAreReclaimedButFinalAndUnknownFilesRemain() {
        val name = uniqueName()
        val directory = storageDirectory()
        assertTrue(directory.mkdirs() || directory.isDirectory)
        val final = File(directory, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val stale = File(
            directory,
            ".${name}.linksi-${UUID.randomUUID()}.part"
        ).apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val unknown = File(directory, ".foreign-${UUID.randomUUID()}.part")
            .apply { writeBytes(byteArrayOf(7, 8, 9)) }
        artifacts += final
        artifacts += stale
        artifacts += unknown

        assertTrue(
            "the test must be able to age the generated partial",
            stale.setLastModified(System.currentTimeMillis() - STALE_PARTIAL_AGE_MILLIS - 1_000L)
        )

        val handle = runBlocking {
            AppStorageSink(context).open(name, "application/octet-stream", append = false)
        }
        try {
            assertFalse("old generated partials are boundedly reclaimed", stale.exists())
            assertTrue("a final file never matches the partial cleanup pattern", final.exists())
            assertTrue("unknown partial-looking files are not ours to delete", unknown.exists())
        } finally {
            runBlocking { handle.abort() }
        }
    }

    private fun storageDirectory(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")

    private fun uniqueName(): String =
        "linksi-storage-integrity-${UUID.randomUUID()}.bin"

    private companion object {
        const val STALE_PARTIAL_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}
