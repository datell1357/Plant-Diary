package com.planterior.helper

import com.planterior.helper.auth.RenderedStateSink
import com.planterior.helper.diagnostic.Todo18CaptureFreshness
import com.planterior.helper.diagnostic.Todo18DiagnosticCapture
import com.planterior.helper.diagnostic.Todo18MiniHomeDisplayedRuntimeDiagnostic
import com.planterior.helper.diagnostic.Todo18PipelineEventKind
import com.planterior.helper.diagnostic.Todo18ProductPipelineDiagnostic
import com.planterior.helper.diagnostic.Todo18RecorderFaultInjector
import com.planterior.helper.diagnostic.Todo18StateChannel
import com.planterior.helper.diagnostic.Todo18StateDispatchPhase
import com.planterior.helper.diagnostic.Todo18StateSnapshot
import com.planterior.helper.diagnostic.Todo18WaitDiagnosticRecorder
import com.planterior.helper.diagnostic.Todo18WaitId
import com.planterior.helper.feature.minihome.MiniHomeDiagnosticEvent
import com.planterior.helper.feature.minihome.MiniHomeExitOutcomeKind
import com.planterior.helper.feature.minihome.MiniHomeRetryDiagnostics
import com.planterior.helper.feature.minihome.MiniHomeRetryObservation
import com.planterior.helper.feature.minihome.MiniHomeRetryStage
import com.planterior.helper.feature.minihome.MiniHomeUiState
import com.planterior.helper.feature.registration.RegistrationDiagnosticEvent
import com.planterior.helper.feature.registration.RegistrationPersistenceDiagnosticObservation
import com.planterior.helper.feature.registration.RegistrationPersistenceDiagnosticStage
import com.planterior.helper.feature.registration.RegistrationUiState
import com.planterior.helper.feature.share.MiniHomeShareDiagnosticObservation
import com.planterior.helper.feature.share.MiniHomeShareUiState
import com.planterior.helper.feature.shop.InventoryFeedback
import com.planterior.helper.feature.shop.InventoryUiState
import com.planterior.helper.inventory.Todo18InventoryCacheSettlement
import com.planterior.helper.inventory.Todo18InventorySettlementObservation
import com.planterior.helper.inventory.Todo18InventorySettlementStage
import com.planterior.helper.registration.Todo18RegistrationCommitRepositoryEvent
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Instance-owned rendered-state transport shared by the target runtime and its Todo18 rule. */
internal class Todo18RenderedStateSink : RenderedStateSink {
    private val recorder: Todo18WaitDiagnosticRecorder
    private val productPipeline: Todo18ProductPipelineDiagnostic
    private val displayedRuntimeDiagnostic: Todo18MiniHomeDisplayedRuntimeDiagnostic
    private val sequence = AtomicLong()
    private val rawMiniHomeStates = Todo18PrimaryEventStream<Todo18MiniHomeStateEvent>()
    private val routeMiniHomeStates = Todo18PrimaryEventStream<Todo18MiniHomeStateEvent>()
    private val displayedMiniHomeStates = Todo18PrimaryEventStream<Todo18MiniHomeStateEvent>()
    private val registrationStates = Todo18PrimaryEventStream<Todo18RegistrationStateEvent>()
    private val inventoryFeedback = Todo18PrimaryEventStream<Todo18InventoryFeedbackEvent>()
    private val shareStates = Todo18PrimaryEventStream<Todo18MiniHomeShareStateEvent>()
    internal val miniHomeShareDiagnostics = Todo18MiniHomeShareDiagnosticRecorder()
    private val armedInventorySettlement = AtomicReference<Todo18InventoryCacheSettlement?>()
    private val inventoryDiagnosticObserver =
        AtomicReference<(Todo18InventorySettlementObservation) -> Unit>({})

