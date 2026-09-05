package com.planterior.helper.feature.shop

import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.OperationId
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class InventorySection {
    WAREHOUSE,
    SHOP,
}

enum class InventoryFeedback {
    ACQUIRED,
    CONDITION_NOT_MET,
    ALREADY_OWNED,
    NETWORK_FAILURE,
    CATALOG_CHANGED,
    ITEM_UNAVAILABLE,
    FAILURE,
    OPEN_MINI_HOME_TO_APPLY,
}

fun interface InventoryReceiptRedeliveryHandle {
    fun cancel()
}

fun interface InventoryReceiptRedeliveryScheduler {
    fun schedule(
        delayMillis: Long,
        task: suspend () -> Unit,
    ): InventoryReceiptRedeliveryHandle
}

fun interface InventoryAcknowledgementRetryBackoff {
    fun delayMillis(attempt: Int): Long
}

private object ExponentialInventoryAcknowledgementRetryBackoff :
    InventoryAcknowledgementRetryBackoff {
    override fun delayMillis(attempt: Int): Long {
        require(attempt >= 1)
        return (1_000L shl minOf(attempt - 1, 6)).coerceAtMost(60_000L)
    }
}

private object CoroutineInventoryReceiptRedeliveryScheduler : InventoryReceiptRedeliveryScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun schedule(
        delayMillis: Long,
        task: suspend () -> Unit,
    ): InventoryReceiptRedeliveryHandle {
        val job = scope.launch {
            delay(delayMillis)
            task()
        }
        return InventoryReceiptRedeliveryHandle { job.cancel() }
    }
}

sealed interface InventoryUiState {
    val owner: AccountId?

    data class Loading(override val owner: AccountId?) : InventoryUiState

    data class Content(
        override val owner: AccountId,
        val snapshot: InventorySnapshot,
        val section: InventorySection,
        val category: ItemCategory?,
        val searchQuery: String = "",
        val acquiringItemId: ItemId? = null,
        val feedback: InventoryFeedback? = null,
        val feedbackCondition: AcquisitionCondition? = null,
        val feedbackPresentationToken: InventoryFeedbackPresentationToken? = null,
        val stale: Boolean = false,
    ) : InventoryUiState {
        val feedbackReceiptId: InventoryReceiptId?
            get() = feedbackPresentationToken?.receiptId
    }

    data class Error(override val owner: AccountId?) : InventoryUiState

    data object Forbidden : InventoryUiState {
        override val owner: AccountId? = null
    }
}

