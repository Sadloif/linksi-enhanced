package com.linksi.app.enhanced.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.linksi.app.enhanced.media.MediaError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The [DownloadEngine] implementation: one unique [DownloadWorker] per [DownloadRequest]
 * (specification section 23).
 *
 * How state reaches the UI:
 *  - The single source of truth is WorkManager itself. `observe` combines
 *    `WorkManager.getWorkInfosForUniqueWorkFlow` with a small in-memory map, so a download queued in
 *    a previous process still reports its real state.
 *  - The in-memory map only supplies what WorkManager cannot: the initial `Queued` state between
 *    `enqueue` and the first database write, the `Cancelled` state right after `cancel`, failures to
 *    enqueue at all, and the `DownloadRequest` payload needed by [retry].
 *  - Nothing is collected until someone observes, and nothing is started by creating the engine:
 *    the module is completely idle until the user asks for a download (specification section 26).
 *
 * Every public method is total: an unusable WorkManager produces `Failed(ENGINE_UNAVAILABLE)`
 * values, never an exception thrown into the UI.
 */
@Singleton
class WorkManagerDownloadEngine @Inject constructor(
    private val workManager: WorkManager,
    private val notifications: DownloadNotifications
) : DownloadEngine {

    private val localStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    /** Ids this process knows about, seeded from persisted work the first time all are observed. */
    private val knownIds = MutableStateFlow<Set<String>>(emptySet())

    /** Finished entries the user removed from the list. Kept in memory only; WorkManager prunes. */
    private val dismissedIds = MutableStateFlow<Set<String>>(emptySet())

    private val requests = ConcurrentHashMap<String, DownloadRequest>()

    private val adoptedPersistedWork = AtomicBoolean(false)

    override fun observe(id: String): Flow<DownloadState> =
        combine(workStateFlow(id), localStates, dismissedIds) { work, local, dismissed ->
            when {
                id in dismissed -> DownloadState.Idle
                work != null -> work
                else -> local[id] ?: DownloadState.Idle
            }
        }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAll(): Flow<Map<String, DownloadState>> = flow {
        adoptPersistedWork()
        emitAll(
            combine(knownIds, dismissedIds) { ids, dismissed -> ids - dismissed }
                .flatMapLatest { ids -> statesOf(ids) }
                .distinctUntilChanged()
        )
    }

    override suspend fun enqueue(request: DownloadRequest) {
        requests[request.id] = request
        dismissedIds.update { it - request.id }
        knownIds.update { it + request.id }
        localStates.update { it + (request.id to DownloadState.Queued(0)) }

        val enqueued = runCatching {
            workManager.enqueueUniqueWork(
                request.id,
                ExistingWorkPolicy.KEEP,
                workRequest(request)
            )
        }

        enqueued.exceptionOrNull()?.let { failure ->
            // WorkManager unavailable: the download action degrades, nothing crashes.
            localStates.update {
                it + (request.id to DownloadState.Failed(MediaError.ENGINE_UNAVAILABLE, failure.message))
            }
        }
    }

    override fun cancel(id: String) {
        runCatching { workManager.cancelUniqueWork(id) }
        notifications.cancel(id)
        localStates.update { it + (id to DownloadState.Cancelled) }
    }

    override suspend fun retry(id: String) {
        // After a process restart the in-memory payload is gone, so it is rebuilt from the request
        // echoed into the finished work item's output data.
        val original = requests[id] ?: recoverRequest(id) ?: return

        // The retry is a new unique work name: `ExistingWorkPolicy.KEEP` would otherwise ignore it.
        dismissedIds.update { it + id }
        localStates.update { it - id }
        enqueue(original.copy(id = DownloadWorkNaming.retryId(id)))
    }

    override fun dismiss(id: String) {
        dismissedIds.update { it + id }
        localStates.update { it - id }
        requests.remove(id)
        notifications.cancel(id)
    }

    private fun workRequest(request: DownloadRequest): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(DownloadWorker::class.java)
            .setInputData(DownloadWorkKeys.input(request))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // Exponential backoff is what makes a flaky connection survivable without hammering it.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(DownloadWorkNaming.TAG_DOWNLOAD)
            .addTag(DownloadWorkNaming.requestTag(request.id))
            .build()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun statesOf(ids: Set<String>): Flow<Map<String, DownloadState>> {
        if (ids.isEmpty()) return flowOf(emptyMap())
        // Sorted so the set of combined flows is stable while a download runs.
        val ordered = ids.sorted()
        return combine(
            ordered.map { id -> observe(id).map { state -> id to state } }
        ) { pairs -> pairs.toMap() }
    }

    private fun workStateFlow(id: String): Flow<DownloadState?> =
        workManager.getWorkInfosForUniqueWorkFlow(id)
            .map { infos -> infos.maxByOrNull { it.generation }?.toDownloadState() }
            // A broken WorkManager database must not kill the downloads screen.
            .catch { emit(null) }

    /**
     * Finds work that outlived this process and adds its ids, so the downloads screen still lists a
     * download that was running when the app was killed. The request payload is not recoverable
     * from `WorkInfo`, which is why [retry] also consults the output data.
     */
    private suspend fun adoptPersistedWork() {
        if (!adoptedPersistedWork.compareAndSet(false, true)) return

        val ids = runCatching {
            workManager.getWorkInfosFlow(
                WorkQuery.Builder.fromTags(listOf(DownloadWorkNaming.TAG_DOWNLOAD)).build()
            ).first()
        }.getOrDefault(emptyList())
            .mapNotNull { DownloadWorkNaming.requestIdFromTags(it.tags) }
            .toSet()

        if (ids.isNotEmpty()) knownIds.update { it + ids }
    }

    private suspend fun recoverRequest(id: String): DownloadRequest? = runCatching {
        workManager.getWorkInfosForUniqueWorkFlow(id).first()
            .asSequence()
            .map { it.outputData }
            .mapNotNull { DownloadWorkKeys.readRequest(it) }
            .firstOrNull()
    }.getOrNull()

    private fun WorkInfo.toDownloadState(): DownloadState = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Queued(0)

        WorkInfo.State.RUNNING -> DownloadState.Downloading(
            bytesDownloaded = DownloadWorkKeys.readBytes(progress),
            totalBytes = DownloadWorkKeys.readTotalBytes(progress),
            bytesPerSecond = DownloadWorkKeys.readSpeed(progress)
        )

        WorkInfo.State.SUCCEEDED -> DownloadState.Completed(
            filePath = DownloadWorkKeys.readLocation(outputData).orEmpty(),
            bytes = DownloadWorkKeys.readBytes(outputData),
            mimeType = DownloadWorkKeys.readMimeType(outputData),
            displayName = DownloadWorkKeys.readDisplayName(outputData)
        )

        WorkInfo.State.FAILED -> DownloadState.Failed(
            error = DownloadWorkKeys.readError(outputData),
            detail = DownloadWorkKeys.readErrorDetail(outputData)
        )

        WorkInfo.State.CANCELLED -> DownloadState.Cancelled
    }

    private companion object {
        /** WorkManager's minimum is 10 s; 30 s keeps a retry storm well clear of the server. */
        const val BACKOFF_SECONDS = 30L
    }
}
