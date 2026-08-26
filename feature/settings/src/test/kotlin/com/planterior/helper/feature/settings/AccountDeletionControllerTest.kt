package com.planterior.helper.feature.settings

import com.planterior.helper.core.model.DeletionRequestId
import com.planterior.helper.core.model.DeletionStatus
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionControllerTest {
    @Test
    fun `request requires successful reauthentication then explicit final confirmation`() =
        runTest {
            val events = mutableListOf<String>()
            val repository = FakeDeletionRepository().apply { eventLog = events }
            val controller = controller(repository, events = events)
            advanceUntilIdle()

            controller.submit()
            controller.reauthenticate()
            advanceUntilIdle()
            controller.submit()
            advanceUntilIdle()

            assertEquals(0, repository.requestCalls)
            controller.setFinalConfirmation(true)
            controller.submit()
            advanceUntilIdle()
            assertEquals(listOf("reauthenticate", "request"), events)
            assertEquals(1, repository.requestCalls)
        }

    @Test
    fun `received request invokes analytics guard once before exposing success`() = runTest {
        val guardStarted = CompletableDeferred<Unit>()
        val releaseGuard = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val repository = FakeDeletionRepository().apply { eventLog = events }
        val controller =
            controller(repository, events = events) {
                events += "analytics-guard"
                guardStarted.complete(Unit)
                releaseGuard.await()
            }
        advanceUntilIdle()
        controller.reauthenticate()
        advanceUntilIdle()
        controller.setFinalConfirmation(true)

        controller.submit()
        guardStarted.await()

        assertNull(controller.ready().workflow)
        assertTrue(controller.ready().submitting)
        releaseGuard.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("reauthenticate", "request", "analytics-guard"), events)
        assertEquals(DeletionStatus.RECEIVED, controller.ready().workflow?.status)
    }

    @Test
    fun `failed request never invokes analytics guard`() = runTest {
        var guardCalls = 0
        val repository = FakeDeletionRepository().apply { requestFailure = true }
        val controller = controller(repository, onReceived = { guardCalls += 1 })
        advanceUntilIdle()
        controller.reauthenticate()
        advanceUntilIdle()
        controller.setFinalConfirmation(true)

        controller.submit()
        advanceUntilIdle()

        assertEquals(0, guardCalls)
        assertEquals(AccountDeletionFailure.REQUEST_FAILED, controller.ready().failure)
    }

    @Test
    fun `received replay is idempotent while cleanup failure retries without invalidating deletion`() =
        runTest {
            var guardCalls = 0
            val received = workflow(DeletionStatus.RECEIVED)
            val repository = FakeDeletionRepository(statusResult = received)
            val controller =
                controller(repository) {
                    guardCalls += 1
                    if (guardCalls == 1) error("local cleanup unavailable")
                }
            advanceUntilIdle()

            assertEquals(DeletionStatus.RECEIVED, controller.ready().workflow?.status)
            assertEquals(1, guardCalls)
            controller.refresh()
            advanceUntilIdle()
            controller.refresh()
            advanceUntilIdle()

            assertEquals(2, guardCalls)
            assertEquals(DeletionStatus.RECEIVED, controller.ready().workflow?.status)
            assertNull(controller.ready().failure)
        }

    @Test
    fun `cancelling accepted deletion does not invoke guard again or restore analytics`() =
        runTest {
            var analyticsOff = false
            var guardCalls = 0
            val repository =
                FakeDeletionRepository(statusResult = workflow(DeletionStatus.RECEIVED))
            val controller =
                controller(repository) {
                    guardCalls += 1
                    analyticsOff = true
                }
            advanceUntilIdle()

            controller.cancel()
            advanceUntilIdle()

            assertEquals(DeletionStatus.CANCELLED, controller.ready().workflow?.status)
            assertEquals(1, guardCalls)
            assertTrue(analyticsOff)
        }

    @Test
    fun `double submit creates one deletion request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeDeletionRepository().apply { requestGate = gate }
        val controller = controller(repository)
        advanceUntilIdle()
        controller.reauthenticate()
        advanceUntilIdle()
        controller.setFinalConfirmation(true)

        controller.submit()
        controller.submit()
        runCurrent()

        assertEquals(1, repository.requestCalls)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `received request can cancel`() = runTest {
        val repository = FakeDeletionRepository(statusResult = workflow(DeletionStatus.RECEIVED))
        val controller = controller(repository)
        advanceUntilIdle()

        controller.cancel()
        advanceUntilIdle()

        assertEquals(1, repository.cancelCalls)
        assertEquals(DeletionStatus.CANCELLED, controller.ready().workflow?.status)
    }

    @Test
    fun `stale request one cancel response cannot replace active request two`() = runTest {
        val cancelGate = CompletableDeferred<Unit>()
        val requestOne = workflow(DeletionStatus.RECEIVED, requestId = "deletion-request-1")
        val requestTwo = workflow(DeletionStatus.RECEIVED, requestId = "deletion-request-2")
        val repository =
            FakeDeletionRepository(statusResult = requestOne).apply {
                this.cancelGate = cancelGate
                cancelResult = workflow(DeletionStatus.CANCELLED, requestId = "deletion-request-1")
            }
        val controller = controller(repository)
        advanceUntilIdle()

        controller.cancel()
        runCurrent()
        repository.statusResult = requestTwo
        controller.refresh()
        runCurrent()
        cancelGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            DeletionRequestId("deletion-request-2"),
            controller.ready().workflow?.requestId,
        )
        assertEquals(DeletionStatus.RECEIVED, controller.ready().workflow?.status)
    }

    @Test
    fun `processing request cannot cancel`() = runTest {
        val repository = FakeDeletionRepository(statusResult = workflow(DeletionStatus.PROCESSING))
        val controller = controller(repository)
        advanceUntilIdle()

        controller.cancel()
        advanceUntilIdle()

        assertEquals(0, repository.cancelCalls)
    }

    @Test
    fun `failed and partially failed requests retain account without cleanup`() = runTest {
        for (status in listOf(DeletionStatus.FAILED, DeletionStatus.PARTIALLY_FAILED)) {
            var cleanups = 0
            val repository = FakeDeletionRepository(statusResult = workflow(status))
            controller(repository, onCompleted = { cleanups += 1 })
            advanceUntilIdle()
            assertEquals(0, cleanups)
        }
    }

    @Test
    fun `completed status invokes typed terminal callback exactly once`() = runTest {
        var cleanups = 0
        val completed = workflow(DeletionStatus.COMPLETED)
        val repository = FakeDeletionRepository(statusResult = completed)
        val controller = controller(repository, onCompleted = { cleanups += 1 })
        advanceUntilIdle()

        controller.refresh()
        controller.refresh()
        advanceUntilIdle()

        assertEquals(1, cleanups)
    }

    @Test
    fun `restart restores authoritative repository status`() = runTest {
        val received = workflow(DeletionStatus.RECEIVED)
        val repository = FakeDeletionRepository(statusResult = received)

        val restarted = controller(repository)
        advanceUntilIdle()

        assertEquals(1, repository.statusCalls)
        assertEquals(received, restarted.ready().workflow)
    }

    @Test
    fun `failed retry replaces request one with fresh request two and seven day grace`() = runTest {
        val requestedAt = Instant.parse("2026-09-01T04:00:00Z")
        val repository =
            FakeDeletionRepository(
                    statusResult = workflow(DeletionStatus.FAILED, requestId = "deletion-request-1")
                )
                .apply {
                    retryResult =
                        workflow(
                            status = DeletionStatus.RECEIVED,
                            requestId = "deletion-request-2",
                            requestedAt = requestedAt,
                            scheduledAt = requestedAt.plusSeconds(7 * 24 * 60 * 60L),
                        )
                }
        val controller = controller(repository)
        advanceUntilIdle()

        controller.reauthenticate()
        advanceUntilIdle()
        controller.setFinalConfirmation(true)
        controller.submit()
        advanceUntilIdle()

        val retried = requireNotNull(controller.ready().workflow)
        assertEquals(DeletionRequestId("deletion-request-2"), retried.requestId)
        assertEquals(DeletionStatus.RECEIVED, retried.status)
        assertEquals(604_800L, retried.scheduledAt.epochSecond - retried.requestedAt.epochSecond)
    }

    @Test
    fun `durable retry replay cannot replace a newer active workflow`() = runTest {
        val retryGate = CompletableDeferred<Unit>()
        val repository =
            FakeDeletionRepository(
                    statusResult = workflow(DeletionStatus.FAILED, requestId = "deletion-request-1")
                )
                .apply {
                    this.retryGate = retryGate
                    retryResult =
                        workflow(DeletionStatus.RECEIVED, requestId = "deletion-request-2")
                }
        val controller = controller(repository)
        advanceUntilIdle()
        controller.reauthenticate()
        advanceUntilIdle()
        controller.setFinalConfirmation(true)

        controller.submit()
        runCurrent()
        repository.statusResult =
            workflow(DeletionStatus.RECEIVED, requestId = "deletion-request-3")
        controller.refresh()
        runCurrent()
        retryGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            DeletionRequestId("deletion-request-3"),
            controller.ready().workflow?.requestId,
        )
        assertEquals(AccountDeletionFailure.REQUEST_FAILED, controller.ready().failure)
    }

    @Test
    fun `partial retry is immediate but still requires reauth and confirmation`() = runTest {
        val partial = workflow(DeletionStatus.PARTIALLY_FAILED)
        val repository = FakeDeletionRepository(statusResult = partial)
        val controller = controller(repository)
        advanceUntilIdle()

        controller.submit()
        controller.reauthenticate()
        advanceUntilIdle()
        controller.setFinalConfirmation(true)
        controller.submit()
        advanceUntilIdle()

        assertEquals(0, repository.requestCalls)
        assertEquals(1, repository.retryCalls)
        assertEquals(DeletionStatus.PARTIALLY_FAILED, controller.ready().workflow?.status)
        assertEquals(partial.requestId, controller.ready().workflow?.requestId)
        assertFalse(controller.ready().terminalCleanupStarted)
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        repository: FakeDeletionRepository,
        events: MutableList<String>? = null,
        onCompleted: suspend (AccountDeletionCompletion) -> Unit = {},
        onReceived: suspend (AccountDeletionReceived) -> Unit = {},
    ) =
        AccountDeletionController(
            AccountDeletionDependencies(
                repository = repository,
                reauthenticator =
                    AccountDeletionReauthenticator {
                        events?.add("reauthenticate")
                        AccountDeletionReauthenticationResult.SUCCEEDED
                    },
                terminalCallback = AccountDeletionTerminalCallback(onCompleted),
                analyticsDeletionGuard = AnalyticsDeletionGuard(onReceived),
            ),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    private fun AccountDeletionController.ready(): AccountDeletionUiState.Ready =
        state.value as AccountDeletionUiState.Ready

    private class FakeDeletionRepository(var statusResult: AccountDeletionWorkflow? = null) :
        AccountDeletionRepository {
        var requestCalls = 0
        var cancelCalls = 0
        var retryCalls = 0
        var statusCalls = 0
        var eventLog: MutableList<String>? = null
        var requestGate: CompletableDeferred<Unit>? = null
        var cancelGate: CompletableDeferred<Unit>? = null
        var retryGate: CompletableDeferred<Unit>? = null
        var cancelResult: AccountDeletionWorkflow = workflow(DeletionStatus.CANCELLED)
        var retryResult: AccountDeletionWorkflow? = null
        var requestFailure = false

        override suspend fun preview(): AccountDeletionScope = scope

        override suspend fun status(): AccountDeletionWorkflow? {
            statusCalls += 1
            return statusResult
        }

        override suspend fun request(
            request: ConfirmedAccountDeletionRequest
        ): AccountDeletionWorkflow {
            requestCalls += 1
            eventLog?.add("request")
            requestGate?.await()
            if (requestFailure) error("request failed")
            return workflow(DeletionStatus.RECEIVED)
        }

        override suspend fun cancel(
            requestId: DeletionRequestId
        ): AccountDeletionCancellationResult {
            cancelCalls += 1
            cancelGate?.await()
            return AccountDeletionCancellationResult(requestId, cancelResult)
        }

        override suspend fun retry(
            request: ConfirmedAccountDeletionRetry
        ): AccountDeletionRetryResult {
            retryCalls += 1
            retryGate?.await()
            val workflow =
                retryResult
                    ?: when (request.kind) {
                        AccountDeletionRetryKind.RESTART_FAILED -> workflow(DeletionStatus.RECEIVED)
                        AccountDeletionRetryKind.RESUME_PARTIALLY_FAILED ->
                            workflow(DeletionStatus.PARTIALLY_FAILED)
                    }
            return AccountDeletionRetryResult(request.requestId, request.kind, workflow)
        }
    }

    companion object {
        private val scope =
            AccountDeletionScope(
                hash = AccountDeletionScopeHash("a".repeat(64)),
                categories = AccountDeletionCategory.entries,
            )

        private fun workflow(
            status: DeletionStatus,
            requestId: String = "deletion-request-1",
            requestedAt: Instant = Instant.parse("2026-08-24T04:00:00Z"),
            scheduledAt: Instant = Instant.parse("2026-08-31T04:00:00Z"),
        ) =
            AccountDeletionWorkflow(
                requestId = DeletionRequestId(requestId),
                scope = scope,
                requestedAt = requestedAt,
                scheduledAt = scheduledAt,
                status = status,
                completedCategories =
                    when (status) {
                        DeletionStatus.COMPLETED -> AccountDeletionCategory.entries.toSet()
                        DeletionStatus.PARTIALLY_FAILED ->
                            setOf(AccountDeletionCategory.PUBLIC_SHARES)
                        else -> emptySet()
                    },
                remainingCategories =
                    when (status) {
                        DeletionStatus.COMPLETED -> emptySet()
                        DeletionStatus.PARTIALLY_FAILED ->
                            AccountDeletionCategory.entries.toSet() -
                                AccountDeletionCategory.PUBLIC_SHARES
                        else -> AccountDeletionCategory.entries.toSet()
                    },
            )
    }
}
