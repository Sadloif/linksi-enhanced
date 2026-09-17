package com.linksi.app.enhanced.media.ytdlp

import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaFormat
import com.linksi.app.enhanced.media.MediaInfo
import com.linksi.app.enhanced.media.MediaSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [YtDlpInfoMapper] and [extractJsonPayload].
 *
 * Case identifiers in the comments map onto LINKSI_ENHANCED_REVISED_SPEC.md sections 16, 19, 26 and
 * 68. The mapper is the single place where a yt-dlp payload becomes the app's own model, so the two
 * decisions that actually change what the user gets - which streams are offered, and which of them
 * needs FFmpeg - are pinned here rather than only observed on a device.
 *
 * Every fixture is the shape yt-dlp really emits from `--dump-single-json`: `vcodec`/`acodec` carry
 * the literal string `none` rather than being omitted, sizes are split between `filesize` and
 * `filesize_approx`, and audio tracks carry `abr`.
 */
class YtDlpInfoMapperTest {

    private companion object {
        const val URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

        /** A progress-shaped line the payload helper must ignore. */
        const val NOISE = "[youtube] Extracting URL: dQw4w9WgXcQ"
    }

    private fun map(json: String, url: String = URL, source: MediaSource = MediaSource.YOUTUBE) =
        YtDlpInfoMapper.map(JSONObject(json), url, source)

    private fun success(json: String): MediaInfo {
        val result = map(json)
        assertTrue("expected a success, got $result", result is MediaExtractionResult.Success)
        return (result as MediaExtractionResult.Success).info
    }

    private fun failure(json: String): MediaExtractionResult.Failure {
        val result = map(json)
        assertTrue("expected a failure, got $result", result is MediaExtractionResult.Failure)
        return result as MediaExtractionResult.Failure
    }

    private fun formats(json: String): List<MediaFormat> = success(json).formats

    private fun ids(list: List<MediaFormat>): List<String> = list.map { it.id }

    // ── The full payload (spec 16: title, uploader, duration, thumbnail, webpage_url) ───────────

    @Test
    fun theHeaderFieldsComeFromThePayload() {
        val info = success(FULL_PAYLOAD)

        assertEquals(URL, info.webpageUrl)
        assertEquals("A Very Good Video", info.title)
        assertEquals("Some Channel", info.uploader)
        assertEquals(213, info.durationSeconds)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", info.thumbnailUrl)
        assertEquals(MediaSource.YOUTUBE, info.source)
        assertTrue(info.hasFormats)
    }

    @Test
    fun aMissingFieldFallsBackRatherThanDroppingTheWholeExtraction() {
        // No uploader, no channel, no thumbnail: the download must still be offered.
        val info = success(
            """{"title": "Only a title", "webpage_url": "$URL", "formats": [$COMBINED_720]}"""
        )
        assertEquals("Only a title", info.title)
        assertNull(info.uploader)
        assertNull(info.thumbnailUrl)
        assertNull(info.durationSeconds)
        assertEquals(1, info.formats.size)
    }

    @Test
    fun theChannelKeyIsUsedWhenThereIsNoUploader() {
        val info = success("""{"channel": "A Channel", "formats": [$COMBINED_720]}""")
        assertEquals("A Channel", info.uploader)
    }

    @Test
    fun titleAndWebpageUrlFallBackToTheUrlTheUserAskedAbout() {
        val info = success("""{"formats": [$COMBINED_720]}""")
        assertEquals(URL, info.title)
        assertEquals(URL, info.webpageUrl)
    }

    // ── Combined versus separate streams (spec 19) ──────────────────────────────────────────────

    @Test
    fun aCombinedStreamIsNotMarkedAsNeedingAMuxAndAVideoOnlyStreamIs() {
        val mapped = formats(FULL_PAYLOAD).associateBy { it.id }

        // 22 is 720p with both codecs: one HTTP GET produces a playable file.
        val combined = mapped.getValue("22")
        assertFalse(combined.isAudioOnly)
        assertFalse("a combined stream needs no FFmpeg", combined.requiresMuxing)
        assertEquals("avc1.64001F", combined.videoCodec)
        assertEquals("mp4a.40.2", combined.audioCodec)

        // 137 is 1080p video with no audio track at all.
        val videoOnly = mapped.getValue("137")
        assertFalse(videoOnly.isAudioOnly)
        assertTrue("a video-only stream must be merged", videoOnly.requiresMuxing)
        assertEquals("avc1.640028", videoOnly.videoCodec)
        assertNull("acodec 'none' means there is no audio codec", videoOnly.audioCodec)
        assertEquals(1080, videoOnly.height)
        assertEquals(1920, videoOnly.width)
        assertEquals(30.0, videoOnly.fps!!, 0.0)
    }

