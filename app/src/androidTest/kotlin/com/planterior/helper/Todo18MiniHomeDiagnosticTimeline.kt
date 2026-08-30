package com.planterior.helper

import com.planterior.helper.core.database.RoomTransactionOwnerObservation
import com.planterior.helper.diagnostic.Todo18OrderedRoomTransactionOwnerObservation
import com.planterior.helper.diagnostic.Todo18RoomTransactionOwnerClassificationEvent
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

    fun recordTransactionOwner(event: Todo18OrderedRoomTransactionOwnerObservation) =
        synchronized(lock) {
            val observation = event.observation
            entries +=
                TimelineEntry(
                    order = entries.size + 1L,
                    source = "room-transaction-owner",
                    kind =
                        when (observation) {
                            is RoomTransactionOwnerObservation.Began -> "began"
                            is RoomTransactionOwnerObservation.Returned -> "returned"
                            is RoomTransactionOwnerObservation.Threw -> "threw"
                            is RoomTransactionOwnerObservation.Cancelled -> "cancelled"
                        },
                    transactionOwner = observation.owner.name,
                    transactionToken = observation.token.value,
                    transactionFailureClass =
                        when (observation) {
                            is RoomTransactionOwnerObservation.Threw ->
                                observation.failure.javaClass.name
                            is RoomTransactionOwnerObservation.Cancelled ->
                                observation.failure.javaClass.name
                            else -> null
                        },
                    transactionFailureMessage =
                        when (observation) {
                            is RoomTransactionOwnerObservation.Threw -> observation.failure.message
                            is RoomTransactionOwnerObservation.Cancelled ->
                                observation.failure.message
                            else -> null
                        },
                )
        }
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
    val transactionOwner: String? = null,
    val transactionToken: Long? = null,
    val transactionFailureClass: String? = null,
    val transactionFailureMessage: String? = null,
)

internal fun TimelineEntry.transactionOwnerClassificationEvent():
    Todo18RoomTransactionOwnerClassificationEvent? =
    when {
        kind == "publication-read-entered" && readId != null ->
            Todo18RoomTransactionOwnerClassificationEvent.PublicationReadEntered(readId)
        kind == "publication-read-returned" && readId != null ->
            Todo18RoomTransactionOwnerClassificationEvent.PublicationReadReturned(readId)
        source == "room-transaction-owner" && kind == "began" ->
            Todo18RoomTransactionOwnerClassificationEvent.Began(
                checkNotNull(enumValueOfOrNull(transactionOwner)),
                checkNotNull(transactionToken),
            )
        source == "room-transaction-owner" && kind in setOf("returned", "threw", "cancelled") ->
            Todo18RoomTransactionOwnerClassificationEvent.Terminal(
                checkNotNull(enumValueOfOrNull(transactionOwner)),
                checkNotNull(transactionToken),
            )
        else -> null
    }

private inline fun <reified T : Enum<T>> enumValueOfOrNull(value: String?): T? = value?.let {
    runCatching { enumValueOf<T>(it) }.getOrNull()
}

private fun MiniHomeUiState.stateName(): String =
    when (this) {
        is MiniHomeUiState.Loading -> "Loading"
        is MiniHomeUiState.Unavailable -> "Unavailable"
        is MiniHomeUiState.Viewing -> "Viewing"
        is MiniHomeUiState.Editing -> "Editing"
        MiniHomeUiState.Forbidden -> "Forbidden"
        MiniHomeUiState.Error -> "Error"
    }
