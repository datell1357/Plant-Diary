package com.planterior.helper.diagnostic

import com.planterior.helper.feature.minihome.MiniHomeRetryObservation
import com.planterior.helper.feature.minihome.MiniHomeRetryStage
import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal enum class Todo18OfflineRetryTransitionStage {
    TRIGGER_RETURNED,
    MINI_HOME_COMMITTED,
    RAW_CONTROLLER_STATE,
    ROUTE_DISPLAYED_CALLBACK,
    RENDERED_SINK_DELIVERY,
}

internal data class Todo18OfflineRetryTransitionObservation(
    val stage: Todo18OfflineRetryTransitionStage,
    val operationId: String,
    val revision: Long? = null,
)

internal data class Todo18OfflineRetryBoundaryObservation(
    val stage: MiniHomeRetryStage,
    val operationId: String?,
    val controllerEpoch: Long?,
    val controllerGeneration: Long?,
    val saveGeneration: Long?,
    val guardDraftIdentity: Boolean?,
    val revision: Long?,
    val outcome: String?,
    val resultIdentity: Int?,
    val failureIdentity: Int?,
    val failureClass: String?,
    val failureMessage: String?,
)

internal data class Todo18OfflineRetryTransitionReceipt(
    val observations: List<Todo18OfflineRetryTransitionObservation>,
    val closed: Boolean,
    val outcomeClass: String? = null,
    val outcomeMessage: String? = null,
    val retryObservations: List<Todo18OfflineRetryBoundaryObservation> = emptyList(),
) {
    fun withPrimaryFailure(primaryFailure: Throwable): Todo18OfflineRetryTransitionReceipt =
        copy(
            outcomeClass = primaryFailure.javaClass.name,
            outcomeMessage = primaryFailure.message,
        )

    fun requireComplete(operationId: String, committedIdentity: String, finalRevision: Long) {
        require(closed) { "offline-retry-transition-unclosed" }
        require(committedIdentity == operationId) { "offline-retry-boundary-identity-mismatch" }
        require(observations.map { it.stage } == Todo18OfflineRetryTransitionStage.entries) {
            "offline-retry-transition-missing-duplicate-or-out-of-order"
        }
        require(observations.all { it.operationId == operationId }) {
            "offline-retry-transition-operation-mismatch"
        }
        require(finalRevision > 1L) { "offline-retry-final-revision-invalid" }
        require(observations.takeLast(3).all { it.revision == finalRevision }) {
            "offline-retry-transition-revision-mismatch"
        }
        requireRetryBoundaries(operationId, finalRevision)
    }

    private fun requireRetryBoundaries(operationId: String, finalRevision: Long) {
        val expectedStages =
            setOf(
                MiniHomeRetryStage.CALLBACK_ENTRY,
                MiniHomeRetryStage.COROUTINE_ENTRY,
                MiniHomeRetryStage.COROUTINE_RETURNED,
                MiniHomeRetryStage.REPOSITORY_SAVE_ENTRY,
                MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED,
                MiniHomeRetryStage.SAVED_APPLY_ENTRY,
                MiniHomeRetryStage.SET_STATE_ATTEMPTED,
                MiniHomeRetryStage.SET_STATE_APPLIED,
                MiniHomeRetryStage.RAW_STATE_PUBLICATION,
            )
        require(retryObservations.size == expectedStages.size) {
            "offline-retry-boundary-cardinality-mismatch"
        }
        require(retryObservations.map { it.stage }.toSet() == expectedStages) {
            "offline-retry-boundary-missing-duplicate-or-unexpected-stage"
        }
        require(
            retryObservations.none {
                it.stage == MiniHomeRetryStage.COROUTINE_THROWN ||
                    it.stage == MiniHomeRetryStage.COROUTINE_CANCELLED ||
                    it.stage == MiniHomeRetryStage.REPOSITORY_SAVE_THROWN ||
                    it.stage == MiniHomeRetryStage.REPOSITORY_SAVE_CANCELLED ||
                    it.stage == MiniHomeRetryStage.SAVED_APPLY_REJECTED ||
                    it.stage == MiniHomeRetryStage.SET_STATE_REJECTED
            }
        ) {
            "offline-retry-boundary-rejected-or-failed-stage"
        }

        fun exactlyOne(stage: MiniHomeRetryStage): Todo18OfflineRetryBoundaryObservation {
            val matches = retryObservations.filter { it.stage == stage }
            require(matches.size == 1) {
                "offline-retry-boundary-${stage.name.lowercase()}-cardinality"
            }
            return matches.single()
        }

        val callback = exactlyOne(MiniHomeRetryStage.CALLBACK_ENTRY)
        val coroutineEntry = exactlyOne(MiniHomeRetryStage.COROUTINE_ENTRY)
        val coroutineReturned = exactlyOne(MiniHomeRetryStage.COROUTINE_RETURNED)
        val repositoryEntry = exactlyOne(MiniHomeRetryStage.REPOSITORY_SAVE_ENTRY)
        val repositoryReturned = exactlyOne(MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED)
        val savedApply = exactlyOne(MiniHomeRetryStage.SAVED_APPLY_ENTRY)
        val setStateAttempted = exactlyOne(MiniHomeRetryStage.SET_STATE_ATTEMPTED)
        val setStateApplied = exactlyOne(MiniHomeRetryStage.SET_STATE_APPLIED)
        val rawPublication = exactlyOne(MiniHomeRetryStage.RAW_STATE_PUBLICATION)

        require(retryObservations.all { it.operationId == operationId }) {
            "offline-retry-boundary-operation-mismatch"
        }
        listOf(callback, coroutineEntry, coroutineReturned).forEach { observation ->
            require(observation.controllerEpoch == null) {
                "offline-retry-boundary-screen-epoch-mismatch"
            }
            require(observation.controllerGeneration == null) {
                "offline-retry-boundary-screen-generation-mismatch"
            }
            require(observation.saveGeneration == null) {
                "offline-retry-boundary-screen-save-generation-mismatch"
            }
            require(observation.guardDraftIdentity == null) {
                "offline-retry-boundary-screen-guard-mismatch"
            }
        }
        require(callback.revision == null && callback.outcome == null) {
            "offline-retry-boundary-callback-payload-mismatch"
        }
        require(
            callback.resultIdentity == null &&
                callback.failureIdentity == null &&
                callback.failureClass == null &&
                callback.failureMessage == null
        ) {
            "offline-retry-boundary-callback-failure-payload-mismatch"
        }
        require(coroutineEntry.revision == null && coroutineEntry.outcome == null) {
            "offline-retry-boundary-coroutine-entry-payload-mismatch"
        }
        require(
            coroutineEntry.resultIdentity == null &&
                coroutineEntry.failureIdentity == null &&
                coroutineEntry.failureClass == null &&
                coroutineEntry.failureMessage == null
        ) {
            "offline-retry-boundary-coroutine-entry-failure-payload-mismatch"
        }
        require(coroutineReturned.outcome == "returned" && coroutineReturned.revision == null) {
            "offline-retry-boundary-coroutine-terminal-mismatch"
        }
        require(
            coroutineReturned.resultIdentity == null &&
                coroutineReturned.failureIdentity == null &&
                coroutineReturned.failureClass == null &&
                coroutineReturned.failureMessage == null
        ) {
            "offline-retry-boundary-coroutine-terminal-identity-mismatch"
        }

        val epoch =
            requireNotNull(repositoryEntry.controllerEpoch) {
                "offline-retry-boundary-controller-epoch-missing"
            }
        val generation =
            requireNotNull(repositoryEntry.controllerGeneration) {
                "offline-retry-boundary-controller-generation-missing"
            }
        val saveGeneration =
            requireNotNull(repositoryEntry.saveGeneration) {
                "offline-retry-boundary-save-generation-missing"
            }

        fun requireControllerIdentity(
            observation: Todo18OfflineRetryBoundaryObservation,
            expectedSaveGeneration: Long?,
            expectedGuard: Boolean,
        ) {
            require(observation.controllerEpoch == epoch) {
                "offline-retry-boundary-controller-epoch-mismatch"
            }
            require(observation.controllerGeneration == generation) {
                "offline-retry-boundary-controller-generation-mismatch"
            }
            require(observation.saveGeneration == expectedSaveGeneration) {
                "offline-retry-boundary-save-generation-mismatch"
            }
            require(observation.guardDraftIdentity == expectedGuard) {
                "offline-retry-boundary-guard-mismatch"
            }
        }

        requireControllerIdentity(repositoryEntry, saveGeneration, expectedGuard = true)
        requireControllerIdentity(repositoryReturned, saveGeneration, expectedGuard = true)
        requireControllerIdentity(savedApply, saveGeneration, expectedGuard = true)
        requireControllerIdentity(setStateAttempted, null, expectedGuard = false)
        requireControllerIdentity(setStateApplied, null, expectedGuard = false)
        require(rawPublication.controllerEpoch == null) {
            "offline-retry-boundary-raw-publication-epoch-mismatch"
        }
        require(rawPublication.controllerGeneration == null) {
            "offline-retry-boundary-raw-publication-generation-mismatch"
        }
        require(
            rawPublication.saveGeneration == null && rawPublication.guardDraftIdentity == false
        ) {
            "offline-retry-boundary-raw-publication-token-mismatch"
        }

        require(repositoryEntry.revision == null && repositoryEntry.outcome == null) {
            "offline-retry-boundary-repository-entry-payload-mismatch"
        }
        require(
            repositoryEntry.resultIdentity == null &&
                repositoryEntry.failureIdentity == null &&
                repositoryEntry.failureClass == null &&
                repositoryEntry.failureMessage == null
        ) {
            "offline-retry-boundary-repository-entry-identity-mismatch"
        }
        require(repositoryReturned.outcome == "returned") {
            "offline-retry-boundary-repository-terminal-outcome-mismatch"
        }
        require(repositoryReturned.revision == finalRevision) {
            "offline-retry-boundary-repository-revision-mismatch"
        }
        val resultIdentity =
            requireNotNull(repositoryReturned.resultIdentity) {
                "offline-retry-boundary-result-identity-missing"
            }
        require(repositoryReturned.failureIdentity == null) {
            "offline-retry-boundary-repository-failure-identity-present"
        }
        require(
            repositoryReturned.failureClass == null && repositoryReturned.failureMessage == null
        ) {
            "offline-retry-boundary-repository-failure-payload-present"
        }
        require(savedApply.revision == finalRevision) {
            "offline-retry-boundary-saved-apply-revision-mismatch"
        }
        require(savedApply.resultIdentity == resultIdentity) {
            "offline-retry-boundary-result-identity-mismatch"
        }
        require(
            savedApply.outcome == null &&
                savedApply.failureIdentity == null &&
                savedApply.failureClass == null &&
                savedApply.failureMessage == null
        ) {
            "offline-retry-boundary-saved-apply-payload-mismatch"
        }
        require(
            setStateAttempted.revision == null &&
                setStateAttempted.outcome == null &&
                setStateAttempted.resultIdentity == null &&
                setStateAttempted.failureIdentity == null &&
                setStateAttempted.failureClass == null &&
                setStateAttempted.failureMessage == null
        ) {
            "offline-retry-boundary-set-state-attempt-payload-mismatch"
        }
        require(
            setStateApplied.revision == finalRevision &&
                setStateApplied.outcome == null &&
                setStateApplied.resultIdentity == null &&
                setStateApplied.failureIdentity == null &&
                setStateApplied.failureClass == null &&
                setStateApplied.failureMessage == null
        ) {
            "offline-retry-boundary-set-state-applied-payload-mismatch"
        }
        require(rawPublication.revision == finalRevision && rawPublication.outcome == null) {
            "offline-retry-boundary-raw-publication-payload-mismatch"
        }
        require(
            rawPublication.resultIdentity == null &&
                rawPublication.failureIdentity == null &&
                rawPublication.failureClass == null &&
                rawPublication.failureMessage == null
        ) {
            "offline-retry-boundary-raw-publication-identity-mismatch"
        }

        val positions =
            retryObservations
                .mapIndexed { index, observation -> observation.stage to index }
                .toMap()
        fun requireBefore(first: MiniHomeRetryStage, second: MiniHomeRetryStage) {
            require(requireNotNull(positions[first]) < requireNotNull(positions[second])) {
                "offline-retry-boundary-order-mismatch-${first.name.lowercase()}-${second.name.lowercase()}"
            }
        }
        requireBefore(MiniHomeRetryStage.CALLBACK_ENTRY, MiniHomeRetryStage.COROUTINE_ENTRY)
        requireBefore(MiniHomeRetryStage.COROUTINE_ENTRY, MiniHomeRetryStage.REPOSITORY_SAVE_ENTRY)
        requireBefore(
            MiniHomeRetryStage.REPOSITORY_SAVE_ENTRY,
            MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED,
        )
        requireBefore(
            MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED,
            MiniHomeRetryStage.SAVED_APPLY_ENTRY,
        )
        requireBefore(MiniHomeRetryStage.SAVED_APPLY_ENTRY, MiniHomeRetryStage.SET_STATE_ATTEMPTED)
        requireBefore(MiniHomeRetryStage.SET_STATE_ATTEMPTED, MiniHomeRetryStage.SET_STATE_APPLIED)
        requireBefore(
            MiniHomeRetryStage.SET_STATE_APPLIED,
            MiniHomeRetryStage.RAW_STATE_PUBLICATION,
        )
        requireBefore(
            MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED,
            MiniHomeRetryStage.COROUTINE_RETURNED,
        )
    }
}

