package com.linksi.app.enhanced.detect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last few accessibility events the detection service judged, so a user can see *why* a copy was
 * ignored.
 *
 * ## Why this is in memory only
 *
 * The accessibility service keeps no log and stores nothing, which is a deliberate privacy promise
 * (see its class comment). When detection fails in the field, that promise left no way to find out
 * what happened - the owner hit exactly that on a ColorOS device and there was nothing to inspect.
 *
 * This holds a handful of **[CopyObservation]s, which contain no text, no URL and no content
 * description** - only a timestamp, an event-type code, a package name and a verdict code. It is kept
 * in memory, is never written to disk, and disappears when the process does.
 *
 * The package name is included because it is the single most useful fact when diagnosing ("was the
 * event even from the app you copied from?"), and it is already visible to the user in their own
 * launcher. Nothing about *what* was copied is retained anywhere.
 */
object CopyObservationLog {

    /** Enough to see the burst produced by one copy action without becoming a transcript. */
    private const val CAPACITY = 12

    private val _recent = MutableStateFlow<List<CopyObservation>>(emptyList())

    /** Newest last. Empty until the service observes an event. */
    val recent: StateFlow<List<CopyObservation>> = _recent.asStateFlow()

    /** Appends [observation], keeping only the most recent [CAPACITY]. */
    @Synchronized
    fun record(observation: CopyObservation) {
        _recent.value = (_recent.value + observation).takeLast(CAPACITY)
    }

    /** Forgets everything; used when the user clears the list. */
    @Synchronized
    fun clear() {
        _recent.value = emptyList()
    }
}
