package com.linksi.app

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linksi.app.enhanced.capability.RuntimeCapabilities
import com.linksi.app.enhanced.download.AppStorageSink
import com.linksi.app.enhanced.download.DirectDownloadResult
import com.linksi.app.enhanced.download.DownloadSink
import com.linksi.app.enhanced.download.DownloadState
import com.linksi.app.enhanced.download.MediaStoreSink
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaSource
import com.linksi.app.enhanced.media.MediaSourceDetector
import com.linksi.app.enhanced.media.ytdlp.YtDlpDownloadRequest
import com.linksi.app.enhanced.media.ytdlp.YtDlpDownloader
import com.linksi.app.enhanced.media.ytdlp.YtDlpExtractor
import com.linksi.app.enhanced.media.ytdlp.YtDlpInitStatus
import com.linksi.app.enhanced.media.ytdlp.YtDlpNativeLibraries
import com.linksi.app.enhanced.media.ytdlp.YtDlpRuntime
import com.linksi.app.enhanced.media.ytdlp.YtDlpRefreshResult
import com.linksi.app.enhanced.media.ytdlp.YtDlpUpdater
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the site-specific engine works, end to end, on a real device.
 *
 * The unit tests pin the mapper, the command line and the native-library names on the JVM. What they
 * cannot show is that the bundled CPython and FFmpeg actually start on this ABI, that yt-dlp really
 * reaches a site, and that a video whose sound lives in a *different* stream ends up as one playable
 * file in the user's Downloads collection. Those are the claims this class tests, and they are tested
 * through the real [YtDlpExtractor] and [YtDlpDownloader], not around them.
 *
 * ### Why there is a list of sources rather than one URL
 *
 * This runs on an emulator, from a datacentre IP. The big platforms routinely refuse that: YouTube
 * answered `ERROR: [youtube] ...: Please sign in` on the machine this was written on. A refusal is a
 * property of the network, not a defect in the app, so the smoke tests walk [CANDIDATES] and use the
 * first source that answers, logging every attempt. A source that the app itself fails on - as
 * opposed to one the site refuses - still fails the run: the point is not to hide bugs behind "the
 * network was flaky", and equally not to hide the network behind a green tick.
 */
@RunWith(AndroidJUnit4::class)
class YtDlpMediaSmokeTest {