internal fun writeTodo18OfflineRetryTransitionReceipt(
    file: File,
    receipt: Todo18OfflineRetryTransitionReceipt,
) {
    file.writeText(
        buildJsonObject {
            put("schema", "todo18-offline-retry-transition-v3")
            put("status", if (receipt.outcomeClass == null) "complete" else "partial")
            put("closed", receipt.closed)
            put("outcomeClass", receipt.outcomeClass?.let(::JsonPrimitive) ?: JsonNull)
            put("outcomeMessage", receipt.outcomeMessage?.let(::JsonPrimitive) ?: JsonNull)
            putJsonArray("observations") {
                receipt.observations.forEach { observation ->
                    add(
                        buildJsonObject {
                            put("stage", observation.stage.name)
                            put("operationId", observation.operationId)
                            put("revision", observation.revision)
                        }
                    )
                }
            }
            putJsonArray("retryObservations") {
                receipt.retryObservations.forEach { observation ->
                    add(
                        buildJsonObject {
                            put("stage", observation.stage.name)
                            put("operationId", observation.operationId)
                            put("controllerEpoch", observation.controllerEpoch)
                            put("controllerGeneration", observation.controllerGeneration)
                            put("saveGeneration", observation.saveGeneration)
                            put(
                                "guardDraftIdentity",
                                observation.guardDraftIdentity?.let(::JsonPrimitive) ?: JsonNull,
                            )
                            put("revision", observation.revision)
                            put("outcome", observation.outcome?.let(::JsonPrimitive) ?: JsonNull)
                            put("resultIdentity", observation.resultIdentity)
                            put("failureIdentity", observation.failureIdentity)
                            put(
                                "failureClass",
                                observation.failureClass?.let(::JsonPrimitive) ?: JsonNull,
                            )
                            put(
                                "failureMessage",
                                observation.failureMessage?.let(::JsonPrimitive) ?: JsonNull,
                            )
                        }
                    )
                }
            }
        }
            .toString()
    )
}

