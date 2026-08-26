package com.planterior.helper.feature.watering

import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ClientProductEvent
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.ProductEventRecorder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WateringConfirmationControllerTest {
    @Test
    fun `completion defaults to authenticated account local today at a timezone boundary`() =
        runTest {
            val repository = FakeRepository(load = WateringLoad.Found(snapshot()))
            val controller = controller(repository, SavedStateHandle())

            controller.start()

            val ready = controller.state.value as WateringConfirmationUiState.Ready
            assertEquals("2026-08-11", ready.draft.wateredDate)
            assertEquals(LocalDate.of(2026, 8, 21), ready.nextDueDate)
        }

    @Test
    fun `future completion date is rejected before repository mutation`() = runTest {
        val repository = FakeRepository(load = WateringLoad.Found(snapshot()))
        val controller = controller(repository, SavedStateHandle())
        controller.start()
        controller.changeWateredDate("2026-08-12")

        controller.confirm()

        val ready = controller.state.value as WateringConfirmationUiState.Ready
        assertEquals(WateringCompletionValidationError.FUTURE_DATE, ready.validationError)
        assertTrue(repository.requests.isEmpty())
    }

    @Test
    fun `failed request freezes and restores exact date and idempotency key for safe retry`() =
        runTest {
            val handle = SavedStateHandle()
            val receipt = receipt()
            val repository =
                FakeRepository(
                    load = WateringLoad.Found(snapshot()),
                    results =
                        ArrayDeque(
                            listOf(
                                WateringCompletionResult.Failed(
                                    WateringCompletionFailure.REMOTE_WRITE_FAILED
                                ),
                                WateringCompletionResult.Completed(receipt),
                            )
                        ),
                )
            val original = controller(repository, handle)
            original.start()
            original.changeWateredDate("2026-08-10")
            original.confirm()

            val failed = original.state.value as WateringConfirmationUiState.Failure
            assertTrue(failed.draft.frozen)

            val restored = controller(repository, handle.snapshot())
            restored.start()
            val restoredFailure = restored.state.value as WateringConfirmationUiState.Failure
            assertEquals(failed.draft, restoredFailure.draft)
            restored.changeWateredDate("2026-08-09")
            assertEquals(
                failed.draft,
                (restored.state.value as WateringConfirmationUiState.Failure).draft,
            )

            restored.confirm()

            assertEquals(WateringConfirmationUiState.Completed(receipt), restored.state.value)
            assertEquals(2, repository.requests.size)
            assertEquals(1, repository.requests.map { it.operationId }.distinct().size)
            assertEquals(1, repository.requests.map { it.wateredDate }.distinct().size)
        }

    @Test
    fun `completed result restores after process recreation without another mutation`() = runTest {
        val handle = SavedStateHandle()
        val receipt = receipt()
        val repository =
            FakeRepository(
                load = WateringLoad.Found(snapshot()),
                results = ArrayDeque(listOf(WateringCompletionResult.Completed(receipt))),
            )
        val original = controller(repository, handle)
        original.start()
        original.changeWateredDate("2026-08-10")
        original.confirm()

        val restored = controller(repository, handle.snapshot())
        restored.start()

        assertEquals(WateringConfirmationUiState.Completed(receipt), restored.state.value)
        assertEquals(1, repository.requests.size)
    }

    @Test
    fun `completed and idempotent completed watering records once across restoration`() = runTest {
        val events = mutableListOf<ClientProductEvent>()
        val handle = SavedStateHandle()
        val repository =
            FakeRepository(
                load = WateringLoad.Found(snapshot()),
                results = ArrayDeque(listOf(WateringCompletionResult.Completed(receipt()))),
            )
        val recorder = ProductEventRecorder(events::add)
        val original = controller(repository, handle, recorder)
        original.start()

        original.confirm()
        original.confirm()
        val restored = controller(repository, handle.snapshot(), recorder)
        restored.start()

        assertEquals(listOf(ClientProductEvent.WATERING_COMPLETED), events)
        assertEquals(1, repository.requests.size)
    }

    @Test
    fun `failed watering emits nothing and completed retry emits once`() = runTest {
        val events = mutableListOf<ClientProductEvent>()
        val repository =
            FakeRepository(
                load = WateringLoad.Found(snapshot()),
                results =
                    ArrayDeque(
                        listOf(
                            WateringCompletionResult.Failed(
                                WateringCompletionFailure.REMOTE_WRITE_FAILED
                            ),
                            WateringCompletionResult.Completed(receipt()),
                        )
                    ),
            )
        val controller =
            controller(repository, SavedStateHandle(), ProductEventRecorder(events::add))
        controller.start()

        controller.confirm()
        assertTrue(events.isEmpty())
        controller.confirm()
        controller.confirm()

        assertEquals(listOf(ClientProductEvent.WATERING_COMPLETED), events)
    }

    @Test
    fun `missing public interval is unavailable and cannot submit`() = runTest {
        val repository =
            FakeRepository(load = WateringLoad.Found(snapshot().copy(publicIntervalDays = null)))
        val controller = controller(repository, SavedStateHandle())

        controller.start()
        controller.confirm()

        val unavailable = controller.state.value as WateringConfirmationUiState.Unavailable
        assertEquals(
            WateringUnavailableReason.MISSING_PUBLIC_INTERVAL,
            unavailable.schedule.reason,
        )
        assertTrue(repository.requests.isEmpty())
    }

    @Test
    fun `account switch result is forbidden and cancellation restores retryable state`() = runTest {
        val forbidden = FakeRepository(load = WateringLoad.Found(snapshot()))
        forbidden.results += WateringCompletionResult.Forbidden
        val forbiddenController = controller(forbidden, SavedStateHandle())
        forbiddenController.start()
        forbiddenController.confirm()
        assertEquals(WateringConfirmationUiState.Forbidden, forbiddenController.state.value)

        val cancellation = CancellationException("confirmation left")
        val cancelling =
            FakeRepository(load = WateringLoad.Found(snapshot()), completionFailure = cancellation)
        val handle = SavedStateHandle()
        val cancellingController = controller(cancelling, handle)
        cancellingController.start()
        try {
            cancellingController.confirm()
            fail("CancellationException expected")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        val cancelled = cancellingController.state.value as WateringConfirmationUiState.Failure
        assertTrue(cancelled.draft.frozen)

        val completed = receipt().copy(wateredDate = LocalDate.of(2026, 8, 11))
        val recovery =
            FakeRepository(
                load = WateringLoad.Found(snapshot().copy(revision = 99)),
                results = ArrayDeque(listOf(WateringCompletionResult.Completed(completed))),
            )
        val restored = controller(recovery, handle.snapshot())
        restored.start()
        assertTrue((restored.state.value as WateringConfirmationUiState.Failure).draft.frozen)

        restored.confirm()

        assertEquals(WateringConfirmationUiState.Completed(completed), restored.state.value)
        val request = recovery.requests.single()
        assertEquals(AccountId("account-a"), request.accountId)
        assertEquals(PersonalPlantId("plant-a"), request.plantId)
        assertEquals(OperationId("watering-operation-stable"), request.operationId)
        assertEquals(LocalDate.of(2026, 8, 11), request.wateredDate)
        assertEquals(4, request.expectedPlantRevision)
    }

    @Test
    fun `lost response restores a frozen full envelope in a fresh controller and reconciles exactly`() =
        runTest {
            val handle = SavedStateHandle()
            val repository =
                FakeRepository(
                    load = WateringLoad.Found(snapshot()),
                    results =
                        ArrayDeque(
                            listOf(
                                WateringCompletionResult.Failed(
                                    WateringCompletionFailure.RECONCILIATION_REQUIRED
                                )
                            )
                        ),
                    reconciliationResults =
                        ArrayDeque(listOf(WateringCompletionResult.Completed(receipt()))),
                )
            val original = controller(repository, handle)
            original.start()
            original.changeWateredDate("2026-08-10")
            original.confirm()

            val restored = controller(repository, handle.snapshot())
            restored.start()
            val failure = restored.state.value as WateringConfirmationUiState.Failure
            assertTrue(failure.draft.frozen)
            restored.reconcile()

            assertEquals(WateringConfirmationUiState.Completed(receipt()), restored.state.value)
            assertEquals(repository.requests.single(), repository.reconciliationRequests.single())
            assertEquals(4, repository.reconciliationRequests.single().expectedPlantRevision)
            assertEquals(
                LocalDate.of(2026, 8, 10),
                repository.reconciliationRequests.single().wateredDate,
            )
        }

    private fun controller(
        repository: WateringRepository,
        handle: SavedStateHandle,
        recorder: ProductEventRecorder = ProductEventRecorder {},
    ) =
        WateringConfirmationController(
            plantId = PersonalPlantId("plant-a"),
            repository = repository,
            clock =
                Clock.fixed(
                    Instant.parse("2026-08-10T15:30:00Z"),
                    ZoneId.of("America/Los_Angeles"),
                ),
            savedStateHandle = handle,
            operationIdFactory = { OperationId("watering-operation-stable") },
            productEventRecorder = recorder,
        )

    private fun snapshot() =
        WateringPlantSnapshot(
            accountId = AccountId("account-a"),
            plantId = PersonalPlantId("plant-a"),
            displayName = "몬스테라",
            lastWateredDate = LocalDate.of(2026, 8, 1),
            publicIntervalDays = 10,
            accountZone = ZoneId.of("Asia/Seoul"),
            revision = 4,
        )

    private fun receipt() =
        WateringCompletionReceipt(
            accountId = AccountId("account-a"),
            plantId = PersonalPlantId("plant-a"),
            operationId = OperationId("watering-operation-stable"),
            recordId = "watering-operation-stable",
            wateredDate = LocalDate.of(2026, 8, 10),
            nextDueDate = LocalDate.of(2026, 8, 20),
            plantRevision = 5,
            scheduleRevision = 3,
            recordedAt = Instant.parse("2026-08-10T15:30:00Z"),
        )

    private fun SavedStateHandle.snapshot(): SavedStateHandle =
        SavedStateHandle(keys().associateWith { key -> get<Any?>(key) })

    private class FakeRepository(
        var load: WateringLoad,
        val results: ArrayDeque<WateringCompletionResult> = ArrayDeque(),
        private val completionFailure: Throwable? = null,
        val reconciliationResults: ArrayDeque<WateringCompletionResult> = ArrayDeque(),
    ) : WateringRepository {
        val requests = mutableListOf<WateringCompletionRequest>()
        val reconciliationRequests = mutableListOf<WateringCompletionRequest>()

        override suspend fun load(plantId: PersonalPlantId) = load

        override suspend fun complete(
            request: WateringCompletionRequest
        ): WateringCompletionResult {
            requests += request
            completionFailure?.let { throw it }
            return results.removeFirstOrNull()
                ?: WateringCompletionResult.Failed(WateringCompletionFailure.REMOTE_WRITE_FAILED)
        }

        override suspend fun reconcile(
            request: WateringCompletionRequest
        ): WateringCompletionResult {
            reconciliationRequests += request
            return reconciliationResults.removeFirstOrNull()
                ?: WateringCompletionResult.Failed(
                    WateringCompletionFailure.RECONCILIATION_REQUIRED
                )
        }
    }
}
