package com.planterior.helper.diagnostic

import com.planterior.helper.core.model.PlantContentId

internal class Todo18WaitDiagnosticRecorder(
    private val faultInjector: Todo18RecorderFaultInjector? = null,
    private val recordLimit: Int = DEFAULT_RECORD_LIMIT,
) {
    private val lock = Any()
    private var active: CaptureState? = null

    fun start(
        waitId: Todo18WaitId,
        freshness: Todo18CaptureFreshness,
    ): Todo18DiagnosticCapture =
        synchronized(lock) {
            check(active == null) { "Todo18 diagnostic wait is already active" }
            CaptureState(waitId, freshness).also { active = it }.handle()
        }

    fun hasActiveCapture(): Boolean = synchronized(lock) { active != null }

    fun activeWaitId(): Todo18WaitId? = synchronized(lock) { active?.waitId }

    fun recordPipeline(
        kind: Todo18PipelineEventKind,
        sourceSequence: Long? = null,
        controllerIdentity: Int? = null,
        requestedContentId: PlantContentId? = null,
        beforeState: Todo18StateKind? = null,
        afterState: Todo18StateKind? = null,
        registrationPlantId: com.planterior.helper.core.model.PersonalPlantId? = null,
        registrationOperationId: com.planterior.helper.core.model.OperationId? = null,
        repositoryIdentity: Int? = null,
        navigationIdentity: String? = null,
        runtimeBinding: Todo18RuntimeBinding? = null,
    ) {
        mutate(Todo18DiagnosticRecordKind.PIPELINE) { capture ->
            capture.pipeline +=
                Todo18PipelineEvent(
                    ordinal = capture.pipeline.size + 1L,
                    kind = kind,
                    sourceSequence = sourceSequence,
                    controllerIdentity = controllerIdentity,
                    requestedContentId = requestedContentId,
                    beforeState = beforeState,
                    afterState = afterState,
                    registrationPlantId = registrationPlantId,
                    registrationOperationId = registrationOperationId,
                    repositoryIdentity = repositoryIdentity,
                    navigationIdentity = navigationIdentity,
                    runtimeBinding = runtimeBinding,
                )
        }
    }

    fun recordState(
        sourceSequence: Long,
        channel: Todo18StateChannel,
        state: Todo18StateKind,
        owner: com.planterior.helper.core.model.AccountId?,
        selectedContentId: com.planterior.helper.core.model.PlantContentId?,
        currentBefore: Todo18StateSnapshot?,
        currentAfter: Todo18StateSnapshot?,
        primaryListenerCount: Int,
        phase: Todo18StateDispatchPhase,
    ) {
        mutate(Todo18DiagnosticRecordKind.STATE_DISPATCH) { capture ->
            capture.stateDispatches +=
                Todo18StateDispatchRecord(
                    ordinal = capture.stateDispatches.size + 1L,
                    waitId = capture.waitId,
                    sourceSequence = sourceSequence,
                    channel = channel,
                    state = state,
                    owner = owner,
                    selectedContentId = selectedContentId,
                    currentBefore = currentBefore,
                    currentAfter = currentAfter,
                    primaryListenerCount = primaryListenerCount,
                    phase = phase,
                    freshForWait = capture.freshness.fresh,
                    isolatedInstance = capture.freshness.isolatedInstance,
                )
        }
    }

    fun recordExact(observation: Todo18ExactEventObservation) {
        mutate(Todo18DiagnosticRecordKind.EXACT_EVENT) { capture ->
            capture.exactEvents += observation.copy(ordinal = capture.exactEvents.size + 1L)
        }
    }

    fun markFailure(failure: Todo18DiagnosticFailure) {
        synchronized(lock) { active?.failures?.add(failure) }
    }

    private fun mutate(
        kind: Todo18DiagnosticRecordKind,
        action: (CaptureState) -> Unit,
    ) {
        synchronized(lock) {
            val capture = active ?: return
            try {
                faultInjector?.beforeRecord(kind)
            } catch (_: AssertionError) {
                capture.failures += Todo18DiagnosticFailure.RECORDER_CALLBACK_FAILED
                return
            } catch (_: Exception) {
                capture.failures += Todo18DiagnosticFailure.RECORDER_CALLBACK_FAILED
                return
            }
            if (
                capture.pipeline.size + capture.stateDispatches.size + capture.exactEvents.size >=
                    recordLimit
            ) {
                capture.failures += Todo18DiagnosticFailure.CAPTURE_LIMIT_EXCEEDED
                return
            }
            action(capture)
        }
    }

    private fun CaptureState.handle(): Todo18DiagnosticCapture =
        object : Todo18DiagnosticCapture {
            override fun recordPipeline(
                kind: Todo18PipelineEventKind,
                sourceSequence: Long?,
                controllerIdentity: Int?,
                requestedContentId: PlantContentId?,
                beforeState: Todo18StateKind?,
                afterState: Todo18StateKind?,
            ) =
                this@Todo18WaitDiagnosticRecorder.recordPipeline(
                    kind,
                    sourceSequence,
                    controllerIdentity,
                    requestedContentId,
                    beforeState,
                    afterState,
                )

            override fun recordPipeline(
                kind: Todo18PipelineEventKind,
                sourceSequence: Long?,
                controllerIdentity: Int?,
                requestedContentId: PlantContentId?,
                beforeState: Todo18StateKind?,
                afterState: Todo18StateKind?,
                registrationPlantId: com.planterior.helper.core.model.PersonalPlantId?,
                registrationOperationId: com.planterior.helper.core.model.OperationId?,
                repositoryIdentity: Int?,
                navigationIdentity: String?,
            ) =
                this@Todo18WaitDiagnosticRecorder.recordPipeline(
                    kind,
                    sourceSequence,
                    controllerIdentity,
                    requestedContentId,
                    beforeState,
                    afterState,
                    registrationPlantId,
                    registrationOperationId,
                    repositoryIdentity,
                    navigationIdentity,
                )

            override fun recordExact(observation: Todo18ExactEventObservation) =
                this@Todo18WaitDiagnosticRecorder.recordExact(observation)

            override fun markFailure(failure: Todo18DiagnosticFailure) =
                this@Todo18WaitDiagnosticRecorder.markFailure(failure)

            override fun snapshot(): Todo18DiagnosticCaptureSnapshot =
                synchronized(lock) {
                    this@handle.snapshot()
                }

            override fun close() {
                synchronized(lock) {
                    if (!closed) {
                        closed = true
                        if (active === this@handle) active = null
                    }
                }
            }
        }

    private fun CaptureState.snapshot() =
        Todo18DiagnosticCaptureSnapshot(
            waitId = waitId,
            freshness = freshness,
            pipeline = pipeline.toList(),
            stateDispatches = stateDispatches.toList(),
            exactEvents = exactEvents.toList(),
            failures = failures.distinct(),
            closed = closed,
        )

    private class CaptureState(
        val waitId: Todo18WaitId,
        val freshness: Todo18CaptureFreshness,
        val pipeline: MutableList<Todo18PipelineEvent> = mutableListOf(),
        val stateDispatches: MutableList<Todo18StateDispatchRecord> = mutableListOf(),
        val exactEvents: MutableList<Todo18ExactEventObservation> = mutableListOf(),
        val failures: MutableList<Todo18DiagnosticFailure> = mutableListOf(),
        var closed: Boolean = false,
    )

    private companion object {
        const val DEFAULT_RECORD_LIMIT = 128
    }
}

interface Todo18DiagnosticCapture : AutoCloseable {
    fun recordPipeline(
        kind: Todo18PipelineEventKind,
        sourceSequence: Long? = null,
        controllerIdentity: Int? = null,
        requestedContentId: PlantContentId? = null,
        beforeState: Todo18StateKind? = null,
        afterState: Todo18StateKind? = null,
    )

    fun recordPipeline(
        kind: Todo18PipelineEventKind,
        sourceSequence: Long? = null,
        controllerIdentity: Int? = null,
        requestedContentId: PlantContentId? = null,
        beforeState: Todo18StateKind? = null,
        afterState: Todo18StateKind? = null,
        registrationPlantId: com.planterior.helper.core.model.PersonalPlantId? = null,
        registrationOperationId: com.planterior.helper.core.model.OperationId? = null,
        repositoryIdentity: Int? = null,
        navigationIdentity: String? = null,
    ) {
        recordPipeline(
            kind,
            sourceSequence,
            controllerIdentity,
            requestedContentId,
            beforeState,
            afterState,
        )
    }

    fun recordExact(observation: Todo18ExactEventObservation)

    fun markFailure(failure: Todo18DiagnosticFailure)

    fun snapshot(): Todo18DiagnosticCaptureSnapshot
}
