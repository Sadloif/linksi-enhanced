package com.linksi.app.enhanced.media.ytdlp

import com.linksi.app.enhanced.capability.RuntimeCapabilities
import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaExtractor
import com.linksi.app.enhanced.media.MediaSource
import com.linksi.app.enhanced.media.MediaSourceDetector
import com.linksi.app.enhanced.media.direct.DirectFileClassifier
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Site-specific extraction through the bundled yt-dlp (specification sections 16 and 19).
 *
 * ### Licence
 *
 * This class is the reason the APK is a GPL-3.0 combined work. `youtubedl-android` (the wrapper used
 * here) and the FFmpeg it bundles are both GPL-3.0, so a *distributed* build containing them must be
 * licensed GPL-3.0 as a whole, with complete corresponding source, the appropriate notices and the
 * section 6 Installation Information duties. The rest of this fork is MIT, and that licence cannot
 * survive the combination. Private, undistributed personal use is unaffected. This was a deliberate
 * decision by the project owner, not an oversight.
 *
 * ### Where it sits in the chain
 *
 * [priority] is below [com.linksi.app.enhanced.media.direct.DirectFileExtractor]: probing a URL that
 * *is* a file costs two cheap requests, while this costs a whole Python interpreter, so the cheap
 * one always gets to answer first. A URL the direct extractor declares `Unsupported` - a web page -
 * falls through to here.
 *
 * [supports] covers the specification's five primary targets ([MediaSourceDetector.PRIMARY_TARGETS]:
 * Instagram, Facebook, TikTok, Pinterest, Reddit), YouTube, and every other http(s) page yt-dlp
 * might understand, i.e. [MediaSource.OTHER]. It deliberately excludes [MediaSource.DIRECT_FILE],
 * which is already answered by the direct extractor and where launching yt-dlp could only waste
 * seconds to reach the same conclusion.
 *
 * ### Failure policy
 *
 * Nothing escapes. An engine that is missing on this ABI reports `Skipped` (the registry's word for
 * "no backend could even be tried"), a site yt-dlp does not know reports `Unsupported`, and every
 * other problem is a `Failure` carrying the [MediaError] the UI should show.
 */
