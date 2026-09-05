package com.planterior.helper

import com.planterior.helper.feature.share.MiniHomeShareDiagnosticObservation
import com.planterior.helper.feature.share.MiniHomeShareDiagnosticStage
import com.planterior.helper.feature.share.MiniHomeShareUiState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal class Todo18MiniHomeShareDiagnosticRecorder {
    private val lock = Any()

    private data class Event(
        val order: Long,
        val thread: String,
        val observation: MiniHomeShareDiagnosticObservation,
    )

    private val events = mutableListOf<Event>()
    private var dropped = 0
    private var nextOrder = 0L
    private val maxEvents = 256

    fun record(observation: MiniHomeShareDiagnosticObservation) =
        synchronized(lock) {
            nextOrder += 1
            if (events.size < maxEvents) {
                events += Event(nextOrder, Thread.currentThread().name, observation)
            } else {
                dropped++
            }
        }

    fun recordDisplayed(state: MiniHomeShareUiState) =
        synchronized(lock) {
            record(
                MiniHomeShareDiagnosticObservation(
                    stage = MiniHomeShareDiagnosticStage.DISPLAYED_STATE_OBSERVED,
                    owner = state.owner,
                    generation = null,
                    stateKind = state::class.simpleName ?: "unknown",
                )
            )
        }

    fun toJson(): String =
        synchronized(lock) {
            Json.encodeToString(
                buildJsonObject {
                    put("droppedCount", dropped)
                    putJsonArray("events") {
                        events.forEach { event ->
                            add(
                                buildJsonObject {
                                    put("order", event.order)
                                    put("thread", event.thread)
                                    put("stage", event.observation.stage.name.lowercase())
                                    put("owner", event.observation.owner?.value)
                                    put("generation", event.observation.generation)
                                    put("stateKind", event.observation.stateKind)
                                    put("resultKind", event.observation.resultKind)
                                }
                            )
                        }
                    }
                }
            )
        }
}
