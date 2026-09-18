package com.linksi.app.enhanced.ui

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linksi.app.data.repository.LinkRepository
import com.linksi.app.domain.model.Link
import com.linksi.app.enhanced.capability.RuntimeCapabilities
import com.linksi.app.enhanced.download.DownloadEngine
import com.linksi.app.enhanced.download.DownloadRequest
import com.linksi.app.enhanced.download.DownloadState
import com.linksi.app.enhanced.download.DownloadWorkNaming
import com.linksi.app.enhanced.media.ExtractorRegistry
import com.linksi.app.enhanced.detect.SmartLinkDetector
import com.linksi.app.enhanced.media.MediaBackend
import com.linksi.app.enhanced.media.MediaError
import com.linksi.app.enhanced.media.MediaExtractionResult
import com.linksi.app.enhanced.media.MediaSourceDetector
import com.linksi.app.enhanced.media.ytdlp.YtDlpRuntime
import com.linksi.app.enhanced.resolver.MediaResolver
import com.linksi.app.enhanced.resolver.resolveLocalThenPrivateServer
import com.linksi.app.utils.extractDomain
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The UI module's read-only view of the Hilt graph.
 *
 * Everything the download UI needs - the engine, the extractor registry and the application
 * context - is already provided by `EnhancedMediaModule` and `AppModule`. This entry point simply
 * exposes those existing bindings to the Compose surfaces and to instrumented tests, so the UI
 * module adds **no new binding** for `OkHttpClient`, `DownloadEngine` or anything else (a second
 * `@Provides` for the same type would break the build).
 *
 * It is a Hilt `@EntryPoint` rather than an injectable class so a plain `@AndroidEntryPoint`
 * activity, and a test that only holds a `Context`, can reach the same objects.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadUiEntryPoint {

    fun downloadEngine(): DownloadEngine

    fun extractorRegistry(): ExtractorRegistry

    /**
     * The site engine, so the settings screen can report its version and refresh it on request.
     *
     * The engine is what reads Instagram, Facebook, TikTok, Pinterest and Reddit, and the copy the
     * wrapper ships goes stale as those sites change, so the version is something the user should be
     * able to see and act on rather than a hidden implementation detail.
     */
    fun ytDlpRuntime(): YtDlpRuntime

    /**
     * The smart-detection orchestrator, so Enhanced features can report whether detection is
     * actually working.
     *
     * This exists because the accessibility service keeps **no log at all**, by documented privacy
     * design, so when detection does not fire on a user's device there is nothing anywhere to read.
     * The detector computes an exact reason for declining - each switch, the overlay permission, the
     * rate limit - and holds it as state; exposing it is what turns a silent failure into a
     * diagnosable one.
     */
    fun smartLinkDetector(): SmartLinkDetector

    companion object {

        /** The graph behind [context]; works with the application or an activity context. */
        fun from(context: Context): DownloadUiEntryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                DownloadUiEntryPoint::class.java
            )
    }
}

/**
 * Extras the download UI exchanges with the rest of the app.
 *
 * Both are plain strings and live here (the UI module) rather than in the manifest, which is not
 * touched at all.
 */
object DownloadNavigation {

    /** Read by `MainActivity` to open the Downloads screen; set by the panel's "Open Downloads". */
    const val EXTRA_OPEN_DOWNLOADS = "com.linksi.app.extra.OPEN_DOWNLOADS"

    /**
     * The URL to show the quick panel for, when the caller already knows it (the link options
     * sheet).
     *
     * When this extra is **absent** the panel reads the clipboard exactly as before, which is what
     * the floating bubble relies on; when it is present the clipboard is not touched at all, so the
     * panel works the same whether or not the app holds input focus.
     */
    const val EXTRA_PANEL_URL = "com.linksi.app.enhanced.extra.PANEL_URL"
}

/** What the SAVE row last did, so the panel can say so without a second callback. */
enum class PanelSaveResult { NONE, SAVED, ALREADY_SAVED, FAILED }

/** What the quick panel renders, plus the messages and flags the activity reacts to. */
data class QuickPanelUiState(
    val panel: QuickPanelState,
    val download: PanelDownloadStatus? = null,
    /** A message to show under the panel, as a string resource id. Null when there is nothing. */
    @androidx.annotation.StringRes val message: Int? = null,
    val isSaving: Boolean = false,
    val saveResult: PanelSaveResult = PanelSaveResult.NONE,
    /** Set once the user asks to leave for the Downloads screen. Consumed by the activity. */
    val openDownloadsRequested: Boolean = false
)

/**
 * The quick panel's state holder (specification sections 13, 20 and 26).
 *
 * The activity around it stays a thin shell for [QuickActionPanel]: analysis, enqueueing and
 * progress observation all run in `viewModelScope`, are cancelled with the panel, and never touch
 * the main thread.
 *
 * Failure isolation is structural:
 *  - analysis runs through [ExtractorRegistry], which returns values rather than throwing, and is
 *    additionally wrapped, so a broken extractor can only ever produce a message;
 *  - every [DownloadEngine] call is total by contract, and every call here is wrapped anyway;
 *  - a failed download leaves [uiState] fully usable: the panel keeps its save, share, copy and
 *    clean actions, and simply shows the reason ([MediaError.ENGINE_UNAVAILABLE] and
 *    [MediaError.UNSUPPORTED_SITE] included).
 */