@Singleton
class YtDlpExtractor @Inject constructor(
    private val runtime: YtDlpRuntime
) : MediaExtractor {

    override val id: String = ID

    override val displayName: String = DISPLAY_NAME

    /** Below [com.linksi.app.enhanced.media.direct.DirectFileExtractor.PRIORITY] by design. */
    override val priority: Int = PRIORITY

    /**
     * Any http(s) URL that is a page rather than the file itself - see [ytDlpSupports].
     */
    override fun supports(source: MediaSource, url: String): Boolean = ytDlpSupports(source, url)

    /**
     * False when this device cannot run the bundled interpreter - a 32-bit-only ROM without the
     * engine's payload, or an APK built without the native libraries. The registry then skips this
     * extractor instead of discovering the problem by launching a process.
     *
     * Deliberately does *not* initialise the engine: this runs on every registry lookup, and the
     * specification forbids startup work from optional modules.
     */
    override fun isAvailable(capabilities: RuntimeCapabilities): Boolean =
        runtime.isUsable(capabilities)

    override suspend fun analyze(url: String, source: MediaSource): MediaExtractionResult {
        if (!supports(source, url)) return MediaExtractionResult.Unsupported(url, source)

        return try {
            when (val init = runtime.ensureReady()) {
                is YtDlpInitStatus.Failed -> MediaExtractionResult.Skipped(
                    url,
                    "the bundled yt-dlp engine could not be started: ${init.reason}"
                )

                YtDlpInitStatus.NotStarted -> MediaExtractionResult.Skipped(
                    url,
                    "the bundled yt-dlp engine has not been started"
                )

                YtDlpInitStatus.Ready -> extract(url, source)
            }
        } catch (cancelled: CancellationException) {
            // The caller's cancellation is the caller's business.
            throw cancelled
        } catch (error: Throwable) {
            // The interface promises that no exception escapes, including from a native crash
            // surfacing as an Error rather than an Exception.
            MediaExtractionResult.Failure(MediaError.EXTRACTOR_FAILED, url, error)
        }
    }

    /** Runs yt-dlp for the info dict and hands the payload to the mapper. */
    private suspend fun extract(url: String, source: MediaSource): MediaExtractionResult {
        val response = try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(infoRequest(url))
            }
        } catch (cancelled: YoutubeDL.CanceledException) {
            return MediaExtractionResult.Failure(MediaError.CANCELLED, url, cancelled)
        } catch (interrupted: InterruptedException) {
            return MediaExtractionResult.Failure(MediaError.CANCELLED, url, interrupted)
        } catch (error: YoutubeDLException) {
            // yt-dlp's own message is the only description of what went wrong, so it is classified
            // into the user-facing vocabulary rather than shown verbatim (specification section 68).
            return failureFor(url, source, error.message, error)
        }

        val payload = extractJsonPayload(response.out)
            ?: return failureFor(url, source, response.err, null)

        val root = runCatching { JSONObject(payload) }.getOrNull()
            ?: return MediaExtractionResult.Failure(MediaError.EXTRACTOR_FAILED, url)

        return YtDlpInfoMapper.map(root, url, source)
    }

    /**
     * A missing payload means yt-dlp refused the URL rather than that the app is broken, so its
     * stderr is classified: "Unsupported URL" is the site's answer and becomes `Unsupported`, while
     * "private", "login required" and a timeout become the matching user-facing failure.
     */
    private fun failureFor(
        url: String,
        source: MediaSource,
        message: String?,
        cause: Throwable?
    ): MediaExtractionResult {
        val error = MediaError.classify(message)
        return if (error == MediaError.UNSUPPORTED_SITE) {
            MediaExtractionResult.Unsupported(url, source)
        } else {
            MediaExtractionResult.Failure(error, url, cause)
        }
    }

    private fun infoRequest(url: String): YoutubeDLRequest = YoutubeDLRequest(url)
        // One JSON object describing one video, on stdout, with no download.
        .addOption("--dump-single-json")
        // A playlist URL is still one link in this app: describe the video, not the list.
        .addOption("--no-playlist")
        // Screaming into stderr is not useful here, and the exit code is what is checked.
        .addOption("--no-warnings")
        // The library injects this unless asked otherwise; being explicit records the intent.
        .addOption("--no-cache-dir")
        .addOption("--socket-timeout", SOCKET_TIMEOUT_SECONDS)

    companion object {
        const val ID = "yt-dlp"

        const val DISPLAY_NAME = "yt-dlp"

        /**
         * Between the direct-file probe and nothing. High enough to outrank any future cheap
         * backend, low enough that the two-request direct probe always runs first.
         */
        const val PRIORITY = 50

        /** Long enough for a slow CDN to answer, short enough that a dead host settles. */
        private const val SOCKET_TIMEOUT_SECONDS = 20
    }
}

/**
 * Whether the site engine should look at this URL (pure, unit tested).
 *
 * The check is a deny, not an allow list, so a site this app has never heard of is still worth one
 * attempt - that is the entire point of yt-dlp. It covers the specification's five primary targets
 * ([MediaSourceDetector.PRIMARY_TARGETS]: Instagram, Facebook, TikTok, Pinterest, Reddit), YouTube,
 * and every generic http(s) page ([MediaSource.OTHER], and [MediaSource.UNKNOWN], because a URL
 * that parsed well enough to get here is worth trying).
 *
 * [MediaSource.DIRECT_FILE] is the one exclusion. Such a URL *is* the file, the direct extractor
 * has already probed it with two cheap requests, and starting a Python interpreter to reach the
 * same conclusion would be pure waste.
 */
internal fun ytDlpSupports(source: MediaSource, url: String): Boolean =
    DirectFileClassifier.isHttpUrl(url) && source != MediaSource.DIRECT_FILE

/**
 * The JSON object inside yt-dlp's stdout, or null when there is not one (pure, unit testable).
 *
 * `--dump-single-json` deliberately diverts yt-dlp's own chatter to stderr so that stdout is the
 * machine-readable channel, but an extractor that prints anyway must not be able to break the
 * parse. Everything from the first `{` to the last `}` is the object; any parse failure after that
 * is the mapper's problem, and it answers with a failure value rather than an exception.
 */
internal fun extractJsonPayload(raw: String?): String? {
    val text = raw.orEmpty()
    val start = text.indexOf('{')
    if (start < 0) return null
    val end = text.lastIndexOf('}')
    if (end <= start) return null
    return text.substring(start, end + 1)
}