    private constructor(recorder: Todo18WaitDiagnosticRecorder) {
        this.recorder = recorder
        productPipeline = Todo18ProductPipelineDiagnostic(recorder)
        displayedRuntimeDiagnostic =
            Todo18MiniHomeDisplayedRuntimeDiagnostic(recorder, productPipeline)
    }

    constructor() : this(Todo18WaitDiagnosticRecorder())

    constructor(
        recorderFaultInjector: Todo18RecorderFaultInjector
    ) : this(Todo18WaitDiagnosticRecorder(recorderFaultInjector))

    override fun onMiniHomeRawState(state: MiniHomeUiState) {
        val viewing = state as? MiniHomeUiState.Viewing
        val outcome = viewing?.exitOutcome
        if (viewing?.saved == true && outcome?.kind == MiniHomeExitOutcomeKind.SAVED) {
            MiniHomeRetryDiagnostics.observe(
                MiniHomeRetryObservation(
                    stage = MiniHomeRetryStage.RAW_STATE_PUBLICATION,
                    operationId = outcome.operationId,
                    controllerEpoch = null,
                    controllerGeneration = null,
                    saveGeneration = null,
                    guardDraftIdentity = false,
                    revision = viewing.committed.revision.value,
                )
            )
        }
        val event = Todo18MiniHomeStateEvent(sequence.incrementAndGet(), state)
        publishMiniHome(Todo18StateChannel.MINI_HOME_RAW, rawMiniHomeStates, event)
    }

    override fun onMiniHomeDisplayedState(state: MiniHomeUiState) {
        val binding = displayedRuntimeDiagnostic.onSinkEntry(state)
        val target = productPipeline.isTarget(state)
        val event = Todo18MiniHomeStateEvent(sequence.incrementAndGet(), state)
        if (target) {
            recorder.recordPipeline(Todo18PipelineEventKind.TASK1_PUBLICATION, event.sequence)
        }
        publishMiniHome(Todo18StateChannel.MINI_HOME_DISPLAYED, displayedMiniHomeStates, event)
        displayedRuntimeDiagnostic.onSinkReturn(state, event.sequence, binding)
    }

    override fun onMiniHomeRouteDisplayedState(state: MiniHomeUiState) {
        routeMiniHomeStates.publish(Todo18MiniHomeStateEvent(sequence.incrementAndGet(), state))
    }

    override fun onMiniHomeShareState(state: MiniHomeShareUiState) {
        shareStates.publish(Todo18MiniHomeShareStateEvent(sequence.incrementAndGet(), state))
        miniHomeShareDiagnostics.recordDisplayed(state)
    }

    override fun onMiniHomeShareDiagnostic(observation: MiniHomeShareDiagnosticObservation) {
        miniHomeShareDiagnostics.record(observation)
    }

    override fun onRegistrationState(state: RegistrationUiState) {
        val event = Todo18RegistrationStateEvent(sequence.incrementAndGet(), state)
        val target = productPipeline.isTarget(state)
        if (target)
            recorder.recordPipeline(Todo18PipelineEventKind.TASK1_PUBLICATION, event.sequence)
        val before = registrationStates.current()?.snapshot()
        val listeners = registrationStates.listenerCount()
        if (target)
            recorder.recordPipeline(Todo18PipelineEventKind.PRIMARY_DISPATCH_BEGIN, event.sequence)
        recordRegistration(event, before, before, listeners, Todo18StateDispatchPhase.BEGIN)
        try {
            registrationStates.publish(event)
        } catch (failure: AssertionError) {
            recordRegistrationFailure(event, before, listeners)
            if (target)
                recorder.recordPipeline(
                    Todo18PipelineEventKind.PRIMARY_DISPATCH_FAILURE,
                    event.sequence,
                )
            throw failure
        } catch (failure: Exception) {
            recordRegistrationFailure(event, before, listeners)
            if (target)
                recorder.recordPipeline(
                    Todo18PipelineEventKind.PRIMARY_DISPATCH_FAILURE,
                    event.sequence,
                )
            throw failure
        }
        recordRegistration(
            event,
            before,
            registrationStates.current()?.snapshot(),
            listeners,
            Todo18StateDispatchPhase.RETURN,
        )
        if (target)
            recorder.recordPipeline(Todo18PipelineEventKind.PRIMARY_DISPATCH_RETURN, event.sequence)
    }

