package com.planterior.helper.feature.settings

import com.planterior.helper.core.model.DeletionRequestId
import com.planterior.helper.core.model.DeletionStatus
import java.time.Instant

@JvmInline
value class AccountDeletionScopeHash(val value: String) {
    init {
        require(value.matches(Regex("^[a-f0-9]{64}$")))
    }
}

enum class AccountDeletionCategory(val serverId: String) {
    FIRESTORE_ACCOUNT_DATA("FIRESTORE_ACCOUNT_DATA"),
    NOTIFICATION_LINKS("NOTIFICATION_LINKS"),
    PUBLIC_SHARES("PUBLIC_SHARES"),
    IDENTIFICATION_MEDIA("IDENTIFICATION_MEDIA"),
    ACCOUNT_MEDIA("ACCOUNT_MEDIA"),
    PRIVATE_MEDIA_RESERVATIONS("PRIVATE_MEDIA_RESERVATIONS"),
    AUTH_ACCOUNT("AUTH_ACCOUNT"),
}

data class AccountDeletionScope(
    val hash: AccountDeletionScopeHash,
    val categories: List<AccountDeletionCategory>,
) {
    init {
        require(categories.isNotEmpty() && categories.distinct().size == categories.size)
    }
}

data class AccountDeletionWorkflow(
    val requestId: DeletionRequestId,
    val scope: AccountDeletionScope,
    val requestedAt: Instant,
    val scheduledAt: Instant,
    val status: DeletionStatus,
    val completedCategories: Set<AccountDeletionCategory> = emptySet(),
    val remainingCategories: Set<AccountDeletionCategory>,
) {
    init {
        val allCategories = scope.categories.toSet()
        require(!scheduledAt.isBefore(requestedAt))
        require(allCategories.containsAll(completedCategories + remainingCategories))
        require(completedCategories.intersect(remainingCategories).isEmpty())
        require(completedCategories + remainingCategories == allCategories)
        when (status) {
            DeletionStatus.RECEIVED,
            DeletionStatus.CANCELLED -> {
                require(completedCategories.isEmpty())
                require(remainingCategories == allCategories)
            }
            DeletionStatus.PROCESSING -> require(remainingCategories.isNotEmpty())
            DeletionStatus.COMPLETED -> {
                require(completedCategories == allCategories)
                require(remainingCategories.isEmpty())
            }
            DeletionStatus.FAILED -> {
                require(completedCategories.isEmpty())
                require(remainingCategories == allCategories)
            }
            DeletionStatus.PARTIALLY_FAILED -> {
                require(completedCategories.isNotEmpty())
                require(remainingCategories.isNotEmpty())
            }
        }
    }
}

class ConfirmedAccountDeletionRequest internal constructor(val scope: AccountDeletionScope)

enum class AccountDeletionRetryKind {
    RESTART_FAILED,
    RESUME_PARTIALLY_FAILED,
}

class ConfirmedAccountDeletionRetry
internal constructor(
    val requestId: DeletionRequestId,
    val scope: AccountDeletionScope,
    val kind: AccountDeletionRetryKind,
)

data class AccountDeletionCancellationResult(
    val expectedRequestId: DeletionRequestId,
    val workflow: AccountDeletionWorkflow,
) {
    init {
        require(workflow.status == DeletionStatus.CANCELLED)
    }
}

data class AccountDeletionRetryResult(
    val retriedRequestId: DeletionRequestId,
    val kind: AccountDeletionRetryKind,
    val workflow: AccountDeletionWorkflow,
) {
    init {
        when (kind) {
            AccountDeletionRetryKind.RESTART_FAILED -> {
                require(workflow.status == DeletionStatus.RECEIVED)
                require(workflow.scheduledAt == workflow.requestedAt.plusMillis(SEVEN_DAYS_MILLIS))
            }
            AccountDeletionRetryKind.RESUME_PARTIALLY_FAILED ->
                require(workflow.status == DeletionStatus.PARTIALLY_FAILED)
        }
    }
}

interface AccountDeletionRepository {
    suspend fun preview(): AccountDeletionScope

    suspend fun status(): AccountDeletionWorkflow?

    suspend fun request(request: ConfirmedAccountDeletionRequest): AccountDeletionWorkflow

    suspend fun cancel(requestId: DeletionRequestId): AccountDeletionCancellationResult

    suspend fun retry(request: ConfirmedAccountDeletionRetry): AccountDeletionRetryResult
}

private const val SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1_000

enum class AccountDeletionReauthenticationResult {
    SUCCEEDED,
    CANCELLED,
    FAILED,
}

fun interface AccountDeletionReauthenticator {
    suspend fun reauthenticate(): AccountDeletionReauthenticationResult
}

data class AccountDeletionCompletion(val requestId: DeletionRequestId)

fun interface AccountDeletionTerminalCallback {
    suspend fun onCompleted(completion: AccountDeletionCompletion)
}

data class AccountDeletionDependencies(
    val repository: AccountDeletionRepository,
    val reauthenticator: AccountDeletionReauthenticator,
    val terminalCallback: AccountDeletionTerminalCallback,
)

enum class AccountDeletionFailure {
    PREVIEW_UNAVAILABLE,
    REAUTHENTICATION_FAILED,
    REQUEST_FAILED,
    CANCEL_FAILED,
    STATUS_UNAVAILABLE,
    TERMINAL_CALLBACK_FAILED,
}

sealed interface AccountDeletionUiState {
    data object Loading : AccountDeletionUiState

    data class Ready(
        val scope: AccountDeletionScope,
        val workflow: AccountDeletionWorkflow? = null,
        val reauthenticated: Boolean = false,
        val finalConfirmed: Boolean = false,
        val reauthenticating: Boolean = false,
        val submitting: Boolean = false,
        val failure: AccountDeletionFailure? = null,
        val lifecycleAnnouncement: String? = null,
        val terminalCleanupStarted: Boolean = false,
    ) : AccountDeletionUiState
}
