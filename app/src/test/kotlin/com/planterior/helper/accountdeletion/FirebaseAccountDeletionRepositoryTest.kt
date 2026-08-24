package com.planterior.helper.accountdeletion

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.DeletionRequestId
import com.planterior.helper.core.model.DeletionStatus
import com.planterior.helper.feature.settings.AccountDeletionCategory
import com.planterior.helper.feature.settings.AccountDeletionController
import com.planterior.helper.feature.settings.AccountDeletionDependencies
import com.planterior.helper.feature.settings.AccountDeletionReauthenticationResult
import com.planterior.helper.feature.settings.AccountDeletionReauthenticator
import com.planterior.helper.feature.settings.AccountDeletionRetryKind
import com.planterior.helper.feature.settings.AccountDeletionScope
import com.planterior.helper.feature.settings.AccountDeletionTerminalCallback
import com.planterior.helper.feature.settings.AccountDeletionUiState
import com.planterior.helper.feature.settings.ConfirmedAccountDeletionRetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FirebaseAccountDeletionRepositoryTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `real eight scope preview reaches ready`() = runTest {
        val callable =
            RecordingAccountDeletionCallable().apply {
                results += previewResponse()
                results += null
            }
        val repository = FirebaseAccountDeletionRepository(AccountId("owner-one"), callable)
        val controller =
            AccountDeletionController(
                AccountDeletionDependencies(
                    repository = repository,
                    reauthenticator =
                        AccountDeletionReauthenticator {
                            AccountDeletionReauthenticationResult.SUCCEEDED
                        },
                    terminalCallback = AccountDeletionTerminalCallback {},
                ),
                dispatcher = StandardTestDispatcher(testScheduler),
            )

        advanceUntilIdle()

        assertTrue(controller.state.value is AccountDeletionUiState.Ready)
        assertEquals(
            AccountDeletionCategory.entries,
            (controller.state.value as AccountDeletionUiState.Ready).scope.categories,
        )
    }

    @Test
    fun `typed repository binds every callable to expected owner and maps split media scopes`() =
        runTest {
            val callable = RecordingAccountDeletionCallable()
            val repository =
                FirebaseAccountDeletionRepository(
                    owner = AccountId("owner-one"),
                    callable = callable,
                    idempotencyKey = { "operation-key" },
                )
            callable.results += previewResponse()
            callable.results += workflowResponse()
            callable.results += workflowResponse(status = "CANCELLED")

            val scope = repository.preview()
            val status = repository.status()
            val cancelled = repository.cancel(DeletionRequestId("request-one"))

            assertEquals(AccountDeletionCategory.entries, scope.categories)
            assertEquals(
                setOf(AccountDeletionCategory.PUBLIC_SHARES),
                status?.completedCategories,
            )
            assertEquals(
                AccountDeletionCategory.entries.toSet() - AccountDeletionCategory.PUBLIC_SHARES,
                status?.remainingCategories,
            )
            assertEquals(DeletionStatus.CANCELLED, cancelled.workflow.status)
            assertEquals(
                listOf(
                    "previewAccountDeletion",
                    "getAccountDeletionStatus",
                    "cancelAccountDeletion",
                ),
                callable.calls.map { it.first },
            )
            assertEquals(
                List(3) { "owner-one" },
                callable.calls.map { it.second["expectedOwnerUid"] },
            )
            assertEquals(
                mapOf("expectedOwnerUid" to "owner-one", "requestId" to "request-one"),
                callable.calls.single { it.first == "cancelAccountDeletion" }.second,
            )
        }

    @Test
    fun `failed retry accepts the fresh server identity and seven day received workflow`() =
        runTest {
            val callable =
                RecordingAccountDeletionCallable().apply {
                    results +=
                        workflowResponse(
                            status = "RECEIVED",
                            requestId = "request-two",
                            requestedAtMillis = 1_800_000_000_000L,
                            scheduledForMillis = 1_800_604_800_000L,
                        )
                }
            val repository =
                FirebaseAccountDeletionRepository(
                    owner = AccountId("owner-one"),
                    callable = callable,
                    idempotencyKey = { "retry-operation-key" },
                )
            val scope = repositoryScope(repository, callable)

            val retried = repository.retry(confirmedRetry(DeletionRequestId("request-one"), scope))

            assertEquals(DeletionRequestId("request-two"), retried.workflow.requestId)
            assertEquals(DeletionStatus.RECEIVED, retried.workflow.status)
            assertEquals(
                604_800_000L,
                retried.workflow.scheduledAt.toEpochMilli() -
                    retried.workflow.requestedAt.toEpochMilli(),
            )
        }

    @Test
    fun `partial retry resumes immediately with the server returned workflow identity`() = runTest {
        val callable = RecordingAccountDeletionCallable()
        val repository =
            FirebaseAccountDeletionRepository(
                owner = AccountId("owner-one"),
                callable = callable,
                idempotencyKey = { "retry-operation-key" },
            )
        val scope = repositoryScope(repository, callable)
        callable.results += workflowResponse(requestId = "request-one")

        val retried =
            repository.retry(
                confirmedRetry(
                    DeletionRequestId("request-one"),
                    scope,
                    AccountDeletionRetryKind.RESUME_PARTIALLY_FAILED,
                )
            )

        assertEquals(DeletionRequestId("request-one"), retried.workflow.requestId)
        assertEquals(DeletionStatus.PARTIALLY_FAILED, retried.workflow.status)
        assertEquals(
            mapOf(
                "expectedOwnerUid" to "owner-one",
                "confirmed" to true,
                "idempotencyKey" to "retry-operation-key",
            ),
            callable.calls.single { it.first == "retryAccountDeletion" }.second,
        )
    }

    @Test
    fun `private media reservation partitions parse for partial and completed status`() = runTest {
        val callable =
            RecordingAccountDeletionCallable().apply {
                results +=
                    workflowResponse(
                        completedScopes = listOf("PUBLIC_SHARES", "PRIVATE_MEDIA_RESERVATIONS"),
                        failedScopes =
                            SERVER_SCOPES - setOf("PUBLIC_SHARES", "PRIVATE_MEDIA_RESERVATIONS"),
                    )
                results += workflowResponse(status = "COMPLETED")
            }
        val repository = FirebaseAccountDeletionRepository(AccountId("owner-one"), callable)

        val partial = requireNotNull(repository.status())
        val completed = requireNotNull(repository.status())

        assertEquals(
            setOf("PUBLIC_SHARES", "PRIVATE_MEDIA_RESERVATIONS"),
            partial.completedCategories.map(AccountDeletionCategory::serverId).toSet(),
        )
        assertTrue(
            partial.remainingCategories.none {
                it.serverId == "PRIVATE_MEDIA_RESERVATIONS"
            }
        )
        assertEquals(AccountDeletionCategory.entries.toSet(), completed.completedCategories)
        assertTrue(completed.remainingCategories.isEmpty())
    }

    @Test
    fun `completed status requires all eight scopes and unknown scope stays fail closed`() =
        runTest {
            val malformed =
                listOf(
                    workflowResponse(
                        status = "COMPLETED",
                        completedScopes =
                            SERVER_SCOPES.filterNot { it == "PRIVATE_MEDIA_RESERVATIONS" },
                    ),
                    workflowResponse(
                        completedScopes = listOf("PUBLIC_SHARES"),
                        failedScopes = SERVER_SCOPES.drop(1) + "FUTURE_PRIVATE_SCOPE",
                    ),
                )

            malformed.forEach { response ->
                val repository =
                    FirebaseAccountDeletionRepository(
                        AccountId("owner-one"),
                        RecordingAccountDeletionCallable().apply { results += response },
                    )
                assertMalformed { repository.status() }
            }
        }

    @Test
    fun `status rejects malformed status partitions`() = runTest {
        val malformed =
            listOf(
                workflowResponse(status = "RECEIVED", completedScopes = listOf("PUBLIC_SHARES")),
                workflowResponse(status = "RECEIVED") + ("requestedAtMillis" to 1.5),
                workflowResponse(
                    status = "RECEIVED",
                    scheduledForMillis = 1_700_604_799_999L,
                ),
                workflowResponse(
                    status = "PROCESSING",
                    failedScopes = listOf("AUTH_ACCOUNT"),
                ),
                workflowResponse(status = "COMPLETED", completedScopes = SERVER_SCOPES.dropLast(1)),
                workflowResponse(status = "COMPLETED", completedAtMillis = null),
                workflowResponse(
                    status = "FAILED",
                    completedScopes = listOf("PUBLIC_SHARES"),
                    failedScopes = SERVER_SCOPES.drop(1),
                ),
                workflowResponse(
                    status = "PARTIALLY_FAILED",
                    completedScopes = listOf("PUBLIC_SHARES"),
                    failedScopes = listOf("PUBLIC_SHARES"),
                ),
                workflowResponse(
                    status = "PARTIALLY_FAILED",
                    completedScopes = listOf("PUBLIC_SHARES"),
                    failedScopes = listOf("AUTH_ACCOUNT"),
                ),
                workflowResponse(
                    status = "PARTIALLY_FAILED",
                    completedScopes = listOf("PUBLIC_SHARES", "PUBLIC_SHARES"),
                    failedScopes = SERVER_SCOPES.drop(1),
                ),
                workflowResponse(
                    status = "PARTIALLY_FAILED",
                    completedScopes = listOf("AUTH_ACCOUNT"),
                    failedScopes = SERVER_SCOPES.dropLast(1).reversed(),
                ),
            )

        malformed.forEach { response ->
            val repository =
                FirebaseAccountDeletionRepository(
                    AccountId("owner-one"),
                    RecordingAccountDeletionCallable().apply { results += response },
                )
            assertMalformed { repository.status() }
        }
    }

    @Test
    fun `absent deletion status remains typed null`() = runTest {
        val callable = RecordingAccountDeletionCallable().apply { results += null }
        val repository = FirebaseAccountDeletionRepository(AccountId("owner-one"), callable)

        assertNull(repository.status())
    }

    private fun previewResponse() =
        mapOf(
            "scope" to
                mapOf(
                    "categories" to SERVER_SCOPES,
                    "gracePeriodMillis" to 604_800_000L,
                ),
            "request" to null,
        )

    private fun workflowResponse(
        status: String = "PARTIALLY_FAILED",
        requestId: String = "request-one",
        requestedAtMillis: Long = 1_700_000_000_000L,
        scheduledForMillis: Long = 1_700_604_800_000L,
        completedScopes: List<String> =
            when (status) {
                "COMPLETED" -> SERVER_SCOPES
                "PARTIALLY_FAILED" -> listOf("PUBLIC_SHARES")
                else -> emptyList()
            },
        failedScopes: List<String> =
            when (status) {
                "FAILED" -> SERVER_SCOPES
                "PARTIALLY_FAILED" -> SERVER_SCOPES.drop(1)
                else -> emptyList()
            },
        completedAtMillis: Long? = if (status == "COMPLETED") scheduledForMillis + 1 else null,
    ) =
        mapOf(
            "ownerUid" to "owner-one",
            "requestId" to requestId,
            "status" to status,
            "requestedAtMillis" to requestedAtMillis,
            "scheduledForMillis" to scheduledForMillis,
            "completedAtMillis" to completedAtMillis,
            "completedScopes" to completedScopes,
            "failedScopes" to failedScopes,
        )

    private suspend fun repositoryScope(
        repository: FirebaseAccountDeletionRepository,
        callable: RecordingAccountDeletionCallable,
    ): AccountDeletionScope {
        callable.results.addFirst(previewResponse())
        return repository.preview()
    }

    private fun confirmedRetry(
        requestId: DeletionRequestId,
        scope: AccountDeletionScope,
        kind: AccountDeletionRetryKind = AccountDeletionRetryKind.RESTART_FAILED,
    ): ConfirmedAccountDeletionRetry {
        val constructor =
            ConfirmedAccountDeletionRetry::class
                .java
                .getDeclaredConstructor(
                    String::class.java,
                    AccountDeletionScope::class.java,
                    AccountDeletionRetryKind::class.java,
                )
        constructor.isAccessible = true
        return constructor.newInstance(
            requestId.value,
            scope,
            kind,
        )
    }

    private suspend fun assertMalformed(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected malformed account deletion response to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected fail-closed boundary behavior.
        }
    }

    private class RecordingAccountDeletionCallable : AccountDeletionCallable {
        val calls = mutableListOf<Pair<String, Map<String, Any>>>()
        val results = ArrayDeque<Any?>()

        override suspend fun call(name: String, data: Map<String, Any>): Any? {
            calls += name to data
            return results.removeFirst()
        }
    }
}
