package com.linksi.app.enhanced.resolver

import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaFormat
import com.linksi.app.enhanced.media.MediaInfo
import com.linksi.app.enhanced.media.MediaSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the optional private resolver's response (specification sections 24 and 25).
 *
 * Kept separate from the HTTP client and free of Android imports so the wire contract is unit
 * testable. It must never throw: a malformed or hostile response is a [MediaExtractionResult]
 * failure, because a broken server must not be able to break the app.
 *
 * ### Wire contract
 *
 * Request: `POST {baseUrl}/resolve` with `{"url": "..."}` and `Accept: application/json`.
 *
 * Success:
 * ```json
 * {
 *   "ok": true,
 *   "title": "Post title",
 *   "uploader": "account",
 *   "duration": 42,
 *   "thumbnail": "https://...",
 *   "formats": [
 *     {
 *       "id": "137", "label": "1080p", "ext": "mp4", "height": 1080, "width": 1920,
 *       "fps": 30, "vcodec": "avc1", "acodec": "none", "filesize": 12345678,
 *       "url": "https://...", "audio_only": false, "requires_muxing": true
 *     }
 *   ]
 * }
 * ```
 *
 * Failure: `{"ok": false, "error": "private content"}` — the text is classified by
 * [MediaError.classify] so a technical server message never reaches the user verbatim.
 */
object ResolverResponseParser {

    fun parse(url: String, source: MediaSource, body: String?): MediaExtractionResult {
        if (body.isNullOrBlank()) {
            return MediaExtractionResult.Failure(MediaError.SERVER_UNAVAILABLE, url)
        }

        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return MediaExtractionResult.Failure(MediaError.SERVER_UNAVAILABLE, url)

        if (!root.optBoolean("ok", false)) {
            val error = root.optString("error").takeIf { it.isNotBlank() }
            return MediaExtractionResult.Failure(MediaError.classify(error), url)
        }

        val formats = parseFormats(root.optJSONArray("formats"))
        if (formats.isEmpty()) {
            return MediaExtractionResult.Failure(MediaError.NO_FORMATS, url)
        }

        val info = MediaInfo(
            webpageUrl = root.optString("webpage_url").takeIf { it.isNotBlank() } ?: url,
            source = source,
            title = root.optString("title").takeIf { it.isNotBlank() } ?: url,
            uploader = root.optString("uploader").takeIf { it.isNotBlank() },
            durationSeconds = root.optInt("duration", 0).takeIf { it > 0 },
            thumbnailUrl = root.optString("thumbnail").takeIf { it.isNotBlank() },
            formats = formats
        )
        return MediaExtractionResult.Success(info)
    }

    private fun parseFormats(array: JSONArray?): List<MediaFormat> {
        if (array == null) return emptyList()
        val formats = mutableListOf<MediaFormat>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            formats += parseFormat(entry, index)
        }
        return formats
    }

    private fun parseFormat(entry: JSONObject, index: Int): MediaFormat {
        val audioOnly = entry.optBoolean("audio_only", false)
        val height = entry.optInt("height", 0).takeIf { it > 0 }
        val extension = entry.optString("ext")
            .takeIf { it.isNotBlank() }
            ?.removePrefix(".")
            ?: if (audioOnly) "m4a" else "mp4"

        return MediaFormat(
            id = entry.optString("id").takeIf { it.isNotBlank() } ?: "format-$index",
            label = entry.optString("label"),
            extension = extension,
            height = height,
            width = entry.optInt("width", 0).takeIf { it > 0 },
            fps = entry.optDouble("fps", 0.0).takeIf { it > 0.0 },
            videoCodec = entry.optString("vcodec").takeIf { it.isNotBlank() && it != "none" },
            audioCodec = entry.optString("acodec").takeIf { it.isNotBlank() && it != "none" },
            fileSizeBytes = entry.optLong("filesize", 0L).takeIf { it > 0L },
            isAudioOnly = audioOnly,
            directUrl = entry.optString("url").takeIf { it.isNotBlank() },
            requiresMuxing = entry.optBoolean("requires_muxing", false)
        )
    }
}
