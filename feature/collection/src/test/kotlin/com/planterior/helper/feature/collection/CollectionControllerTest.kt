package com.planterior.helper.feature.collection

import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.RegistrationMethod
import com.planterior.helper.feature.watering.WateringScheduleStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CollectionControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-18T03:00:00Z"), ZoneId.of("Asia/Seoul"))

    @Test
    fun `collection transitions from loading to content empty and error without timing waits`() =
        runTest {
            val deferred = CompletableDeferred<CollectionLoad>()
            val repository = FakeRepository(collectionBlock = { deferred.await() })
            val controller = CollectionController(repository, SavedStateHandle())

            val loading = launch { controller.start() }
            assertEquals(CollectionUiState.Loading, controller.state.value)
            deferred.complete(CollectionLoad.Fresh(listOf(item("plant-a"))))
            loading.join()
            assertTrue(controller.state.value is CollectionUiState.Content)

            repository.collectionBlock = { CollectionLoad.Fresh(emptyList()) }
            controller.retry()
            assertEquals(CollectionUiState.Empty, controller.state.value)

            repository.collectionBlock = { CollectionLoad.Failed }
            controller.retry()
            assertEquals(CollectionUiState.Error, controller.state.value)
        }

    @Test
    fun `stale collection keeps cached rows visible and marks freshness`() = runTest {
        val controller =
            CollectionController(
                FakeRepository(
                    collectionBlock = {
                        CollectionLoad.Stale(
                            listOf(item("cached")),
                            Instant.parse("2026-08-17T00:00:00Z"),
                        )
                    }
                ),
                SavedStateHandle(),
            )

        controller.start()

        val content = controller.state.value as CollectionUiState.Content
        assertTrue(content.stale)
        assertEquals(listOf("cached"), content.items.map { it.id.value })
    }

    @Test
    fun `saved list index and offset restore after controller recreation`() {
        val handle = SavedStateHandle()
        val original = CollectionController(FakeRepository(), handle)
        original.updateListPosition(index = 37, offset = 19)

        val restored = CollectionController(FakeRepository(), handle)

        assertEquals(CollectionListPosition(37, 19), restored.listPosition)
    }

    @Test
    fun `older collection completion cannot overwrite a newer retry`() = runTest {
        val old = CompletableDeferred<CollectionLoad>()
        val newer = CompletableDeferred<CollectionLoad>()
        var calls = 0
        val controller =
            CollectionController(
                FakeRepository(
                    collectionBlock = { if (calls++ == 0) old.await() else newer.await() }
                ),
                SavedStateHandle(),
            )

        val first = launch { controller.start() }
        val second = launch { controller.retry() }
        newer.complete(CollectionLoad.Fresh(listOf(item("new"))))
        second.join()
        old.complete(CollectionLoad.Fresh(listOf(item("old"))))
        first.join()

        assertEquals(
            listOf("new"),
            (controller.state.value as CollectionUiState.Content).items.map { it.id.value },
        )
    }

    @Test
    fun `detail controller represents fresh partial stale no content forbidden and not found`() =
        runTest {
            val repository = FakeRepository()
            val controller =
                PlantDetailController(
                    PersonalPlantId("plant-a"),
                    repository,
                    clock,
                    SavedStateHandle(),
                )
            val detail = detail()

            repository.detailBlock = { DetailLoad.Fresh(detail) }
            controller.start()
            assertTrue(controller.state.value is PlantDetailUiState.Content)

            repository.detailBlock = {
                DetailLoad.Partial(detail, setOf(CareField.HUMIDITY))
            }
            controller.retry()
            assertEquals(
                setOf(CareField.HUMIDITY),
                (controller.state.value as PlantDetailUiState.Partial).missing,
            )

            repository.detailBlock = {
                DetailLoad.Stale(
                    detail.plant,
                    null,
                    editingAllowed = true,
                    accountZone = ZoneId.of("Asia/Seoul"),
                )
            }
            controller.retry()
            assertTrue(controller.state.value is PlantDetailUiState.Stale)

            repository.detailBlock = {
                DetailLoad.NoStandardContent(detail.plant, ZoneId.of("Asia/Seoul"))
            }
            controller.retry()
            assertTrue(controller.state.value is PlantDetailUiState.NoStandardContent)

            repository.detailBlock = { DetailLoad.Forbidden }
            controller.retry()
            assertEquals(PlantDetailUiState.Forbidden, controller.state.value)

            repository.detailBlock = { DetailLoad.NotFound }
            controller.retry()
            assertEquals(PlantDetailUiState.NotFound, controller.state.value)
        }

    @Test
    fun `detail resume reclassifies schedule across account zone midnight with a virtual clock`() =
        runTest {
            val mutableClock =
                MutableClock(
                    Instant.parse("2026-08-18T14:59:00Z"),
                    ZoneId.of("America/Los_Angeles"),
                )
            val controller =
                PlantDetailController(
                    PersonalPlantId("plant-a"),
                    FakeRepository(detailBlock = { DetailLoad.Fresh(detail()) }),
                    mutableClock,
                    SavedStateHandle(),
                )
            controller.start()
            assertEquals(
                WateringScheduleStatus.Upcoming(LocalDate.of(2026, 8, 19), 1),
                (controller.state.value as PlantDetailUiState.Content).wateringSchedule,
            )

            mutableClock.advanceSeconds(60)
            controller.onResume()

            assertEquals(
                WateringScheduleStatus.Due(LocalDate.of(2026, 8, 19)),
                (controller.state.value as PlantDetailUiState.Content).wateringSchedule,
            )
        }

    @Test
    fun `account zone midnight reclassifies on deterministic coroutine virtual time`() = runTest {
        val virtualClock =
            SchedulerClock(
                Instant.parse("2026-08-18T14:59:00Z"),
                testScheduler,
                ZoneId.of("America/Los_Angeles"),
            )
        val controller =
            PlantDetailController(
                PersonalPlantId("plant-a"),
                FakeRepository(detailBlock = { DetailLoad.Fresh(detail()) }),
                virtualClock,
                SavedStateHandle(),
            )
        controller.start()
        assertTrue(
            (controller.state.value as PlantDetailUiState.Content).wateringSchedule
                is WateringScheduleStatus.Upcoming
        )

        val midnight = launch { controller.reclassifyAtNextAccountMidnight() }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        midnight.join()

        assertEquals(
            WateringScheduleStatus.Due(LocalDate.of(2026, 8, 19)),
            (controller.state.value as PlantDetailUiState.Content).wateringSchedule,
        )
    }

    @Test
    fun `edit validates future date and grapheme limits before repository write`() = runTest {
        val repository = FakeRepository(detailBlock = { DetailLoad.Fresh(detail()) })
        val controller =
            PlantDetailController(
                PersonalPlantId("plant-a"),
                repository,
                clock,
                SavedStateHandle(),
            )
        controller.start()
        controller.beginEditing()
        controller.changeLastWateredDate("2026-08-19")
        controller.changeLocation("🌿".repeat(51))
        controller.changePrivateNote("a".repeat(1001))

        controller.saveEdit()

        val editor = (controller.state.value as PlantDetailUiState.Content).editor
        assertEquals(
            setOf(
                EditValidationError.FUTURE_LAST_WATERED_DATE,
                EditValidationError.LOCATION_TOO_LONG,
                EditValidationError.NOTE_TOO_LONG,
            ),
            editor.errors,
        )
        assertTrue(repository.edits.isEmpty())
    }

    @Test
    fun `failed edit retains exact draft through recreation and retries stable operation`() =
        runTest {
            val handle = SavedStateHandle()
            val repository =
                FakeRepository(
                    detailBlock = { DetailLoad.Fresh(detail()) },
                    editResults =
                        ArrayDeque(
                            listOf(
                                EditResult.Failed(EditFailure.REMOTE_WRITE_FAILED),
                                EditResult.Saved(
                                    detail()
                                        .plant
                                        .copy(
                                            location = "침실",
                                            privateNote = "새 잎 확인",
                                        )
                                ),
                            )
                        ),
                )
            val original =
                PlantDetailController(PersonalPlantId("plant-a"), repository, clock, handle)
            original.start()
            original.beginEditing()
            original.changeLastWateredDate("2026-08-17")
            original.changeLocation("침실")
            original.changePrivateNote("새 잎 확인")
            original.saveEdit()

            val failed = (original.state.value as PlantDetailUiState.Content).editor
            assertEquals(EditFailure.REMOTE_WRITE_FAILED, failed.failure)
            assertEquals("침실", failed.location)
            assertEquals("새 잎 확인", failed.privateNote)

            val restored =
                PlantDetailController(PersonalPlantId("plant-a"), repository, clock, handle)
            restored.start()
            val restoredEditor = (restored.state.value as PlantDetailUiState.Content).editor
            assertEquals("2026-08-17", restoredEditor.lastWateredDate)
            assertEquals("침실", restoredEditor.location)
            assertEquals("새 잎 확인", restoredEditor.privateNote)
            assertEquals(failed.operationId, restoredEditor.operationId)

            restored.saveEdit()

            assertEquals(2, repository.edits.size)
            assertEquals(1, repository.edits.map { it.operationId }.distinct().size)
            assertEquals(null, (restored.state.value as PlantDetailUiState.Content).editor.failure)
        }

    @Test
    fun `failed edit freezes the exact snapshot and ignores changes before stable retry`() =
        runTest {
            val repository =
                FakeRepository(
                    detailBlock = { DetailLoad.Fresh(detail()) },
                    editResults =
                        ArrayDeque(
                            listOf(
                                EditResult.Failed(EditFailure.REMOTE_WRITE_FAILED),
                                EditResult.Saved(detail().plant.copy(location = "침실")),
                            )
                        ),
                )
            val controller =
                PlantDetailController(
                    PersonalPlantId("plant-a"),
                    repository,
                    clock,
                    SavedStateHandle(),
                )
            controller.start()
            controller.beginEditing()
            controller.changeLocation("침실")
            controller.changePrivateNote("동쪽 창")
            controller.saveEdit()

            val frozen = (controller.state.value as PlantDetailUiState.Content).editor
            assertTrue(frozen.isFrozen)
            controller.changeLocation("거실")
            controller.changePrivateNote("바꾸면 안 됨")
            assertEquals(frozen, (controller.state.value as PlantDetailUiState.Content).editor)

            controller.saveEdit()

            assertEquals(2, repository.edits.size)
            assertEquals(repository.edits.first(), repository.edits.last())
        }

    @Test
    fun `conflict and outbox mismatch require deterministic reconciliation instead of retry`() =
        runTest {
            for (failure in listOf(EditFailure.REVISION_CONFLICT, EditFailure.OUTBOX_MISMATCH)) {
                val refreshed =
                    detail().copy(plant = detail().plant.copy(revision = 2, location = "서버 위치"))
                val repository =
                    FakeRepository(
                        detailBlock = { DetailLoad.Fresh(detail()) },
                        editResults = ArrayDeque(listOf(EditResult.Failed(failure))),
                        reconcileBlock = { _, _, _ -> DetailLoad.Fresh(refreshed) },
                    )
                val attemptOperations =
                    ArrayDeque(
                        listOf(
                            OperationId("operation-conflict-frozen"),
                            OperationId("operation-conflict-fresh"),
                        )
                    )
                val controller =
                    PlantDetailController(
                        PersonalPlantId("plant-a"),
                        repository,
                        clock,
                        SavedStateHandle(),
                        operationIdFactory = { attemptOperations.removeFirst() },
                    )
                controller.start()
                controller.beginEditing()
                controller.changeLocation("내 변경")
                controller.saveEdit()

                val failed = (controller.state.value as PlantDetailUiState.Content).editor
                assertTrue(failed.requiresReconciliation)
                controller.saveEdit()
                assertEquals(1, repository.edits.size)

                val frozenOperation = requireNotNull(failed.operationId)
                controller.reconcileFailedEdit()

                assertEquals(1, repository.reconciliations.size)
                val state = controller.state.value as PlantDetailUiState.Content
                assertEquals("서버 위치", state.detail.plant.location)
                assertFalse(state.editor.isEditing)
                controller.beginEditing()
                assertNotEquals(
                    frozenOperation,
                    (controller.state.value as PlantDetailUiState.Content).editor.operationId,
                )
            }
        }

    @Test
    fun `each new edit attempt gets a new saved random operation id`() = runTest {
        val operations =
            ArrayDeque(
                listOf(
                    OperationId("operation-attempt-one"),
                    OperationId("operation-attempt-two"),
                )
            )
        val controller =
            PlantDetailController(
                PersonalPlantId("plant-a"),
                FakeRepository(detailBlock = { DetailLoad.Fresh(detail()) }),
                clock,
                SavedStateHandle(),
                operationIdFactory = { operations.removeFirst() },
            )
        controller.start()

        controller.beginEditing()
        val first = (controller.state.value as PlantDetailUiState.Content).editor.operationId
        controller.cancelEdit()
        controller.beginEditing()
        val second = (controller.state.value as PlantDetailUiState.Content).editor.operationId

        assertEquals(OperationId("operation-attempt-one"), first)
        assertEquals(OperationId("operation-attempt-two"), second)
    }

    @Test
    fun `save cancellation restores exact frozen draft and operation before rethrow`() = runTest {
        val cancellation = CancellationException("route left while saving")
        val repository =
            FakeRepository(
                detailBlock = { DetailLoad.Fresh(detail()) },
                saveFailure = cancellation,
            )
        val controller =
            PlantDetailController(PersonalPlantId("plant-a"), repository, clock, SavedStateHandle())
        controller.start()
        controller.beginEditing()
        controller.changeLocation("침실")
        val before = (controller.state.value as PlantDetailUiState.Content).editor

        try {
            controller.saveEdit()
            fail("CancellationException expected")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }

        val restored = (controller.state.value as PlantDetailUiState.Content).editor
        assertEquals(before.copy(saving = false), restored)
        assertFalse(restored.saving)
    }

    @Test
    fun `last watered validation uses authenticated account zone at date boundary`() = runTest {
        val accountZoneDetail = detail().copy(accountZone = ZoneId.of("Asia/Seoul"))
        val repository = FakeRepository(detailBlock = { DetailLoad.Fresh(accountZoneDetail) })
        val deviceZoneClock =
            Clock.fixed(Instant.parse("2026-08-18T00:30:00Z"), ZoneId.of("America/Los_Angeles"))
        val controller =
            PlantDetailController(
                PersonalPlantId("plant-a"),
                repository,
                deviceZoneClock,
                SavedStateHandle(),
            )
        controller.start()
        controller.beginEditing()
        controller.changeLastWateredDate("2026-08-18")

        controller.saveEdit()

        assertEquals(1, repository.edits.size)
    }

    @Test
    fun `incomplete stale detail cannot begin editing`() = runTest {
        val controller =
            PlantDetailController(
                PersonalPlantId("plant-a"),
                FakeRepository(
                    detailBlock = {
                        DetailLoad.Stale(
                            detail().plant,
                            null,
                            editingAllowed = false,
                            accountZone = null,
                        )
                    }
                ),
                clock,
                SavedStateHandle(),
            )
        controller.start()

        controller.beginEditing()

        assertFalse((controller.state.value as PlantDetailUiState.Stale).editor.isEditing)
    }

    @Test
    fun `controller rethrows cancellation without replacing current detail with error`() = runTest {
        val cancellation = CancellationException("route left")
        val controller =
            PlantDetailController(
                PersonalPlantId("plant-a"),
                FakeRepository(detailBlock = { throw cancellation }),
                clock,
                SavedStateHandle(),
            )

        try {
            controller.start()
            fail("CancellationException expected")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertEquals(PlantDetailUiState.Loading, controller.state.value)
    }

    private fun item(id: String) =
        CollectionPlant(PersonalPlantId(id), "몬스테라", representativePhotoPath = null)

    private class SchedulerClock(
        private val base: Instant,
        private val scheduler: TestCoroutineScheduler,
        private val currentZone: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = currentZone

        override fun withZone(zone: ZoneId): Clock = SchedulerClock(base, scheduler, zone)

        override fun instant(): Instant = base.plusMillis(scheduler.currentTime)
    }

    private class MutableClock(
        private var current: Instant,
        private val currentZone: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = currentZone

        override fun withZone(zone: ZoneId): Clock =
            object : Clock() {
                override fun getZone(): ZoneId = zone

                override fun withZone(zone: ZoneId): Clock = this@MutableClock.withZone(zone)

                override fun instant(): Instant = current
            }

        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }

    private fun detail() =
        PlantDetail(
            plant =
                PersonalPlantDetail(
                    accountId = AccountId("account-a"),
                    id = PersonalPlantId("plant-a"),
                    displayName = "몬스테라",
                    contentId = PlantContentId("species-a"),
                    registrationMethod = RegistrationMethod.IDENTIFIED,
                    representativePhotoPath = null,
                    location = "거실",
                    privateNote = "잎을 닦음",
                    lastWateredDate = LocalDate.of(2026, 8, 12),
                    revision = 1,
                    updatedAt = Instant.parse("2026-08-18T00:00:00Z"),
                ),
            accountZone = ZoneId.of("Asia/Seoul"),
            guidance =
                PlantCareGuidance(
                    wateringIntervalDays = 7,
                    lightGuidance = "밝은 간접광",
                    minimumTemperatureCelsius = 18.0,
                    maximumTemperatureCelsius = 28.0,
                    minimumHumidityPercent = 40,
                    maximumHumidityPercent = 70,
                    symptoms =
                        listOf(
                            PublicSymptomGuidance(
                                id = "droop",
                                symptom = "잎 처짐",
                                possibleCause = "흙이 말랐을 수 있어요",
                                action = "흙을 확인하고 물을 주세요",
                            )
                        ),
                ),
        )

    private inner class FakeRepository(
        collectionBlock: suspend () -> CollectionLoad = {
            CollectionLoad.Fresh(emptyList())
        },
        detailBlock: suspend (PersonalPlantId) -> DetailLoad = { DetailLoad.NotFound },
        private val editResults: ArrayDeque<EditResult> = ArrayDeque(),
        private val saveFailure: Throwable? = null,
        private val reconcileBlock:
            suspend (
                AccountId,
                PersonalPlantId,
                com.planterior.helper.core.model.OperationId,
            ) -> DetailLoad =
            { _, _, _ ->
                DetailLoad.NotFound
            },
    ) : CollectionRepository {
        var collectionBlock = collectionBlock
        var detailBlock = detailBlock
        val edits = mutableListOf<PlantEditRequest>()
        val reconciliations =
            mutableListOf<
                Triple<AccountId, PersonalPlantId, com.planterior.helper.core.model.OperationId>
            >()

        override suspend fun loadCollection() = collectionBlock()

        override suspend fun loadDetail(plantId: PersonalPlantId) = detailBlock(plantId)

        override suspend fun saveEdit(request: PlantEditRequest): EditResult {
            edits += request
            saveFailure?.let { throw it }
            return editResults.removeFirstOrNull()
                ?: EditResult.Saved(detail().plant.copy(location = request.location))
        }

        override suspend fun reconcileFailedEdit(
            accountId: AccountId,
            plantId: PersonalPlantId,
            operationId: com.planterior.helper.core.model.OperationId,
        ): DetailLoad {
            reconciliations += Triple(accountId, plantId, operationId)
            return reconcileBlock(accountId, plantId, operationId)
        }
    }
}
