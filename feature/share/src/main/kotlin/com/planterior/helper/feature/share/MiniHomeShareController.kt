package com.planterior.helper.feature.share

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.MiniHomeAuthOwnership
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MiniHomeShareDiagnosticStage {
    LOAD_ENTERED,
    LOAD_RETURNED,
    STATE_PUBLISHED,
    DISPLAYED_STATE_OBSERVED,
}

data class MiniHomeShareDiagnosticObservation(
    val stage: MiniHomeShareDiagnosticStage,
    val owner: AccountId?,
    val generation: Long?,
    val stateKind: String,
    val resultKind: String? = null,
)

/** 확정 구성 캡처 진행 상태이다. */
enum class MiniHomeShareRenderState {
    Rendering,
    Ready,
    Failed,
}

sealed interface MiniHomeShareLinkState {
    data object Idle : MiniHomeShareLinkState

    data object Generating : MiniHomeShareLinkState

    data class Active(
        val link: MiniHomeShareLink,
        val revokeFailure: MiniHomeShareFailure? = null,
        val revoking: Boolean = false,
    ) : MiniHomeShareLinkState

    data class Failed(val failure: MiniHomeShareFailure) : MiniHomeShareLinkState

    data object Revoked : MiniHomeShareLinkState
}

/** 화면에 인라인으로 보여주는 짧은 상태 피드백이다. 전달 성공을 주장하지 않는다. */
enum class MiniHomeShareFeedback(val error: Boolean) {
    LINK_COPIED(false),
    SHEET_OPENED(false),
    SHEET_CANCELLED(false),
    NO_TARGET(true),
    SHEET_FAILED(true),
}

/** 시스템 공유 시트의 결과이다. 어떤 값도 실제 전달을 의미하지 않는다. */
sealed interface MiniHomeShareSheetOutcome {
    data object Opened : MiniHomeShareSheetOutcome

    data object Cancelled : MiniHomeShareSheetOutcome

    data object NoTarget : MiniHomeShareSheetOutcome

    data object Failed : MiniHomeShareSheetOutcome

    /** 캡처가 낡아 아무것도 열지 않았다. 사용자에게는 조용히 넘어간다. */
    data object Stale : MiniHomeShareSheetOutcome
}

/**
 * 재시도 가능한 실패 뒤에도 그대로 다시 보내야 하는 얼어붙은 요청이다.
 *
 * 서버는 같은 operation ID를 재생 처리하므로, 모호한 실패에서 새 ID를 만들면 링크가 두 번 생길 수 있다.
 */
data class MiniHomeSharePendingOperation(
    val operationId: OperationId,
    val expectedRevision: Revision,
)

sealed interface MiniHomeShareUiState {
    val owner: AccountId?

    data class Loading(val accountId: AccountId?) : MiniHomeShareUiState {
        override val owner: AccountId? = accountId
    }

    data class Ready(
        val target: MiniHomeShareTarget,
        val render: MiniHomeShareRenderState = MiniHomeShareRenderState.Rendering,
        val link: MiniHomeShareLinkState = MiniHomeShareLinkState.Idle,
        val feedback: MiniHomeShareFeedback? = null,
    ) : MiniHomeShareUiState {
        override val owner: AccountId = target.owner
    }

    data class NoTarget(val accountId: AccountId) : MiniHomeShareUiState {
        override val owner: AccountId = accountId
    }

    data object Forbidden : MiniHomeShareUiState {
        override val owner: AccountId? = null
    }

    data object Error : MiniHomeShareUiState {
        override val owner: AccountId? = null
    }
}

/**
 * 미니홈 공유 화면의 상태 기계이다.
 *
 * 모든 소유자 종속 상태는 세대(generation)로 격리한다. 이전 소유자의 작업이 늦게 완료되어도 새 소유자 상태에는 절대 게시되지 않는다.
 */