    fun armInventoryFeedback(settlement: Todo18InventoryCacheSettlement) {
        armedInventorySettlement.set(settlement)
    }

    fun observeInventoryDiagnostics(observer: (Todo18InventorySettlementObservation) -> Unit) {
        inventoryDiagnosticObserver.set(observer)
    }

    override fun onInventoryState(state: InventoryUiState) {
        val content = state as? InventoryUiState.Content ?: return
        if (content.stale) return
        if (content.feedback != InventoryFeedback.ACQUIRED) return
        val settlement = armedInventorySettlement.get() ?: return
        if (
            content.owner != settlement.accountId ||
                content.feedbackReceiptId?.value !=
                    "${settlement.accountId.value}/${settlement.operationId.value}" ||
                content.snapshot.owned.none { it.itemId == settlement.itemId }
        )
            return
        if (!armedInventorySettlement.compareAndSet(settlement, null)) return
        inventoryFeedback.publish(
            Todo18InventoryFeedbackEvent(
                sequence.incrementAndGet(),
                settlement,
                InventoryFeedback.ACQUIRED,
            )
        )
        try {
            inventoryDiagnosticObserver
                .get()
                .invoke(
                    Todo18InventorySettlementObservation(
                        Todo18InventorySettlementStage.RENDERED_FEEDBACK,
                        settlement,
                        stale = content.stale,
                        ownedItemIds = content.snapshot.owned.map { it.itemId },
                        feedback = content.feedback,
                    )
                )
        } catch (_: AssertionError) {
            return
        } catch (_: Exception) {
            return
        }
    }

    override fun onMiniHomeDiagnosticEvent(event: MiniHomeDiagnosticEvent) =
        displayedRuntimeDiagnostic.onDiagnosticEvent(event)

    override fun onRegistrationDiagnosticEvent(event: RegistrationDiagnosticEvent) =
        productPipeline.onRegistrationEvent(event)

    fun onRegistrationCommitRepositoryEvent(event: Todo18RegistrationCommitRepositoryEvent) {
        recorder.recordPipeline(
            kind =
                when (event) {
                    is Todo18RegistrationCommitRepositoryEvent.Entry ->
                        Todo18PipelineEventKind.REGISTRATION_REPOSITORY_ENTRY
                    is Todo18RegistrationCommitRepositoryEvent.Completed ->
                        Todo18PipelineEventKind.REGISTRATION_REPOSITORY_COMPLETED
                    is Todo18RegistrationCommitRepositoryEvent.Failed ->
                        Todo18PipelineEventKind.REGISTRATION_REPOSITORY_FAILED
                    is Todo18RegistrationCommitRepositoryEvent.Cancelled ->
                        Todo18PipelineEventKind.REGISTRATION_REPOSITORY_CANCELLED
                },
            registrationPlantId = event.plantId,
            registrationOperationId = event.operationId,
            repositoryIdentity = event.repositoryIdentity.value,
        )
    }

    fun onRegistrationPersistenceDiagnostic(
        observation: RegistrationPersistenceDiagnosticObservation
    ) {
        recorder.recordPipeline(
            kind = observation.stage.toPipelineEventKind(),
            registrationAccountId = observation.accountId,
            registrationOperationId = observation.operationId,
            registrationPlantId = observation.plantId,
        )
    }

