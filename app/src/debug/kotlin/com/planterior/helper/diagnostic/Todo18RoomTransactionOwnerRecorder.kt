package com.planterior.helper.diagnostic

import com.planterior.helper.core.database.RoomTransactionOwner
import com.planterior.helper.core.database.RoomTransactionOwnerObservation
import java.util.concurrent.atomic.AtomicLong

internal class Todo18RoomTransactionOwnerRecorder {
    private val lock = Any()
    private val observations = mutableListOf<Todo18OrderedRoomTransactionOwnerObservation>()
    private val listeners =
        linkedMapOf<Long, (Todo18OrderedRoomTransactionOwnerObservation) -> Unit>()
    private val nextListener = AtomicLong()

    fun record(observation: RoomTransactionOwnerObservation) {
        val ordered: Todo18OrderedRoomTransactionOwnerObservation
        val currentListeners: List<(Todo18OrderedRoomTransactionOwnerObservation) -> Unit>
        synchronized(lock) {
            ordered =
                Todo18OrderedRoomTransactionOwnerObservation(
                    order = observations.size + 1L,
                    observation = observation,
                )
            observations += ordered
            currentListeners = listeners.values.toList()
        }
        currentListeners.forEach { listener ->
            try {
                listener(ordered)
            } catch (_: AssertionError) {} catch (_: Exception) {}
        }
    }

    fun subscribe(listener: (Todo18OrderedRoomTransactionOwnerObservation) -> Unit): AutoCloseable {
        val id = nextListener.incrementAndGet()
        synchronized(lock) { listeners[id] = listener }
        return AutoCloseable { synchronized(lock) { listeners.remove(id) } }
    }

    fun snapshot(): List<Todo18OrderedRoomTransactionOwnerObservation> =
        synchronized(lock) { observations.toList() }
}

internal data class Todo18OrderedRoomTransactionOwnerObservation(
    val order: Long,
    val observation: RoomTransactionOwnerObservation,
)

internal sealed interface Todo18RoomTransactionOwnerClassification {
    data class Exact(
        val owner: RoomTransactionOwner,
        val token: Long,
    ) : Todo18RoomTransactionOwnerClassification

    data object Unknown : Todo18RoomTransactionOwnerClassification
}

internal object Todo18RoomTransactionOwnerClassifier {
    fun classify(
        events: List<Todo18RoomTransactionOwnerClassificationEvent>
    ): Todo18RoomTransactionOwnerClassification {
        val secondReadEntered = events.indexOfFirst {
            it is Todo18RoomTransactionOwnerClassificationEvent.PublicationReadEntered &&
                it.readId == 2L
        }
        if (secondReadEntered < 0) return Todo18RoomTransactionOwnerClassification.Unknown
        val firstReadReturned = events.indexOfFirst {
            it is Todo18RoomTransactionOwnerClassificationEvent.PublicationReadReturned &&
                it.readId == 1L
        }
        if (firstReadReturned !in 0 until secondReadEntered) {
            return Todo18RoomTransactionOwnerClassification.Unknown
        }
        val secondReadReturned =
            events
                .indexOfFirst {
                    it is Todo18RoomTransactionOwnerClassificationEvent.PublicationReadReturned &&
                        it.readId == 2L
                }
                .let { if (it < 0) events.size else it }
        val candidates =
            events.subList(0, secondReadEntered).mapIndexedNotNull { beginIndex, event ->
                val began =
                    event as? Todo18RoomTransactionOwnerClassificationEvent.Began
                        ?: return@mapIndexedNotNull null
                val matchingBegins =
                    events
                        .take(secondReadReturned)
                        .filterIsInstance<Todo18RoomTransactionOwnerClassificationEvent.Began>()
                        .filter { it.token == began.token }
                val matchingTerminals =
                    events
                        .take(secondReadReturned)
                        .filterIsInstance<Todo18RoomTransactionOwnerClassificationEvent.Terminal>()
                        .filter { it.token == began.token }
                val terminalIndex = events.indexOfFirst { it === matchingTerminals.singleOrNull() }
                began.takeIf {
                    matchingBegins.singleOrNull() == began &&
                        matchingTerminals.singleOrNull()?.owner == began.owner &&
                        terminalIndex > beginIndex &&
                        terminalIndex > secondReadEntered
                }
            }
        return candidates.singleOrNull()?.let {
            Todo18RoomTransactionOwnerClassification.Exact(it.owner, it.token)
        } ?: Todo18RoomTransactionOwnerClassification.Unknown
    }
}

internal sealed interface Todo18RoomTransactionOwnerClassificationEvent {
    data class PublicationReadEntered(val readId: Long) :
        Todo18RoomTransactionOwnerClassificationEvent

    data class PublicationReadReturned(val readId: Long) :
        Todo18RoomTransactionOwnerClassificationEvent

    data class Began(val owner: RoomTransactionOwner, val token: Long) :
        Todo18RoomTransactionOwnerClassificationEvent

    data class Terminal(val owner: RoomTransactionOwner, val token: Long) :
        Todo18RoomTransactionOwnerClassificationEvent
}