    @Test
    fun theBestVideoFormatInThisPayloadIsTheOneThatNeedsMuxing() {
        // The requirement, stated directly: highest resolution wins, and because that stream is
        // video-only the download it implies has to merge a separate audio track.
        val info = success(FULL_PAYLOAD)
        val best = info.bestFormat()
        assertNotNull(best)
        assertEquals("137", best!!.id)
        assertEquals(1080, best.height)
        assertTrue(best.requiresMuxing)
    }

    @Test
    fun everyVideoOnlyFormatIsMarkedEvenWhenItIsNotTheBest() {
        // Marking only the winner would make the flag a property of the sort order rather than of
        // the stream, and the UI offers all of them.
        val videoOnly = formats(FULL_PAYLOAD).filter { it.isVideo && it.audioCodec == null }
        assertTrue(videoOnly.isNotEmpty())
        assertTrue("every video-only format needs a mux", videoOnly.all { it.requiresMuxing })
    }

    @Test
    fun aCombinedStreamWinsATieAgainstAVideoOnlyStreamOfTheSameResolution() {
        val mapped = formats(
            """
            {"formats": [
              $VIDEO_ONLY_720,
              $COMBINED_720
            ]}
            """
        )
        // Combined first, so the entry the UI preselects plays without FFmpeg.
        assertEquals(listOf("22", "136"), ids(mapped))
        assertEquals("22", YtDlpInfoMapper.defaultFormat(mapped)!!.id)
    }

    @Test
    fun defaultFormatPrefersTheCombinedStreamAtTheSameResolution() {
        val mapped = YtDlpInfoMapper.mapFormats(
            JSONArray().put(JSONObject(VIDEO_ONLY_720)).put(JSONObject(COMBINED_720))
        )
        val chosen = YtDlpInfoMapper.defaultFormat(mapped)
        assertNotNull(chosen)
        assertEquals("22", chosen!!.id)
        assertFalse(chosen.requiresMuxing)
    }

    @Test
    fun defaultFormatStillPrefersAHigherResolutionThatNeedsAMerge() {
        // "Prefer combined" is a tie-break, never a reason to hand the user a worse picture.
        val mapped = YtDlpInfoMapper.mapFormats(
            JSONArray().put(JSONObject(VIDEO_ONLY_1080)).put(JSONObject(COMBINED_720))
        )
        assertEquals("137", YtDlpInfoMapper.defaultFormat(mapped)!!.id)
    }

    @Test
    fun defaultFormatFallsBackToAudioWhenThereIsNoVideoAtAll() {
        val mapped = YtDlpInfoMapper.mapFormats(
            JSONArray().put(JSONObject(AUDIO_M4A_128)).put(JSONObject(AUDIO_WEBM_160))
        )
        assertEquals("251", YtDlpInfoMapper.defaultFormat(mapped)!!.id)
    }

    @Test
    fun defaultFormatIsNullForAnEmptyList() {
        assertNull(YtDlpInfoMapper.defaultFormat(emptyList()))
    }

    // ── Audio-only detection (spec 19: `vcodec == "none"`) ──────────────────────────────────────

    @Test
    fun videoCodecNoneMeansAudioOnly() {
        val mapped = formats(FULL_PAYLOAD).associateBy { it.id }

        val audio = mapped.getValue("140")
        assertTrue(audio.isAudioOnly)
        assertFalse("an audio-only stream is already complete", audio.requiresMuxing)
        assertNull("vcodec 'none' means there is no video codec", audio.videoCodec)
        assertEquals("mp4a.40.2", audio.audioCodec)
        assertEquals("m4a", audio.extension)
    }

    @Test
    fun anEntryWithNoCodecsAtAllIsAStoryboardAndIsDropped() {
        // yt-dlp describes storyboards exactly like this; offering one would be offering an image
        // sheet the user cannot play.
        val mapped = formats(FULL_PAYLOAD)
        assertFalse("sb0 must not survive", ids(mapped).contains("sb0"))
        assertEquals(6, mapped.size)
    }

