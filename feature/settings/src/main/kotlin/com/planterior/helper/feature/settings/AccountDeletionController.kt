package com.planterior.helper.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.planterior.helper.core.model.DeletionStatus
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AccountDeletionController(
    private val dependencies: AccountDeletionDependencies,
    private val analyticsDeletionGuard: AnalyticsDeletionGuard =
        dependencies.analyticsDeletionGuard,
    private val dispatcher: CoroutineContext = Dispatchers.Main.immediate,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow<AccountDeletionUiState>(AccountDeletionUiState.Loading)
    val state: StateFlow<AccountDeletionUiState> = mutableState.asStateFlow()
    private val completedCallbacks = mutableSetOf<String>()
    private val guardedReceivedRequests = mutableSetOf<String>()
    private var refreshing = false

    init {
        refresh()
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch(dispatcher) {
            try {
                val scope = dependencies.repository.preview()
                val workflow = dependencies.repository.status()
                invokeAnalyticsGuard(workflow)
                val current = mutableState.value as? AccountDeletionUiState.Ready
                mutableState.value =
                    AccountDeletionUiState.Ready(
                        scope = scope,
                        workflow = workflow,
                        terminalCleanupStarted = current?.terminalCleanupStarted == true,
                    )
                invokeTerminalCallback(workflow)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                val current = mutableState.value as? AccountDeletionUiState.Ready
                mutableState.value =
                    current?.copy(failure = AccountDeletionFailure.STATUS_UNAVAILABLE)
                        ?: AccountDeletionUiState.Loading
            } finally {
                refreshing = false
            }
        }
    }

    fun reauthenticate() {
        val current = mutableState.value as? AccountDeletionUiState.Ready ?: return
        if (current.reauthenticating || current.submitting) return
        mutableState.value = current.copy(reauthenticating = true, failure = null)
        viewModelScope.launch(dispatcher) {
            val result =
                try {
                    dependencies.reauthenticator.reauthenticate()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    AccountDeletionReauthenticationResult.FAILED
                }
            updateReady { ready ->
                when (result) {
                    AccountDeletionReauthenticationResult.SUCCEEDED ->
                        ready.copy(
                            reauthenticated = true,
                            reauthenticating = false,
                            lifecycleAnnouncement = "최근 인증이 완료됐어요.",
                        )
                    AccountDeletionReauthenticationResult.CANCELLED ->
                        ready.copy(reauthenticating = false)
                    AccountDeletionReauthenticationResult.FAILED ->
                        ready.copy(
                            reauthenticated = false,
                            reauthenticating = false,
                            failure = AccountDeletionFailure.REAUTHENTICATION_FAILED,
                        )
                }
            }
        }
    }

    fun setFinalConfirmation(confirmed: Boolean) {
        updateReady { current ->
            if (!current.reauthenticated) current else current.copy(finalConfirmed = confirmed)
        }
    }

    fun submit() {
        val current = mutableState.value as? AccountDeletionUiState.Ready ?: return
        if (!current.reauthenticated || !current.finalConfirmed || current.submitting) return
        val retry =
            when (current.workflow?.status) {
                DeletionStatus.FAILED,
                DeletionStatus.PARTIALLY_FAILED -> current.workflow
                null,
                DeletionStatus.CANCELLED -> null
                DeletionStatus.RECEIVED,
                DeletionStatus.PROCESSING,
                DeletionStatus.COMPLETED -> return
            }
        mutableState.value = current.copy(submitting = true, failure = null)
        viewModelScope.launch(dispatcher) {
            try {
                val retryResult = retry?.let {
                    dependencies.repository.retry(
                        ConfirmedAccountDeletionRetry(
                            requestId = it.requestId,
                            scope = it.scope,
                            kind =
                                when (it.status) {
                                    DeletionStatus.FAILED -> AccountDeletionRetryKind.RESTART_FAILED
                                    DeletionStatus.PARTIALLY_FAILED ->
                                        AccountDeletionRetryKind.RESUME_PARTIALLY_FAILED
                                    else -> error("Unsupported account deletion retry")
                                },
                        )
                    )
                }
                val workflow =
                    retryResult?.workflow
                        ?: dependencies.repository.request(
                            ConfirmedAccountDeletionRequest(current.scope)
                        )
                val active = mutableState.value as? AccountDeletionUiState.Ready ?: return@launch
                val operationStillCurrent =
                    if (retryResult == null) {
                        active.workflow?.requestId == current.workflow?.requestId
                    } else {
                        retryResult.retriedRequestId == current.workflow?.requestId &&
                            active.workflow?.requestId == retryResult.retriedRequestId
                    }
                if (!operationStillCurrent) {
                    mutableState.value =
                        active.copy(
                            submitting = false,
                            failure = AccountDeletionFailure.REQUEST_FAILED,
                        )
                    return@launch
                }
                invokeAnalyticsGuard(workflow)
                mutableState.value =
                    active.copy(
                        workflow = workflow,
                        reauthenticated = false,
                        finalConfirmed = false,
                        submitting = false,
                        lifecycleAnnouncement = workflow.announcement(),
                    )
                invokeTerminalCallback(workflow)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                updateReady { ready ->
                    ready.copy(submitting = false, failure = AccountDeletionFailure.REQUEST_FAILED)
                }
            }
        }
    }

    fun cancel() {
        val current = mutableState.value as? AccountDeletionUiState.Ready ?: return
        val workflow = current.workflow ?: return
        if (workflow.status != DeletionStatus.RECEIVED || current.submitting) return
        mutableState.value = current.copy(submitting = true, failure = null)
        viewModelScope.launch(dispatcher) {
            try {
                val result = dependencies.repository.cancel(workflow.requestId)
                val active = mutableState.value as? AccountDeletionUiState.Ready ?: return@launch
                if (
                    result.expectedRequestId != workflow.requestId ||
                        result.workflow.requestId != workflow.requestId ||
                        active.workflow?.requestId != workflow.requestId
                ) {
                    mutableState.value =
                        active.copy(
                            submitting = false,
                            failure = AccountDeletionFailure.CANCEL_FAILED,
                        )
                    return@launch
                }
                mutableState.value =
                    active.copy(
                        workflow = result.workflow,
                        submitting = false,
                        lifecycleAnnouncement = result.workflow.announcement(),
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                updateReady { ready ->
                    ready.copy(submitting = false, failure = AccountDeletionFailure.CANCEL_FAILED)
                }
            }
        }
    }

    private suspend fun invokeAnalyticsGuard(workflow: AccountDeletionWorkflow?) {
        if (
            workflow?.status != DeletionStatus.RECEIVED ||
                workflow.requestId.value in guardedReceivedRequests
        ) {
            return
        }
        try {
            analyticsDeletionGuard.onReceived(AccountDeletionReceived(workflow.requestId))
            guardedReceivedRequests += workflow.requestId.value
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The authoritative deletion remains accepted. A later status refresh retries only the
            // local fail-safe cleanup while analytics stays off.
        }
    }

    private suspend fun invokeTerminalCallback(workflow: AccountDeletionWorkflow?) {
        if (
            workflow?.status != DeletionStatus.COMPLETED ||
                workflow.remainingCategories.isNotEmpty() ||
                !completedCallbacks.add(workflow.requestId.value)
        ) {
            return
        }
        updateReady { it.copy(terminalCleanupStarted = true) }
        try {
            dependencies.terminalCallback.onCompleted(AccountDeletionCompletion(workflow.requestId))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            updateReady { it.copy(failure = AccountDeletionFailure.TERMINAL_CALLBACK_FAILED) }
        }
    }

    private fun updateReady(
        transform: (AccountDeletionUiState.Ready) -> AccountDeletionUiState.Ready
    ) {
        val current = mutableState.value as? AccountDeletionUiState.Ready ?: return
        mutableState.value = transform(current)
    }
}

private fun AccountDeletionWorkflow.announcement(): String =
    when (status) {
        DeletionStatus.RECEIVED -> "삭제 요청이 접수됐어요."
        DeletionStatus.PROCESSING -> "계정 삭제를 처리하고 있어요."
        DeletionStatus.COMPLETED -> "계정 삭제가 완료됐어요."
        DeletionStatus.FAILED -> "계정 삭제에 실패했어요. 계정은 유지돼요."
        DeletionStatus.PARTIALLY_FAILED -> "일부 삭제에 실패했어요. 계정은 유지돼요."
        DeletionStatus.CANCELLED -> "삭제 요청이 취소됐어요."
    }