    private fun RegistrationPersistenceDiagnosticStage.toPipelineEventKind():
        Todo18PipelineEventKind =
        when (this) {
            RegistrationPersistenceDiagnosticStage.COMMITTED_READ_ENTERED ->
                Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_ENTERED
            RegistrationPersistenceDiagnosticStage.COMMITTED_READ_RETURNED ->
                Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_RETURNED
            RegistrationPersistenceDiagnosticStage.COMMITTED_READ_THREW ->
                Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_THREW
            RegistrationPersistenceDiagnosticStage.COMMITTED_READ_CANCELLED ->
                Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_CANCELLED
            RegistrationPersistenceDiagnosticStage.CACHE_UPSERT_ENTERED ->
                Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_ENTERED
            RegistrationPersistenceDiagnosticStage.CACHE_UPSERT_RETURNED ->
                Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_RETURNED
            RegistrationPersistenceDiagnosticStage.CACHE_UPSERT_THREW ->
                Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_THREW
            RegistrationPersistenceDiagnosticStage.CACHE_UPSERT_CANCELLED ->
                Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_CANCELLED
            RegistrationPersistenceDiagnosticStage.OUTBOX_REMOVE_ENTERED ->
                Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_ENTERED
            RegistrationPersistenceDiagnosticStage.OUTBOX_REMOVE_RETURNED ->
                Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_RETURNED
            RegistrationPersistenceDiagnosticStage.OUTBOX_REMOVE_THREW ->
                Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_THREW
            RegistrationPersistenceDiagnosticStage.OUTBOX_REMOVE_CANCELLED ->
                Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_CANCELLED
            RegistrationPersistenceDiagnosticStage.COMPLETED_RETURNED ->
                Todo18PipelineEventKind.REGISTRATION_COMPLETED_RETURNED
        }

    fun currentRawMiniHomeState(): Todo18MiniHomeStateEvent? = rawMiniHomeStates.current()

    fun currentDisplayedMiniHomeState(): Todo18MiniHomeStateEvent? =
        displayedMiniHomeStates.current()

    fun currentRouteMiniHomeState(): Todo18MiniHomeStateEvent? = routeMiniHomeStates.current()

    fun currentRegistrationState(): Todo18RegistrationStateEvent? = registrationStates.current()

    fun currentInventoryFeedback(): Todo18InventoryFeedbackEvent? = inventoryFeedback.current()

    fun currentMiniHomeShareState(): Todo18MiniHomeShareStateEvent? = shareStates.current()

    fun subscribeToRawMiniHomeStates(listener: (Todo18MiniHomeStateEvent) -> Unit): AutoCloseable =
        rawMiniHomeStates.subscribe(listener)

    fun subscribeToDisplayedMiniHomeStates(
        listener: (Todo18MiniHomeStateEvent) -> Unit
    ): AutoCloseable = displayedMiniHomeStates.subscribe(listener)

    fun subscribeToRouteMiniHomeStates(
        listener: (Todo18MiniHomeStateEvent) -> Unit
    ): AutoCloseable = routeMiniHomeStates.subscribe(listener)

    fun subscribeToRegistrationStates(
        listener: (Todo18RegistrationStateEvent) -> Unit
    ): AutoCloseable = registrationStates.subscribe(listener)

    fun subscribeToInventoryFeedback(
        listener: (Todo18InventoryFeedbackEvent) -> Unit
    ): AutoCloseable = inventoryFeedback.subscribe(listener)

    fun subscribeToMiniHomeShareStates(
        listener: (Todo18MiniHomeShareStateEvent) -> Unit
    ): AutoCloseable = shareStates.subscribe(listener)

    fun sequenceValue(): Long = sequence.get()

    fun primaryListenerCount(): Int =
        rawMiniHomeStates.listenerCount() +
            routeMiniHomeStates.listenerCount() +
            displayedMiniHomeStates.listenerCount() +
            registrationStates.listenerCount() +
            inventoryFeedback.listenerCount() +
            shareStates.listenerCount()

    fun isFresh(): Boolean = captureFreshness().fresh

    fun startDiagnosticCapture(
        waitId: Todo18WaitId,
        freshness: Todo18CaptureFreshness = captureFreshness(),
    ): Todo18DiagnosticCapture = recorder.start(waitId, freshness)