internal class Todo18OfflineRetryTransitionRecorder {
    private val lock = Any()
    private val observations = mutableListOf<Todo18OfflineRetryTransitionObservation>()
    private val retryObservations = mutableListOf<Todo18OfflineRetryBoundaryObservation>()
    private var closed = false

    fun record(observation: Todo18OfflineRetryTransitionObservation) {
        synchronized(lock) {
            check(!closed) { "offline-retry-transition-already-closed" }
            observations += observation
        }
    }

    fun recordRetry(observation: MiniHomeRetryObservation) {
        synchronized(lock) {
            check(!closed) { "offline-retry-transition-already-closed" }
            retryObservations +=
                Todo18OfflineRetryBoundaryObservation(
                    stage = observation.stage,
                    operationId = observation.operationId?.value,
                    controllerEpoch = observation.controllerEpoch,
                    controllerGeneration = observation.controllerGeneration,
                    saveGeneration = observation.saveGeneration,
                    guardDraftIdentity = observation.guardDraftIdentity,
                    revision = observation.revision,
                    outcome = observation.outcome,
                    resultIdentity = observation.result?.let(System::identityHashCode),
                    failureIdentity = observation.failure?.let(System::identityHashCode),
                    failureClass = observation.failure?.javaClass?.name,
                    failureMessage = observation.failure?.message,
                )
        }
    }

    fun close(primaryFailure: Throwable? = null): Todo18OfflineRetryTransitionReceipt =
        synchronized(lock) {
            check(!closed) { "offline-retry-transition-already-closed" }
            closed = true
            Todo18OfflineRetryTransitionReceipt(
                observations.toList(),
                closed = true,
                outcomeClass = primaryFailure?.javaClass?.name,
                outcomeMessage = primaryFailure?.message,
                retryObservations = retryObservations.toList(),
            )
        }
}
