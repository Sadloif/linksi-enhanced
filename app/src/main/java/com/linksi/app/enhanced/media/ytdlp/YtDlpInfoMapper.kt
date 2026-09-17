package com.linksi.app.enhanced.media.ytdlp

import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaFormat
import com.linksi.app.enhanced.media.MediaInfo
import com.linksi.app.enhanced.media.MediaSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps one yt-dlp *info dict* onto [MediaInfo] (specification sections 16, 19, 26 and 68).
 *
 * yt-dlp is asked for `--dump-single-json`, so the whole description of a link arrives as a single
 * JSON object on stdout. Nothing here touches Android, the network or yt-dlp itself: the mapper
 * takes a [JSONObject] and returns a value, which is what makes the site-specific layer testable on
 * the plain JVM instead of only on a device - the same split the server resolver uses for its own
 * response body.
 *
 * It never throws. A malformed, truncated or deliberately hostile payload is a
 * [MediaExtractionResult] failure: a broken extraction may disable one download button, it may
 * never crash the app.
 *
 * ### Fields read
 *
 * | yt-dlp | here |
 * |---|---|
 * | `title` | [MediaInfo.title], falling back to the URL |
 * | `uploader`, then `channel` | [MediaInfo.uploader] |
 * | `duration` | [MediaInfo.durationSeconds] |
 * | `thumbnail` | [MediaInfo.thumbnailUrl] |
 * | `webpage_url` | [MediaInfo.webpageUrl] |
 * | `formats[]` | [MediaInfo.formats], see [mapFormats] |
 *
 * ### How a stream is classified
 *
 * yt-dlp writes the literal string `none` into `vcodec`/`acodec` for a stream that does not carry
 * that kind of data, so:
 *
 *  - `vcodec == "none"` -> audio only;
 *  - `acodec == "none"` alongside a video codec -> video only, which means the two halves have to
 *    be merged before the file is playable, so [MediaFormat.requiresMuxing] is set. That is true of
 *    every such format, and therefore of the best one too, so "the best video format has
 *    `acodec == "none"`" is covered by construction rather than special cased;
 *  - both `none` -> a storyboard or another non-playable entry, dropped entirely;
 *  - a *missing* codec is not the same as `"none"`. It means "not stated", and the format is
 *    treated as carrying that kind of data, because reading it as absent would silently mark
 *    ordinary progressive formats as needing FFmpeg.
 *
 * ### Which format is offered first
 *
 * [mapFormats] sorts video best-first by height, then frame rate, and puts a **combined** stream
 * (one that already carries audio) ahead of a video-only stream of the same resolution. The sort in
 * [MediaInfo.displayFormats] is stable, so that preference survives into the UI, and
 * [defaultFormat] applies the same rule for a caller that has to pick one format without a user.
 */
object YtDlpInfoMapper {

    /** What yt-dlp writes into `vcodec`/`acodec` for a stream that carries no such data. */
    private const val NO_CODEC = "none"

    /** Container to assume when the payload does not name one. */
    private const val DEFAULT_VIDEO_EXTENSION = "mp4"

    /** Container to assume for an audio-only stream when the payload does not name one. */
    private const val DEFAULT_AUDIO_EXTENSION = "m4a"

    /**
     * A format together with the one yt-dlp field [MediaFormat] has no room for.
     *
     * `abr` is the audio bitrate in kbit/s. It is kept beside the format rather than inside it
     * because the model is ordered by resolution and frame rate, while `abr` is what orders the
     * audio tracks and what distinguishes three audio tracks from each other.
     */
    private data class Parsed(val format: MediaFormat, val abr: Double?)

    /**
     * Video best-first: height, then frame rate, then **combined before video-only**. The
     * `requiresMuxing` term is the tie-break that makes the default a file that plays as-is
     * whenever the site offers one.
     */
    private val VIDEO_ORDER: Comparator<MediaFormat> =
        compareByDescending<MediaFormat> { it.height ?: 0 }
            .thenByDescending { it.fps ?: 0.0 }
            .thenBy { it.requiresMuxing }
            .thenByDescending { it.fileSizeBytes ?: 0L }
            .thenBy { it.id }

    /**
     * Turns a parsed info dict into the result the media layer speaks.
     *
     * [url] is the URL the user asked about and is used whenever the payload omits `webpage_url` or
     * `title`, so the caller is never handed an empty description it would have to re-derive.
     */
    fun map(root: JSONObject?, url: String, source: MediaSource): MediaExtractionResult {
        if (root == null) {
            return MediaExtractionResult.Failure(MediaError.EXTRACTOR_FAILED, url)
        }

        val formats = mapFormats(root.optJSONArray("formats"))
        if (formats.isEmpty()) {
            // yt-dlp answered, but with nothing that can be downloaded.
            return MediaExtractionResult.Failure(MediaError.NO_FORMATS, url)
        }

        return MediaExtractionResult.Success(
            MediaInfo(
                webpageUrl = text(root, "webpage_url") ?: url,
                source = source,
                title = text(root, "title") ?: url,
                uploader = text(root, "uploader") ?: text(root, "channel"),
                durationSeconds = wholeSeconds(root, "duration"),
                thumbnailUrl = text(root, "thumbnail"),
                formats = formats
            )
        )
    }

