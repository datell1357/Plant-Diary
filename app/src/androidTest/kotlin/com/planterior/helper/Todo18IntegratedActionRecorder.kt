package com.planterior.helper

import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.feature.minihome.MiniHomeSaveActionObservation
import com.planterior.helper.feature.minihome.MiniHomeSaveActionStage
import com.planterior.helper.feature.watering.WateringConfirmActionObservation
import com.planterior.helper.feature.watering.WateringConfirmActionStage

internal class Todo18IntegratedActionRecorder {
    private val lock = Any()
    private var active: ActiveCapture? = null
    private var observationCount = 0

    fun isFresh(): Boolean = synchronized(lock) { active == null && observationCount == 0 }

    fun start(
        scenario: String,
        kind: Todo18IntegratedActionKind,
        listenerCount: Int,
    ): Capture =
        synchronized(lock) {
            check(active == null) { "Todo18 action capture already active" }
            val next = ActiveCapture(scenario, kind, listenerCount)
            active = next
            Capture(next)
        }

    fun record(observation: MiniHomeSaveActionObservation) {
        synchronized(lock) {
            observationCount += 1
            active
                ?.takeIf { it.kind == Todo18IntegratedActionKind.MINI_HOME_SAVE }
                ?.append(observation.stage.name, observation.operationId?.value, null)
        }
    }

    fun record(observation: WateringConfirmActionObservation) {
        synchronized(lock) {
            observationCount += 1
            active
                ?.takeIf { it.kind == Todo18IntegratedActionKind.WATERING_CONFIRM }
                ?.apply {
                    val operationId = observation.operationId?.value
                    if (operationId != null) fillPendingWateringOperation(operationId)
                    append(
                        observation.stage.name,
                        operationId,
                        observation.plantId?.value ?: wateringPlantId(),
                    )
                }
        }
    }

    fun recordMiniHomeSemantic(
        stage: MiniHomeSaveActionStage,
        operationId: OperationId,
    ) {
        synchronized(lock) {
            require(stage in MINI_HOME_SEMANTIC_STAGES)
            requireActive(Todo18IntegratedActionKind.MINI_HOME_SAVE).apply {
                recordSemantic(stage)
                append(stage.name, operationId.value, null)
            }
        }
    }

    fun recordWateringSemantic(
        stage: WateringConfirmActionStage,
        plantId: PersonalPlantId,
    ) {
        synchronized(lock) {
            require(stage in WATERING_SEMANTIC_STAGES)
            requireActive(Todo18IntegratedActionKind.WATERING_CONFIRM).apply {
                recordSemantic(stage)
                append(stage.name, null, plantId.value)
            }
        }
    }

    private fun requireActive(kind: Todo18IntegratedActionKind): ActiveCapture =
        checkNotNull(active?.takeIf { it.kind == kind }) { "Todo18 $kind capture is not active" }

    inner class Capture internal constructor(private val value: ActiveCapture) {
        fun recordBoundaryDelivery() {
            synchronized(lock) { value.boundaryDelivered = true }
        }

        fun close(
            listenerCount: Int,
            bindingValidated: Boolean,
        ): Todo18IntegratedActionSnapshot =
            synchronized(lock) {
                check(active === value) { "Todo18 action capture is not active" }
                active = null
                value.snapshot(listenerCount, bindingValidated)
            }
    }

    internal class ActiveCapture(
        val scenario: String,
        val kind: Todo18IntegratedActionKind,
        val initialListenerCount: Int,
    ) {
        private val observations = mutableListOf<Todo18IntegratedActionObservation>()
        private var semanticFacts = Todo18IntegratedSemanticFacts()
        var boundaryDelivered = false

        fun recordSemantic(stage: MiniHomeSaveActionStage) {
            semanticFacts =
                when (stage) {
                    MiniHomeSaveActionStage.SAVE_NODE_COUNT -> semanticFacts.copy(nodeCount = 1)
                    MiniHomeSaveActionStage.SAVE_NODE_DISPLAYED ->
                        semanticFacts.copy(displayed = true)
                    MiniHomeSaveActionStage.SAVE_NODE_ENABLED -> semanticFacts.copy(enabled = true)
                    MiniHomeSaveActionStage.SAVE_NODE_ON_CLICK -> semanticFacts.copy(onClick = true)
                    else -> semanticFacts
                }
        }

        fun recordSemantic(stage: WateringConfirmActionStage) {
            semanticFacts =
                when (stage) {
                    WateringConfirmActionStage.CONFIRM_NODE_COUNT ->
                        semanticFacts.copy(nodeCount = 1)
                    WateringConfirmActionStage.CONFIRM_NODE_DISPLAYED ->
                        semanticFacts.copy(displayed = true)
                    WateringConfirmActionStage.CONFIRM_NODE_ENABLED ->
                        semanticFacts.copy(enabled = true)
                    WateringConfirmActionStage.CONFIRM_NODE_ON_CLICK ->
                        semanticFacts.copy(onClick = true)
                    else -> semanticFacts
                }
        }

        fun append(stage: String, operationId: String?, plantId: String?) {
            observations +=
                Todo18IntegratedActionObservation(
                    ordinal = observations.size + 1,
                    stage = stage,
                    operationId = operationId,
                    plantId = plantId,
                )
        }

        fun fillPendingWateringOperation(operationId: String) {
            observations.indices.forEach { index ->
                val current = observations[index]
                if (current.operationId == null) {
                    observations[index] = current.copy(operationId = operationId)
                }
            }
        }

        fun wateringPlantId(): String? = observations.firstNotNullOfOrNull { it.plantId }

        fun snapshot(
            listenerCount: Int,
            bindingValidated: Boolean,
        ) =
            Todo18IntegratedActionSnapshot(
                scenario = scenario,
                kind = kind,
                observations = observations.toList(),
                semanticFacts = semanticFacts,
                bindingValidated = bindingValidated,
                boundaryDelivered = boundaryDelivered,
                initialListenerCount = initialListenerCount,
                finalListenerCount = listenerCount,
                captureClosed = true,
            )
    }

    private companion object {
        val MINI_HOME_SEMANTIC_STAGES =
            listOf(
                MiniHomeSaveActionStage.SAVE_NODE_COUNT,
                MiniHomeSaveActionStage.SAVE_NODE_DISPLAYED,
                MiniHomeSaveActionStage.SAVE_NODE_ENABLED,
                MiniHomeSaveActionStage.SAVE_NODE_ON_CLICK,
            )
        val WATERING_SEMANTIC_STAGES =
            listOf(
                WateringConfirmActionStage.CONFIRM_NODE_COUNT,
                WateringConfirmActionStage.CONFIRM_NODE_DISPLAYED,
                WateringConfirmActionStage.CONFIRM_NODE_ENABLED,
                WateringConfirmActionStage.CONFIRM_NODE_ON_CLICK,
            )
    }
}