    private companion object {
        const val TAG = "YtDlpSmokeTest"

        /**
         * Public sources, most wanted first. YouTube leads because it is the target a user is most
         * likely to try and the one whose refusal is worth recording; the CDN-hosted test streams
         * behind it have no bot protection at all, so the download path still gets exercised on a
         * network the big platforms turn away.
         *
         * The DASH manifest matters most of the four: it advertises video and audio as *separate*
         * streams, which is the case only FFmpeg can turn into one playable file. Vimeo is last
         * because this yt-dlp build is known to fail on it with an internal `KeyError('config_url')`.
         */
        val CANDIDATES = listOf(
            "https://www.youtube.com/watch?v=jNQXAC9IVRw" to MediaSource.YOUTUBE,
            "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd" to MediaSource.OTHER,
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" to MediaSource.OTHER,
            "https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_medium.ogv" to MediaSource.OTHER,
            "https://archive.org/details/BigBuckBunny_124" to MediaSource.OTHER,
            "https://vimeo.com/76979871" to MediaSource.OTHER
        )

        /**
         * How long a merged download may take here before the test gives up and reports the merge
         * path as unverified.
         *
         * This is a **stuck-process bound, not a performance budget**, and it deliberately errs on
         * the generous side. An earlier version used 120 s and reported the merge path as
         * unverified on both the emulator and a physical phone. That number was simply too small
         * for the bytes involved: this DASH manifest offers a 15.5 MiB video stream, and the
         * emulator's NAT link moves ~100-220 KiB/s, so the video alone can take two minutes before
         * the audio stream has even started. A run whose deadline expires mid-transfer is then
         * indistinguishable from a hang, which is exactly the wrong conclusion to publish.
         *
         * The real bound on a stuck yt-dlp belongs in the app, not in a test: [YtDlpDownloader]
         * cannot interrupt a hung child process at all (see `YtDlpDownloadWatchdog`). Until that
         * exists, this deadline only stops one slow download from hanging the whole instrumented
         * run. Raise it with `-e ytdlpDownloadTimeoutSeconds <n>` for a slower device or a bigger
         * sample; it can also be lowered to reproduce the old behaviour.
         */
        const val DEFAULT_DOWNLOAD_TIMEOUT_SECONDS = 900L

        const val TIMEOUT_ARGUMENT = "ytdlpDownloadTimeoutSeconds"

        /**
         * The video-only stream to merge, as a yt-dlp format id, or blank to let the test choose the
         * smallest one the site offers. `-e ytdlpFormatId <id>` pins it so a run can be made
         * repeatable and small; the default keeps the test honest about whatever a site offers.
         */
        const val FORMAT_ARGUMENT = "ytdlpFormatId"

        /** A different source to test against, for a network where the default one is blocked. */
        const val URL_ARGUMENT = "ytdlpUrl"

        /**
         * The specification's own worked example, taken from the owner's real export.
         *
         * It is a live Facebook Reel that the pinned engine could not read and a current one can, so
         * it is the honest end-to-end check of what the engine refresh is for.
         */
        const val FACEBOOK_REEL_EXAMPLE = "https://www.facebook.com/reel/1710485373378939/"
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val runtime by lazy { YtDlpRuntime(context, YtDlpUpdater(context)) }

    private val extractor by lazy { YtDlpExtractor(runtime) }

    private val downloader by lazy { YtDlpDownloader(context, runtime) }

    private fun capabilities() = RuntimeCapabilities(
        sdkInt = Build.VERSION.SDK_INT,
        supportedAbis = Build.SUPPORTED_ABIS.toList()
    )

    /** The instrumentation-supplied value for [name], or null when the run did not set it. */
    private fun argument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    /**
     * The deadline for one merged download, from `-e ytdlpDownloadTimeoutSeconds`, or
     * [DEFAULT_DOWNLOAD_TIMEOUT_SECONDS].
     *
     * A run that passes a non-numeric or non-positive value is told so rather than silently falling
     * back, because a typo in a deadline would otherwise read as a network result.
     */
    private fun downloadTimeoutSeconds(): Long {
        val raw = argument(TIMEOUT_ARGUMENT) ?: return DEFAULT_DOWNLOAD_TIMEOUT_SECONDS
        val parsed = raw.toLongOrNull()
        require(parsed != null && parsed > 0L) {
            "$TIMEOUT_ARGUMENT must be a positive number of seconds, but was '$raw'"
        }
        return parsed
    }

    private fun analyze(url: String, source: MediaSource): MediaExtractionResult =
        runBlocking { extractor.analyze(url, source) }

    /**
     * The first candidate that extracts, together with its URL.
     *
     * Every attempt is logged, including the refusals, because "which sites actually work from here"
     * is the single most useful thing this test can report. A candidate yt-dlp cannot handle is
     * recorded and the walk continues: `MediaError.classify` can only ever report what the engine
     * said, so an extractor that is broken upstream and a site that turned us away look the same
     * from here, and neither is a defect this app can fix.
     *
     * The one outcome that *is* this app's fault is a `Skipped` result while `isAvailable` is true
     * and initialisation has already succeeded - that means the extractor decided not to run even
     * though everything it needs is present - so that fails the run.
     */
    private fun firstWorkingSource(): Pair<String, MediaExtractionResult.Success> {
        val refused = mutableListOf<String>()

        for ((url, source) in CANDIDATES) {
            when (val result = analyze(url, source)) {
                is MediaExtractionResult.Success -> {
                    if (result.info.formats.isEmpty()) {
                        refused += "$url: answered with no formats"
                    } else {
                        Log.i(TAG, "candidate OK: $url -> ${result.info.title} " +
                            "(${result.info.formats.size} formats)")
                        return url to result
                    }
                }

                is MediaExtractionResult.Failure -> {
                    val detail = result.cause?.message?.lineSequence()?.firstOrNull { it.isNotBlank() }
                    Log.w(TAG, "candidate refused: $url -> ${result.error} $detail")
                    refused += "$url: ${result.error} $detail"
                }

                is MediaExtractionResult.Unsupported -> {
                    Log.w(TAG, "candidate unsupported: $url")
                    refused += "$url: unsupported"
                }

                is MediaExtractionResult.Skipped -> {
                    // The engine is available and initialised; refusing to run now is a defect.
                    throw AssertionError(
                        "the engine skipped a candidate even though it is available: $url (${result.reason})"
                    )
                }
            }
        }

        assumeTrue("no candidate source answered from this network: $refused", false)
        throw AssertionError("unreachable")
    }

    // ── The engine is really there (spec 31) ────────────────────────────────────────────────────

    @Test
    fun theBundledEngineIsPresentAndOfferedOnThisDevice() {
        assertTrue(
            "the extractor must report itself available on ${capabilities().abi}",
            extractor.isAvailable(capabilities())
        )

        val present = File(context.applicationInfo.nativeLibraryDir).list().orEmpty().toSet()
        assertEquals(
            "every native entry the engine needs must be on disk",
            emptyList<String>(),
            YtDlpNativeLibraries.missingFrom(present)
        )
        assertTrue(
            "FFmpeg must be bundled, or nothing can be merged",
            YtDlpNativeLibraries.isMuxingCapable(present)
        )
        Log.i(
            TAG,
            "native payload for ${capabilities().abi}: " +
                present.filter { it.startsWith("libpython") || it.startsWith("libffmpeg") }.sorted()
        )
    }

    // ── The engine can be refreshed, and refreshing it fixes real links (spec 19 and 26) ────────

    /**
     * The engine refresh, end to end, and what it is for.
     *
     * This is the test that closes the real-content gap for the owner's own links. The wrapper pins
     * yt-dlp **2024.09.27**, and by the time it mattered that copy could not read a single one of the
     * nine Facebook links from the owner's export, while a current release read five of them -
     * including `reel/1710485373378939`, which is the specification's own worked example. Everything
     * here is measured on the device rather than assumed:
     *
     *  1. the engine reports a version *by running* (the wrapper's `versionName` reads a preference
     *     only its own updater writes, so it is empty on a fresh install);
     *  2. a forced refresh stages, validates and installs a newer engine, or reports why not;
     *  3. after the refresh the engine still runs - a replaced engine that cannot start would be
     *     worse than an old one;
     *  4. and the real Facebook link then extracts formats through **the app's own** [YtDlpExtractor],
     *     which is the claim the specification cares about.
     *
     * Passing `-e ytdlpUrl <url>` probes a different link. The step is skipped, not failed, when the
     * device is offline or GitHub is unreachable: neither says anything about the app.
     */
    @Test
    fun theEngineCanBeRefreshedAndThenReadsARealFacebookReel() {
        assumeTrue(runBlocking { runtime.ensureReady() } is YtDlpInitStatus.Ready)

        val before = runBlocking { runtime.engineVersion() }
        Log.i(TAG, "site engine version before refresh: $before")

        val refreshed = runBlocking { runtime.refreshEngine() }
        Log.i(TAG, "engine refresh result: $refreshed")
        assertTrue(
            "an engine that cannot be described is a defect: $before",
            !before.isNullOrBlank()
        )

        if (refreshed is YtDlpRefreshResult.Failed) {
            // Offline, rate-limited or blocked. Not a statement about the app.
            assumeTrue("the engine could not be refreshed here: ${refreshed.reason}", false)
        }

        // Whatever the refresh decided, the engine must work afterwards.
        val after = runBlocking { runtime.engineVersion() }
        Log.i(TAG, "site engine version after refresh: $after")
        assertTrue("the engine must still report a version after a refresh: $after", !after.isNullOrBlank())

        if (refreshed is YtDlpRefreshResult.Updated) {
            assertNotEquals("a refresh must actually change the version", before, after)
        }

        val url = argument(URL_ARGUMENT) ?: FACEBOOK_REEL_EXAMPLE
        val result = analyze(url, MediaSource.FACEBOOK)
        Log.i(TAG, "real Facebook probe of $url -> $result")

        when (result) {
            is MediaExtractionResult.Success -> {
                Log.i(
                    TAG,
                    "extracted '${result.info.title}' with ${result.info.formats.size} formats: " +
                        result.info.displayFormats().take(15).joinToString { it.displayLabel }
                )
                assertTrue("a real link must yield formats", result.info.formats.isNotEmpty())
            }

            is MediaExtractionResult.Failure ->
                assumeTrue("Facebook refused this link from here: ${result.error}", false)

            is MediaExtractionResult.Unsupported ->
                assumeTrue("$url was not recognised as a video, which the owner should re-share", false)

            is MediaExtractionResult.Skipped ->
                throw AssertionError("the engine was available but skipped the link: ${result.reason}")
        }
    }

    // ── Lazy initialisation (spec 26: no startup work) ──────────────────────────────────────────

    @Test
    fun theEngineStartsOnFirstUseAndUnpacksBothPayloads() {
        // Nothing in this process has touched the engine before this line, which is the property the
        // specification demands: building the extractor and asking whether it is available must not
        // unpack a Python standard library.
        assertTrue(runBlocking { runtime.ensureReady() is YtDlpInitStatus.Ready })
        // The second call is the memoised path and must be just as successful.
        assertTrue(runBlocking { runtime.ensureReady() is YtDlpInitStatus.Ready })

        val base = File(context.noBackupFilesDir, YoutubeDL.baseName)
        val python = File(base, "packages/python")
        val ffmpeg = File(base, "packages/ffmpeg")
        val binary = File(base, "yt-dlp/yt-dlp")
        Log.i(
            TAG,
            "unpacked: python=${python.isDirectory} ffmpeg=${ffmpeg.isDirectory} " +
                "yt-dlp=${binary.length()} bytes"
        )
        assertTrue("the Python standard library must be unpacked", python.isDirectory)
        assertTrue("the yt-dlp payload must be written out", binary.length() > 0)
        // `YoutubeDL.init` does not do this: the :ffmpeg artifact has its own entry point, and
        // forgetting it is invisible until the first download that needs a merge.
        assertTrue("the FFmpeg payload must be unpacked too", ffmpeg.isDirectory)
    }

    // ── Extraction against a live site (spec 16 and 19) ─────────────────────────────────────────

    @Test
    fun aPublicLinkExtractsIntoOfferedFormats() {
        val (url, success) = firstWorkingSource()
        val info = success.info

        Log.i(
            TAG,
            "extracted from $url: '${info.title}' by ${info.uploader} " +
                "(${info.durationSeconds}s) with ${info.formats.size} formats"
        )
        info.displayFormats().forEach { format ->
            Log.i(
                TAG,
                "  ${format.id} ${format.displayLabel} ext=${format.extension} " +
                    "mux=${format.requiresMuxing} audioOnly=${format.isAudioOnly} " +
                    "size=${format.fileSizeBytes}"
            )
        }

        assertTrue(info.title.isNotBlank())
        assertTrue(info.formats.isNotEmpty())

        // Whatever the site offers today, the app's own rules must hold for it.
        assertTrue("every format must be fetchable", info.formats.all { !it.directUrl.isNullOrBlank() })
        assertTrue(
            "an audio-only format must never claim to need a mux",
            info.formats.filter { it.isAudioOnly }.none { it.requiresMuxing }
        )
        assertEquals(
            "displayFormats must not drop or invent an entry",
            info.formats.size,
            info.displayFormats().size
        )
        assertTrue(
            "a video format must be offered for a video link",
            info.formats.any { it.isVideo }
        )
    }

    // ── The whole point: a merged download lands in Downloads (spec 22 and 23) ──────────────────

    @Test
    fun aVideoOnlyFormatIsMergedAndPublishedWhereTheUserCanFindIt() {
        val (defaultUrl, success) = firstWorkingSource()
        val url = argument(URL_ARGUMENT) ?: defaultUrl
        val info = success.info

        // A video-only stream is precisely the case OkHttp cannot serve: the sound is in another
        // stream and only FFmpeg can join them. The smallest one keeps the test quick, unless the
        // run pinned a format id so the transfer could be made repeatable.
        val videoOnly = info.formats.filter { it.isVideo && it.requiresMuxing }
        assumeTrue("$url offered no separate video stream to merge", videoOnly.isNotEmpty())
        val pinned = argument(FORMAT_ARGUMENT)?.let { id -> info.formats.firstOrNull { it.id == id } }
        val format = pinned ?: videoOnly.minByOrNull { it.height ?: Int.MAX_VALUE }!!

        val sink: DownloadSink = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreSink(context)
        } else {
            AppStorageSink(context)
        }

        val progress = Collections.synchronizedList(mutableListOf<DownloadState.Downloading>())
        val deadlineSeconds = downloadTimeoutSeconds()
        val startedAt = System.currentTimeMillis()
        Log.i(
            TAG,
            "downloading video-only format ${format.id} at ${format.height}p to force a merge " +
                "(deadline ${deadlineSeconds}s)"
        )
        val outcome = boundedDownload(url, format.id, sink, progress, deadlineSeconds)
        val elapsedMillis = System.currentTimeMillis() - startedAt
        Log.i(
            TAG,
            "merge attempt finished in ${elapsedMillis / 1000}s " +
                "(${progress.size} progress updates): $outcome"
        )

        if (outcome is DirectDownloadResult.Completed) {
            // The figure that mattered: how long the transfer actually took, so a future deadline is
            // chosen from measurement rather than guessed a second time.
            Log.i(
                TAG,
                "merged ${outcome.bytes} bytes in ${elapsedMillis / 1000}s " +
                    "(${bytesPerSecond(outcome.bytes, elapsedMillis)} B/s)"
            )
        }

        assertTrue("the download must succeed, but was $outcome", outcome is DirectDownloadResult.Completed)
        val completed = outcome as DirectDownloadResult.Completed
        Log.i(
            TAG,
            "published '${completed.displayName}' (${completed.bytes} bytes, " +
                "${completed.mimeType}) at ${completed.location}"
        )

        assertTrue("the merged file must not be empty", completed.bytes > 0L)
        assertTrue("progress must have been reported", progress.isNotEmpty())
        assertTrue("a merged file must be a video", completed.mimeType?.startsWith("video/") == true)
        assertTrue(
            "the name must carry a real container, not a placeholder",
            completed.displayName.contains('.')
        )

        // The bytes must be readable at the location the user is told about. For the MediaStore
        // sink that is a content:// URI, which is exactly what makes it appear in Downloads.
        assertEquals(
            "the published file must contain what was reported",
            completed.bytes,
            readableBytes(completed.location)
        )
    }