    /**
     * Maps the `formats` array, already ordered the way [MediaInfo.displayFormats] expects.
     *
     * An entry that cannot describe a downloadable stream is dropped rather than represented as a
     * broken row: a storyboard or similar entry whose `vcodec` and `acodec` are both `none`, and
     * anything without a usable `url`.
     */
    fun mapFormats(array: JSONArray?): List<MediaFormat> {
        if (array == null) return emptyList()

        val parsed = (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { entry -> parseFormat(entry, index) }
        }

        val videos = parsed.filter { !it.format.isAudioOnly }
            .sortedWith(compareBy(VIDEO_ORDER) { it.format })

        // Best bitrate first among the audio tracks; size is only a tie-break for a payload that
        // reports no bitrate at all.
        val audios = parsed.filter { it.format.isAudioOnly }
            .sortedWith(
                compareByDescending<Parsed> { it.abr ?: 0.0 }
                    .thenByDescending { it.format.fileSizeBytes ?: 0L }
                    .thenBy { it.format.id }
            )

        // Video first, audio last: the same order MediaInfo.displayFormats() produces, so a caller
        // that skips that call still gets a sensible list.
        return (videos + audios).map { it.format }
    }

    /**
     * The format to use when nobody has chosen one: the best video stream, preferring one that
     * already carries audio, and only then an audio-only stream. Null when [formats] is empty.
     */
    fun defaultFormat(formats: List<MediaFormat>): MediaFormat? =
        formats.filter { it.isVideo }.sortedWith(VIDEO_ORDER).firstOrNull()
            ?: formats.firstOrNull()

    /**
     * One entry of `formats`, or null when it describes nothing downloadable.
     *
     * [index] only gives an unnamed format a stable id, so a hostile payload full of anonymous
     * entries cannot collapse them all onto one.
     */
    private fun parseFormat(entry: JSONObject, index: Int): Parsed? {
        val videoCodec = text(entry, "vcodec")
        val audioCodec = text(entry, "acodec")
        val height = positiveInt(entry, "height")
        val abr = positiveDouble(entry, "abr")

        val videoAbsent = videoCodec.equalsNoCodec()
        val audioAbsent = audioCodec.equalsNoCodec()

        // A storyboard ("vcodec": "none", "acodec": "none") is an image sheet, not media.
        if (videoAbsent && audioAbsent) return null

        // yt-dlp says "none" in words. An entry that omits vcodec entirely but carries a bitrate and
        // no resolution is an audio track described the short way, which several extractors do.
        val audioOnly = videoAbsent || (videoCodec == null && height == null && abr != null)

        val url = text(entry, "url") ?: return null

        val extension = text(entry, "ext")
            ?.removePrefix(".")
            ?.takeIf { it.isNotBlank() }
            ?: if (audioOnly) DEFAULT_AUDIO_EXTENSION else DEFAULT_VIDEO_EXTENSION

        val format = MediaFormat(
            id = text(entry, "format_id") ?: "format-$index",
            // Deliberately blank. MediaFormat.displayLabel already derives "1080p" from the height,
            // "Audio only" from the flag and "Original quality" from a heightless single format, and
            // duplicating that logic here would only give the two a chance to disagree.
            label = "",
            extension = extension,
            height = height,
            width = positiveInt(entry, "width"),
            fps = positiveDouble(entry, "fps"),
            videoCodec = videoCodec?.takeIf { !videoAbsent },
            audioCodec = audioCodec?.takeIf { !audioAbsent },
            fileSizeBytes = sizeBytes(entry),
            isAudioOnly = audioOnly,
            directUrl = url,
            // Video-only means the sound lives in a different stream and FFmpeg has to join them.
            // An audio-only or a combined stream is already complete.
            requiresMuxing = !audioOnly && audioAbsent
        )

        return Parsed(format, abr)
    }

    /**
     * `filesize` first, then `filesize_approx`.
     *
     * `filesize` is the exact length yt-dlp measured; `filesize_approx` is its estimate and is
     * routinely the only one present for a DASH or HLS stream. A non-positive value means "not
     * stated" in both fields, which is why neither is trusted without a check.
     */
    private fun sizeBytes(entry: JSONObject): Long? =
        positiveLong(entry, "filesize") ?: positiveLong(entry, "filesize_approx")

    /**
     * Reads a string field, treating everything that is not really text as absent.
     *
     * `JSONObject.optString` is unusable for this: on the Android platform implementation a JSON
     * `null` comes back as the four-character string `"null"`, which would sail through
     * `isNotBlank()` and name the user's file "null". This reads the raw value instead, so a null, a
     * number, an object and a whitespace-only string all mean "not stated".
     */
    private fun text(container: JSONObject, key: String): String? {
        if (container.isNull(key)) return null
        val raw = container.opt(key) ?: return null
        if (raw === JSONObject.NULL) return null
        if (raw !is String) return null
        val value = raw.trim()
        return value.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    /**
     * Reads a number, rejecting anything that is not one.
     *
     * A JSON *string* that happens to look like a number is not accepted either: yt-dlp always sends
     * real numbers, so a string here is a sign of a payload this code should not be trusting.
     */
    private fun number(container: JSONObject, key: String): Double? {
        if (container.isNull(key)) return null
        val value = (container.opt(key) as? Number)?.toDouble() ?: return null
        return value.takeIf { it.isFinite() }
    }

    private fun positiveInt(container: JSONObject, key: String): Int? =
        number(container, key)?.toInt()?.takeIf { it > 0 }

    private fun positiveDouble(container: JSONObject, key: String): Double? =
        number(container, key)?.takeIf { it > 0.0 }

    private fun positiveLong(container: JSONObject, key: String): Long? =
        number(container, key)?.toLong()?.takeIf { it > 0L }

    /** `duration` is a float in yt-dlp's output for most sites; the model stores whole seconds. */
    private fun wholeSeconds(container: JSONObject, key: String): Int? =
        number(container, key)?.toInt()?.takeIf { it > 0 }

    private fun String?.equalsNoCodec(): Boolean =
        this != null && this.equals(NO_CODEC, ignoreCase = true)
}