    @Test
    fun anAudioTrackThatOmitsVcodecEntirelyIsStillAudioOnly() {
        // Several extractors write only `abr` and no `vcodec`; reading that as "video present"
        // would offer an audio file as a resolution.
        val mapped = formats(
            """{"formats": [{"format_id": "a", "ext": "m4a", "abr": 96.0, "url": "https://x/a"}]}"""
        )
        assertEquals(1, mapped.size)
        assertTrue(mapped.single().isAudioOnly)
        assertFalse(mapped.single().requiresMuxing)
    }

    @Test
    fun aMissingCodecIsNotTheSameAsTheStringNone() {
        // "not stated" must not be read as "absent", or every ordinary progressive format would be
        // marked as needing FFmpeg.
        val mapped = formats(
            """{"formats": [{"format_id": "v", "ext": "mp4", "height": 480, "url": "https://x/v"}]}"""
        )
        val format = mapped.single()
        assertFalse(format.isAudioOnly)
        assertFalse(format.requiresMuxing)
        assertNull(format.videoCodec)
    }

    @Test
    fun audioOnlyTracksAreOrderedByBitrateThenSize() {
        val mapped = formats(
            """
            {"formats": [
              $AUDIO_M4A_128,
              {"format_id": "low", "ext": "m4a", "vcodec": "none", "acodec": "mp4a", "abr": 48.0,
               "url": "https://x/low"},
              $AUDIO_WEBM_160
            ]}
            """
        )
        assertEquals(listOf("251", "140", "low"), ids(mapped))
    }

    @Test
    fun audioTracksWithNoBitrateFallBackToSizeOrdering() {
        val mapped = formats(
            """
            {"formats": [
              {"format_id": "small", "ext": "m4a", "vcodec": "none", "acodec": "mp4a",
               "filesize": 100, "url": "https://x/small"},
              {"format_id": "big", "ext": "m4a", "vcodec": "none", "acodec": "mp4a",
               "filesize": 900, "url": "https://x/big"}
            ]}
            """
        )
        assertEquals(listOf("big", "small"), ids(mapped))
    }

    // ── Sizes (spec 19: filesize, then filesize_approx) ─────────────────────────────────────────

    @Test
    fun theExactSizeIsPreferredOverTheEstimate() {
        val mapped = formats(
            """
            {"formats": [{"format_id": "v", "ext": "mp4", "height": 720, "url": "https://x/v",
              "filesize": 1234567, "filesize_approx": 9999999}]}
            """
        )
        assertEquals(1234567L, mapped.single().fileSizeBytes)
    }

    @Test
    fun theEstimateIsUsedWhenThereIsNoExactSize() {
        val mapped = formats(
            """{"formats": [{"format_id": "v", "ext": "mp4", "height": 720, "url": "https://x/v",
              "filesize": null, "filesize_approx": 4200000}]}"""
        )
        assertEquals(4200000L, mapped.single().fileSizeBytes)
    }

    @Test
    fun aNonPositiveSizeMeansUnknownRatherThanZeroBytes() {
        // The UI must show "unknown size", never "0 B", so both fields are checked for positivity.
        val mapped = formats(
            """{"formats": [{"format_id": "v", "ext": "mp4", "height": 720, "url": "https://x/v",
              "filesize": 0, "filesize_approx": -1}]}"""
        )
        assertNull(mapped.single().fileSizeBytes)
        assertFalse(mapped.single().hasKnownSize)
    }

    @Test
    fun aPayloadWithNoSizesAtAllLeavesTheSizeUnknown() {
        val mapped = formats(
            """{"formats": [{"format_id": "v", "ext": "mp4", "height": 720, "url": "https://x/v"}]}"""
        )
        assertNull(mapped.single().fileSizeBytes)
    }

    // ── Ordering, including the one MediaInfo.displayFormats() must preserve (spec 19) ──────────

    @Test
    fun formatsAreVideoBestFirstThenAudio() {
        val mapped = formats(FULL_PAYLOAD)
        assertEquals(listOf("137", "248", "22", "18", "251", "140"), ids(mapped))
    }

    @Test
    fun displayFormatsStillOrdersVideoByDescendingHeightThenAudio() {
        val info = success(FULL_PAYLOAD)
        val displayed = info.displayFormats()

        val videos = displayed.takeWhile { it.isVideo }
        val audios = displayed.dropWhile { it.isVideo }

        assertTrue("the video block must not be empty", videos.isNotEmpty())
        assertTrue("audio must come last", audios.all { it.isAudioOnly })
        assertEquals(
            "the video block must be in descending resolution",
            videos.map { it.height ?: 0 }.sortedDescending(),
            videos.map { it.height ?: 0 }
        )
        assertEquals(
            "displayFormats must not drop or invent an entry",
            info.formats.size,
            displayed.size
        )
    }