class MiniHomeShareController(
    private val repository: MiniHomeShareRepository,
    private val operationIdFactory: () -> OperationId = OperationId::random,
    private val onDiagnostic: (MiniHomeShareDiagnosticObservation) -> Unit = {},
) {
    private val _state = MutableStateFlow<MiniHomeShareUiState>(MiniHomeShareUiState.Loading(null))
    val state: StateFlow<MiniHomeShareUiState> = _state.asStateFlow()

    private var generation = 0L
    private var owner: AccountId? = null
    private var creating = false
    private var pendingOperation: MiniHomeSharePendingOperation? = null

    /**
     * 프로세스 재생성으로 잃어버린 얼어붙은 요청을 되살린다.
     *
     * bearer URL이나 토큰은 절대 복원 대상이 아니다. 재생에 필요한 operation ID와 revision만 되살린다.
     */
    fun restore(saved: Map<String, String>) {
        val operationId =
            saved[OPERATION_KEY]?.let { runCatching { OperationId(it) }.getOrNull() } ?: return
        val revision =
            saved[REVISION_KEY]?.toLongOrNull()?.let { runCatching { Revision(it) }.getOrNull() }
                ?: return
        pendingOperation = MiniHomeSharePendingOperation(operationId, revision)
    }

    suspend fun start(authOwnership: MiniHomeAuthOwnership) {
        when (authOwnership) {
            MiniHomeAuthOwnership.Restoring,
            MiniHomeAuthOwnership.Unknown -> {
                generation += 1
                publish(MiniHomeShareUiState.Loading(null))
            }
            MiniHomeAuthOwnership.SignedOut -> {
                val token = beginGeneration(null)
                pendingOperation = null
                repository.clearOwnerArtifacts()
                if (!isCurrent(token)) return
                publish(MiniHomeShareUiState.Forbidden)
            }
            MiniHomeAuthOwnership.Unmanaged -> load(null)
            is MiniHomeAuthOwnership.Authenticated -> load(authOwnership.accountId)
        }
    }

    private suspend fun load(expectedOwner: AccountId?) {
        val previousOwner = owner
        val ownerChanged = previousOwner != null && previousOwner != expectedOwner
        val token = beginGeneration(expectedOwner)
        observeDiagnostic(
            MiniHomeShareDiagnosticObservation(
                MiniHomeShareDiagnosticStage.LOAD_ENTERED,
                expectedOwner,
                token,
                "loading",
            )
        )
        if (ownerChanged) {
            // 계정이 바뀌면 이전 소유자의 얼어붙은 요청과 로컬 산출물을 모두 버린다.
            pendingOperation = null
            repository.clearOwnerArtifacts()
            if (!isCurrent(token)) return
        }
        publish(MiniHomeShareUiState.Loading(expectedOwner))
        val loaded =
            try {
                repository.loadCommitted()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                MiniHomeShareLoadResult.Failed
            }
        observeDiagnostic(
            MiniHomeShareDiagnosticObservation(
                MiniHomeShareDiagnosticStage.LOAD_RETURNED,
                expectedOwner,
                token,
                _state.value::class.simpleName ?: "unknown",
                loaded::class.simpleName,
            )
        )
        if (!isCurrent(token)) return
        publish(
            when (loaded) {
                is MiniHomeShareLoadResult.Ready ->
                    when {
                        expectedOwner != null && loaded.target.owner != expectedOwner ->
                            MiniHomeShareUiState.Error
                        // 저장된 적이 없는 미니홈만 공유 대상이 아니다. 배치가 비어 있어도 저장본은 공유할 수 있다.
                        loaded.target.committed.revision.value < 1 ->
                            MiniHomeShareUiState.NoTarget(expectedOwner ?: loaded.target.owner)
                        else -> MiniHomeShareUiState.Ready(loaded.target)
                    }
                MiniHomeShareLoadResult.NoTarget ->
                    MiniHomeShareUiState.NoTarget(expectedOwner ?: AccountId.LEGACY)
                MiniHomeShareLoadResult.Forbidden -> MiniHomeShareUiState.Forbidden
                MiniHomeShareLoadResult.Failed -> MiniHomeShareUiState.Error
            }
        )
        // 확정 구성이 달라졌으면 이전 revision으로 얼린 요청은 더 이상 유효하지 않다.
        val ready = _state.value as? MiniHomeShareUiState.Ready
        if (
            ready == null || pendingOperation?.expectedRevision != ready.target.committed.revision
        ) {
            pendingOperation = null
        }
    }

    suspend fun retryLoad(authOwnership: MiniHomeAuthOwnership) = start(authOwnership)

    /** 현재 확정 구성을 가리키는 캡처 토큰이다. 준비된 상태가 아니면 `null`이다. */
    fun captureToken(): MiniHomeShareCaptureToken? {
        val ready = _state.value as? MiniHomeShareUiState.Ready ?: return null
        return MiniHomeShareCaptureToken(
            ready.target.owner,
            ready.target.committed.revision,
            generation,
        )
    }

    fun isCurrent(token: MiniHomeShareCaptureToken): Boolean = captureToken() == token

    /** 레이어가 실제로 기록됐다는 신호를 받은 뒤에만 준비 완료로 넘어간다. */
    fun onRecorded(token: MiniHomeShareCaptureToken) {
        if (!isCurrent(token)) return
        update(generation) { it.copy(render = MiniHomeShareRenderState.Ready) }
    }

    /** 사용자가 명시적으로 요청할 때만 링크를 만든다. 진행 중 요청은 같은 operation ID로 얼어붙는다. */
    suspend fun createLink() {
        val ready = _state.value as? MiniHomeShareUiState.Ready ?: return
        if (creating) return
        val revision = ready.target.committed.revision
        // 재시도 가능한 실패로 얼어붙은 요청이 있으면 새 ID를 만들지 않고 그대로 재생한다.
        val frozen =
            pendingOperation?.takeIf { it.expectedRevision == revision }
                ?: MiniHomeSharePendingOperation(operationIdFactory(), revision)
        pendingOperation = frozen
        creating = true
        val token = generation
        update(token) { it.copy(link = MiniHomeShareLinkState.Generating, feedback = null) }
        val result =
            try {
                repository.createLink(
                    MiniHomeShareLinkRequest(frozen.operationId, frozen.expectedRevision)
                )
            } catch (error: CancellationException) {
                creating = false
                throw error
            } catch (_: Exception) {
                MiniHomeShareCreateResult.Failed(MiniHomeShareFailure.MALFORMED_RESPONSE)
            }
        creating = false
        if (!isCurrentGeneration(token)) return
        when (result) {
            is MiniHomeShareCreateResult.Created -> pendingOperation = null
            is MiniHomeShareCreateResult.Failed ->
                // 영구 실패는 같은 요청을 반복해도 결과가 같으므로 얼린 요청을 버린다.
                if (!result.failure.retryable) pendingOperation = null
        }
        update(token) {
            when (result) {
                is MiniHomeShareCreateResult.Created ->
                    it.copy(link = MiniHomeShareLinkState.Active(result.link))
                is MiniHomeShareCreateResult.Failed ->
                    it.copy(link = MiniHomeShareLinkState.Failed(result.failure))
            }
        }
    }

    suspend fun revokeLink() {
        val ready = _state.value as? MiniHomeShareUiState.Ready ?: return
        val active = ready.link as? MiniHomeShareLinkState.Active ?: return
        if (active.revoking) return
        val token = generation
        update(token) { it.copy(link = active.copy(revoking = true, revokeFailure = null)) }
        val result =
            try {
                repository.revokeLink(active.link.shareId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                MiniHomeShareRevokeResult.Failed(MiniHomeShareFailure.MALFORMED_RESPONSE)
            }
        update(token) {
            when (result) {
                is MiniHomeShareRevokeResult.Revoked ->
                    it.copy(link = MiniHomeShareLinkState.Revoked, feedback = null)
                is MiniHomeShareRevokeResult.Failed ->
                    it.copy(link = active.copy(revoking = false, revokeFailure = result.failure))
            }
        }
    }

    fun onLinkCopied() {
        update(generation) { it.copy(feedback = MiniHomeShareFeedback.LINK_COPIED) }
    }

    fun onSheetOutcome(outcome: MiniHomeShareSheetOutcome) {
        val feedback =
            when (outcome) {
                MiniHomeShareSheetOutcome.Opened -> MiniHomeShareFeedback.SHEET_OPENED
                MiniHomeShareSheetOutcome.Cancelled -> MiniHomeShareFeedback.SHEET_CANCELLED
                MiniHomeShareSheetOutcome.NoTarget -> MiniHomeShareFeedback.NO_TARGET
                MiniHomeShareSheetOutcome.Failed -> MiniHomeShareFeedback.SHEET_FAILED
                // 낡은 캡처는 사용자가 요청한 적 없는 상태 변화라 아무것도 알리지 않는다.
                MiniHomeShareSheetOutcome.Stale -> null
            }
        update(generation) { it.copy(feedback = feedback ?: it.feedback) }
    }

    fun onRenderFailed() {
        update(generation) { it.copy(render = MiniHomeShareRenderState.Failed) }
    }

    fun retryRender() {
        update(generation) { it.copy(render = MiniHomeShareRenderState.Rendering) }
    }

    /**
     * 프로세스 재생성에 넘길 수 있는 비민감 상태이다.
     *
     * bearer URL과 토큰, share ID는 절대 포함하지 않고 재생에 필요한 요청 좌표만 남긴다.
     */
    fun persistableState(): Map<String, String> {
        val state = _state.value
        val status =
            when {
                state !is MiniHomeShareUiState.Ready -> "SHARE_IDLE"
                state.link is MiniHomeShareLinkState.Active -> "SHARE_LINK_ACTIVE"
                state.link is MiniHomeShareLinkState.Revoked -> "SHARE_LINK_REVOKED"
                state.link is MiniHomeShareLinkState.Generating -> "SHARE_LINK_GENERATING"
                state.link is MiniHomeShareLinkState.Failed -> "SHARE_LINK_FAILED"
                else -> "SHARE_IDLE"
            }
        return buildMap {
            put(STATUS_KEY, status)
            pendingOperation?.let {
                put(OPERATION_KEY, it.operationId.value)
                put(REVISION_KEY, it.expectedRevision.value.toString())
            }
        }
    }

    private fun beginGeneration(nextOwner: AccountId?): Long {
        generation += 1
        owner = nextOwner
        creating = false
        return generation
    }

    private fun publish(next: MiniHomeShareUiState) {
        val publishedGeneration = generation
        _state.value = next
        observeDiagnostic(
            MiniHomeShareDiagnosticObservation(
                MiniHomeShareDiagnosticStage.STATE_PUBLISHED,
                next.owner,
                publishedGeneration,
                next::class.simpleName ?: "unknown",
            )
        )
    }

    private fun observeDiagnostic(observation: MiniHomeShareDiagnosticObservation) {
        try {
            onDiagnostic(observation)
        } catch (_: AssertionError) {} catch (_: Exception) {}
    }

    private fun isCurrent(token: Long): Boolean = token == generation

    private fun isCurrentGeneration(token: Long): Boolean = token == generation

    private fun update(
        token: Long,
        transform: (MiniHomeShareUiState.Ready) -> MiniHomeShareUiState.Ready,
    ) {
        if (!isCurrent(token)) return
        val ready = _state.value as? MiniHomeShareUiState.Ready ?: return
        _state.value = transform(ready)
    }

    private companion object {
        const val STATUS_KEY = "mini-home-share.status"
        const val OPERATION_KEY = "mini-home-share.operation"
        const val REVISION_KEY = "mini-home-share.revision"
    }
}