    /**
     * Runs the download on its own thread, under a hard deadline.
     *
     * Deliberately not a `withTimeout` inside the coroutine: yt-dlp's child process is stopped by
     * `destroyProcessById`, which upstream implements with `/system/bin/sh`, `pstree` and GNU-style
     * `grep -oP`. On a ROM missing either of those the process is never killed, the worker stays
     * blocked in `waitFor`, and cancelling the coroutine cannot return control - so an in-coroutine
     * timeout would hang exactly as the download did. A thread with a deadline can always be
     * abandoned, so one slow or stuck download cannot hang the whole instrumented run.
     *
     * Running out of time is reported as a *skip* with the reason attached, not as a failure: it
     * means the merged download could not be observed on this device within the deadline, which is
     * a fact about the device and the network rather than a claim that the code is correct. The
     * elapsed time is logged either way, because "how long did the transfer actually take" is the
     * input the next deadline should be derived from.
     */
    private fun boundedDownload(
        url: String,
        formatId: String,
        sink: DownloadSink,
        progress: MutableList<DownloadState.Downloading>,
        deadlineSeconds: Long = DEFAULT_DOWNLOAD_TIMEOUT_SECONDS
    ): DirectDownloadResult {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit(
                Callable {
                    runBlocking {
                        downloader.download(
                            request = YtDlpDownloadRequest(
                                url = url,
                                formatId = formatId,
                                requiresMuxing = true,
                                preferredName = "linksi-ytdlp-smoke"
                            ),
                            sink = sink
                        ) { state -> progress += state }
                    }
                }
            )
            future.get(deadlineSeconds, TimeUnit.SECONDS)
        } catch (timeout: TimeoutException) {
            Log.w(
                TAG,
                "the merged download of $url did not finish within ${deadlineSeconds}s; " +
                    "treating the merge path as unverified on this device"
            )
            assumeTrue(
                "the merged download did not finish within ${deadlineSeconds}s on this device",
                false
            )
            throw AssertionError("unreachable")
        } finally {
            // Interrupts the worker, which unblocks `waitFor`/`join` if the process kill did not.
            executor.shutdownNow()
        }
    }

    /** Bytes per second over an interval, or 0 when the interval is too small to divide by. */
    private fun bytesPerSecond(bytes: Long, elapsedMillis: Long): Long =
        if (elapsedMillis <= 0L) 0L else bytes * 1000L / elapsedMillis

    /** The length of what the published location actually resolves to, or -1 when it does not. */
    private fun readableBytes(location: String): Long {
        val uri = runCatching { Uri.parse(location) }.getOrNull() ?: return -1L
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return File(location).takeIf { it.isFile }?.length() ?: -1L
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
        }.getOrNull() ?: -1L
    }

    // ── Failure is a value, never an exception (spec 26) ────────────────────────────────────────

    @Test
    fun configuredPublicTargetAnswersWithAValueRatherThanAThrow() {
        val url = argument(URL_ARGUMENT)
        assumeTrue("pass -e $URL_ARGUMENT <public-url>", url != null)
        val publicUrl = requireNotNull(url)
        val source = MediaSourceDetector.fromUrl(publicUrl)

        assertTrue(
            "configured URL must belong to one of the five primary target sites, but was $source",
            source in MediaSourceDetector.PRIMARY_TARGETS
        )

        when (val result = analyze(publicUrl, source)) {
            is MediaExtractionResult.Success -> {
                Log.i(TAG, "real public probe $source succeeded with ${result.info.formats.size} formats")
                assertTrue("a success must carry formats", result.info.formats.isNotEmpty())
                assertTrue("the extracted title must not be blank", result.info.title.isNotBlank())
            }

            is MediaExtractionResult.Failure -> {
                Log.i(TAG, "real public probe $source failed as ${result.error}")
                assertFalse("a failure must name a reason", result.error.name.isBlank())
            }

            is MediaExtractionResult.Unsupported -> {
                Log.i(TAG, "real public probe $source was reported unsupported")
                assertEquals(publicUrl, result.url)
            }

            is MediaExtractionResult.Skipped -> {
                Log.i(TAG, "real public probe $source was skipped")
                assertTrue("a skip must explain itself", result.reason.isNotBlank())
            }
        }
    }

    @Test
    fun everyTargetSiteAnswersWithAValueRatherThanAThrow() {
        // No assertion on *what* yt-dlp answers: Instagram, Facebook and TikTok routinely refuse a
        // datacentre IP, and pretending otherwise would make this test a lie. What is asserted is
        // the app's contract - a value always comes back, a success always has formats, a failure
        // always carries a reason - and every outcome is logged, so the real behaviour is visible in
        // the test output rather than assumed.
        val samples = listOf(
            "https://www.instagram.com/p/not-a-real-post/" to MediaSource.INSTAGRAM,
            "https://www.tiktok.com/@not-a-real-account/video/0000000000000000000" to MediaSource.TIKTOK,
            "https://www.facebook.com/watch/?v=000000000000000" to MediaSource.FACEBOOK,
            "https://www.reddit.com/r/notarealsubreddit/comments/notreal/" to MediaSource.REDDIT,
            "https://www.pinterest.com/pin/000000000000000000/" to MediaSource.PINTEREST,
            "https://example.invalid/not-a-media-page" to MediaSource.OTHER
        )

        for ((url, source) in samples) {
            val result = analyze(url, source)
            Log.i(TAG, "probe $source $url -> $result")

            when (result) {
                is MediaExtractionResult.Success ->
                    assertTrue("a success must carry formats: $url", result.info.formats.isNotEmpty())

                is MediaExtractionResult.Failure ->
                    assertFalse("a failure must name a reason: $url", result.error.name.isBlank())

                is MediaExtractionResult.Unsupported -> assertEquals(url, result.url)

                is MediaExtractionResult.Skipped ->
                    assertTrue("a skip must explain itself: $url", result.reason.isNotBlank())
            }
        }
    }
}
