package com.planterior.helper

import com.planterior.helper.core.model.DeletionRequestId
import com.planterior.helper.core.model.DeletionStatus
import com.planterior.helper.feature.settings.AccountDeletionCancellationResult
import com.planterior.helper.feature.settings.AccountDeletionCategory
import com.planterior.helper.feature.settings.AccountDeletionDependencies
import com.planterior.helper.feature.settings.AccountDeletionReauthenticationResult
import com.planterior.helper.feature.settings.AccountDeletionReauthenticator
import com.planterior.helper.feature.settings.AccountDeletionRepository
import com.planterior.helper.feature.settings.AccountDeletionRetryResult
import com.planterior.helper.feature.settings.AccountDeletionScope
import com.planterior.helper.feature.settings.AccountDeletionScopeHash
import com.planterior.helper.feature.settings.AccountDeletionTerminalCallback
import com.planterior.helper.feature.settings.AccountDeletionWorkflow
import com.planterior.helper.feature.settings.AnalyticsDeletionGuard
import com.planterior.helper.feature.settings.ConfirmedAccountDeletionRequest
import com.planterior.helper.feature.settings.ConfirmedAccountDeletionRetry

/** Partially failed account-deletion repository fixture. */
internal class Todo18AccountDeletionRepositoryFixture(private val scenario: Todo18Scenario) :
    AccountDeletionRepository {
    private val categories =
        listOf(
            AccountDeletionCategory.FIRESTORE_ACCOUNT_DATA,
            AccountDeletionCategory.PUBLIC_SHARES,
            AccountDeletionCategory.AUTH_ACCOUNT,
        )
    private val scope = AccountDeletionScope(AccountDeletionScopeHash("b".repeat(64)), categories)
    private var workflow =
        AccountDeletionWorkflow(
            requestId = DeletionRequestId("todo18-deletion"),
            scope = scope,
            requestedAt = scenario.now().minusSeconds(60),
            scheduledAt = scenario.now(),
            status = DeletionStatus.PARTIALLY_FAILED,
            completedCategories = setOf(AccountDeletionCategory.PUBLIC_SHARES),
            remainingCategories =
                setOf(
                    AccountDeletionCategory.FIRESTORE_ACCOUNT_DATA,
                    AccountDeletionCategory.AUTH_ACCOUNT,
                ),
        )

    val dependencies =
        AccountDeletionDependencies(
            repository = this,
            reauthenticator =
                AccountDeletionReauthenticator {
                    AccountDeletionReauthenticationResult.SUCCEEDED
                },
            terminalCallback = AccountDeletionTerminalCallback {},
            analyticsDeletionGuard = AnalyticsDeletionGuard.NO_OP,
        )

    override suspend fun preview(): AccountDeletionScope = scope

    override suspend fun status(): AccountDeletionWorkflow {
        scenario.emit("account-deletion-partial", workflow.requestId.value)
        return workflow
    }

    override suspend fun request(
        request: ConfirmedAccountDeletionRequest
    ): AccountDeletionWorkflow = workflow

    override suspend fun cancel(requestId: DeletionRequestId): AccountDeletionCancellationResult =
        error("A partially failed request cannot be cancelled")

    override suspend fun retry(request: ConfirmedAccountDeletionRetry): AccountDeletionRetryResult =
        AccountDeletionRetryResult(request.requestId, request.kind, workflow)
}
