package com.planterior.helper.feature.minihome

import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MiniHomeController(
    private val repository: MiniHomeRepository,
    private val savedStateHandle: SavedStateHandle,
    private val operationIdFactory: () -> OperationId = OperationId::random,
    private val placementIdFactory: () -> PlacementId = {
        PlacementId(UUID.randomUUID().toString())
    },
) {
    private val _state = MutableStateFlow<MiniHomeUiState>(MiniHomeUiState.Loading(null))
    val state: StateFlow<MiniHomeUiState> = _state.asStateFlow()
    private val controllerEpoch = (savedStateHandle.get<Long>(CONTROLLER_EPOCH_KEY) ?: 0L) + 1L
    private val _session =
        MutableStateFlow(MiniHomeControllerSessionToken(controllerEpoch, 0L, null))
    val session: StateFlow<MiniHomeControllerSessionToken> = _session.asStateFlow()
    private val ownerJobsLock = Any()
    private val ownerJobs = mutableMapOf<AccountId, MutableSet<Job>>()
    private var generation = 0L
    private var owner: AccountId? = null
    private var loadSequence = 0L
    private var stateVersion = 0L
    private var activeDiscard: ControllerOperationToken? = null
    private var saveGeneration = 0L
    private var activeSave: ActiveSave? = null
    val diagnosticIdentity = MiniHomeControllerIdentity(System.identityHashCode(this))

    private var confirmedSave =
        MiniHomeDraftCodec.decode(savedStateHandle[CONFIRMED_SAVE_KEY])?.let {
            ConfirmedSave(
                it.owner,
                it.operationId,
                it.lineageId,
                it.supersedesOperationId,
                it.layout,
            )
        }

    init {
        savedStateHandle[CONTROLLER_EPOCH_KEY] = controllerEpoch
    }

    suspend fun start() = start(MiniHomeAuthOwnership.Unmanaged)

    suspend fun start(authOwnership: MiniHomeAuthOwnership) {
        when (authOwnership) {
            MiniHomeAuthOwnership.Restoring,
            MiniHomeAuthOwnership.Unknown -> {
                loadSequence += 1
                return
            }
            MiniHomeAuthOwnership.SignedOut -> {
                val requestSequence = ++loadSequence
                transitionAuthoritativeOwner(null)
                if (requestSequence != loadSequence) return
            }
            MiniHomeAuthOwnership.Unmanaged -> load(null, null)
            is MiniHomeAuthOwnership.Authenticated -> {
                val requestSequence = ++loadSequence
                val transitioned = transitionAuthoritativeOwner(authOwnership.accountId)
                if (requestSequence != loadSequence || owner != authOwnership.accountId) {
                    return
                }
                load(
                    null,
                    authOwnership.accountId,
                    requestSequence,
                    transitioned,
                )
            }
        }
    }

    private suspend fun load(
        discardFeedback: MiniHomeDiscardFeedback?,
        expectedOwner: AccountId?,
        requestSequence: Long = ++loadSequence,
        ownerTransitioned: Boolean = false,
    ) {
        val previousOwner = owner
        val token = captureToken(guardDraftIdentity = false)
        if (previousOwner == null && !setState(MiniHomeUiState.Loading(expectedOwner), token)) {
            return
        }
        val loadStateVersion = stateVersion
        val tracked = registerOwnerJob(token)
        try {
            val loaded =
                try {
                    repository.load()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    MiniHomeLoadResult.Failed
                }
            if (!isCurrentLoad(token, requestSequence)) return
            when (loaded) {
                is MiniHomeLoadResult.Ready -> {
                    if (expectedOwner != null && loaded.accountId != expectedOwner) {
                        if (ownerTransitioned) {
                            setState(MiniHomeUiState.Unavailable(expectedOwner), token)
                        }
                        return
                    }
                    val ownerToken =
                        switchOwnerAfterLoad(token, loaded.accountId, requestSequence) ?: return
                    if (loaded.accountId == previousOwner) {
                        awaitSameOwnerSaveSettlement(loaded.accountId)
                        if (!isCurrentLoad(ownerToken, requestSequence)) return
                        if (
                            stateVersion != loadStateVersion &&
                                !loaded.correlatesNewerSameOwnerState()
                        ) {
                            return
                        }
                    }
                    publishLoaded(loaded, ownerToken, discardFeedback)
                }
                MiniHomeLoadResult.Forbidden -> {
                    if (expectedOwner != null) {
                        if (ownerTransitioned) {
                            setState(MiniHomeUiState.Unavailable(expectedOwner), token)
                        }
                        return
                    }
                    transitionAuthoritativeOwner(null)
                }
                MiniHomeLoadResult.Failed ->
                    when {
                        expectedOwner != null && ownerTransitioned ->
                            setState(MiniHomeUiState.Unavailable(expectedOwner), token)
                        previousOwner == null -> setState(MiniHomeUiState.Error, token)
                    }
            }
        } finally {
            unregisterOwnerJob(tracked)
        }
    }

    private fun publishLoaded(
        loaded: MiniHomeLoadResult.Ready,
        ownerToken: ControllerOperationToken,
        discardFeedback: MiniHomeDiscardFeedback?,
    ) {
        val savedRestored =
            MiniHomeDraftCodec.decode(savedStateHandle[DRAFT_KEY])?.takeIf {
                it.owner == loaded.accountId
            }
        val confirmed = confirmedSave?.takeIf { it.owner == loaded.accountId }
        val loadAtOrBeforeConfirmation =
            confirmed != null && loaded.committed.revision.value <= confirmed.layout.revision.value
        val newerDraft = savedRestored?.takeIf { restored ->
            confirmed != null && restored.operationId != confirmed.operationId
        }
        if (loadAtOrBeforeConfirmation && newerDraft == null) {
            publishConfirmedSave(requireNotNull(confirmed), loaded, ownerToken)
            return
        }
        if (
            confirmed != null && loaded.committed.revision.value > confirmed.layout.revision.value
        ) {
            clearConfirmedSave(loaded.accountId, ownerToken)
        }
        val correlatedLoad =
            if (loadAtOrBeforeConfirmation) {
                loaded.copy(committed = requireNotNull(confirmed).layout, stale = false)
            } else {
                loaded
            }
        val pendingRestored = correlatedLoad.pending?.restored(correlatedLoad.accountId)
        if (
            correlatedLoad.pending == null &&
                savedRestored != null &&
                correlatedLoad.committedReceipt.matches(
                    savedRestored,
                    correlatedLoad.committed,
                )
        ) {
            val recovered =
                ConfirmedSave(
                    correlatedLoad.accountId,
                    savedRestored.operationId,
                    savedRestored.lineageId,
                    savedRestored.supersedesOperationId,
                    correlatedLoad.committed,
                )
            recordConfirmedSave(recovered, ownerToken)
            publishConfirmedSave(recovered, correlatedLoad, ownerToken)
            return
        }
        val savedSupersedesPending =
            pendingRestored != null &&
                savedRestored != null &&
                savedRestored.lineageId == pendingRestored.lineageId &&
                savedRestored.operationId != pendingRestored.operationId &&
                savedRestored.supersedesOperationId == pendingRestored.operationId
        val restored =
            if (savedSupersedesPending) savedRestored else pendingRestored ?: savedRestored
        val restoredFromPending = restored?.operationId == pendingRestored?.operationId
        if (restored == null) {
            setState(correlatedLoad.viewing(), ownerToken)
            return
        }
        val saveState =
            when {
                !restoredFromPending &&
                    restored.expectedRevision != correlatedLoad.committed.revision ->
                    MiniHomeSaveState.Conflict
                !restoredFromPending -> MiniHomeSaveState.Idle
                correlatedLoad.pending?.failure?.requiresCorrection == true ->
                    MiniHomeSaveState.ValidationFailed(
                        requireNotNull(correlatedLoad.pending.failure),
                        correlatedLoad.pending.failureDetails,
                    )
                correlatedLoad.pending?.state == MiniHomePendingState.RECONCILIATION_REQUIRED &&
                    correlatedLoad.pending.failure?.requiresReconciliation == true ->
                    MiniHomeSaveState.ReconciliationRequired(
                        requireNotNull(correlatedLoad.pending.failure)
                    )
                correlatedLoad.pending?.state == MiniHomePendingState.RECONCILIATION_REQUIRED ||
                    restored.expectedRevision != correlatedLoad.committed.revision ->
                    MiniHomeSaveState.Conflict
                correlatedLoad.pending?.failure?.retryable == true ->
                    MiniHomeSaveState.Failed(correlatedLoad.pending.failure)
                else -> MiniHomeSaveState.Idle
            }
        setEditing(
            MiniHomeUiState.Editing(
                correlatedLoad.committed,
                restored.layout,
                correlatedLoad.plants,
                correlatedLoad.decorations,
                restored.layout.placements.lastOrNull()?.id,
                restored.operationId,
                saveState,
                stale = correlatedLoad.stale,
                lineageId = restored.lineageId,
                supersedesOperationId = restored.supersedesOperationId,
                discardHandle =
                    correlatedLoad.pending?.discardHandle?.takeIf {
                        restoredFromPending || savedSupersedesPending
                    },
                discardFeedback = discardFeedback,
                owner = correlatedLoad.accountId,
            ),
            ownerToken,
        )
    }

    private suspend fun switchOwnerAfterLoad(
        token: ControllerOperationToken,
        loadedOwner: AccountId,
        requestSequence: Long,
    ): ControllerOperationToken? {
        if (!isCurrentLoad(token, requestSequence)) return null
        if (owner == loadedOwner) {
            resolveRestorationOwner(owner, loadedOwner)
            return token.copy(draftIdentity = null, guardDraftIdentity = false)
        }
        transitionAuthoritativeOwner(loadedOwner)
        if (requestSequence != loadSequence || owner != loadedOwner) return null
        return captureToken(guardDraftIdentity = false)
    }

    private suspend fun transitionAuthoritativeOwner(incomingOwner: AccountId?): Boolean {
        val previousOwner = owner
        resolveRestorationOwner(previousOwner, incomingOwner)
        if (previousOwner == incomingOwner) {
            if (incomingOwner == null) {
                _state.value = MiniHomeUiState.Forbidden
                stateVersion += 1
            }
            return false
        }

        generation += 1
        owner = incomingOwner
        saveGeneration += 1
        activeSave = null
        activeDiscard = null
        _state.value =
            incomingOwner?.let { MiniHomeUiState.Loading(it) } ?: MiniHomeUiState.Forbidden
        stateVersion += 1
        publishSession()

        cancelAndJoinOwnerJobs(previousOwner, currentCoroutineContext()[Job])
        return true
    }

    private suspend fun switchToForbiddenFromOperation(token: ControllerOperationToken) {
        if (!isCurrent(token)) return
        loadSequence += 1
        transitionAuthoritativeOwner(null)
    }

    fun beginEditing(diagnosticObserver: ((MiniHomeDiagnosticEvent) -> Unit)? = null) {
        val before = _state.value
        val viewing = before as? MiniHomeUiState.Viewing
        if (viewing != null) {
            val token = captureToken(editing = null, guardDraftIdentity = false)
            val operationId = operationIdFactory()
            val editing =
                MiniHomeUiState.Editing(
                    viewing.committed,
                    viewing.committed,
                    viewing.plants,
                    viewing.decorations,
                    null,
                    operationId,
                    MiniHomeSaveState.Idle,
                    stale = viewing.stale,
                    lineageId = operationId,
                    owner = viewing.owner,
                )
            setEditing(editing, token)
        }
        safeMiniHomeDiagnostic(diagnosticObserver) {
            MiniHomeDiagnosticEvent.BeginEditControllerTransition(
                diagnosticIdentity,
                before,
                _state.value,
            )
        }
    }

    fun rename(value: String) = updateDraft { it.copy(name = value) }

    fun addPlant(id: PersonalPlantId) = add(MiniHomePlacementTarget.Plant(id))

    fun addDecoration(id: ItemId) = add(MiniHomePlacementTarget.Decoration(id))

    fun select(id: PlacementId) {
        val editing = editable() ?: return
        val token = captureToken(editing)
        if (editing.draft.placements.none { it.id == id }) return
        setEditing(editing.copy(selectedPlacementId = id, issue = null), token)
    }

    fun moveSelected(position: GridPosition) {
        val editing = editable() ?: return
        val token = captureToken(editing)
        val selected = editing.selectedPlacementId ?: return
        if (editing.draft.placements.any { it.id != selected && it.position == position }) {
            setEditing(editing.copy(issue = MiniHomePlacementIssue.OCCUPIED), token)
            return
        }
        val placements =
            MiniHomePlacementPolicy.layer(
                editing.draft.placements.map {
                    if (it.id == selected) it.copy(position = position) else it
                }
            )
        setEditing(editing.afterDraftChange(editing.draft.copy(placements = placements)), token)
    }

    fun moveSelectedBy(columnDelta: Int, rowDelta: Int) {
        val editing = editable() ?: return
        val token = captureToken(editing)
        val selected =
            editing.draft.placements.singleOrNull { it.id == editing.selectedPlacementId } ?: return
        if (!isCurrent(token)) return
        moveSelected(
            GridPosition(
                (selected.position.column + columnDelta).coerceIn(0, MiniHomeGrid.COLUMNS - 1),
                (selected.position.row + rowDelta).coerceIn(0, MiniHomeGrid.ROWS - 1),
            )
        )
    }

    fun removeSelected() {
        val editing = editable() ?: return
        val token = captureToken(editing)
        val selected = editing.selectedPlacementId ?: return
        val placements =
            MiniHomePlacementPolicy.layer(editing.draft.placements.filterNot { it.id == selected })
        setEditing(
            editing
                .afterDraftChange(editing.draft.copy(placements = placements))
                .copy(selectedPlacementId = null),
            token,
        )
    }

    suspend fun discardChanges(): MiniHomeDiscardResult {
        val editing =
            _state.value as? MiniHomeUiState.Editing ?: return MiniHomeDiscardResult.Rejected
        val token = captureToken(editing)
        val activeOwner = token.owner ?: return MiniHomeDiscardResult.OwnerMismatch
        if (activeDiscard?.let(::isCurrent) == true) return MiniHomeDiscardResult.Rejected
        val correlatedSave = activeSave?.takeIf {
            it.owner == activeOwner && it.operationId == editing.operationId
        }
        saveGeneration += 1
        activeDiscard = token
        val tracked = registerOwnerJob(token)
        return try {
            val saveResult = correlatedSave?.completion?.await()
            if (!isCurrent(token)) {
                return MiniHomeDiscardResult.StaleHandle(null)
            }
            if (saveResult is MiniHomeSaveResult.Saved) {
                clearDraft(activeOwner, token)
                setState(
                    MiniHomeUiState.Viewing(
                        saveResult.layout,
                        editing.plants,
                        editing.decorations,
                        stale = false,
                        saved = true,
                        exitOutcome = token.exitOutcome(MiniHomeExitOutcomeKind.SAVED),
                        owner = activeOwner,
                    ),
                    token.copy(draftIdentity = null, guardDraftIdentity = false),
                )
                return MiniHomeDiscardResult.Committed(saveResult.layout)
            }
            val correlatedEditing = editing.withCorrelatedSaveResult(saveResult)
            val handle = saveResult.discardHandleOrNull() ?: correlatedEditing.discardHandle
            val discardEditing =
                correlatedEditing.copy(
                    saveState = correlatedEditing.discardFailureState(saveResult),
                    discardHandle = handle,
                )
            val result =
                try {
                    if (handle == null) {
                        repository.abandonPending(activeOwner, editing.operationId)
                    } else {
                        repository.abandon(handle)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    MiniHomeDiscardResult.Rejected
                }
            if (!isCurrent(token)) return MiniHomeDiscardResult.StaleHandle(null)
            when (result) {
                MiniHomeDiscardResult.Consumed -> {
                    clearDraft(activeOwner, token)
                    setState(
                        MiniHomeUiState.Viewing(
                            discardEditing.committed,
                            discardEditing.plants,
                            discardEditing.decorations,
                            discardEditing.stale,
                            exitOutcome = token.exitOutcome(MiniHomeExitOutcomeKind.DISCARDED),
                            owner = activeOwner,
                        ),
                        token.copy(draftIdentity = null, guardDraftIdentity = false),
                    )
                    MiniHomeDiscardResult.Consumed
                }
                is MiniHomeDiscardResult.Committed -> {
                    clearDraft(activeOwner, token)
                    setState(
                        MiniHomeUiState.Viewing(
                            result.authoritative,
                            discardEditing.plants,
                            discardEditing.decorations,
                            stale = false,
                            saved = true,
                            exitOutcome = token.exitOutcome(MiniHomeExitOutcomeKind.SAVED),
                            owner = activeOwner,
                        ),
                        token.copy(draftIdentity = null, guardDraftIdentity = false),
                    )
                    result
                }
                MiniHomeDiscardResult.Missing ->
                    if (handle == null) {
                        clearDraft(activeOwner, token)
                        setState(
                            MiniHomeUiState.Viewing(
                                discardEditing.committed,
                                discardEditing.plants,
                                discardEditing.decorations,
                                discardEditing.stale,
                                exitOutcome = token.exitOutcome(MiniHomeExitOutcomeKind.DISCARDED),
                                owner = activeOwner,
                            ),
                            token.copy(draftIdentity = null, guardDraftIdentity = false),
                        )
                        MiniHomeDiscardResult.Missing
                    } else {
                        reloadAfterDiscard(discardEditing, token, result)
                    }
                is MiniHomeDiscardResult.StaleHandle ->
                    reloadAfterDiscard(discardEditing, token, result)
                MiniHomeDiscardResult.OwnerMismatch,
                MiniHomeDiscardResult.Rejected ->
                    if (handle == null) {
                        setEditing(
                            discardEditing.copy(
                                discardFeedback = MiniHomeDiscardFeedback.RETRY_REQUIRED
                            ),
                            token,
                        )
                        result
                    } else {
                        reloadAfterDiscard(discardEditing, token, result)
                    }
            }
        } finally {
            if (activeDiscard == token) activeDiscard = null
            unregisterOwnerJob(tracked)
        }
    }

    private suspend fun reloadAfterDiscard(
        editing: MiniHomeUiState.Editing,
        token: ControllerOperationToken,
        discardResult: MiniHomeDiscardResult,
    ): MiniHomeDiscardResult {
        val activeOwner = token.owner ?: return MiniHomeDiscardResult.OwnerMismatch
        val loaded =
            try {
                repository.load()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                MiniHomeLoadResult.Failed
            }
        if (!isCurrent(token)) return MiniHomeDiscardResult.StaleHandle(null)
        if (loaded !is MiniHomeLoadResult.Ready || loaded.accountId != activeOwner) {
            setEditing(
                editing.copy(
                    discardFeedback = MiniHomeDiscardFeedback.RETRY_REQUIRED,
                    saveState = editing.discardFailureState(),
                ),
                token,
            )
            return if (
                loaded is MiniHomeLoadResult.Forbidden ||
                    (loaded is MiniHomeLoadResult.Ready && loaded.accountId != activeOwner)
            ) {
                MiniHomeDiscardResult.OwnerMismatch
            } else {
                MiniHomeDiscardResult.Rejected
            }
        }
        val pending = loaded.pending
        if (pending != null) {
            val restored = pending.restored(activeOwner)
            val saveState =
                when {
                    pending.failure?.requiresCorrection == true ->
                        MiniHomeSaveState.ValidationFailed(
                            requireNotNull(pending.failure),
                            pending.failureDetails,
                        )
                    pending.state == MiniHomePendingState.RECONCILIATION_REQUIRED &&
                        pending.failure?.requiresReconciliation == true ->
                        MiniHomeSaveState.ReconciliationRequired(requireNotNull(pending.failure))
                    pending.state == MiniHomePendingState.RECONCILIATION_REQUIRED ||
                        restored.expectedRevision != loaded.committed.revision ->
                        MiniHomeSaveState.Conflict
                    pending.failure?.retryable == true -> MiniHomeSaveState.Failed(pending.failure)
                    else -> MiniHomeSaveState.Idle
                }
            val replacement =
                MiniHomeUiState.Editing(
                    loaded.committed,
                    restored.layout,
                    loaded.plants,
                    loaded.decorations,
                    restored.layout.placements.lastOrNull()?.id,
                    restored.operationId,
                    saveState,
                    stale = loaded.stale,
                    lineageId = restored.lineageId,
                    supersedesOperationId = restored.supersedesOperationId,
                    discardHandle = pending.discardHandle,
                    discardFeedback =
                        if (
                            discardResult is MiniHomeDiscardResult.StaleHandle ||
                                discardResult is MiniHomeDiscardResult.Missing
                        ) {
                            MiniHomeDiscardFeedback.STALE_HANDLE
                        } else {
                            MiniHomeDiscardFeedback.RETRY_REQUIRED
                        },
                    owner = activeOwner,
                )
            setEditing(replacement, token)
            return if (discardResult is MiniHomeDiscardResult.Missing) {
                MiniHomeDiscardResult.StaleHandle(pending)
            } else if (discardResult is MiniHomeDiscardResult.StaleHandle) {
                MiniHomeDiscardResult.StaleHandle(pending)
            } else {
                discardResult
            }
        }
        if (
            discardResult is MiniHomeDiscardResult.Missing ||
                discardResult is MiniHomeDiscardResult.StaleHandle
        ) {
            clearDraft(activeOwner, token)
            setState(
                loaded.viewing(),
                token.copy(draftIdentity = null, guardDraftIdentity = false),
            )
            return MiniHomeDiscardResult.Missing
        }
        setEditing(
            editing.copy(
                committed = loaded.committed,
                plants = loaded.plants,
                decorations = loaded.decorations,
                stale = loaded.stale,
                discardFeedback = MiniHomeDiscardFeedback.RETRY_REQUIRED,
                saveState = editing.discardFailureState(),
            ),
            token,
        )
        return discardResult
    }

    suspend fun adoptAuthoritativeAfterConflict() {
        val editing = _state.value as? MiniHomeUiState.Editing ?: return
        if (editing.saveState !is MiniHomeSaveState.Conflict) return
        val token = captureToken(editing)
        val activeOwner = token.owner ?: return
        val tracked = registerOwnerJob(token)
        try {
            val handle = editing.discardHandle
            if (handle != null) {
                val result = repository.abandon(handle)
                if (!isCurrent(token)) return
                when (result) {
                    MiniHomeDiscardResult.Consumed -> Unit
                    is MiniHomeDiscardResult.Committed -> {
                        clearDraft(activeOwner, token)
                        setState(
                            MiniHomeUiState.Viewing(
                                result.authoritative,
                                editing.plants,
                                editing.decorations,
                                stale = false,
                                saved = true,
                                owner = activeOwner,
                            ),
                            token.copy(draftIdentity = null, guardDraftIdentity = false),
                        )
                        return
                    }
                    MiniHomeDiscardResult.Missing,
                    is MiniHomeDiscardResult.StaleHandle -> {
                        clearDraft(activeOwner, token)
                        if (!isCurrent(token)) return
                        load(MiniHomeDiscardFeedback.STALE_HANDLE, activeOwner)
                        return
                    }
                    MiniHomeDiscardResult.OwnerMismatch,
                    MiniHomeDiscardResult.Rejected -> {
                        setEditing(
                            editing.copy(discardFeedback = MiniHomeDiscardFeedback.RETRY_REQUIRED),
                            token,
                        )
                        return
                    }
                }
            }
            if (!isCurrent(token)) return
            val adoptedOperationId = operationIdFactory()
            val adopted =
                editing.copy(
                    draft = editing.committed,
                    selectedPlacementId = null,
                    operationId = adoptedOperationId,
                    saveState = MiniHomeSaveState.Idle,
                    issue = null,
                    lineageId = adoptedOperationId,
                    supersedesOperationId = null,
                    discardHandle = null,
                )
            setEditing(adopted, token)
        } finally {
            unregisterOwnerJob(tracked)
        }
    }

    suspend fun save() {
        val current = _state.value
        val currentOperationId = (current as? MiniHomeUiState.Editing)?.operationId
        MiniHomeSaveActionDiagnostics.observe(
            MiniHomeSaveActionObservation(
                MiniHomeSaveActionStage.CONTROLLER_ENTRY,
                currentOperationId,
            )
        )
        val editing = current as? MiniHomeUiState.Editing
        if (editing == null) {
            MiniHomeSaveActionDiagnostics.observe(
                MiniHomeSaveActionObservation(
                    MiniHomeSaveActionStage.GUARD_DECISION,
                    decision = MiniHomeSaveActionDecision.REJECTED,
                )
            )
            return
        }
        if (
            editing.saveState is MiniHomeSaveState.Conflict ||
                editing.saveState is MiniHomeSaveState.ValidationFailed ||
                editing.saveState is MiniHomeSaveState.ReconciliationRequired ||
                editing.saveState is MiniHomeSaveState.Saving
        ) {
            MiniHomeSaveActionDiagnostics.observe(
                MiniHomeSaveActionObservation(
                    MiniHomeSaveActionStage.GUARD_DECISION,
                    editing.operationId,
                    MiniHomeSaveActionDecision.REJECTED,
                )
            )
            return
        }
        val token = captureToken(editing)
        val activeOwner = token.owner
        if (activeOwner == null) {
            MiniHomeSaveActionDiagnostics.observe(
                MiniHomeSaveActionObservation(
                    MiniHomeSaveActionStage.GUARD_DECISION,
                    editing.operationId,
                    MiniHomeSaveActionDecision.REJECTED,
                )
            )
            return
        }
        MiniHomeSaveActionDiagnostics.observe(
            MiniHomeSaveActionObservation(
                MiniHomeSaveActionStage.GUARD_DECISION,
                editing.operationId,
                MiniHomeSaveActionDecision.ACCEPTED,
            )
        )
        val violation = MiniHomeRequestContract.validate(editing.request(activeOwner))
        if (violation != null) {
            MiniHomeSaveActionDiagnostics.observe(
                MiniHomeSaveActionObservation(
                    MiniHomeSaveActionStage.VALIDATION_DECISION,
                    editing.operationId,
                    MiniHomeSaveActionDecision.REJECTED,
                )
            )
            setEditing(
                editing.copy(
                    issue =
                        if (violation.field == "name") {
                            MiniHomePlacementIssue.INVALID_NAME
                        } else {
                            MiniHomePlacementIssue.INVALID_REQUEST
                        }
                ),
                token,
            )
            return
        }
        MiniHomeSaveActionDiagnostics.observe(
            MiniHomeSaveActionObservation(
                MiniHomeSaveActionStage.VALIDATION_DECISION,
                editing.operationId,
                MiniHomeSaveActionDecision.ACCEPTED,
            )
        )
        val frozen = editing.copy(saveState = MiniHomeSaveState.Saving, issue = null)
        if (!setEditing(frozen, token)) return
        MiniHomeSaveActionDiagnostics.observe(
            MiniHomeSaveActionObservation(
                MiniHomeSaveActionStage.SAVING_PUBLICATION,
                frozen.operationId,
            )
        )
        val requestToken = captureToken(frozen)
        val tracked = registerOwnerJob(requestToken)
        if (!isCurrent(requestToken)) {
            unregisterOwnerJob(tracked)
            return
        }
        val saveRequestGeneration = ++saveGeneration
        val request = frozen.request(activeOwner)
        val registration =
            ActiveSave(
                activeOwner,
                request.operationId,
                saveRequestGeneration,
                CompletableDeferred(),
                CompletableDeferred(),
            )
        activeSave = registration
        observeRetryBoundary(
            MiniHomeRetryStage.REPOSITORY_SAVE_ENTRY,
            requestToken,
            request.operationId,
            saveRequestGeneration,
        )
        val result =
            try {
                repository.save(request).also { savedResult ->
                    observeRetryBoundary(
                        MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED,
                        requestToken,
                        request.operationId,
                        saveRequestGeneration,
                        outcome = "returned",
                        result = savedResult,
                    )
                }
            } catch (error: CancellationException) {
                observeRetryBoundary(
                    MiniHomeRetryStage.REPOSITORY_SAVE_CANCELLED,
                    requestToken,
                    request.operationId,
                    saveRequestGeneration,
                    outcome = "cancellation",
                    failure = error,
                )
                registration.completion.complete(null)
                if (isCurrent(requestToken) && saveRequestGeneration == saveGeneration) {
                    setEditing(frozen.copy(saveState = MiniHomeSaveState.Idle), requestToken)
                }
                registration.settled.complete(Unit)
                if (activeSave === registration) activeSave = null
                unregisterOwnerJob(tracked)
                throw error
            } catch (error: Exception) {
                observeRetryBoundary(
                    MiniHomeRetryStage.REPOSITORY_SAVE_THROWN,
                    requestToken,
                    request.operationId,
                    saveRequestGeneration,
                    outcome = "exception",
                    failure = error,
                )
                MiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK)
            } catch (error: Throwable) {
                observeRetryBoundary(
                    MiniHomeRetryStage.REPOSITORY_SAVE_THROWN,
                    requestToken,
                    request.operationId,
                    saveRequestGeneration,
                    outcome = "exception",
                    failure = error,
                )
                throw error
            }
        registration.completion.complete(result)
        try {
            if (result is MiniHomeSaveResult.Saved) {
                observeRetryBoundary(
                    MiniHomeRetryStage.SAVED_APPLY_ENTRY,
                    requestToken,
                    frozen.operationId,
                    saveRequestGeneration,
                    result = result,
                )
            }
            if (!isCurrent(requestToken) || saveRequestGeneration != saveGeneration) {
                if (result is MiniHomeSaveResult.Saved) {
                    observeRetryBoundary(
                        MiniHomeRetryStage.SAVED_APPLY_REJECTED,
                        requestToken,
                        frozen.operationId,
                        saveRequestGeneration,
                        result = result,
                        outcome =
                            if (!isCurrent(requestToken)) "stale-token"
                            else "stale-save-generation",
                    )
                }
                return
            }
            if (result is MiniHomeSaveResult.PendingChanged) {
                load(MiniHomeDiscardFeedback.STALE_HANDLE, activeOwner)
                return
            }
            applySaveResult(frozen, result, requestToken)
        } finally {
            registration.settled.complete(Unit)
            if (activeSave === registration && registration.generation == saveGeneration) {
                activeSave = null
            }
            unregisterOwnerJob(tracked)
        }
    }

    suspend fun reconcileSaveFailure() {
        val editing = _state.value as? MiniHomeUiState.Editing ?: return
        val required = editing.saveState as? MiniHomeSaveState.ReconciliationRequired ?: return
        val token = captureToken(editing)
        val activeOwner = token.owner ?: return
        val reconcileRequestGeneration = ++saveGeneration
        val reconciling = editing.copy(saveState = MiniHomeSaveState.Saving, issue = null)
        if (!setEditing(reconciling, token)) return
        val requestToken = captureToken(reconciling)
        val tracked = registerOwnerJob(requestToken)
        if (!isCurrent(requestToken)) {
            unregisterOwnerJob(tracked)
            return
        }
        val request = reconciling.request(activeOwner)
        val registration =
            ActiveSave(
                activeOwner,
                request.operationId,
                reconcileRequestGeneration,
                CompletableDeferred(),
                CompletableDeferred(),
            )
        activeSave = registration
        val result =
            try {
                repository.reconcile(request, required.failure, editing.discardHandle)
            } catch (error: CancellationException) {
                registration.completion.complete(null)
                if (isCurrent(requestToken) && reconcileRequestGeneration == saveGeneration) {
                    setEditing(editing, requestToken)
                }
                registration.settled.complete(Unit)
                if (activeSave === registration) activeSave = null
                unregisterOwnerJob(tracked)
                throw error
            } catch (_: Exception) {
                MiniHomeSaveResult.RequiresReconciliation(required.failure)
            }
        registration.completion.complete(result)
        try {
            if (!isCurrent(requestToken) || reconcileRequestGeneration != saveGeneration) return
            if (result is MiniHomeSaveResult.PendingChanged) {
                load(MiniHomeDiscardFeedback.STALE_HANDLE, activeOwner)
                return
            }
            applySaveResult(reconciling, result, requestToken)
        } finally {
            registration.settled.complete(Unit)
            if (activeSave === registration && registration.generation == saveGeneration) {
                activeSave = null
            }
            unregisterOwnerJob(tracked)
        }
    }

    private suspend fun applySaveResult(
        frozen: MiniHomeUiState.Editing,
        result: MiniHomeSaveResult,
        token: ControllerOperationToken,
    ) {
        if (!isCurrent(token)) return
        val activeOwner = token.owner ?: return
        when (result) {
            is MiniHomeSaveResult.Saved -> {
                recordConfirmedSave(
                    ConfirmedSave(
                        activeOwner,
                        frozen.operationId,
                        frozen.lineageId,
                        frozen.supersedesOperationId,
                        result.layout,
                    ),
                    token,
                )
                clearDraft(activeOwner, token)
                setState(
                    MiniHomeUiState.Viewing(
                        result.layout,
                        frozen.plants,
                        frozen.decorations,
                        stale = false,
                        saved = true,
                        exitOutcome = token.exitOutcome(MiniHomeExitOutcomeKind.SAVED),
                        owner = activeOwner,
                    ),
                    token.copy(draftIdentity = null, guardDraftIdentity = false),
                )
            }
            is MiniHomeSaveResult.Conflict ->
                setEditing(
                    frozen.copy(
                        committed = result.authoritative,
                        plants = result.plants,
                        decorations = result.decorations,
                        saveState = MiniHomeSaveState.Conflict,
                    ),
                    token,
                )
            is MiniHomeSaveResult.Failed -> {
                val saveState =
                    when {
                        result.failure.requiresCorrection ->
                            MiniHomeSaveState.ValidationFailed(result.failure)
                        result.failure.requiresReconciliation ->
                            MiniHomeSaveState.ReconciliationRequired(result.failure)
                        else -> MiniHomeSaveState.Failed(result.failure)
                    }
                setEditing(
                    frozen.copy(
                        saveState = saveState,
                        discardHandle = result.discardHandle ?: frozen.discardHandle,
                    ),
                    token,
                )
            }
            is MiniHomeSaveResult.RequiresCorrection ->
                setEditing(
                    frozen.copy(
                        saveState =
                            MiniHomeSaveState.ValidationFailed(result.failure, result.details),
                        discardHandle = result.discardHandle ?: frozen.discardHandle,
                    ),
                    token,
                )
            is MiniHomeSaveResult.RequiresReconciliation ->
                setEditing(
                    frozen.copy(
                        saveState = MiniHomeSaveState.ReconciliationRequired(result.failure),
                        discardHandle = result.discardHandle ?: frozen.discardHandle,
                    ),
                    token,
                )
            is MiniHomeSaveResult.Reconciled -> {
                val corrected =
                    result.correctedDraft.copy(
                        revision = result.authoritative.revision,
                        updatedAt = result.authoritative.updatedAt,
                    )
                val correctedOperationId = operationIdFactory()
                setEditing(
                    frozen.copy(
                        committed = result.authoritative,
                        draft = corrected,
                        plants = result.plants,
                        decorations = result.decorations,
                        selectedPlacementId =
                            frozen.selectedPlacementId?.takeIf { selected ->
                                corrected.placements.any { it.id == selected }
                            },
                        operationId = correctedOperationId,
                        saveState =
                            MiniHomeSaveState.Corrected(
                                result.failure,
                                result.removedTargets,
                            ),
                        lineageId = correctedOperationId,
                        supersedesOperationId = null,
                        discardHandle = null,
                    ),
                    token,
                )
            }
            is MiniHomeSaveResult.PendingChanged -> Unit
            MiniHomeSaveResult.Forbidden -> switchToForbiddenFromOperation(token)
        }
    }

    fun ownerOrNull(): AccountId? = owner

    private fun publishSession() {
        _session.value = MiniHomeControllerSessionToken(controllerEpoch, generation, owner)
    }

    private fun ControllerOperationToken.exitOutcome(
        kind: MiniHomeExitOutcomeKind
    ): MiniHomeExitOutcome? {
        val activeOwner = owner ?: return null
        val identity = draftIdentity ?: return null
        return MiniHomeExitOutcome(
            kind,
            activeOwner,
            identity.operationId,
            identity.lineageId,
            identity.discardHandle,
        )
    }

    private fun MiniHomeUiState.Editing.withCorrelatedSaveResult(
        result: MiniHomeSaveResult?
    ): MiniHomeUiState.Editing =
        when (result) {
            is MiniHomeSaveResult.Conflict ->
                copy(
                    committed = result.authoritative,
                    plants = result.plants,
                    decorations = result.decorations,
                )
            is MiniHomeSaveResult.Reconciled ->
                copy(
                    committed = result.authoritative,
                    plants = result.plants,
                    decorations = result.decorations,
                )
            else -> this
        }

    private fun MiniHomeUiState.Editing.discardFailureState(
        result: MiniHomeSaveResult? = null
    ): MiniHomeSaveState =
        when (result) {
            is MiniHomeSaveResult.Failed ->
                when {
                    result.failure.requiresCorrection ->
                        MiniHomeSaveState.ValidationFailed(result.failure)
                    result.failure.requiresReconciliation ->
                        MiniHomeSaveState.ReconciliationRequired(result.failure)
                    else -> MiniHomeSaveState.Failed(result.failure)
                }
            is MiniHomeSaveResult.RequiresCorrection ->
                MiniHomeSaveState.ValidationFailed(result.failure, result.details)
            is MiniHomeSaveResult.RequiresReconciliation ->
                MiniHomeSaveState.ReconciliationRequired(result.failure)
            is MiniHomeSaveResult.Conflict -> MiniHomeSaveState.Conflict
            is MiniHomeSaveResult.PendingChanged ->
                MiniHomeSaveState.ReconciliationRequired(MiniHomeSaveFailure.OUTBOX_MISMATCH)
            else ->
                if (saveState is MiniHomeSaveState.Saving) {
                    MiniHomeSaveState.ReconciliationRequired(MiniHomeSaveFailure.OUTBOX_MISMATCH)
                } else {
                    saveState
                }
        }

    private fun MiniHomeSaveResult?.discardHandleOrNull(): MiniHomeDiscardHandle? =
        when (this) {
            is MiniHomeSaveResult.Failed -> discardHandle
            is MiniHomeSaveResult.RequiresCorrection -> discardHandle
            is MiniHomeSaveResult.RequiresReconciliation -> discardHandle
            else -> null
        }

    private fun publishConfirmedSave(
        confirmed: ConfirmedSave,
        loaded: MiniHomeLoadResult.Ready,
        token: ControllerOperationToken,
    ) {
        clearDraft(confirmed.owner, token)
        setState(
            MiniHomeUiState.Viewing(
                confirmed.layout,
                loaded.plants,
                loaded.decorations,
                stale = false,
                saved = true,
                exitOutcome =
                    MiniHomeExitOutcome(
                        MiniHomeExitOutcomeKind.SAVED,
                        confirmed.owner,
                        confirmed.operationId,
                        confirmed.lineageId,
                        null,
                    ),
                owner = confirmed.owner,
                loadIdentity = loaded.loadIdentity,
            ),
            token.copy(draftIdentity = null, guardDraftIdentity = false),
        )
    }

    private fun MiniHomeLoadResult.Ready.correlatesNewerSameOwnerState(): Boolean {
        val confirmed = confirmedSave?.takeIf { it.owner == accountId }
        if (confirmed != null && committed.revision.value <= confirmed.layout.revision.value) {
            return true
        }
        val restored =
            MiniHomeDraftCodec.decode(savedStateHandle[DRAFT_KEY])?.takeIf {
                it.owner == accountId
            } ?: return false
        return pending == null && committedReceipt.matches(restored, committed)
    }

    private fun MiniHomeCommittedReceipt?.matches(
        restored: RestoredMiniHomeDraft,
        committed: MiniHomeLayout,
    ): Boolean =
        this != null &&
            operationId == restored.operationId &&
            expectedRevision == restored.expectedRevision &&
            committedRevision == committed.revision &&
            committedRevision == restored.expectedRevision.next() &&
            MiniHomePayloadHash.constantTimeEquals(
                payloadHash,
                MiniHomePayloadHash.create(restored.expectedRevision, restored.layout),
            ) &&
            committed.id == restored.layout.id &&
            committed.name == restored.layout.name &&
            committed.placements == restored.layout.placements

    private fun MiniHomeUiState.Editing.pendingReconciliationFailure(): MiniHomeSaveFailure =
        (saveState as? MiniHomeSaveState.ReconciliationRequired)?.failure
            ?: MiniHomeSaveFailure.OUTBOX_MISMATCH

    private fun MiniHomeUiState.Editing.request(accountId: AccountId) =
        MiniHomeSaveRequest(
            accountId,
            operationId,
            committed.revision,
            draft,
            lineageId,
            supersedesOperationId,
        )

    private fun add(target: MiniHomePlacementTarget) {
        val editing = editable() ?: return
        val token = captureToken(editing)
        if (editing.draft.placements.any { it.target.stableId == target.stableId }) {
            setEditing(editing.copy(issue = MiniHomePlacementIssue.ALREADY_PLACED), token)
            return
        }
        if (target is MiniHomePlacementTarget.Decoration) {
            val choice = editing.decorations.singleOrNull { it.id == target.itemId }
            if (choice == null || !choice.availableForApplication || choice.category == null) {
                setEditing(editing.copy(issue = MiniHomePlacementIssue.ITEM_UNAVAILABLE), token)
                return
            }
            val category = choice.category
            val categoryCount =
                editing.draft.placements.count { placement ->
                    val itemId =
                        (placement.target as? MiniHomePlacementTarget.Decoration)?.itemId
                            ?: return@count false
                    editing.decorations.singleOrNull { it.id == itemId }?.category == category
                }
            val limit = if (category == ItemCategory.BACKGROUND) 1 else 10
            if (categoryCount >= limit) {
                setEditing(editing.copy(issue = MiniHomePlacementIssue.CATEGORY_LIMIT), token)
                return
            }
        }
        val position =
            (0 until MiniHomeGrid.ROWS)
                .flatMap { row ->
                    (0 until MiniHomeGrid.COLUMNS).map { column -> GridPosition(column, row) }
                }
                .firstOrNull { candidate ->
                    editing.draft.placements.none { it.position == candidate }
                }
        if (position == null) {
            setEditing(editing.copy(issue = MiniHomePlacementIssue.ROOM_FULL), token)
            return
        }
        val id = placementIdFactory()
        val placements =
            MiniHomePlacementPolicy.layer(
                editing.draft.placements +
                    MiniHomePlacement(id, target, position, MiniHomeZIndex(0))
            )
        setEditing(
            editing
                .afterDraftChange(editing.draft.copy(placements = placements))
                .copy(selectedPlacementId = id),
            token,
        )
    }

    private fun editable(): MiniHomeUiState.Editing? =
        (_state.value as? MiniHomeUiState.Editing)?.takeIf { !it.frozen }

    private fun updateDraft(transform: (MiniHomeLayout) -> MiniHomeLayout) {
        val editing = editable() ?: return
        val token = captureToken(editing)
        val transformed = runCatching {
            transform(editing.draft)
        }
            .getOrElse {
                setEditing(editing.copy(issue = MiniHomePlacementIssue.INVALID_NAME), token)
                return
            }
        setEditing(editing.afterDraftChange(transformed), token)
    }

    private fun MiniHomeUiState.Editing.afterDraftChange(
        changed: MiniHomeLayout
    ): MiniHomeUiState.Editing {
        if (changed == draft) return copy(issue = null)
        val correctingValidation = saveState is MiniHomeSaveState.ValidationFailed
        val correctedOperationId = if (correctingValidation) operationIdFactory() else operationId
        return copy(
            draft = changed,
            operationId = correctedOperationId,
            saveState = if (correctingValidation) MiniHomeSaveState.Idle else saveState,
            issue = null,
            supersedesOperationId =
                if (correctingValidation) operationId else supersedesOperationId,
        )
    }

    private fun captureToken(
        editing: MiniHomeUiState.Editing? = _state.value as? MiniHomeUiState.Editing,
        guardDraftIdentity: Boolean = editing != null,
    ): ControllerOperationToken =
        ControllerOperationToken(
            controllerEpoch,
            generation,
            owner,
            editing?.let { DraftIdentity(it.operationId, it.lineageId, it.discardHandle) },
            guardDraftIdentity,
        )

    private fun isCurrentLoad(token: ControllerOperationToken, requestSequence: Long): Boolean =
        requestSequence == loadSequence && isCurrent(token)

    private suspend fun awaitSameOwnerSaveSettlement(activeOwner: AccountId) {
        while (true) {
            val registration = activeSave?.takeIf { it.owner == activeOwner } ?: return
            registration.settled.await()
            if (activeSave === registration || activeSave == null) return
        }
    }

    private fun isCurrent(token: ControllerOperationToken): Boolean {
        if (
            token.controllerEpoch != controllerEpoch ||
                savedStateHandle.get<Long>(CONTROLLER_EPOCH_KEY) != controllerEpoch ||
                token.generation != generation ||
                token.owner != owner
        ) {
            return false
        }
        if (!token.guardDraftIdentity) return true
        val editing = _state.value as? MiniHomeUiState.Editing ?: return false
        return token.draftIdentity ==
            DraftIdentity(editing.operationId, editing.lineageId, editing.discardHandle)
    }

    private fun observeRetryBoundary(
        stage: MiniHomeRetryStage,
        token: ControllerOperationToken,
        operationId: OperationId? = token.draftIdentity?.operationId,
        saveGeneration: Long? = null,
        outcome: String? = null,
        result: MiniHomeSaveResult? = null,
        revision: Long? = (result as? MiniHomeSaveResult.Saved)?.layout?.revision?.value,
        failure: Throwable? = null,
    ) {
        MiniHomeRetryDiagnostics.observe(
            MiniHomeRetryObservation(
                stage = stage,
                operationId = operationId,
                controllerEpoch = token.controllerEpoch,
                controllerGeneration = token.generation,
                saveGeneration = saveGeneration,
                guardDraftIdentity = token.guardDraftIdentity,
                revision = revision,
                outcome = outcome,
                result = result,
                failure = failure,
            )
        )
    }

    private fun diagnosticOperationId(
        value: MiniHomeUiState,
        token: ControllerOperationToken,
    ): OperationId? =
        token.draftIdentity?.operationId
            ?: (value as? MiniHomeUiState.Editing)?.operationId
            ?: (value as? MiniHomeUiState.Viewing)?.exitOutcome?.operationId

    private fun setState(value: MiniHomeUiState, token: ControllerOperationToken): Boolean {
        observeRetryBoundary(
            MiniHomeRetryStage.SET_STATE_ATTEMPTED,
            token,
            diagnosticOperationId(value, token),
        )
        if (!isCurrent(token)) {
            observeRetryBoundary(
                MiniHomeRetryStage.SET_STATE_REJECTED,
                token,
                diagnosticOperationId(value, token),
                outcome = "stale-token",
            )
            return false
        }
        _state.value = value
        stateVersion += 1
        observeRetryBoundary(
            MiniHomeRetryStage.SET_STATE_APPLIED,
            token,
            diagnosticOperationId(value, token),
            revision = (value as? MiniHomeUiState.Viewing)?.committed?.revision?.value,
        )
        return true
    }

    private fun setEditing(
        value: MiniHomeUiState.Editing,
        token: ControllerOperationToken,
    ): Boolean {
        if (!isCurrent(token)) return false
        val activeOwner = token.owner ?: return false
        _state.value = value
        stateVersion += 1
        persistDraft(value, activeOwner, token)
        return true
    }

    private fun persistDraft(
        editing: MiniHomeUiState.Editing,
        explicitOwner: AccountId,
        token: ControllerOperationToken,
    ) {
        check(
            token.controllerEpoch == controllerEpoch &&
                savedStateHandle.get<Long>(CONTROLLER_EPOCH_KEY) == controllerEpoch &&
                token.generation == generation &&
                token.owner == explicitOwner &&
                owner == explicitOwner
        ) {
            "stale mini-home owner token cannot persist a draft"
        }
        bindRestorationOwner(explicitOwner)
        savedStateHandle[DRAFT_KEY] =
            MiniHomeDraftCodec.encode(
                RestoredMiniHomeDraft(
                    explicitOwner,
                    editing.operationId,
                    editing.committed.revision,
                    editing.draft,
                    editing.lineageId,
                    editing.supersedesOperationId,
                )
            )
    }

    private fun recordConfirmedSave(
        value: ConfirmedSave,
        token: ControllerOperationToken,
    ): Boolean {
        if (!isCurrent(token) || token.owner != value.owner) return false
        confirmedSave = value
        bindRestorationOwner(value.owner)
        savedStateHandle[CONFIRMED_SAVE_KEY] =
            MiniHomeDraftCodec.encode(
                RestoredMiniHomeDraft(
                    value.owner,
                    value.operationId,
                    value.layout.revision,
                    value.layout,
                    value.lineageId,
                    value.supersedesOperationId,
                )
            )
        return true
    }

    private fun clearConfirmedSave(
        explicitOwner: AccountId,
        token: ControllerOperationToken,
    ): Boolean {
        if (!isCurrent(token) || token.owner != explicitOwner) return false
        if (confirmedSave?.owner == explicitOwner) confirmedSave = null
        val persisted = MiniHomeDraftCodec.decode(savedStateHandle[CONFIRMED_SAVE_KEY])
        if (persisted == null || persisted.owner == explicitOwner) {
            savedStateHandle[CONFIRMED_SAVE_KEY] = null
        }
        return true
    }

    private fun clearDraft(
        explicitOwner: AccountId,
        token: ControllerOperationToken,
    ): Boolean {
        if (!isCurrent(token) || token.owner != explicitOwner) return false
        val persisted = MiniHomeDraftCodec.decode(savedStateHandle[DRAFT_KEY])
        if (persisted == null || persisted.owner == explicitOwner) {
            savedStateHandle[DRAFT_KEY] = null
        }
        return true
    }

    private fun resolveRestorationOwner(
        currentOwner: AccountId?,
        incomingOwner: AccountId?,
    ) {
        val restoredOwner =
            savedStateHandle.get<String>(RESTORATION_OWNER_KEY)?.let { raw ->
                runCatching { AccountId(raw) }.getOrNull()
            }
        val currentOwnerChanged = currentOwner != null && currentOwner != incomingOwner
        val restoredOwnerChanged = restoredOwner == null || restoredOwner != incomingOwner
        if (currentOwnerChanged || restoredOwnerChanged) {
            confirmedSave = null
            savedStateHandle[DRAFT_KEY] = null
            savedStateHandle[CONFIRMED_SAVE_KEY] = null
        }
        bindRestorationOwner(incomingOwner)
    }

    private fun bindRestorationOwner(authoritativeOwner: AccountId?) {
        savedStateHandle[RESTORATION_OWNER_KEY] = authoritativeOwner?.value
    }

    private suspend fun registerOwnerJob(token: ControllerOperationToken): TrackedOwnerJob? {
        val activeOwner = token.owner ?: return null
        val job = currentCoroutineContext()[Job] ?: return null
        synchronized(ownerJobsLock) { ownerJobs.getOrPut(activeOwner, ::mutableSetOf).add(job) }
        return TrackedOwnerJob(activeOwner, job)
    }

    private fun unregisterOwnerJob(tracked: TrackedOwnerJob?) {
        tracked ?: return
        synchronized(ownerJobsLock) {
            ownerJobs[tracked.owner]?.let { jobs ->
                jobs.remove(tracked.job)
                if (jobs.isEmpty()) ownerJobs.remove(tracked.owner)
            }
        }
    }

    private suspend fun cancelAndJoinOwnerJobs(
        previousOwner: AccountId?,
        except: Job?,
    ) {
        previousOwner ?: return
        val jobs =
            synchronized(ownerJobsLock) {
                ownerJobs[previousOwner].orEmpty().filterNot { it === except }.toList()
            }
        jobs.forEach(Job::cancel)
        jobs.forEach { it.join() }
    }

    private fun MiniHomeLoadResult.Ready.viewing() =
        MiniHomeUiState.Viewing(
            committed,
            plants,
            decorations,
            stale,
            owner = accountId,
            loadIdentity = loadIdentity,
        )

    private fun MiniHomePendingSave.restored(owner: AccountId) =
        RestoredMiniHomeDraft(
            owner,
            operationId,
            expectedRevision,
            layout,
            lineageId,
            supersedesOperationId,
        )

    private data class DraftIdentity(
        val operationId: OperationId,
        val lineageId: OperationId,
        val discardHandle: MiniHomeDiscardHandle?,
    )

    private data class ControllerOperationToken(
        val controllerEpoch: Long,
        val generation: Long,
        val owner: AccountId?,
        val draftIdentity: DraftIdentity?,
        val guardDraftIdentity: Boolean,
    )

    private data class TrackedOwnerJob(val owner: AccountId, val job: Job)

    private data class ActiveSave(
        val owner: AccountId,
        val operationId: OperationId,
        val generation: Long,
        val completion: CompletableDeferred<MiniHomeSaveResult?>,
        val settled: CompletableDeferred<Unit>,
    )

    private data class ConfirmedSave(
        val owner: AccountId,
        val operationId: OperationId,
        val lineageId: OperationId,
        val supersedesOperationId: OperationId?,
        val layout: MiniHomeLayout,
    )

    private companion object {
        const val DRAFT_KEY = "mini-home.draft"
        const val CONFIRMED_SAVE_KEY = "mini-home.confirmed-save"
        const val CONTROLLER_EPOCH_KEY = "mini-home.controller-epoch"
        const val RESTORATION_OWNER_KEY = "mini-home.restoration-owner"
    }
}
