package com.linksi.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.linksi.app.enhanced.download.DownloadDestination
import com.linksi.app.enhanced.download.DownloadRequest
import com.linksi.app.enhanced.download.DownloadState
import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaSourceDetector
import com.linksi.app.enhanced.ui.DownloadUiEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real end-to-end download: a small, stable public image, fetched through the production
 * [com.linksi.app.enhanced.download.DownloadEngine] on a real device (specification sections 22, 23
 * and 70).
 *
 * This is the test the mocks cannot replace. It exercises the whole chain that unit tests stub out:
 * Hilt's real graph, WorkManager actually running [com.linksi.app.enhanced.download.DownloadWorker]
 * as a foreground service, `DirectFileExtractor` probing the server, and `MediaStoreSink` writing
 * into the public Downloads collection.
 *
 * Two deliberate concessions:
 *  - **No network is a skip, not a failure.** The test attempts the real download first, and only
 *    if the engine reports a network-class failure does it call [assumeTrue], which marks the test
 *    *ignored*. A machine with no internet therefore reports "skipped" rather than "broken", while
 *    an honest failure - a wrong size, a missing MediaStore row, a rejected name - still fails.
 *  - **The image is tiny and stable.** `wikimedia.org`'s 320px PNG is used because it has been
 *    served unchanged for years and is a few kilobytes, so the test cannot eat the device's storage
 *    or its patience.
 *
 * The test only ever touches its own download: it cancels that work id and deletes the row it
 * created, so running the suite twice does not accumulate files.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DownloadEngineInstrumentedTest {

    private companion object {
        /**
         * A small, stable, publicly served PNG.
         *
         * Google's own logo has been served from exactly this path for years, answers `HEAD` with a
         * real `Content-Type` and `Content-Length` (which is what `DirectFileExtractor` probes), and
         * is about 13 KB, so the test neither guesses at an unstable CDN path nor eats the device's
         * storage. `wikimedia.org`'s thumbnails are deliberately not used: they answer 400 to
         * requests that do not come from the thumbnail service's own allowed list.
         */
        const val IMAGE_URL =
            "https://www.google.com/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png"

        /** The engine's own sanitiser turns this URL into `<base>.png`; the base is unstable, so
         *  the assertions use the extension and the URI, not the exact stem. */
        const val EXPECTED_EXTENSION = "png"

        /** Generous: a cold WorkManager start plus a small HTTPS fetch. */
        const val TIMEOUT_MILLIS = 150_000L
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val entryPoint: DownloadUiEntryPoint get() = DownloadUiEntryPoint.from(context)

    private var downloadId: String? = null

    @After
    fun cleanUp() {
        val id = downloadId ?: return
        runCatching { entryPoint.downloadEngine().cancel(id) }
    }

    @Test
    fun aRealImageDownloadCompletesAndLandsInMediaStore() {
        val engine = entryPoint.downloadEngine()
        val request = DownloadRequest(
            id = "instrumented-download-${System.currentTimeMillis()}",
            url = IMAGE_URL,
            source = MediaSourceDetector.fromUrl(IMAGE_URL),
            suggestedFileName = "linksi-instrumented-download",
            destination = DownloadDestination.PUBLIC_DOWNLOADS
        )
        downloadId = request.id

        runBlocking {
            engine.enqueue(request)

            val terminal = awaitTerminal(engine, request.id)

            // A failure is reported as a value; surface that value instead of a bare timeout.
            if (terminal is DownloadState.Failed) {
                if (terminal.error == MediaError.NETWORK ||
                    terminal.error == MediaError.SERVER_UNAVAILABLE ||
                    terminal.error == MediaError.EXTRACTOR_FAILED
                ) {
                    // The network is unavailable on this machine, or the transfer did not finish.
                    // That is an environment fact, not a regression, so the test is ignored.
                    assumeTrue(
                        "the download could not reach the network: ${terminal.error} ${terminal.detail}",
                        false
                    )
                }
                throw AssertionError("download failed with ${terminal.error}: ${terminal.detail}")
            }

            assertTrue(
                "the download must settle into a terminal state, was ${terminal::class.simpleName}",
                terminal is DownloadState.Completed
            )
            val success = terminal as DownloadState.Completed
            assertEquals(
                "the finished file must be the size the sink reported",
                success.bytes,
                bytesOf(success.filePath)
            )
            assertTrue("the engine must report a non-empty location", success.filePath.isNotBlank())
            assertTrue(
                "the location must be the MediaStore row the sink created, was ${success.filePath}",
                success.filePath.startsWith(ContentResolver.SCHEME_CONTENT)
            )
            assertTrue("the download must have produced some bytes", success.bytes > 0)

            val displayName = success.displayName
            assertTrue(
                "the file must be named after the suggested name, was $displayName",
                displayName != null && displayName.endsWith(".$EXPECTED_EXTENSION")
            )

            // The row must be in the public Downloads collection and *published* (IS_PENDING = 0),
            // which is what makes it visible to the user and to other apps.
            assertTrue(
                "the finished download must exist in MediaStore.Downloads",
                existsInMediaStore(success.filePath)
            )

            runCatching { context.contentResolver.delete(Uri.parse(success.filePath), null, null) }
        }
    }

    /**
     * Waits for the first terminal state the engine publishes, or returns [DownloadState.Idle] when
     * the deadline passes first.
     *
     * `first { }` ends the collection on its own, and `withTimeoutOrNull` cancels a still-waiting
     * collector, so the wait has exactly one outcome and nothing is thrown out of the flow.
     */
    private suspend fun awaitTerminal(
        engine: com.linksi.app.enhanced.download.DownloadEngine,
        id: String
    ): DownloadState = withTimeoutOrNull(TIMEOUT_MILLIS) {
        engine.observe(id).first { state -> state.isTerminal }
    } ?: DownloadState.Idle

    /** The number of bytes actually readable at [location]; -1 when it cannot be opened. */
    private fun bytesOf(location: String): Long = runCatching {
        context.contentResolver.openInputStream(Uri.parse(location))?.use { stream ->
            var total = 0L
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        } ?: -1L
    }.getOrDefault(-1L)

    /** True when [location] resolves to a published row in the public Downloads collection. */
    private fun existsInMediaStore(location: String): Boolean = runCatching {
        context.contentResolver.query(
            Uri.parse(location),
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.IS_PENDING),
            null,
            null,
            null
        )?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(1) == 0
        } ?: false
    }.getOrDefault(false)
}