    @Test
    fun displayFormatsKeepsTheCombinedStreamAheadOfTheVideoOnlyOneAtTheSameHeight() {
        // The mapper's preference only survives because MediaInfo's sort is stable; that is the
        // property being pinned, not an accident of the comparator.
        val info = success("""{"formats": [$VIDEO_ONLY_720, $COMBINED_720]}""")
        assertEquals(listOf("22", "136"), ids(info.displayFormats()))
    }

    @Test
    fun displayFormatsCollapsesASingleVideoFormatIntoOriginalQuality() {
        val info = success("""{"formats": [$COMBINED_720]}""")
        val displayed = info.displayFormats()
        assertEquals(1, displayed.size)
        assertEquals("Original quality", displayed.single().label)
    }

    // ── Missing and hostile payloads (spec 26 and 68) ───────────────────────────────────────────

    @Test
    fun aNullPayloadIsAFailureRatherThanACrash() {
        val result = YtDlpInfoMapper.map(null, URL, MediaSource.YOUTUBE)
        assertTrue(result is MediaExtractionResult.Failure)
        val failure = result as MediaExtractionResult.Failure
        assertEquals(MediaError.EXTRACTOR_FAILED, failure.error)
        assertEquals(URL, failure.url)
    }

    @Test
    fun anEmptyObjectReportsThatThereAreNoFormats() {
        assertEquals(MediaError.NO_FORMATS, failure("{}").error)
    }

    @Test
    fun aFormatsFieldOfTheWrongTypeIsReportedAsNoFormats() {
        assertEquals(MediaError.NO_FORMATS, failure("""{"formats": "not an array"}""").error)
    }

    @Test
    fun formatsEntriesThatAreNotObjectsAreIgnored() {
        assertEquals(
            MediaError.NO_FORMATS,
            failure("""{"formats": [null, 42, "nope", [], {}]}""").error
        )
    }

    @Test
    fun aFormatWithNoUrlIsUnusableAndDropped() {
        // Nothing can be fetched from it, so offering it would only produce a failed download.
        assertEquals(MediaError.NO_FORMATS, failure("""{"formats": [{"format_id": "x"}]}""").error)
        assertEquals(
            MediaError.NO_FORMATS,
            failure("""{"formats": [{"format_id": "x", "url": null}]}""").error
        )
        assertEquals(
            MediaError.NO_FORMATS,
            failure("""{"formats": [{"format_id": "x", "url": "   "}]}""").error
        )
    }

    @Test
    fun hostileFieldTypesAreIgnoredRatherThanCoerced() {
        // Numbers where strings belong, strings where numbers belong, and a JSON null that some
        // platform implementations stringify as the four characters "null".
        val info = success(
            """
            {"title": 42, "uploader": {"a": 1}, "duration": "much", "thumbnail": null,
             "webpage_url": "   ",
             "formats": [{"format_id": 7, "ext": "mp4", "height": "720", "width": true,
                          "fps": "30", "vcodec": 5, "acodec": "none", "filesize": "big",
                          "url": "https://x/v"}]}
            """
        )

        assertEquals(URL, info.title)
        assertNull(info.uploader)
        assertNull(info.durationSeconds)
        assertNull(info.thumbnailUrl)
        assertEquals(URL, info.webpageUrl)

        val format = info.formats.single()
        // Non-string format_id, ext and vcodec all fall back; a JSON string is never read as a
        // number, so a bogus height cannot invent a resolution the site never advertised.
        assertEquals("format-0", format.id)
        assertEquals("mp4", format.extension)
        assertNull(format.height)
        assertNull(format.width)
        assertNull(format.fps)
        assertNull(format.videoCodec)
        assertNull(format.fileSizeBytes)
        // `acodec: "none"` is still readable, so this is honestly a video-only stream.
        assertFalse(format.isAudioOnly)
        assertTrue(format.requiresMuxing)
    }

    @Test
    fun theLiteralStringNullIsNotAName() {
        // `optString` on a JSON null returns "null" on the platform implementation, which would
        // otherwise become the user's file name.
        val info = success("""{"title": "null", "uploader": "NULL", "formats": [$COMBINED_720]}""")
        assertEquals(URL, info.title)
        assertNull(info.uploader)
    }