    fun hasActiveDiagnosticCapture(): Boolean = recorder.hasActiveCapture()

    private fun publishMiniHome(
        channel: Todo18StateChannel,
        stream: Todo18PrimaryEventStream<Todo18MiniHomeStateEvent>,
        event: Todo18MiniHomeStateEvent,
    ) {
        val before = stream.current()?.snapshot(channel)
        val listeners = stream.listenerCount()
        val target =
            channel == Todo18StateChannel.MINI_HOME_DISPLAYED &&
                productPipeline.isTarget(event.state)
        if (target)
            recorder.recordPipeline(Todo18PipelineEventKind.PRIMARY_DISPATCH_BEGIN, event.sequence)
        recordMiniHome(channel, event, before, before, listeners, Todo18StateDispatchPhase.BEGIN)
        try {
            stream.publish(event)
        } catch (failure: AssertionError) {
            recordMiniHomeFailure(channel, event, before, listeners)
            if (target)
                recorder.recordPipeline(
                    Todo18PipelineEventKind.PRIMARY_DISPATCH_FAILURE,
                    event.sequence,
                )
            throw failure
        } catch (failure: Exception) {
            recordMiniHomeFailure(channel, event, before, listeners)
            if (target)
                recorder.recordPipeline(
                    Todo18PipelineEventKind.PRIMARY_DISPATCH_FAILURE,
                    event.sequence,
                )
            throw failure
        }
        recordMiniHome(
            channel,
            event,
            before,
            stream.current()?.snapshot(channel),
            listeners,
            Todo18StateDispatchPhase.RETURN,
        )
        if (target)
            recorder.recordPipeline(Todo18PipelineEventKind.PRIMARY_DISPATCH_RETURN, event.sequence)
    }

    private fun recordMiniHome(
        channel: Todo18StateChannel,
        event: Todo18MiniHomeStateEvent,
        before: Todo18StateSnapshot?,
        after: Todo18StateSnapshot?,
        listeners: Int,
        phase: Todo18StateDispatchPhase,
    ) =
        recorder.recordState(
            event.sequence,
            channel,
            event.state.kind(),
            event.state.owner,
            null,
            before,
            after,
            listeners,
            phase,
        )

    private fun recordRegistration(
        event: Todo18RegistrationStateEvent,
        before: Todo18StateSnapshot?,
        after: Todo18StateSnapshot?,
        listeners: Int,
        phase: Todo18StateDispatchPhase,
    ) =
        recorder.recordState(
            event.sequence,
            Todo18StateChannel.REGISTRATION,
            event.state.kind(),
            null,
            event.state.selectedContentId(),
            before,
            after,
            listeners,
            phase,
        )

    private fun recordMiniHomeFailure(
        channel: Todo18StateChannel,
        event: Todo18MiniHomeStateEvent,
        before: Todo18StateSnapshot?,
        listeners: Int,
    ) =
        recordMiniHome(
            channel,
            event,
            before,
            streamCurrent(channel),
            listeners,
            Todo18StateDispatchPhase.FAILURE,
        )

    private fun recordRegistrationFailure(
        event: Todo18RegistrationStateEvent,
        before: Todo18StateSnapshot?,
        listeners: Int,
    ) =
        recordRegistration(
            event,
            before,
            registrationStates.current()?.snapshot(),
            listeners,
            Todo18StateDispatchPhase.FAILURE,
        )

    private fun streamCurrent(channel: Todo18StateChannel): Todo18StateSnapshot? =
        when (channel) {
            Todo18StateChannel.MINI_HOME_RAW -> rawMiniHomeStates.current()?.snapshot(channel)
            Todo18StateChannel.MINI_HOME_DISPLAYED ->
                displayedMiniHomeStates.current()?.snapshot(channel)
            Todo18StateChannel.REGISTRATION -> error("Registration uses its typed stream")
        }
}
