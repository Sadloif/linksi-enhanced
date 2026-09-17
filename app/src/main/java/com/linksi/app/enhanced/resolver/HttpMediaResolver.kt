package com.linksi.app.enhanced.resolver

import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Optional private server resolver (specification sections 24 and 25).
 *
 * This is the *fallback* path used when no local extractor can handle a link. Privacy rules from
 * the specification are enforced here rather than documented and hoped for:
 *
 *  - it is inert unless [ServerResolverConfig.isUsable], which itself requires an explicit opt-in
 *    **and** an `https://` base URL (section 25.4);
 *  - it sends exactly one thing: the URL the user asked about, as `{"url": "..."}`. Never clipboard
 *    contents, never accessibility content, never the Linksi database (section 25.2, 25.3, 25.8);
 *  - the API key, when configured, travels in the `Authorization` header, never in the URL, so it
 *    cannot leak through proxy or access logs;
 *  - every failure is a value ([MediaExtractionResult]), so a dead or hostile server degrades to
 *    "download unavailable" and leaves saving, opening and sharing untouched (section 26).
 *
 * The client is built internally from [ServerResolverConfig.timeoutSeconds] so this class needs no
 * Hilt binding and cannot collide with the media module's own `OkHttpClient` provider.
 */
class HttpMediaResolver(
    override val config: ServerResolverConfig,
    private val client: OkHttpClient = defaultClient(config)
) : MediaResolver {

    override val id: String = "private-server"
    override val displayName: String = "Private server"

    override suspend fun resolve(url: String, source: MediaSource): MediaExtractionResult {
        if (!config.isUsable) {
            return MediaExtractionResult.Skipped(url, config.unusableReason ?: "resolver not configured")
        }

        return withContext(Dispatchers.IO) {
            val endpoint = config.baseUrl.trim().trimEnd('/') + ENDPOINT
            val payload = runCatching {
                org.json.JSONObject().put("url", url).toString()
            }.getOrNull() ?: return@withContext MediaExtractionResult.Failure(
                MediaError.SERVER_UNAVAILABLE,
                url
            )

            val request = try {
                Request.Builder()
                    .url(endpoint)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .apply {
                        config.apiKey?.takeIf { it.isNotBlank() }?.let {
                            header("Authorization", "Bearer $it")
                        }
                    }
                    .build()
            } catch (e: IllegalArgumentException) {
                // A malformed configured base URL must not crash anything.
                return@withContext MediaExtractionResult.Failure(MediaError.SERVER_UNAVAILABLE, url, e)
            }

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext MediaExtractionResult.Failure(
                            MediaError.SERVER_UNAVAILABLE,
                            url
                        )
                    }
                    ResolverResponseParser.parse(url, source, response.body?.string())
                }
            } catch (e: Exception) {
                MediaExtractionResult.Failure(MediaError.SERVER_UNAVAILABLE, url, e)
            }
        }
    }

    companion object {
        private const val ENDPOINT = "/resolve"
        private const val USER_AGENT = "Linksi-Enhanced/1.0"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(config: ServerResolverConfig): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .callTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .build()
    }
}