@HiltViewModel
class QuickPanelViewModel @Inject constructor(
    private val engine: DownloadEngine,
    private val extractors: ExtractorRegistry,
    private val resolver: MediaResolver,
    private val linkRepository: LinkRepository
) : ViewModel() {

    private val capabilities: RuntimeCapabilities = currentCapabilities()

    private val _uiState = MutableStateFlow(
        QuickPanelUiState(
            panel = buildPanelState(rawUrl = "", source = com.linksi.app.enhanced.media.MediaSource.UNKNOWN)
        )
    )

    val uiState: StateFlow<QuickPanelUiState> = _uiState.asStateFlow()

    /** Guards the whole preparation path: the panel analyses a URL exactly once. */
    private var preparedUrl: String? = null

    // ── Preparation ───────────────────────────────────────────────────────────

    /**
     * Points the panel at [url] and starts the one analysis that decides whether it can be
     * downloaded.
     *
     * The state is published immediately with the content type the URL alone can prove, so the
     * panel appears without waiting for the network; the analysis then either adds real formats (a
     * download row appears) or resolves nothing (no download row, every other action intact).
     */
    fun showUrl(url: String?) {
        val trimmed = url?.trim().orEmpty()
        if (trimmed == preparedUrl) return
        preparedUrl = trimmed

        _uiState.value = QuickPanelUiState(
            panel = buildPanelState(rawUrl = trimmed, source = MediaSourceDetector.fromUrl(trimmed))
        )
        if (trimmed.isBlank()) return

        viewModelScope.launch { analyze(trimmed) }
    }

    private suspend fun analyze(url: String) {
        val source = MediaSourceDetector.fromUrl(url)
        val result = resolveLocalThenPrivateServer(
            url = url,
            source = source,
            local = {
                safeCall {
                    extractors.analyze(url, source, capabilities)
                }.getOrElse { failure ->
                    MediaExtractionResult.Failure(MediaError.EXTRACTOR_FAILED, url, failure)
                }
            },
            resolver = resolver
        )

        // The panel may have been pointed at another URL (or finished) while the network call was
        // in flight; applying a stale result would show the wrong formats.
        if (preparedUrl != url) return

        when (result) {
            is MediaExtractionResult.Success -> {
                val info = result.info
                _uiState.update { current ->
                    current.copy(
                        panel = buildPanelState(
                            rawUrl = url,
                            source = info.source,
                            formats = info.formats,
                            title = info.title
                        )
                    )
                }
            }

            // A page, a private post or a format-less video: the panel simply has no download row.
            is MediaExtractionResult.Unsupported -> Unit

            // No extractor ran at all (engine missing on this ABI) or it broke. Say so, once,
            // without disabling anything else.
            is MediaExtractionResult.Skipped ->
                showMessage(MediaError.ENGINE_UNAVAILABLE)

            is MediaExtractionResult.Failure ->
                showMessage(result.error)
        }
    }
    // ── Downloading ───────────────────────────────────────────────────────────

    /**
     * Enqueues the download for [formatId] (empty for an image or direct file) and starts showing
     * its progress.
     *
     * A second tap of the *same* format is a no-op: the request id is unique per URL and format, so
     * the user cannot accidentally queue the same download twice, and choosing a different quality
     * starts a genuinely separate download.
     */
    fun download(formatId: String) {
        val panel = _uiState.value.panel
        val url = panel.cleanedUrl
        if (url.isBlank()) return

        val selectedFormat = panel.availableFormats.firstOrNull { it.id == formatId }
        val request = DownloadUrlMetadata.requestFor(
            url = url,
            formatId = formatId,
            source = panel.source,
            backend = selectedFormat?.backend ?: MediaBackend.LOCAL,
            requiresMuxing = selectedFormat?.requiresMuxing ?: false
        )
        val current = _uiState.value.download
        if (current != null && current.downloadId == request.id && !current.phase.isTerminal) return

        _uiState.update {
            it.copy(
                download = PanelDownloadStatus.queued(url, request.id, formatId),
                message = null
            )
        }

        viewModelScope.launch {
            safeCall { engine.enqueue(request) }
            observe(request.id)
        }
    }

    private suspend fun observe(id: String) {
        engine.observe(id)
            // A broken WorkManager database must not leave the panel frozen on "starting". The flow
            // is deliberately allowed to end here: the status card keeps the failure message and
            // every other panel action keeps working.
            .catch { _ ->
                _uiState.update { it.copy(download = failedStatus(id, MediaError.ENGINE_UNAVAILABLE)) }
            }
            .collect { state -> applyState(id, state) }
    }

    private fun applyState(id: String, state: DownloadState) {
        _uiState.update { current ->
            val status = current.download ?: return@update current
            if (status.downloadId != id) return@update current
            current.copy(
                download = status.copy(phase = state, label = panelLabelFor(state)),
                message = (state as? DownloadState.Failed)?.let { DownloadUiText.errorRes(it.error) }
            )
        }
    }

    /** Cancels the running download and reflects it straight away, without waiting for the engine. */
    fun cancelDownload() {
        val status = _uiState.value.download ?: return
        if (status.downloadId.isBlank()) return
        runCatching { engine.cancel(status.downloadId) }
        _uiState.update {
            it.copy(
                download = status.copy(
                    phase = DownloadState.Cancelled,
                    label = panelLabelFor(DownloadState.Cancelled)
                )
            )
        }
    }

    /**
     * Re-queues the failed download. The engine derives a fresh work name, so this observes the
     * derived id rather than the original one.
     */
    fun retryDownload() {
        val status = _uiState.value.download ?: return
        val originalId = status.downloadId
        if (originalId.isBlank()) return

        val nextId = DownloadWorkNaming.retryId(originalId)
        _uiState.update {
            it.copy(
                download = status.copy(
                    downloadId = nextId,
                    phase = DownloadState.Queued(0),
                    label = panelLabelFor(DownloadState.Queued(0))
                ),
                message = null
            )
        }

        viewModelScope.launch {
            safeCall { engine.retry(originalId) }
            observe(nextId)
        }
    }

    /** Clears the status card so the panel is back to just its actions. */
    fun dismissDownloadStatus() {
        _uiState.update { it.copy(download = null, message = null) }
    }

    /** Asks the activity to open the Downloads screen. */
    fun requestOpenDownloads() {
        _uiState.update { it.copy(openDownloadsRequested = true) }
    }

    fun consumeOpenDownloadsRequest() {
        _uiState.update { it.copy(openDownloadsRequested = false) }
    }

    // ── Saving (the link management action the host owns) ─────────────────────

    /** Saves the cleaned URL through the same repository the rest of the app uses. */
    fun save() {
        val state = _uiState.value
        if (state.isSaving || state.saveResult == PanelSaveResult.SAVED) return
        val url = state.panel.cleanedUrl
        if (url.isBlank()) return

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = safeCall {
                when {
                    linkRepository.isUrlAlreadySaved(url) -> PanelSaveResult.ALREADY_SAVED
                    linkRepository.insertLink(linkFor(url, state.panel.title)) > 0L ->
                        PanelSaveResult.SAVED
                    else -> PanelSaveResult.FAILED
                }
            }.getOrDefault(PanelSaveResult.FAILED)

            _uiState.update { it.copy(isSaving = false, saveResult = result) }
        }
    }

    /** Re-saves the link under its cleaned address ("Clean URL", specification section 9.5). */
    fun cleanUrl(cleanedUrl: String) {
        if (cleanedUrl.isBlank()) return
        viewModelScope.launch {
            safeCall {
                val existing = linkRepository.getLinkByUrl(_uiState.value.panel.url)
                if (existing != null) {
                    linkRepository.updateLink(existing.copy(url = cleanedUrl))
                } else {
                    linkRepository.insertLink(linkFor(cleanedUrl, _uiState.value.panel.title))
                }
            }
        }
    }

    private fun linkFor(url: String, title: String): Link {
        val domain = runCatching { extractDomain(url) }.getOrDefault("")
        return Link(
            url = url,
            title = title.ifBlank { domain },
            domain = domain,
            faviconUrl = if (domain.isBlank()) {
                ""
            } else {
                "https://www.google.com/s2/favicons?domain=$domain&sz=64"
            }
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showMessage(error: MediaError) {
        _uiState.update { it.copy(message = DownloadUiText.errorRes(error)) }
    }

    private fun failedStatus(id: String, error: MediaError): PanelDownloadStatus {
        val state = DownloadState.Failed(error)
        return PanelDownloadStatus(
            url = _uiState.value.panel.cleanedUrl,
            downloadId = id,
            phase = state,
            label = panelLabelFor(state)
        )
    }

    /**
     * The device facts the extractor registry needs, read here rather than injected, so the UI
     * module adds no binding that could collide with `EnhancedMediaModule`.
     */
    private fun currentCapabilities(): RuntimeCapabilities = RuntimeCapabilities(
        sdkInt = Build.VERSION.SDK_INT,
        supportedAbis = Build.SUPPORTED_ABIS.toList()
    )
}

/**
 * `runCatching` for suspending code.
 *
 * The stdlib version catches `Throwable`, which would also swallow the `CancellationException` that
 * cancels a coroutine - a suspended view-model scope must let that through, or a closed panel could
 * leave work running. Only genuine failures become a [Result].
 */
internal suspend inline fun <T> safeCall(block: () -> T): Result<T> = try {    Result.success(block())
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