class InventoryController(
    private val repository: InventoryRepository,
    private val savedStateHandle: SavedStateHandle,
    private val clock: Clock = Clock.systemUTC(),
    private val redeliveryScheduler: InventoryReceiptRedeliveryScheduler =
        CoroutineInventoryReceiptRedeliveryScheduler,
    private val acknowledgementRetryScheduler: InventoryReceiptRedeliveryScheduler =
        CoroutineInventoryReceiptRedeliveryScheduler,
    private val acknowledgementRetryBackoff: InventoryAcknowledgementRetryBackoff =
        ExponentialInventoryAcknowledgementRetryBackoff,
    private val operationIdFactory: () -> OperationId = OperationId::random,
) {
    private data class ActionToken(
        val owner: AccountId?,
        val generation: Long,
        val action: Long,
    )

    private data class FeedbackToken(
        val owner: AccountId,
        val itemId: ItemId,
        val operationId: OperationId,
        val sequence: Long,
        val receiptKind: InventoryOwnershipReceiptKind?,
        val terminalReceipt: InventoryAcquisitionTerminalReceipt? = null,
    )

    private data class ReceiptCandidate(
        val receiptId: InventoryReceiptId,
        val expected: InventoryReceiptPresentationExpectation?,
        val feedback: FeedbackToken?,
    )

    private data class PreparedAcquisition(
        val request: InventoryAcquireRequest,
        val feedbackToken: FeedbackToken,
    )

    private data class VisibleFeedback(
        val feedback: InventoryFeedback?,
        val condition: AcquisitionCondition?,
        val presentationToken: InventoryFeedbackPresentationToken?,
    )

    private data class ProvisionalReceiptPublication(
        val receiptId: InventoryReceiptId,
        val presentationToken: InventoryFeedbackPresentationToken,
        val previous: VisibleFeedback,
    )

    private data class RegisteredAction(
        val token: ActionToken,
        val job: Job,
        val obsoleteJobs: List<Job> = emptyList(),
        val ownerChanged: Boolean = false,
    )

    private data class ScheduledReceiptRedelivery(
        val owner: AccountId,
        val generation: Long,
        val receiptId: InventoryReceiptId,
        val retryAtEpochMillis: Long,
        val handle: InventoryReceiptRedeliveryHandle? = null,
    )

    private data class PendingAcknowledgement(
        val owner: AccountId,
        val generation: Long,
        val claim: InventoryReceiptClaim,
        val attempt: Int,
    )

    private data class ScheduledAcknowledgementRetry(
        val owner: AccountId,
        val generation: Long,
        val presentationToken: InventoryFeedbackPresentationToken,
        val attempt: Int,
        val handle: InventoryReceiptRedeliveryHandle? = null,
    )

    private val presentationClaimToken: String
    private val controllerEpoch: Long

    init {
        presentationClaimToken =
            savedStateHandle.get<String>(PRESENTATION_CLAIM_TOKEN_KEY)?.takeIf {
                it.isNotBlank() && it.length <= 128
            }
                ?: UUID.randomUUID().toString().also {
                    savedStateHandle[PRESENTATION_CLAIM_TOKEN_KEY] = it
                }
        controllerEpoch =
            savedStateHandle
                .get<Long>(PRESENTATION_CONTROLLER_EPOCH_KEY)
                ?.takeIf { it in 0 until Long.MAX_VALUE }
                ?.plus(1) ?: 1
        savedStateHandle[PRESENTATION_CONTROLLER_EPOCH_KEY] = controllerEpoch
    }

    private val _state = MutableStateFlow<InventoryUiState>(InventoryUiState.Loading(null))
    val state: StateFlow<InventoryUiState> = _state.asStateFlow()
    private val actionLock = Any()
    private val activeActions = mutableMapOf<Long, RegisteredAction>()
    private var generation = 0L
    private var nextAction = 0L
    private var nextOperationSequence = 0L
    private var pendingOperationId: OperationId? = null
    private var pendingOperationSequence = 0L
    private var latestFeedbackToken: FeedbackToken? = null
    private var latestInventoryAction = Long.MIN_VALUE
    private val receiptCandidates = mutableListOf<ReceiptCandidate>()
    private val receiptsById = mutableMapOf<InventoryReceiptId, InventoryReceiptClaim>()
    private val receiptDelivery = Mutex()
    private var scheduledReceiptRedelivery: ScheduledReceiptRedelivery? = null
    private var pendingAcknowledgement: PendingAcknowledgement? = null
    private var scheduledAcknowledgementRetry: ScheduledAcknowledgementRetry? = null
    private var owner: AccountId? = null
    private var cleared = false

    suspend fun start(auth: InventoryAuthOwnership) {
        val currentJob = requireNotNull(currentCoroutineContext()[Job])
        val action =
            when (auth) {
                InventoryAuthOwnership.Restoring,
                InventoryAuthOwnership.Unknown,
                InventoryAuthOwnership.Unmanaged -> registerAction(currentJob)
                InventoryAuthOwnership.SignedOut -> registerAuthAction(null, currentJob)
                is InventoryAuthOwnership.Authenticated ->
                    registerAuthAction(auth.accountId, currentJob)
            }
        try {
            action.obsoleteJobs.forEach(Job::cancel)
            action.obsoleteJobs.joinAll()
            if (!isCurrent(action.token)) return
            when (auth) {
                InventoryAuthOwnership.Restoring,
                InventoryAuthOwnership.Unknown -> Unit
                InventoryAuthOwnership.SignedOut ->
                    mutate(action.token) {
                        clearPending()
                        _state.value = InventoryUiState.Forbidden
                    }
                InventoryAuthOwnership.Unmanaged -> load(action.token)
                is InventoryAuthOwnership.Authenticated -> {
                    if (action.ownerChanged) {
                        mutate(action.token) {
                            clearPendingForOtherOwner(auth.accountId)
                            _state.value = InventoryUiState.Loading(auth.accountId)
                        }
                    }
                    load(action.token)
                }
            }
        } finally {
            unregister(action.token)
        }
    }

    fun clear() {
        val jobs =
            synchronized(actionLock) {
                if (cleared) return
                cleared = true
                cancelScheduledRedeliveryLocked()
                cancelScheduledAcknowledgementRetryLocked()
                activeActions.values.map { it.job }.distinct().also { activeActions.clear() }
            }
        jobs.forEach(Job::cancel)
    }

    fun selectSection(section: InventorySection) {
        updateContent {
            savedStateHandle[SECTION_KEY] = section.name
            it.copy(
                section = section,
                feedback = it.feedback.takeIf { _ -> it.feedbackReceiptId != null },
            )
        }
    }

    fun selectCategory(category: ItemCategory?) {
        updateContent {
            savedStateHandle[CATEGORY_KEY] = category?.name
            it.copy(
                category = category,
                feedback = it.feedback.takeIf { _ -> it.feedbackReceiptId != null },
            )
        }
    }

    fun search(query: String) {
        val bounded = query.take(MAX_SEARCH_LENGTH)
        updateContent {
            savedStateHandle[SEARCH_KEY] = bounded
            it.copy(
                searchQuery = bounded,
                feedback = it.feedback.takeIf { _ -> it.feedbackReceiptId != null },
            )
        }
    }

    fun acknowledgeFeedback() = updateContent {
        if (it.feedbackReceiptId == null) {
            it.copy(feedback = null, feedbackCondition = null)
        } else {
            it
        }
    }

    fun openingMiniHome() = updateContent {
        if (it.feedbackReceiptId == null) {
            it.copy(feedback = InventoryFeedback.OPEN_MINI_HOME_TO_APPLY)
        } else {
            it
        }
    }

    suspend fun retry() {
        val action = registerAction(requireNotNull(currentCoroutineContext()[Job]))
        try {
            var reload = false
            val retriedAcknowledgement = receiptDelivery.withLock {
                val pending = currentPendingAcknowledgement(action.token) ?: return@withLock false
                cancelScheduledAcknowledgementRetry(pending.claim.feedbackPresentationToken())
                reload = attemptPendingAcknowledgementLocked(action.token)
                true
            }
            if (!retriedAcknowledgement || reload) load(action.token, forceRefresh = true)
        } finally {
            unregister(action.token)
        }
    }

    suspend fun feedbackConsumed(presentationToken: InventoryFeedbackPresentationToken) {
        val action = registerAction(requireNotNull(currentCoroutineContext()[Job]))
        try {
            var reload = false
            receiptDelivery.withLock {
                val claim =
                    synchronized(actionLock) {
                        if (!isCurrentLocked(action.token)) return@synchronized null
                        val content = _state.value as? InventoryUiState.Content
                        if (content?.feedbackPresentationToken != presentationToken) {
                            return@synchronized null
                        }
                        receiptsById[presentationToken.receiptId]?.takeIf {
                            it.feedbackPresentationToken() == presentationToken
                        }
                    } ?: return@withLock
                val recorded =
                    synchronized(actionLock) {
                        if (!isCurrentLocked(action.token)) return@synchronized false
                        val current = pendingAcknowledgement
                        if (current?.claim?.feedbackPresentationToken() == presentationToken) {
                            return@synchronized false
                        }
                        pendingAcknowledgement =
                            PendingAcknowledgement(
                                requireNotNull(action.token.owner),
                                action.token.generation,
                                claim,
                                attempt = 0,
                            )
                        true
                    }
                if (recorded) reload = attemptPendingAcknowledgementLocked(action.token)
            }
            if (reload) load(action.token)
        } finally {
            unregister(action.token)
        }
    }

    suspend fun acquire(itemId: ItemId) {
        val action = registerAction(requireNotNull(currentCoroutineContext()[Job]))
        try {
            val prepared = prepareAcquisition(action.token, itemId) ?: return
            val request = prepared.request
            val result =
                try {
                    repository.acquire(request)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    InventoryAcquireResult.Failure(
                        InventoryFailure.MALFORMED_RESPONSE,
                        request.operationId,
                    )
                }
            if (!isCurrent(action.token)) return
            when (result) {
                is InventoryAcquireResult.Success -> {
                    val feedback =
                        prepared.terminalFeedback(
                            result.receipt,
                            InventoryOwnershipReceiptKind.ACQUIRED,
                        )
                    finishAcquisitionRequest(action.token, feedback)
                    enqueueTerminalFeedback(action.token, feedback)
                    reloadAfterAcquisition(action.token)
                }
                is InventoryAcquireResult.AlreadyOwned -> {
                    val feedback =
                        prepared.terminalFeedback(
                            result.receipt,
                            InventoryOwnershipReceiptKind.ALREADY_OWNED,
                        )
                    finishAcquisitionRequest(action.token, feedback)
                    enqueueTerminalFeedback(action.token, feedback)
                    reloadAfterAcquisition(action.token)
                }
                is InventoryAcquireResult.ConditionNotMet ->
                    publishNonReceiptFeedback(
                        action.token,
                        prepared.feedbackToken,
                        InventoryFeedback.CONDITION_NOT_MET,
                        result.condition,
                        clearPending = true,
                    )
                is InventoryAcquireResult.Failure ->
                    publishNonReceiptFeedback(
                        action.token,
                        prepared.feedbackToken,
                        when (result.reason) {
                            InventoryFailure.NETWORK -> InventoryFeedback.NETWORK_FAILURE
                            InventoryFailure.CATALOG_CHANGED -> InventoryFeedback.CATALOG_CHANGED
                            InventoryFailure.ITEM_UNAVAILABLE -> InventoryFeedback.ITEM_UNAVAILABLE
                            else -> InventoryFeedback.FAILURE
                        },
                        clearPending = result.reason != InventoryFailure.NETWORK,
                    )
                InventoryAcquireResult.Forbidden ->
                    publishNonReceiptFeedback(
                        action.token,
                        prepared.feedbackToken,
                        InventoryFeedback.FAILURE,
                        clearPending = false,
                    )
            }
        } finally {
            unregister(action.token)
        }
    }

    private fun prepareAcquisition(
        token: ActionToken,
        itemId: ItemId,
    ): PreparedAcquisition? =
        synchronized(actionLock) {
            if (!isCurrentLocked(token)) return@synchronized null
            val content = _state.value as? InventoryUiState.Content ?: return@synchronized null
            if (content.owner != token.owner) return@synchronized null
            val item =
                content.snapshot.catalog.singleOrNull { it.id == itemId }
                    ?: return@synchronized null
            if (
                InventoryPolicy.shopEntries(content.snapshot, null)
                    .single { it.id == itemId }
                    .eligibility != AcquisitionEligibility.ELIGIBLE
            ) {
                return@synchronized null
            }
            val pendingOwner = savedStateHandle.get<String>(PENDING_OWNER_KEY)
            val operationId =
                if (pendingOwner == content.owner.value) {
                    savedStateHandle.get<String>(PENDING_OPERATION_KEY)?.let(::OperationId)
                } else {
                    null
                }
                    ?: operationIdFactory().also {
                        savedStateHandle[PENDING_OWNER_KEY] = content.owner.value
                        savedStateHandle[PENDING_OPERATION_KEY] = it.value
                        savedStateHandle[PENDING_ITEM_KEY] = item.id.value
                        savedStateHandle[PENDING_REVISION_KEY] = item.revision.value
                    }
            val pendingItem = savedStateHandle.get<String>(PENDING_ITEM_KEY)
            val pendingRevision = savedStateHandle.get<Long>(PENDING_REVISION_KEY)
            if (pendingItem != item.id.value || pendingRevision != item.revision.value) {
                return@synchronized null
            }
            val sequence =
                if (pendingOperationId == operationId) {
                    pendingOperationSequence
                } else {
                    ++nextOperationSequence
                }
            pendingOperationId = operationId
            pendingOperationSequence = sequence
            val feedbackToken =
                FeedbackToken(content.owner, item.id, operationId, sequence, receiptKind = null)
            if ((latestFeedbackToken?.sequence ?: Long.MIN_VALUE) <= sequence) {
                latestFeedbackToken = feedbackToken
            }
            val durableReceiptVisible = content.feedbackPresentationToken != null
            _state.value =
                content.copy(
                    acquiringItemId = itemId,
                    feedback = content.feedback.takeIf { durableReceiptVisible },
                    feedbackCondition = content.feedbackCondition.takeIf { durableReceiptVisible },
                )
            PreparedAcquisition(
                InventoryAcquireRequest(content.owner, item.id, item.revision, operationId),
                feedbackToken,
            )
        }

    private fun PreparedAcquisition.terminalFeedback(
        receipt: InventoryOwnershipReceipt,
        kind: InventoryOwnershipReceiptKind,
    ): FeedbackToken {
        require(
            receipt.accountId == request.accountId &&
                receipt.itemId == request.itemId &&
                receipt.catalogRevision == request.expectedCatalogRevision
        )
        val terminalReceipt =
            InventoryAcquisitionTerminalReceipt(
                request.accountId,
                request.itemId,
                request.operationId,
                kind,
                receipt,
            )
        return feedbackToken.copy(receiptKind = kind, terminalReceipt = terminalReceipt)
    }

    private fun finishAcquisitionRequest(token: ActionToken, feedback: FeedbackToken) {
        mutateContent(token) { content ->
            clearPending(feedback)
            content.copy(acquiringItemId = null)
        }
    }

    private suspend fun enqueueTerminalFeedback(token: ActionToken, feedback: FeedbackToken) {
        val receipt = requireNotNull(feedback.terminalReceipt)
        receiptDelivery.withLock {
            enqueueReceiptCandidates(
                token,
                listOf(
                    ReceiptCandidate(
                        receipt.receiptId,
                        InventoryReceiptPresentationExpectation(receipt),
                        feedback,
                    )
                ),
            )
        }
    }

    private fun publishNonReceiptFeedback(
        token: ActionToken,
        correlation: FeedbackToken,
        feedback: InventoryFeedback,
        condition: AcquisitionCondition? = null,
        clearPending: Boolean,
    ) {
        mutateContent(token) { content ->
            if (clearPending) clearPending(correlation)
            if (content.feedbackPresentationToken != null) {
                return@mutateContent content.copy(acquiringItemId = null)
            }
            if (!canPublishLocked(correlation)) return@mutateContent content
            latestFeedbackToken = correlation
            content.copy(
                acquiringItemId = null,
                feedback = feedback,
                feedbackCondition = condition,
                feedbackPresentationToken = null,
            )
        }
    }

    private suspend fun reloadAfterAcquisition(token: ActionToken) {
        if (!isCurrent(token) || token.owner == null) return
        val loaded = loadRepository(forceRefresh = true)
        if (!isCurrent(token)) return
        when (loaded) {
            is InventoryLoadResult.Ready ->
                publishLoaded(
                    token,
                    loaded.snapshot,
                    loaded.stale,
                    loaded.receiptCandidates,
                    loaded.receiptCandidatesAuthoritative,
                )
            is InventoryLoadResult.Partial ->
                publishLoaded(
                    token,
                    loaded.snapshot,
                    loaded.stale,
                    loaded.receiptCandidates,
                    loaded.receiptCandidatesAuthoritative,
                )
            InventoryLoadResult.Forbidden,
            InventoryLoadResult.Failed -> Unit
        }
    }

    private suspend fun load(token: ActionToken, forceRefresh: Boolean = false) {
        if (!isCurrent(token)) return
        mutate(token) {
            if (_state.value !is InventoryUiState.Content) {
                _state.value = InventoryUiState.Loading(token.owner)
            }
        }
        val loaded = loadRepository(forceRefresh)
        if (!isCurrent(token)) return
        when (loaded) {
            is InventoryLoadResult.Ready ->
                publishLoaded(
                    token,
                    loaded.snapshot,
                    loaded.stale,
                    loaded.receiptCandidates,
                    loaded.receiptCandidatesAuthoritative,
                )
            is InventoryLoadResult.Partial ->
                publishLoaded(
                    token,
                    loaded.snapshot,
                    loaded.stale,
                    loaded.receiptCandidates,
                    loaded.receiptCandidatesAuthoritative,
                )
            InventoryLoadResult.Forbidden,
            InventoryLoadResult.Failed ->
                mutate(token) {
                    if (_state.value !is InventoryUiState.Content) {
                        _state.value = InventoryUiState.Error(token.owner)
                    }
                }
        }
        val refreshRequired =
            when (loaded) {
                is InventoryLoadResult.Ready -> loaded.refreshRequired
                is InventoryLoadResult.Partial -> loaded.refreshRequired
                InventoryLoadResult.Forbidden,
                InventoryLoadResult.Failed -> false
            }
        if (refreshRequired && isCurrent(token)) {
            val refresh = loadRepository(forceRefresh = true)
            if (isCurrent(token)) {
                when (refresh) {
                    is InventoryLoadResult.Ready ->
                        publishLoaded(
                            token,
                            refresh.snapshot,
                            refresh.stale,
                            refresh.receiptCandidates,
                            refresh.receiptCandidatesAuthoritative,
                        )
                    is InventoryLoadResult.Partial ->
                        publishLoaded(
                            token,
                            refresh.snapshot,
                            refresh.stale,
                            refresh.receiptCandidates,
                            refresh.receiptCandidatesAuthoritative,
                        )
                    InventoryLoadResult.Forbidden,
                    InventoryLoadResult.Failed -> Unit
                }
            }
        }
    }

    private fun replaceLoadedCandidates(
        token: ActionToken,
        receiptIds: List<InventoryReceiptId>,
    ) {
        mutate(token) {
            val existing = receiptCandidates.associateBy { it.receiptId }
            val retainedLocal = receiptCandidates.filter {
                it.feedback?.terminalReceipt != null && it.receiptId !in receiptsById
            }
            receiptCandidates.clear()
            receiptIds.distinct().forEach { receiptId ->
                if (receiptId !in receiptsById) {
                    receiptCandidates +=
                        existing[receiptId]
                            ?: ReceiptCandidate(receiptId, expected = null, feedback = null)
                }
            }
            retainedLocal.forEach { candidate ->
                if (receiptCandidates.none { it.receiptId == candidate.receiptId }) {
                    val insertionIndex = receiptCandidates.indexOfFirst { candidate.precedes(it) }
                    if (insertionIndex < 0) receiptCandidates += candidate
                    else receiptCandidates.add(insertionIndex, candidate)
                }
            }
            val scheduled = scheduledReceiptRedelivery
            if (
                scheduled != null && receiptCandidates.none { it.receiptId == scheduled.receiptId }
            ) {
                cancelScheduledRedeliveryLocked()
            }
        }
    }

    private fun enqueueReceiptCandidates(
        token: ActionToken,
        candidates: List<ReceiptCandidate>,
    ) {
        mutate(token) {
            candidates.forEach { candidate ->
                if (
                    candidate.receiptId !in receiptsById &&
                        receiptCandidates.none { it.receiptId == candidate.receiptId }
                ) {
                    val insertionIndex = receiptCandidates.indexOfFirst { existing ->
                        candidate.precedes(existing)
                    }
                    if (insertionIndex < 0) receiptCandidates += candidate
                    else receiptCandidates.add(insertionIndex, candidate)
                }
            }
        }
    }

    private fun ReceiptCandidate.precedes(other: ReceiptCandidate): Boolean {
        val sequence = feedback?.sequence
        val otherSequence = other.feedback?.sequence
        if (sequence != null && otherSequence != null && sequence != otherSequence) {
            return sequence > otherSequence
        }
        val receipt = expected?.receipt ?: return false
        val otherReceipt = other.expected?.receipt ?: return false
        return receipt.createdAtEpochMillis < otherReceipt.createdAtEpochMillis ||
            (receipt.createdAtEpochMillis == otherReceipt.createdAtEpochMillis &&
                receipt.receiptId.value < otherReceipt.receiptId.value)
    }

    private suspend fun presentNextReceiptLocked(token: ActionToken) {
        while (isCurrent(token)) {
            val candidate = nextReceiptCandidate(token) ?: return
            val capability =
                repository.claimForPresentation(
                    candidate.receiptId,
                    candidate.expected,
                    token.claimant(),
                )
            if (!isCurrent(token)) return
            val claim = (capability as? InventoryReceiptClaimResult.Claimed)?.claim
            if (claim == null) {
                if (capability is InventoryReceiptClaimResult.Unavailable) {
                    scheduleRedelivery(token, candidate, capability.retryAtEpochMillis)
                    return
                }
                if (!discardUnavailableCandidate(token, candidate, capability)) return
                continue
            }
            if (!isCandidatePublishable(token, candidate)) return
            if (claim.deliveryPhase == InventoryReceiptDeliveryPhase.ACK_PENDING) {
                resumePendingAcknowledgementLocked(token, candidate, claim)
                return
            }
            scheduleRedelivery(token, candidate, claim.leaseExpiresAtEpochMillis)
            val publication = publishClaimedReceipt(token, candidate, claim) ?: return
            val presented =
                try {
                    repository.markReceiptPresented(claim)
                } catch (error: CancellationException) {
                    rollbackProvisionalReceipt(token, publication)
                    throw error
                }
            val presentedClaim = (presented as? InventoryReceiptPresentationResult.Presented)?.claim
            if (
                presentedClaim == null ||
                    presentedClaim.feedbackPresentationToken() != publication.presentationToken
            ) {
                rollbackProvisionalReceipt(token, publication)
                return
            }
            if (!finalizePresentedReceipt(token, candidate, publication, presentedClaim)) return
            return
        }
    }

    private fun nextReceiptCandidate(token: ActionToken): ReceiptCandidate? =
        synchronized(actionLock) {
            if (!isCurrentLocked(token)) return@synchronized null
            val content = _state.value as? InventoryUiState.Content ?: return@synchronized null
            if (content.feedbackReceiptId != null) return@synchronized null
            val next = receiptCandidates.firstOrNull() ?: return@synchronized null
            next.takeIf { it.isEligibleForPresentation(content, token) }
        }

    private fun ReceiptCandidate.isEligibleForPresentation(
        content: InventoryUiState.Content,
        token: ActionToken,
    ): Boolean {
        val localTerminal = feedback?.terminalReceipt ?: return true
        return content.owner == token.owner &&
            content.snapshot.accountId == token.owner &&
            localTerminal.owner == token.owner &&
            !content.stale &&
            content.snapshot.owned.any { it.itemId == localTerminal.itemId }
    }

    private fun publishClaimedReceipt(
        token: ActionToken,
        candidate: ReceiptCandidate,
        claim: InventoryReceiptClaim,
    ): ProvisionalReceiptPublication? =
        synchronized(actionLock) {
            if (!isCurrentLocked(token)) return@synchronized null
            val content = _state.value as? InventoryUiState.Content ?: return@synchronized null
            if (
                content.owner != token.owner ||
                    claim.receipt.owner != token.owner ||
                    content.feedbackReceiptId != null ||
                    receiptCandidates.firstOrNull() != candidate ||
                    !candidate.isEligibleForPresentation(content, token)
            ) {
                return@synchronized null
            }
            val presentationToken = claim.feedbackPresentationToken()
            val publication =
                ProvisionalReceiptPublication(
                    candidate.receiptId,
                    presentationToken,
                    VisibleFeedback(
                        content.feedback,
                        content.feedbackCondition,
                        content.feedbackPresentationToken,
                    ),
                )
            _state.value =
                content.copy(
                    acquiringItemId =
                        content.acquiringItemId?.takeUnless { it == claim.receipt.itemId },
                    feedback = claim.receipt.kind.feedback(),
                    feedbackCondition = null,
                    feedbackPresentationToken = presentationToken,
                )
            publication
        }

    private fun rollbackProvisionalReceipt(
        token: ActionToken,
        publication: ProvisionalReceiptPublication,
    ) {
        synchronized(actionLock) {
            if (!isCurrentLocked(token)) return
            val content = _state.value as? InventoryUiState.Content ?: return
            if (content.feedbackPresentationToken != publication.presentationToken) return
            if (publication.receiptId in receiptsById) return
            _state.value =
                content.copy(
                    feedback = publication.previous.feedback,
                    feedbackCondition = publication.previous.condition,
                    feedbackPresentationToken = publication.previous.presentationToken,
                )
        }
    }

    private fun finalizePresentedReceipt(
        token: ActionToken,
        candidate: ReceiptCandidate,
        publication: ProvisionalReceiptPublication,
        presentedClaim: InventoryReceiptClaim,
    ): Boolean =
        synchronized(actionLock) {
            if (!isCurrentLocked(token)) return@synchronized false
            val content = _state.value as? InventoryUiState.Content ?: return@synchronized false
            if (
                content.feedbackPresentationToken != publication.presentationToken ||
                    receiptCandidates.firstOrNull() != candidate
            ) {
                return@synchronized false
            }
            receiptCandidates.removeAt(0)
            receiptsById[candidate.receiptId] = presentedClaim
            clearPending(presentedClaim.receipt)
            if (scheduledReceiptRedelivery?.receiptId == candidate.receiptId) {
                cancelScheduledRedeliveryLocked()
            }
            true
        }

    private fun isCandidatePublishable(token: ActionToken, candidate: ReceiptCandidate): Boolean =
        synchronized(actionLock) {
            if (!isCurrentLocked(token)) return@synchronized false
            val content = _state.value as? InventoryUiState.Content ?: return@synchronized false
            content.feedbackReceiptId == null &&
                receiptCandidates.firstOrNull()?.receiptId == candidate.receiptId &&
                candidate.isEligibleForPresentation(content, token)
        }

    private fun discardUnavailableCandidate(
        token: ActionToken,
        candidate: ReceiptCandidate,
        result: InventoryReceiptClaimResult,
    ): Boolean {
        if (result !is InventoryReceiptClaimResult.Missing) return false
        cancelScheduledRedelivery(candidate.receiptId)
        var removed = false
        val current =
            mutate(token) {
                if (receiptCandidates.firstOrNull() == candidate) {
                    receiptCandidates.removeAt(0)
                    removed = true
                }
            }
        return current && removed
    }

    private suspend fun resumePendingAcknowledgementLocked(
        token: ActionToken,
        candidate: ReceiptCandidate,
        claim: InventoryReceiptClaim,
    ) {
        val resumed =
            synchronized(actionLock) {
                if (!isCurrentLocked(token) || token.owner == null) return@synchronized false
                if (receiptCandidates.firstOrNull() != candidate) return@synchronized false
                receiptCandidates.removeAt(0)
                receiptsById[candidate.receiptId] = claim
                pendingAcknowledgement =
                    PendingAcknowledgement(token.owner, token.generation, claim, attempt = 0)
                if (scheduledReceiptRedelivery?.receiptId == candidate.receiptId) {
                    cancelScheduledRedeliveryLocked()
                }
                true
            }
        if (resumed) attemptPendingAcknowledgementLocked(token)
    }

    /** Returns true when a terminal capability result requires an authoritative reload. */
    private suspend fun attemptPendingAcknowledgementLocked(token: ActionToken): Boolean {
        var pending = currentPendingAcknowledgement(token) ?: return false
        val consumption =
            if (pending.claim.deliveryPhase == InventoryReceiptDeliveryPhase.ACK_PENDING) {
                InventoryReceiptConsumptionResult.PendingAcknowledgement(pending.claim)
            } else {
                try {
                    repository.markReceiptConsumed(pending.claim)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    InventoryReceiptConsumptionResult.DatabaseFailure
                }
            }
        if (!isCurrent(token)) return false
        when (consumption) {
            InventoryReceiptConsumptionResult.DatabaseFailure -> {
                scheduleAcknowledgementRetry(token, pending)
                return false
            }
            is InventoryReceiptConsumptionResult.PendingAcknowledgement -> {
                pending = pending.copy(claim = consumption.claim)
                synchronized(actionLock) {
                    if (!isCurrentLocked(token)) return false
                    pendingAcknowledgement = pending
                    receiptsById[pending.claim.receipt.receiptId] = pending.claim
                }
            }
            InventoryReceiptConsumptionResult.Missing,
            InventoryReceiptConsumptionResult.Stale,
            InventoryReceiptConsumptionResult.Mismatch,
            InventoryReceiptConsumptionResult.Forbidden -> {
                discardPendingAcknowledgementForReload(token, pending)
                return true
            }
        }
        val acknowledged =
            try {
                repository.acknowledgeReceipt(pending.claim)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                InventoryReceiptAcknowledgement.DATABASE_FAILURE
            }
        if (!isCurrent(token)) return false
        return when (acknowledged) {
            InventoryReceiptAcknowledgement.ACKNOWLEDGED,
            InventoryReceiptAcknowledgement.ALREADY_ACKNOWLEDGED -> {
                completePendingAcknowledgement(token, pending)
                presentNextReceiptLocked(token)
                false
            }
            InventoryReceiptAcknowledgement.DATABASE_FAILURE -> {
                scheduleAcknowledgementRetry(token, pending)
                false
            }
            InventoryReceiptAcknowledgement.MISSING,
            InventoryReceiptAcknowledgement.MISMATCH,
            InventoryReceiptAcknowledgement.FORBIDDEN -> {
                discardPendingAcknowledgementForReload(token, pending)
                true
            }
        }
    }

    private fun currentPendingAcknowledgement(token: ActionToken): PendingAcknowledgement? =
        synchronized(actionLock) {
            if (!isCurrentLocked(token)) return@synchronized null
            pendingAcknowledgement?.takeIf {
                it.owner == token.owner && it.generation == token.generation
            }
        }

    private fun completePendingAcknowledgement(
        token: ActionToken,
        pending: PendingAcknowledgement,
    ) {
        synchronized(actionLock) {
            if (!isCurrentLocked(token)) return
            val presentationToken = pending.claim.feedbackPresentationToken()
            if (pendingAcknowledgement?.claim?.feedbackPresentationToken() != presentationToken) {
                return
            }
            pendingAcknowledgement = null
            receiptsById.remove(pending.claim.receipt.receiptId)
            cancelScheduledAcknowledgementRetryLocked()
            if (scheduledReceiptRedelivery?.receiptId == pending.claim.receipt.receiptId) {
                cancelScheduledRedeliveryLocked()
            }
            val content = _state.value as? InventoryUiState.Content
            if (content?.feedbackPresentationToken == presentationToken) {
                _state.value =
                    content.copy(
                        feedback = null,
                        feedbackCondition = null,
                        feedbackPresentationToken = null,
                    )
            }
        }
    }

    private fun discardPendingAcknowledgementForReload(
        token: ActionToken,
        pending: PendingAcknowledgement,
    ) {
        synchronized(actionLock) {
            if (!isCurrentLocked(token)) return
            val presentationToken = pending.claim.feedbackPresentationToken()
            if (pendingAcknowledgement?.claim?.feedbackPresentationToken() != presentationToken) {
                return
            }
            pendingAcknowledgement = null
            receiptsById.remove(pending.claim.receipt.receiptId)
            cancelScheduledAcknowledgementRetryLocked()
            val content = _state.value as? InventoryUiState.Content
            if (content?.feedbackPresentationToken == presentationToken) {
                _state.value =
                    content.copy(
                        feedback = null,
                        feedbackCondition = null,
                        feedbackPresentationToken = null,
                    )
            }
        }
    }

    private fun scheduleAcknowledgementRetry(
        token: ActionToken,
        pending: PendingAcknowledgement,
    ) {
        val scheduled =
            synchronized(actionLock) {
                if (!isCurrentLocked(token) || token.owner == null) return
                val current = pendingAcknowledgement ?: return
                if (
                    current.claim.feedbackPresentationToken() !=
                        pending.claim.feedbackPresentationToken()
                ) {
                    return
                }
                val attempt = current.attempt + 1
                val identity =
                    ScheduledAcknowledgementRetry(
                        token.owner,
                        token.generation,
                        current.claim.feedbackPresentationToken(),
                        attempt,
                    )
                val alreadyScheduled = scheduledAcknowledgementRetry
                if (alreadyScheduled?.sameIdentity(identity) == true) return
                cancelScheduledAcknowledgementRetryLocked()
                pendingAcknowledgement = current.copy(attempt = attempt)
                identity.also { scheduledAcknowledgementRetry = it }
            }
        val delayMillis = acknowledgementRetryBackoff.delayMillis(scheduled.attempt)
        require(delayMillis >= 0)
        val handle =
            acknowledgementRetryScheduler.schedule(delayMillis) {
                runScheduledAcknowledgementRetry(scheduled)
            }
        synchronized(actionLock) {
            val current = scheduledAcknowledgementRetry
            if (current?.sameIdentity(scheduled) == true) {
                scheduledAcknowledgementRetry = current.copy(handle = handle)
            } else {
                handle.cancel()
            }
        }
    }

    private suspend fun runScheduledAcknowledgementRetry(scheduled: ScheduledAcknowledgementRetry) {
        val currentJob = requireNotNull(currentCoroutineContext()[Job])
        val action =
            synchronized(actionLock) {
                val current = scheduledAcknowledgementRetry
                if (
                    cleared ||
                        current?.sameIdentity(scheduled) != true ||
                        owner != scheduled.owner ||
                        generation != scheduled.generation ||
                        pendingAcknowledgement?.claim?.feedbackPresentationToken() !=
                            scheduled.presentationToken
                ) {
                    return
                }
                scheduledAcknowledgementRetry = null
                val token = ActionToken(owner, generation, ++nextAction)
                RegisteredAction(token, currentJob).also { activeActions[token.action] = it }
            }
        try {
            var reload = false
            receiptDelivery.withLock {
                if (!isCurrent(action.token)) return@withLock
                reload = attemptPendingAcknowledgementLocked(action.token)
            }
            if (reload) load(action.token)
        } finally {
            unregister(action.token)
        }
    }

    private fun cancelScheduledAcknowledgementRetry(
        presentationToken: InventoryFeedbackPresentationToken
    ) {
        synchronized(actionLock) {
            if (scheduledAcknowledgementRetry?.presentationToken == presentationToken) {
                cancelScheduledAcknowledgementRetryLocked()
            }
        }
    }

    private fun cancelScheduledAcknowledgementRetryLocked() {
        scheduledAcknowledgementRetry?.handle?.cancel()
        scheduledAcknowledgementRetry = null
    }

    private fun ScheduledAcknowledgementRetry.sameIdentity(other: ScheduledAcknowledgementRetry) =
        owner == other.owner &&
            generation == other.generation &&
            presentationToken == other.presentationToken &&
            attempt == other.attempt

    private fun scheduleRedelivery(
        token: ActionToken,
        candidate: ReceiptCandidate,
        retryAtEpochMillis: Long,
    ) {
        val scheduled =
            synchronized(actionLock) {
                if (!isCurrentLocked(token) || token.owner == null) return
                if (receiptCandidates.firstOrNull()?.receiptId != candidate.receiptId) return
                val current = scheduledReceiptRedelivery
                if (
                    current != null &&
                        current.owner == token.owner &&
                        current.generation == token.generation &&
                        current.receiptId == candidate.receiptId &&
                        current.retryAtEpochMillis == retryAtEpochMillis
                ) {
                    return
                }
                cancelScheduledRedeliveryLocked()
                ScheduledReceiptRedelivery(
                        token.owner,
                        token.generation,
                        candidate.receiptId,
                        retryAtEpochMillis,
                    )
                    .also { scheduledReceiptRedelivery = it }
            }
        val nowEpochMillis = clock.millis()
        val delayMillis =
            if (retryAtEpochMillis <= nowEpochMillis) 0 else retryAtEpochMillis - nowEpochMillis
        val handle = redeliveryScheduler.schedule(delayMillis) { runScheduledRedelivery(scheduled) }
        synchronized(actionLock) {
            val current = scheduledReceiptRedelivery
            if (current?.sameIdentity(scheduled) == true) {
                scheduledReceiptRedelivery = current.copy(handle = handle)
            } else {
                handle.cancel()
            }
        }
    }

    private suspend fun runScheduledRedelivery(scheduled: ScheduledReceiptRedelivery) {
        val currentJob = requireNotNull(currentCoroutineContext()[Job])
        val action =
            synchronized(actionLock) {
                val current = scheduledReceiptRedelivery
                if (
                    cleared ||
                        current?.sameIdentity(scheduled) != true ||
                        owner != scheduled.owner ||
                        generation != scheduled.generation
                ) {
                    return
                }
                scheduledReceiptRedelivery = null
                val token = ActionToken(owner, generation, ++nextAction)
                RegisteredAction(token, currentJob).also { activeActions[token.action] = it }
            }
        try {
            receiptDelivery.withLock {
                if (!isCurrent(action.token)) return@withLock
                val head = synchronized(actionLock) { receiptCandidates.firstOrNull()?.receiptId }
                if (head != scheduled.receiptId) return@withLock
                presentNextReceiptLocked(action.token)
            }
        } finally {
            unregister(action.token)
        }
    }

    private fun cancelScheduledRedelivery(receiptId: InventoryReceiptId) {
        synchronized(actionLock) {
            if (scheduledReceiptRedelivery?.receiptId == receiptId) {
                cancelScheduledRedeliveryLocked()
            }
        }
    }

    private fun cancelScheduledRedeliveryLocked() {
        scheduledReceiptRedelivery?.handle?.cancel()
        scheduledReceiptRedelivery = null
    }

    private fun ScheduledReceiptRedelivery.sameIdentity(other: ScheduledReceiptRedelivery) =
        owner == other.owner &&
            generation == other.generation &&
            receiptId == other.receiptId &&
            retryAtEpochMillis == other.retryAtEpochMillis

    private suspend fun loadRepository(forceRefresh: Boolean = false): InventoryLoadResult =
        try {
            repository.load(forceRefresh)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            InventoryLoadResult.Failed
        }

    private suspend fun publishLoaded(
        token: ActionToken,
        snapshot: InventorySnapshot,
        stale: Boolean,
        receiptIds: List<InventoryReceiptId>,
        receiptCandidatesAuthoritative: Boolean,
    ) {
        if (token.owner == null || snapshot.accountId != token.owner) return
        receiptDelivery.withLock {
            if (receiptCandidatesAuthoritative) replaceLoadedCandidates(token, receiptIds)
            mutate(token) {
                val current = _state.value as? InventoryUiState.Content
                val currentSnapshot = current?.takeIf { it.owner == token.owner }?.snapshot
                val acceptsInventory =
                    currentSnapshot == null ||
                        acceptsInventoryEpoch(currentSnapshot, snapshot, token.action)
                val inventoryBase =
                    if (acceptsInventory) {
                        latestInventoryAction = maxOf(latestInventoryAction, token.action)
                        content(snapshot, stale)
                    } else {
                        requireNotNull(current)
                    }
                _state.value =
                    inventoryBase.copy(
                        acquiringItemId = current?.acquiringItemId,
                        feedback = current?.feedback,
                        feedbackCondition = current?.feedbackCondition,
                        feedbackPresentationToken = current?.feedbackPresentationToken,
                    )
            }
            presentNextReceiptLocked(token)
        }
    }

    private fun acceptsInventoryEpoch(
        current: InventorySnapshot,
        incoming: InventorySnapshot,
        action: Long,
    ): Boolean {
        if (incoming.verified != current.verified) return incoming.verified
        return incoming.generation > current.generation ||
            (incoming.generation == current.generation &&
                incoming.snapshotHash == current.snapshotHash &&
                action >= latestInventoryAction)
    }

    private fun canPublishLocked(incoming: FeedbackToken): Boolean {
        val current = latestFeedbackToken ?: return incoming.owner == owner
        if (incoming.owner != owner) return false
        if (incoming.sequence != current.sequence) return incoming.sequence > current.sequence
        if (
            incoming.owner != current.owner ||
                incoming.itemId != current.itemId ||
                incoming.operationId != current.operationId
        ) {
            return false
        }
        return current.receiptKind == null || incoming.receiptKind == current.receiptKind
    }

    private fun content(snapshot: InventorySnapshot, stale: Boolean) =
        InventoryUiState.Content(
            snapshot.accountId,
            snapshot,
            savedStateHandle.get<String>(SECTION_KEY)?.let {
                runCatching { InventorySection.valueOf(it) }.getOrNull()
            } ?: InventorySection.WAREHOUSE,
            savedStateHandle.get<String>(CATEGORY_KEY)?.let {
                runCatching { ItemCategory.valueOf(it) }.getOrNull()
            },
            searchQuery =
                savedStateHandle.get<String>(SEARCH_KEY)?.take(MAX_SEARCH_LENGTH).orEmpty(),
            stale = stale,
        )

    private fun registerAction(job: Job): RegisteredAction =
        synchronized(actionLock) {
            val token = ActionToken(owner, generation, ++nextAction)
            RegisteredAction(token, job).also { activeActions[token.action] = it }
        }

    private fun registerAuthAction(incoming: AccountId?, job: Job): RegisteredAction =
        synchronized(actionLock) {
            val changed = owner != incoming
            if (changed) {
                cancelScheduledRedeliveryLocked()
                cancelScheduledAcknowledgementRetryLocked()
                pendingAcknowledgement = null
                generation += 1
                owner = incoming
                pendingOperationId = null
                pendingOperationSequence = 0L
                latestFeedbackToken = null
                latestInventoryAction = Long.MIN_VALUE
                receiptCandidates.clear()
                receiptsById.clear()
            }
            val token = ActionToken(owner, generation, ++nextAction)
            val obsolete =
                if (changed) {
                    activeActions.values
                        .asSequence()
                        .filter { it.token.generation != generation && it.job !== job }
                        .map { it.job }
                        .distinct()
                        .toList()
                } else {
                    emptyList()
                }
            RegisteredAction(token, job, obsolete, changed).also {
                activeActions[token.action] = it
            }
        }

    private fun unregister(token: ActionToken) =
        synchronized(actionLock) {
            activeActions.remove(token.action)
        }

    private fun isCurrent(token: ActionToken): Boolean =
        synchronized(actionLock) {
            isCurrentLocked(token)
        }

    private fun ActionToken.claimant() =
        InventoryReceiptClaimant(
            presentationClaimToken,
            controllerEpoch,
            generation,
        )

    private fun InventoryReceiptId.operationId(): OperationId? {
        val currentOwner = owner ?: return null
        val prefix = "${currentOwner.value}/"
        if (!value.startsWith(prefix)) return null
        return value.removePrefix(prefix).takeIf(String::isNotEmpty)?.let(::OperationId)
    }

    private fun isCurrentLocked(token: ActionToken): Boolean =
        !cleared &&
            token.owner == owner &&
            token.generation == generation &&
            activeActions[token.action]?.token == token

    private inline fun mutate(token: ActionToken, mutation: () -> Unit): Boolean =
        synchronized(actionLock) {
            if (!isCurrentLocked(token)) return@synchronized false
            mutation()
            true
        }

    private inline fun mutateContent(
        token: ActionToken,
        transform: (InventoryUiState.Content) -> InventoryUiState.Content,
    ): Boolean =
        mutate(token) {
            val content = _state.value as? InventoryUiState.Content ?: return@mutate
            if (content.owner != token.owner) return@mutate
            _state.value = transform(content)
        }

    private fun updateContent(transform: (InventoryUiState.Content) -> InventoryUiState.Content) {
        synchronized(actionLock) {
            val content = _state.value as? InventoryUiState.Content ?: return
            if (content.owner != owner) return
            _state.value = transform(content)
        }
    }

    private fun clearPendingForOtherOwner(currentOwner: AccountId) {
        if (savedStateHandle.get<String>(PENDING_OWNER_KEY) != currentOwner.value) {
            clearPending()
        }
    }

    private fun clearPending(feedback: FeedbackToken) {
        if (
            savedStateHandle.get<String>(PENDING_OWNER_KEY) != feedback.owner.value ||
                savedStateHandle.get<String>(PENDING_OPERATION_KEY) != feedback.operationId.value ||
                savedStateHandle.get<String>(PENDING_ITEM_KEY) != feedback.itemId.value
        ) {
            return
        }
        clearPending()
    }

    private fun clearPending(receipt: InventoryAcquisitionTerminalReceipt) {
        if (
            savedStateHandle.get<String>(PENDING_OWNER_KEY) != receipt.owner.value ||
                savedStateHandle.get<String>(PENDING_OPERATION_KEY) != receipt.operationId.value ||
                savedStateHandle.get<String>(PENDING_ITEM_KEY) != receipt.itemId.value
        ) {
            return
        }
        clearPending()
    }

    private fun clearPending() {
        savedStateHandle.remove<String>(PENDING_OWNER_KEY)
        savedStateHandle.remove<String>(PENDING_OPERATION_KEY)
        savedStateHandle.remove<String>(PENDING_ITEM_KEY)
        savedStateHandle.remove<Long>(PENDING_REVISION_KEY)
        pendingOperationId = null
        pendingOperationSequence = 0L
    }

    private fun InventoryOwnershipReceiptKind?.feedback(): InventoryFeedback =
        when (requireNotNull(this)) {
            InventoryOwnershipReceiptKind.ACQUIRED -> InventoryFeedback.ACQUIRED
            InventoryOwnershipReceiptKind.ALREADY_OWNED -> InventoryFeedback.ALREADY_OWNED
        }

    private companion object {
        const val SECTION_KEY = "inventory.section"
        const val CATEGORY_KEY = "inventory.category"
        const val SEARCH_KEY = "inventory.search"
        const val MAX_SEARCH_LENGTH = 100
        const val PENDING_OWNER_KEY = "inventory.pending.owner"
        const val PENDING_OPERATION_KEY = "inventory.pending.operation"
        const val PENDING_ITEM_KEY = "inventory.pending.item"
        const val PENDING_REVISION_KEY = "inventory.pending.revision"
        const val PRESENTATION_CLAIM_TOKEN_KEY = "inventory.presentation.claim-token"
        const val PRESENTATION_CONTROLLER_EPOCH_KEY = "inventory.presentation.controller-epoch"
    }
}
