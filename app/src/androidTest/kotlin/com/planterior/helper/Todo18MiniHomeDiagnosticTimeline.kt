package com.planterior.helper

import com.planterior.helper.feature.minihome.MiniHomeUiState

internal class Timeline {
    private val lock = Any()
    private val entries = mutableListOf<TimelineEntry>()

    fun recordBoundary(event: Todo18BoundaryEvent) =
        synchronized(lock) {
            entries +=
                TimelineEntry(
                    order = entries.size + 1L,
                    source = "boundary",
                    kind = event.kind,
                    identity = event.identity,
                    loadId = event.loadId,
                    readId = event.readId,
                    diagnosticOrder = event.diagnosticOrder,
                    cacheOutcome = event.cacheOutcome,
                )
        }

    fun recordState(source: String, event: Todo18MiniHomeStateEvent) =
        synchronized(lock) {
            entries +=
                TimelineEntry(
                    order = entries.size + 1L,
                    source = source,
                    sinkSequence = event.sequence,
                    state = event.state.stateName(),
                    owner = event.state.owner?.value,
                )
        }

    fun snapshot(): List<TimelineEntry> = synchronized(lock) { entries.toList() }
}

internal data class TimelineEntry(
    val order: Long,
    val source: String,
    val kind: String? = null,
    val identity: String? = null,
    val loadId: Long? = null,
    val readId: Long? = null,
    val diagnosticOrder: Long? = null,
    val cacheOutcome: String? = null,
    val sinkSequence: Long? = null,
    val state: String? = null,
    val owner: String? = null,
)

private fun MiniHomeUiState.stateName(): String =
    when (this) {
        is MiniHomeUiState.Loading -> "Loading"
        is MiniHomeUiState.Unavailable -> "Unavailable"
        is MiniHomeUiState.Viewing -> "Viewing"
        is MiniHomeUiState.Editing -> "Editing"
        MiniHomeUiState.Forbidden -> "Forbidden"
        MiniHomeUiState.Error -> "Error"
    }
