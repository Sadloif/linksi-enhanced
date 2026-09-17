package com.linksi.app.enhanced.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linksi.app.enhanced.download.DownloadEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Downloads screen renders. */
data class DownloadsUiState(
    val rows: List<DownloadRow> = emptyList(),
    /** True until the engine has produced its first snapshot, so the empty state is not premature. */
    val isLoading: Boolean = true,
    /** True when the engine could not be read at all. The screen then says so instead of hanging. */
    val isUnavailable: Boolean = false
) {
    val activeRows: List<DownloadRow> get() = DownloadListModel.activeRows(rows)
    val finishedRows: List<DownloadRow> get() = DownloadListModel.finishedRows(rows)
    val isEmpty: Boolean get() = rows.isEmpty()
}

/**
 * The Downloads screen's state holder (specification section 22).
 *
 * It does exactly one thing: mirror [DownloadEngine.observeAll] into an ordered list of
 * [DownloadRow]s and forward the three per-row actions. All formatting lives in the pure
 * [DownloadListModel], so the ordering rules and the "never invent a percentage" rule are unit
 * tested without Android.
 *
 * Reading the engine is lazy: nothing is collected until the screen is opened, and the collection
 * stops when the screen's view-model is cleared. Nothing here runs at app start.
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val engine: DownloadEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            engine.observeAll()
                .catch { _ -> _uiState.update { it.copy(isLoading = false, isUnavailable = true) } }
                .collect { states ->
                    _uiState.update {
                        it.copy(
                            rows = DownloadListModel.rowsOf(states),
                            isLoading = false,
                            isUnavailable = false
                        )
                    }
                }
        }
    }

    fun cancel(id: String) {
        runCatching { engine.cancel(id) }
    }

    fun retry(id: String) {
        viewModelScope.launch { runCatching { engine.retry(id) } }
    }

    fun dismiss(id: String) {
        runCatching { engine.dismiss(id) }
    }
}
