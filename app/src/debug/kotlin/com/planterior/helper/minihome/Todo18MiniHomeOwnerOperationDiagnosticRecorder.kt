package com.planterior.helper.minihome

import com.planterior.helper.feature.minihome.MiniHomeOwnerOperationObservation
import com.planterior.helper.feature.minihome.MiniHomePublicationTransactionObservation

/** Non-suspending, instance-owned diagnostic capture for owner and Room boundaries. */
internal class Todo18MiniHomeOwnerOperationDiagnosticRecorder {
    companion object {
        const val MAX_EVENTS = 256
    }

    private val lock = Any()

    data class Event(
        val order: Long,
        val thread: String,
        val ownerOperation: MiniHomeOwnerOperationObservation? = null,
        val publicationTransaction: MiniHomePublicationTransactionObservation? = null,
    )

    private val events = mutableListOf<Event>()
    private var nextOrder = 1L
    private var droppedEvents = 0

    fun recordOwnerOperation(observation: MiniHomeOwnerOperationObservation) {
        synchronized(lock) {
            if (events.size < MAX_EVENTS) {
                events +=
                    Event(nextOrder++, Thread.currentThread().name, ownerOperation = observation)
            } else droppedEvents++
        }
    }

    fun recordPublicationTransaction(observation: MiniHomePublicationTransactionObservation) {
        synchronized(lock) {
            if (events.size < MAX_EVENTS) {
                events +=
                    Event(
                        nextOrder++,
                        Thread.currentThread().name,
                        publicationTransaction = observation,
                    )
            } else droppedEvents++
        }
    }

    data class Snapshot(val events: List<Event>, val droppedEvents: Int)

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(events.toList(), droppedEvents) }
}