    @Test
    fun aTruncatedPayloadIsAFailureNotAnException() {
        // JSONObject's constructor throws on this; the mapper is only ever handed a parsed object,
        // so the guard being tested is that a *failed parse* upstream is handled as a value.
        val parsed = runCatching { JSONObject("""{"formats": [""") }.getOrNull()
        assertNull(parsed)
        val result = YtDlpInfoMapper.map(parsed, URL, MediaSource.YOUTUBE)
        assertTrue(result is MediaExtractionResult.Failure)
    }

    @Test
    fun mapFormatsAcceptsANullArray() {
        assertTrue(YtDlpInfoMapper.mapFormats(null).isEmpty())
    }

    // ── The JSON payload helper (the layer above the mapper) ────────────────────────────────────

    @Test
    fun thePayloadIsTheObjectOnStdout() {
        assertEquals("""{"a":1}""", extractJsonPayload("""{"a":1}"""))
        assertEquals("""{"a":1}""", extractJsonPayload("""  {"a":1}  """))
    }

    @Test
    fun ytDlpChatterAroundTheObjectIsIgnored() {
        assertEquals(
            """{"a":1}""",
            extractJsonPayload("$NOISE\n{\"a\":1}\n")
        )
    }

    @Test
    fun aPayloadWithNoObjectIsNull() {
        assertNull(extractJsonPayload(null))
        assertNull(extractJsonPayload(""))
        assertNull(extractJsonPayload("   "))
        assertNull(extractJsonPayload(NOISE))
        assertNull(extractJsonPayload("ERROR: Unsupported URL: https://example.com/x"))
    }

    @Test
    fun anUnbalancedPayloadIsNotSilentlyRepaired() {
        // The helper hands back what it could delimit; parsing it is the mapper's problem, and a
        // failure there is a value rather than an exception.
        val payload = extractJsonPayload("""{"a":1""")
        assertNull(payload)
    }

    // The small fixtures come first: Kotlin initialises properties in declaration order, and
    // FULL_PAYLOAD interpolates them.

    private val AUDIO_M4A_128 = """
        {"format_id": "140", "ext": "m4a", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 128.0,
         "filesize": 3400000, "url": "https://x/140"}
    """

    private val AUDIO_WEBM_160 = """
        {"format_id": "251", "ext": "webm", "vcodec": "none", "acodec": "opus", "abr": 160.0,
         "filesize_approx": 4200000, "url": "https://x/251"}
    """

    private val VIDEO_ONLY_1080 = """
        {"format_id": "137", "ext": "mp4", "height": 1080, "width": 1920, "fps": 30,
         "vcodec": "avc1.640028", "acodec": "none", "filesize": 55000000, "url": "https://x/137"}
    """

    private val VIDEO_ONLY_720 = """
        {"format_id": "136", "ext": "mp4", "height": 720, "width": 1280, "fps": 30,
         "vcodec": "avc1.4d401f", "acodec": "none", "filesize": 18000000, "url": "https://x/136"}
    """

    private val COMBINED_720 = """
        {"format_id": "22", "ext": "mp4", "height": 720, "width": 1280, "fps": 30,
         "vcodec": "avc1.64001F", "acodec": "mp4a.40.2", "filesize": 24000000, "url": "https://x/22"}
    """

    private val COMBINED_360 = """
        {"format_id": "18", "ext": "mp4", "height": 360, "width": 640, "fps": 30,
         "vcodec": "avc1.42001E", "acodec": "mp4a.40.2", "filesize": 12000000, "url": "https://x/18"}
    """

    private val FULL_PAYLOAD = """
    {
      "id": "dQw4w9WgXcQ",
      "title": "A Very Good Video",
      "uploader": "Some Channel",
      "channel": "Some Channel",
      "duration": 213.0,
      "thumbnail": "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
      "webpage_url": "$URL",
      "formats": [
        {"format_id": "sb0", "ext": "mhtml", "vcodec": "none", "acodec": "none",
         "url": "https://x/sb0"},
        $AUDIO_M4A_128,
        $AUDIO_WEBM_160,
        $VIDEO_ONLY_1080,
        {"format_id": "248", "ext": "webm", "height": 1080, "width": 1920, "fps": 30,
         "vcodec": "vp9", "acodec": "none", "filesize_approx": 48000000, "url": "https://x/248"},
        $COMBINED_720,
        $COMBINED_360
      ]
    }
    """
}
