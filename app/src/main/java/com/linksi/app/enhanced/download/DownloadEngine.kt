package com.linksi.app.enhanced.download

import kotlinx.coroutines.flow.Flow

/**
 * Background download execution (specification section 23).
 *
 * The engine is an interface so the Android implementation (WorkManager plus a typed foreground
 * service) stays replaceable and so the UI never touches platform APIs directly. `enqueue` must not
 * block: it registers the request and returns, and all progress is observed through [observe].
 *
 * Every method must be safe to call when the engine is unavailable - a missing engine disables the
 * download action, it never throws into the UI (specification section 26).
 */
interface DownloadEngine {

    /** State changes for one download. Completes when the state is terminal. */
    fun observe(id: String): Flow<DownloadState>

    /** All known downloads, keyed by request id, for the downloads screen and notifications. */
    fun observeAll(): Flow<Map<String, DownloadState>>

    /** Registers a download. [DownloadRequest.id] is the caller-supplied stable id. */
    suspend fun enqueue(request: DownloadRequest)

    /** Cancels an active download and removes any partial file. Safe to call for unknown ids. */
    fun cancel(id: String)

    /** Re-queues a failed or cancelled download with a fresh id derived from the original. */
    suspend fun retry(id: String)

    /** Removes a finished entry from the store (does not delete the file). */
    fun dismiss(id: String)
}

/** Small helper so callers can reason about a whole batch without touching the engine twice. */
fun Map<String, DownloadState>.activeDownloads(): List<Pair<String, DownloadState.Downloading>> =
    entries.mapNotNull { (id, state) ->
        (state as? DownloadState.Downloading)?.let { id to it }
    }

fun Map<String, DownloadState>.hasActiveWork(): Boolean = values.any { !it.isTerminal }
