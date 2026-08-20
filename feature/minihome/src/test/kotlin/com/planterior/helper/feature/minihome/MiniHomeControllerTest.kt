package com.planterior.helper.feature.minihome

import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MiniHomeControllerTest {
    @Test
    fun `surrounding whitespace is rejected before freezing or transmitting`() = runTest {
        val repository = FakeRepository()
        val controller = MiniHomeController(repository, SavedStateHandle())
        controller.start()
        controller.beginEditing()
        controller.rename("  나의 미니 식물원  ")

        controller.save()
        controller.save()

        val editing = controller.state.value as MiniHomeUiState.Editing
        assertEquals(MiniHomePlacementIssue.INVALID_NAME, editing.issue)
        assertFalse(editing.frozen)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `permanent server validation unfreezes draft blocks unchanged save and uses a new corrected operation`() =
        runTest {
            val repository =
                FakeRepository(
                    saveResult =
                        MiniHomeSaveResult.RequiresCorrection(
                            MiniHomeSaveFailure.INVALID_REQUEST,
                            "field=name",
                        )
                )
            var allocated = 0
            val controller =
                MiniHomeController(
                    repository,
                    SavedStateHandle(),
                    operationIdFactory = { OperationId("validation-operation-${++allocated}") },
                )
            controller.start()
            controller.beginEditing()

            controller.save()
            controller.save()

            val rejected = controller.state.value as MiniHomeUiState.Editing
            assertEquals(1, repository.saved.size)
            assertEquals("validation-operation-1", rejected.operationId.value)
            assertTrue(rejected.saveState is MiniHomeSaveState.ValidationFailed)
            assertFalse(rejected.frozen)

            repository.saveResult = null
            controller.rename("수정한 미니 식물원")
            val corrected = controller.state.value as MiniHomeUiState.Editing
            assertEquals("validation-operation-2", corrected.operationId.value)
            assertEquals(MiniHomeSaveState.Idle, corrected.saveState)
            controller.save()

            assertEquals(2, repository.saved.size)
            assertEquals("validation-operation-2", repository.saved.last().operationId.value)
            assertTrue(controller.state.value is MiniHomeUiState.Viewing)
        }

    @Test
    fun `corrected lineage survives process recreation and discard cannot resurrect invalid ancestor`() =
        runTest {
            val savedState = SavedStateHandle()
            val rejectedDraft = authoritative(1).copy(name = "거절된 편집")
            val repository =
                FakeRepository(
                    saveResult =
                        MiniHomeSaveResult.RequiresCorrection(
                            MiniHomeSaveFailure.INVALID_REQUEST,
                            "field=name",
                        )
                )
            var allocated = 0
            val first =
                MiniHomeController(
                    repository,
                    savedState,
                    operationIdFactory = { OperationId("lineage-controller-${++allocated}") },
                )
            first.start()
            first.beginEditing()
            first.rename(rejectedDraft.name)
            first.save()
            val rejected = first.state.value as MiniHomeUiState.Editing
            repository.pending =
                MiniHomePendingSave(
                    rejected.operationId,
                    Revision(1),
                    rejected.draft,
                    MiniHomePendingState.RECONCILIATION_REQUIRED,
                    MiniHomeSaveFailure.INVALID_REQUEST,
                    "field=name",
                    rejected.lineageId,
                    discardHandle =
                        MiniHomeDiscardHandle(
                            AccountId("account-a"),
                            "miniHomeLayouts",
                            rejected.operationId.value,
                            rejected.lineageId.value,
                            "rejected-generation",
                            1,
                        ),
                )
            first.rename("수정한 편집")
            val corrected = first.state.value as MiniHomeUiState.Editing

            val recreated =
                MiniHomeController(
                    repository,
                    savedState,
                    operationIdFactory = { OperationId("lineage-controller-${++allocated}") },
                )
            recreated.start()
            val restored = recreated.state.value as MiniHomeUiState.Editing
            assertEquals(corrected.operationId, restored.operationId)
            assertEquals(rejected.lineageId, restored.lineageId)
            assertEquals(rejected.operationId, restored.supersedesOperationId)
            assertEquals(MiniHomeSaveState.Idle, restored.saveState)

            recreated.discardChanges()

            assertEquals(
                rejected.lineageId.value,
                repository.abandonedHandles.single().rowLineageId,
            )
            assertTrue(recreated.state.value is MiniHomeUiState.Viewing)
            val afterRestart = MiniHomeController(repository, SavedStateHandle())
            afterRestart.start()
            assertTrue(afterRestart.state.value is MiniHomeUiState.Viewing)
        }

    @Test
    fun `quarantined pending discard forwards its true durable handle instead of synthetic IDs`() =
        runTest {
            val handle =
                MiniHomeDiscardHandle(
                    AccountId("account-a"),
                    "miniHomeLayouts",
                    "malformed/row-operation",
                    "malformed/row-lineage",
                    "persisted-row-generation",
                )
            val synthetic = OperationId("synthetic-operation")
            val repository =
                FakeRepository(
                    pending =
                        MiniHomePendingSave(
                            synthetic,
                            Revision(1),
                            authoritative(1),
                            MiniHomePendingState.RECONCILIATION_REQUIRED,
                            MiniHomeSaveFailure.MALFORMED_RESPONSE,
                            discardHandle = handle,
                        )
                )
            val controller = MiniHomeController(repository, SavedStateHandle())
            controller.start()

            controller.discardChanges()

            assertEquals(listOf(handle), repository.abandonedHandles)
            assertTrue(repository.abandoned.isEmpty())
            assertTrue(controller.state.value is MiniHomeUiState.Viewing)
        }

    @Test
    fun `stale discard reloads replacement draft and never reports false success`() = runTest {
        val oldHandle =
            MiniHomeDiscardHandle(
                AccountId("account-a"),
                "miniHomeLayouts",
                "same-operation",
                "same-lineage",
                "generation-1",
                3,
            )
        val replacementHandle = oldHandle.copy(rowHandleId = "generation-2", rowVersion = 0)
        val replacement =
            MiniHomePendingSave(
                OperationId("same-operation"),
                Revision(1),
                authoritative(1).copy(name = "교체된 편집"),
                MiniHomePendingState.RECONCILIATION_REQUIRED,
                MiniHomeSaveFailure.OUTBOX_MISMATCH,
                discardHandle = replacementHandle,
            )
        val repository =
            FakeRepository(
                pending =
                    replacement.copy(
                        layout = authoritative(1).copy(name = "이전 편집"),
                        discardHandle = oldHandle,
                    ),
                discardResult = MiniHomeDiscardResult.StaleHandle(replacement),
            )
        val controller = MiniHomeController(repository, SavedStateHandle())
        controller.start()
        repository.pending = replacement

        controller.discardChanges()

        val editing = controller.state.value as MiniHomeUiState.Editing
        assertEquals("교체된 편집", editing.draft.name)
        assertEquals(replacementHandle, editing.discardHandle)
        assertEquals(MiniHomeDiscardFeedback.STALE_HANDLE, editing.discardFeedback)
        assertTrue(editing.saveState is MiniHomeSaveState.ReconciliationRequired)
    }

    @Test
    fun `missing discard clears only after safe reload proves no replacement`() = runTest {
        val handle =
            MiniHomeDiscardHandle(
                AccountId("account-a"),
                "miniHomeLayouts",
                "missing-operation",
                "missing-lineage",
                "missing-generation",
                1,
            )
        val repository =
            FakeRepository(
                pending =
                    MiniHomePendingSave(
                        OperationId("missing-operation"),
                        Revision(1),
                        authoritative(1),
                        MiniHomePendingState.RECONCILIATION_REQUIRED,
                        MiniHomeSaveFailure.OUTBOX_MISMATCH,
                        discardHandle = handle,
                    ),
                discardResult = MiniHomeDiscardResult.Missing,
            )
        val controller = MiniHomeController(repository, SavedStateHandle())
        controller.start()
        repository.pending = null

        controller.discardChanges()

        assertTrue(controller.state.value is MiniHomeUiState.Viewing)
    }

    @Test
    fun `missing discard reloads concurrent replacement instead of clearing editing`() = runTest {
        val handle =
            MiniHomeDiscardHandle(
                AccountId("account-a"),
                "miniHomeLayouts",
                "missing-aba",
                "missing-aba",
                "gone-generation",
                2,
            )
        val replacement =
            MiniHomePendingSave(
                OperationId("replacement-operation"),
                Revision(1),
                authoritative(1).copy(name = "동시 교체 편집"),
                MiniHomePendingState.RECONCILIATION_REQUIRED,
                MiniHomeSaveFailure.PAYLOAD_MISMATCH,
                discardHandle =
                    handle.copy(
                        rowOperationId = "replacement-operation",
                        rowHandleId = "replacement-generation",
                        rowVersion = 0,
                    ),
            )
        val repository =
            FakeRepository(
                pending =
                    replacement.copy(
                        operationId = OperationId("missing-aba"),
                        discardHandle = handle,
                    ),
                discardResult = MiniHomeDiscardResult.Missing,
            )
        val controller = MiniHomeController(repository, SavedStateHandle())
        controller.start()
        repository.pending = replacement

        controller.discardChanges()

        val editing = controller.state.value as MiniHomeUiState.Editing
        assertEquals("동시 교체 편집", editing.draft.name)
        assertEquals(MiniHomeDiscardFeedback.STALE_HANDLE, editing.discardFeedback)
    }

    @Test
    fun `late discard result cannot clear replacement account after account switch`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val handle =
            MiniHomeDiscardHandle(
                AccountId("account-a"),
                "miniHomeLayouts",
                "account-a-operation",
                "account-a-operation",
                "account-a-generation",
                1,
            )
        val repository =
            FakeRepository(
                pending =
                    MiniHomePendingSave(
                        OperationId("account-a-operation"),
                        Revision(1),
                        authoritative(1).copy(name = "A 편집"),
                        MiniHomePendingState.RECONCILIATION_REQUIRED,
                        MiniHomeSaveFailure.OUTBOX_MISMATCH,
                        discardHandle = handle,
                    ),
                discardGate = gate,
            )
        val controller = MiniHomeController(repository, SavedStateHandle())
        controller.start()

        val discarding = async { controller.discardChanges() }
        repository.discardEntered.await()
        repository.account = AccountId("account-b")
        repository.pending = null
        controller.start()
        gate.complete(Unit)
        assertTrue(runCatching { discarding.await() }.exceptionOrNull() is CancellationException)

        assertEquals(AccountId("account-b"), controller.ownerOrNull())
        assertTrue(controller.state.value is MiniHomeUiState.Viewing)
    }

    @Test
    fun `late conflict adoption cannot write account A draft after account B loads`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val handle =
            MiniHomeDiscardHandle(
                AccountId("account-a"),
                "miniHomeLayouts",
                "account-a-conflict",
                "account-a-conflict",
                "account-a-row",
                1,
            )
        val savedState = SavedStateHandle()
        val repository =
            FakeRepository(
                pending =
                    MiniHomePendingSave(
                        OperationId("account-a-conflict"),
                        Revision(0),
                        authoritative(1).copy(name = "A 충돌 편집"),
                        MiniHomePendingState.PENDING,
                        discardHandle = handle,
                    ),
                discardGate = gate,
            )
        val controller = MiniHomeController(repository, savedState)
        controller.start()
        assertTrue(
            (controller.state.value as MiniHomeUiState.Editing).saveState
                is MiniHomeSaveState.Conflict
        )

        val adopting = async { controller.adoptAuthoritativeAfterConflict() }
        repository.discardEntered.await()
        repository.account = AccountId("account-b")
        repository.pending = null
        controller.start()
        gate.complete(Unit)
        assertTrue(runCatching { adopting.await() }.exceptionOrNull() is CancellationException)

        assertEquals(AccountId("account-b"), controller.ownerOrNull())
        assertTrue(controller.state.value is MiniHomeUiState.Viewing)
        assertEquals(null, savedState.get<String>("mini-home.draft"))
    }

    @Test
    fun `multi validation corrections preserve root lineage and supersede one operation at a time`() =
        runTest {
            val repository =
                FakeRepository(
                    saveResult =
                        MiniHomeSaveResult.RequiresCorrection(MiniHomeSaveFailure.INVALID_REQUEST)
                )
            var allocated = 0
            val controller =
                MiniHomeController(
                    repository,
                    SavedStateHandle(),
                    operationIdFactory = { OperationId("lineage-multi-${++allocated}") },
                )
            controller.start()
            controller.beginEditing()
            controller.save()
            val first = controller.state.value as MiniHomeUiState.Editing
            controller.rename("두 번째")
            val second = controller.state.value as MiniHomeUiState.Editing
            controller.save()
            controller.rename("세 번째")
            val third = controller.state.value as MiniHomeUiState.Editing

            assertEquals(first.lineageId, second.lineageId)
            assertEquals(first.lineageId, third.lineageId)
            assertEquals(first.operationId, second.supersedesOperationId)
            assertEquals(second.operationId, third.supersedesOperationId)
            controller.discardChanges()
            assertEquals(first.lineageId.value, repository.abandonedHandles.single().rowLineageId)
        }

    @Test
    fun `draft survives controller recreation and save commits the exact layout`() = runTest {
        val repository = FakeRepository()
        val savedState = SavedStateHandle()
        val first =
            MiniHomeController(
                repository,
                savedState,
                operationIdFactory = { OperationId("operation-fixed") },
            )
        first.start()
        first.beginEditing()
        first.addPlant(PersonalPlantId("plant-a"))
        first.moveSelected(GridPosition(3, 2))
        val draft = (first.state.value as MiniHomeUiState.Editing).draft

        val restored =
            MiniHomeController(
                repository,
                savedState,
                operationIdFactory = { OperationId("operation-other") },
            )
        restored.start()
        assertEquals(draft, (restored.state.value as MiniHomeUiState.Editing).draft)

        restored.save()

        val viewing = restored.state.value as MiniHomeUiState.Viewing
        assertEquals(Revision(2), viewing.committed.revision)
        assertEquals(draft.placements, viewing.committed.placements)
        assertEquals("operation-fixed", repository.saved.single().operationId.value)
    }

    @Test
    fun `conflict preserves draft and reloads the exact authoritative layout`() = runTest {
        val repository = FakeRepository(saveResult = MiniHomeSaveResult.Conflict(authoritative(5)))
        val controller = MiniHomeController(repository, SavedStateHandle())
        controller.start()
        controller.beginEditing()
        controller.addPlant(PersonalPlantId("plant-a"))
        val draft = (controller.state.value as MiniHomeUiState.Editing).draft

        controller.save()

        val conflict = controller.state.value as MiniHomeUiState.Editing
        assertEquals(draft, conflict.draft)
        assertEquals(Revision(5), conflict.committed.revision)
        assertTrue(conflict.saveState is MiniHomeSaveState.Conflict)
        controller.adoptAuthoritativeAfterConflict()
        assertEquals(authoritative(5), (controller.state.value as MiniHomeUiState.Editing).draft)
    }

    @Test
    fun `unavailable entity reconciles inventory preserves valid edits and never retries fixed request`() =
        runTest {
            val authoritative = authoritative(3)
            val retained =
                MiniHomePlacement(
                    com.planterior.helper.core.model.PlacementId("placement-retained"),
                    MiniHomePlacementTarget.Plant(PersonalPlantId("plant-b")),
                    GridPosition(2, 2),
                    MiniHomeZIndex(0),
                )
            val corrected =
                authoritative.copy(
                    name = "내 편집 이름",
                    placements = listOf(retained),
                )
            val repository =
                FakeRepository(
                    saveResult =
                        MiniHomeSaveResult.RequiresReconciliation(
                            MiniHomeSaveFailure.UNAVAILABLE_ENTITY
                        ),
                    reconcileResult =
                        MiniHomeSaveResult.Reconciled(
                            MiniHomeSaveFailure.UNAVAILABLE_ENTITY,
                            authoritative,
                            listOf(
                                MiniHomePlantChoice(
                                    PersonalPlantId("plant-b"),
                                    "스투키",
                                    null,
                                )
                            ),
                            emptyList(),
                            corrected,
                            removedTargets = 1,
                        ),
                )
            var nextOperation = 0
            val controller =
                MiniHomeController(
                    repository,
                    SavedStateHandle(),
                    operationIdFactory = { OperationId("operation-${++nextOperation}") },
                )
            controller.start()
            controller.beginEditing()
            controller.rename("내 편집 이름")
            controller.addPlant(PersonalPlantId("plant-a"))

            controller.save()
            controller.save()

            assertEquals(1, repository.saved.size)
            val blocked = controller.state.value as MiniHomeUiState.Editing
            assertEquals(
                MiniHomeSaveState.ReconciliationRequired(MiniHomeSaveFailure.UNAVAILABLE_ENTITY),
                blocked.saveState,
            )

            controller.reconcileSaveFailure()

            val reconciled = controller.state.value as MiniHomeUiState.Editing
            assertEquals(corrected, reconciled.draft)
            assertEquals(listOf(PersonalPlantId("plant-b")), reconciled.plants.map { it.id })
            assertEquals(
                MiniHomeSaveState.Corrected(MiniHomeSaveFailure.UNAVAILABLE_ENTITY, 1),
                reconciled.saveState,
            )
            assertEquals("operation-2", reconciled.operationId.value)
            assertFalse(reconciled.frozen)
        }

    @Test
    fun `validation rejection restores editable blocks save and allocates after correction`() =
        runTest {
            val restoredLayout = authoritative(1).copy(name = "서버가 거절한 편집")
            val repository =
                FakeRepository(
                    pending =
                        MiniHomePendingSave(
                            OperationId("validation-fixed"),
                            Revision(1),
                            restoredLayout,
                            MiniHomePendingState.RECONCILIATION_REQUIRED,
                            MiniHomeSaveFailure.INVALID_REQUEST,
                            "field=name;reason=INVALID_REQUEST",
                        )
                )
            var allocated = 0
            val controller =
                MiniHomeController(
                    repository,
                    SavedStateHandle(),
                    operationIdFactory = { OperationId("validation-new-${++allocated}") },
                )

            controller.start()
            controller.save()
            val restored = controller.state.value as MiniHomeUiState.Editing
            assertTrue(restored.saveState is MiniHomeSaveState.ValidationFailed)
            assertFalse(restored.frozen)
            assertTrue(repository.saved.isEmpty())

            controller.rename("수정한 편집")
            val corrected = controller.state.value as MiniHomeUiState.Editing
            assertEquals("validation-new-1", corrected.operationId.value)
            assertEquals(MiniHomeSaveState.Idle, corrected.saveState)
        }

    @Test
    fun `mismatch restored after restart requires reconciliation and allocates once only when safe`() =
        runTest {
            val restoredLayout = authoritative(1).copy(name = "복원한 편집")
            val repository =
                FakeRepository(
                    pending =
                        MiniHomePendingSave(
                            OperationId("operation-fixed"),
                            Revision(1),
                            restoredLayout,
                            MiniHomePendingState.RECONCILIATION_REQUIRED,
                            MiniHomeSaveFailure.OUTBOX_MISMATCH,
                        ),
                    reconcileResult =
                        MiniHomeSaveResult.Reconciled(
                            MiniHomeSaveFailure.OUTBOX_MISMATCH,
                            authoritative(1),
                            listOf(MiniHomePlantChoice(PersonalPlantId("plant-a"), "몬스테라", null)),
                            emptyList(),
                            restoredLayout,
                            removedTargets = 0,
                        ),
                )
            var created = 0
            val controller =
                MiniHomeController(
                    repository,
                    SavedStateHandle(),
                    operationIdFactory = { OperationId("safe-new-${++created}") },
                )

            controller.start()
            controller.save()
            assertEquals(0, repository.saved.size)
            assertEquals(0, created)

            controller.reconcileSaveFailure()
            controller.reconcileSaveFailure()

            val reconciled = controller.state.value as MiniHomeUiState.Editing
            assertEquals("safe-new-1", reconciled.operationId.value)
            assertEquals(1, repository.reconciled.size)
        }

    @Test
    fun `every restored permanent reason blocks save and allocates only after explicit correction`() =
        runTest {
            val permanent =
                listOf(
                    MiniHomeSaveFailure.UNAVAILABLE_ENTITY,
                    MiniHomeSaveFailure.OUTBOX_MISMATCH,
                    MiniHomeSaveFailure.PAYLOAD_MISMATCH,
                    MiniHomeSaveFailure.REVISION_CONFLICT,
                    MiniHomeSaveFailure.PERMISSION_DENIED,
                    MiniHomeSaveFailure.MALFORMED_RESPONSE,
                )

            permanent.forEach { reason ->
                val restoredLayout = authoritative(1).copy(name = "${reason.name} 편집")
                val repository =
                    FakeRepository(
                        pending =
                            MiniHomePendingSave(
                                OperationId("fixed-${reason.name.lowercase()}"),
                                Revision(1),
                                restoredLayout,
                                MiniHomePendingState.RECONCILIATION_REQUIRED,
                                reason,
                                "persisted ${reason.name}",
                            ),
                        reconcileResult =
                            MiniHomeSaveResult.Reconciled(
                                reason,
                                authoritative(2),
                                listOf(
                                    MiniHomePlantChoice(
                                        PersonalPlantId("plant-a"),
                                        "몬스테라",
                                        null,
                                    )
                                ),
                                emptyList(),
                                restoredLayout.copy(revision = Revision(2)),
                                0,
                            ),
                    )
                var allocated = 0
                val controller =
                    MiniHomeController(
                        repository,
                        SavedStateHandle(),
                        operationIdFactory = {
                            OperationId("corrected-${reason.name.lowercase()}-${++allocated}")
                        },
                    )

                controller.start()
                controller.save()
                assertEquals(0, repository.saved.size)
                assertEquals(0, allocated)
                assertEquals(
                    MiniHomeSaveState.ReconciliationRequired(reason),
                    (controller.state.value as MiniHomeUiState.Editing).saveState,
                )

                controller.reconcileSaveFailure()

                assertEquals(1, repository.reconciled.size)
                assertEquals(1, allocated)
                assertTrue(
                    (controller.state.value as MiniHomeUiState.Editing).saveState
                        is MiniHomeSaveState.Corrected
                )
            }
        }

    @Test
    fun `conflict also refreshes authoritative inventory without altering preserved draft`() =
        runTest {
            val freshPlants = listOf(MiniHomePlantChoice(PersonalPlantId("plant-b"), "새 식물", null))
            val freshDecor = listOf(MiniHomeDecorationChoice(ItemId("decor-b"), "새 장식"))
            val repository =
                FakeRepository(
                    saveResult =
                        MiniHomeSaveResult.Conflict(authoritative(5), freshPlants, freshDecor)
                )
            val controller = MiniHomeController(repository, SavedStateHandle())
            controller.start()
            controller.beginEditing()
            controller.addPlant(PersonalPlantId("plant-a"))
            val draft = (controller.state.value as MiniHomeUiState.Editing).draft

            controller.save()

            val conflict = controller.state.value as MiniHomeUiState.Editing
            assertEquals(draft, conflict.draft)
            assertEquals(freshPlants, conflict.plants)
            assertEquals(freshDecor, conflict.decorations)
            assertTrue(conflict.saveState is MiniHomeSaveState.Conflict)
        }

    @Test
    fun `discard waits for paused pre outbox save registration then invalidates its late callback`() =
        runTest {
            val saveGate = CompletableDeferred<Unit>()
            val repository =
                FakeRepository(
                    saveResult = MiniHomeSaveResult.Failed(MiniHomeSaveFailure.DATABASE),
                    saveGate = saveGate,
                )
            val controller = MiniHomeController(repository, SavedStateHandle())
            controller.start()
            controller.beginEditing()
            controller.rename("등록 전 일시정지")
            val saving = async { controller.save() }
            repository.saveEntered.await()

            val discarding = async { controller.discardChanges() }
            runCurrent()

            assertFalse(discarding.isCompleted)
            assertFalse(repository.pendingDiscardEntered.isCompleted)
            saveGate.complete(Unit)
            assertEquals(MiniHomeDiscardResult.Missing, discarding.await())
            saving.await()
            assertTrue(controller.state.value is MiniHomeUiState.Viewing)
            assertEquals(1, repository.pendingDiscardCalls)
        }

    @Test
    fun `remote commit winning save discard race is surfaced and never reported discarded`() =
        runTest {
            val saveGate = CompletableDeferred<Unit>()
            val committed = authoritative(2).copy(name = "경합 중 확정된 편집")
            val repository =
                FakeRepository(
                    saveResult = MiniHomeSaveResult.Saved(committed),
                    saveGate = saveGate,
                )
            val controller = MiniHomeController(repository, SavedStateHandle())
            controller.start()
            controller.beginEditing()
            controller.rename("경합 중 확정된 편집")
            val saving = async { controller.save() }
            repository.saveEntered.await()

            val discarding = async { controller.discardChanges() }
            runCurrent()
            assertFalse(discarding.isCompleted)
            saveGate.complete(Unit)

            assertEquals(MiniHomeDiscardResult.Committed(committed), discarding.await())
            saving.await()
            val viewing = controller.state.value as MiniHomeUiState.Viewing
            assertEquals(committed, viewing.committed)
            assertTrue(viewing.saved)
            assertEquals(0, repository.pendingDiscardCalls)
        }

    @Test
    fun `double save and discard share one registered operation and one authoritative decision`() =
        runTest {
            val saveGate = CompletableDeferred<Unit>()
            val repository =
                FakeRepository(
                    saveResult = MiniHomeSaveResult.Failed(MiniHomeSaveFailure.DATABASE),
                    saveGate = saveGate,
                )
            val controller = MiniHomeController(repository, SavedStateHandle())
            controller.start()
            controller.beginEditing()
            controller.rename("중복 입력 편집")
            val firstSave = async { controller.save() }
            repository.saveEntered.await()
            val secondSave = async { controller.save() }
            val firstDiscard = async { controller.discardChanges() }
            val secondDiscard = async { controller.discardChanges() }
            runCurrent()

            assertEquals(1, repository.saved.size)
            assertTrue(secondSave.isCompleted)
            assertEquals(MiniHomeDiscardResult.Rejected, secondDiscard.await())
            saveGate.complete(Unit)
            assertEquals(MiniHomeDiscardResult.Missing, firstDiscard.await())
            firstSave.await()
            assertTrue(controller.state.value is MiniHomeUiState.Viewing)
            assertEquals(1, repository.pendingDiscardCalls)
        }

    @Test
    fun `account switch invalidates paused save and discard without crossing owner`() = runTest {
        val saveGate = CompletableDeferred<Unit>()
        val repository =
            FakeRepository(
                saveResult = MiniHomeSaveResult.Failed(MiniHomeSaveFailure.DATABASE),
                saveGate = saveGate,
            )
        val controller = MiniHomeController(repository, SavedStateHandle())
        controller.start()
        controller.beginEditing()
        controller.rename("A 경합 편집")
        val saving = async { controller.save() }
        repository.saveEntered.await()
        val discarding = async { controller.discardChanges() }
        runCurrent()

        repository.account = AccountId("account-b")
        repository.pending = null
        controller.start()
        saveGate.complete(Unit)

        assertTrue(runCatching { saving.await() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { discarding.await() }.exceptionOrNull() is CancellationException)
        assertEquals(AccountId("account-b"), controller.ownerOrNull())
        assertTrue(controller.state.value is MiniHomeUiState.Viewing)
    }

    @Test
    fun `pre outbox database failure exits as missing only after authoritative no row query`() =
        runTest {
            val repository =
                FakeRepository(saveResult = MiniHomeSaveResult.Failed(MiniHomeSaveFailure.DATABASE))
            val controller = MiniHomeController(repository, SavedStateHandle())
            controller.start()
            controller.beginEditing()
            controller.rename("로컬 저장 실패 편집")
            controller.save()

            val result = controller.discardChanges()

            assertEquals(MiniHomeDiscardResult.Missing, result)
            assertEquals(1, repository.pendingDiscardCalls)
            assertTrue(repository.abandonedHandles.isEmpty())
            assertEquals(null, repository.pending)
            assertTrue(controller.state.value is MiniHomeUiState.Viewing)
        }

    @Test
    fun `handleless authoritative query failure is rejected and keeps retryable editing`() =
        runTest {
            val repository =
                FakeRepository(
                    saveResult = MiniHomeSaveResult.Failed(MiniHomeSaveFailure.DATABASE),
                    pendingDiscardThrows = true,
                )
            val controller = MiniHomeController(repository, SavedStateHandle())
            controller.start()
            controller.beginEditing()
            controller.rename("조회 실패 편집")
            controller.save()

            val result = controller.discardChanges()

            val editing = controller.state.value as MiniHomeUiState.Editing
            assertEquals(MiniHomeDiscardResult.Rejected, result)
            assertEquals("조회 실패 편집", editing.draft.name)
            assertEquals(MiniHomeDiscardFeedback.RETRY_REQUIRED, editing.discardFeedback)
            assertTrue(editing.saveState is MiniHomeSaveState.Failed)
        }

    @Test
    fun `row appearing during handleless lookup is CAS discarded before editing closes`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository =
                FakeRepository(
                    saveResult = MiniHomeSaveResult.Failed(MiniHomeSaveFailure.DATABASE),
                    pendingDiscardGate = gate,
                )
            val controller = MiniHomeController(repository, SavedStateHandle())
            controller.start()
            controller.beginEditing()
            controller.rename("동시 생성 전 편집")
            controller.save()

            val discarding = async { controller.discardChanges() }
            repository.pendingDiscardEntered.await()
            val handle =
                MiniHomeDiscardHandle(
                    AccountId("account-a"),
                    "miniHomeLayouts",
                    "concurrent-operation",
                    "concurrent-operation",
                    "concurrent-generation",
                    0,
                )
            repository.pending =
                MiniHomePendingSave(
                    OperationId("concurrent-operation"),
                    Revision(1),
                    authoritative(1).copy(name = "동시 생성된 행"),
                    MiniHomePendingState.MAY_HAVE_COMMITTED,
                    MiniHomeSaveFailure.DATABASE,
                    discardHandle = handle,
                )
            gate.complete(Unit)

            val result = discarding.await()

            assertEquals(MiniHomeDiscardResult.Consumed, result)
            assertEquals(listOf(handle), repository.abandonedHandles)
            assertEquals(null, repository.pending)
            assertTrue(controller.state.value is MiniHomeUiState.Viewing)
        }

    @Test
    fun `late handleless lookup cannot exit the replacement account`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository =
            FakeRepository(
                saveResult = MiniHomeSaveResult.Failed(MiniHomeSaveFailure.DATABASE),
                pendingDiscardGate = gate,
            )
        val controller = MiniHomeController(repository, SavedStateHandle())
        controller.start()
        controller.beginEditing()
        controller.rename("A 로컬 실패")
        controller.save()

        val discarding = async { controller.discardChanges() }
        repository.pendingDiscardEntered.await()
        repository.account = AccountId("account-b")
        repository.pending = null
        controller.start()
        gate.complete(Unit)

        assertTrue(runCatching { discarding.await() }.exceptionOrNull() is CancellationException)
        assertEquals(AccountId("account-b"), controller.ownerOrNull())
        assertTrue(controller.state.value is MiniHomeUiState.Viewing)
    }

    @Test
    fun `transient save exposes durable handle and restart discard removes pending row before exit`() =
        runTest {
            val handle =
                MiniHomeDiscardHandle(
                    AccountId("account-a"),
                    "miniHomeLayouts",
                    "transient-operation",
                    "transient-operation",
                    "transient-generation",
                    4,
                )
            val repository =
                FakeRepository(
                    saveResult = MiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK, handle)
                )
            val savedState = SavedStateHandle()
            val first =
                MiniHomeController(
                    repository,
                    savedState,
                    operationIdFactory = { OperationId("transient-operation") },
                )
            first.start()
            first.beginEditing()
            first.rename("응답 유실 편집")

            first.save()

            assertEquals(handle, (first.state.value as MiniHomeUiState.Editing).discardHandle)
            repository.pending =
                MiniHomePendingSave(
                    OperationId("transient-operation"),
                    Revision(1),
                    authoritative(1).copy(name = "응답 유실 편집"),
                    MiniHomePendingState.MAY_HAVE_COMMITTED,
                    MiniHomeSaveFailure.NETWORK,
                    lineageId = OperationId("transient-operation"),
                    discardHandle = handle,
                )
            val restarted = MiniHomeController(repository, savedState)
            restarted.start()

            val result = restarted.discardChanges()

            assertEquals(MiniHomeDiscardResult.Consumed, result)
            assertEquals(listOf(handle), repository.abandonedHandles)
            assertEquals(null, repository.pending)
            assertTrue(restarted.state.value is MiniHomeUiState.Viewing)
        }

    @Test
    fun `missing transient handle discovers and CAS consumes the authoritative pending row`() =
        runTest {
            val replacementHandle =
                MiniHomeDiscardHandle(
                    AccountId("account-a"),
                    "miniHomeLayouts",
                    "replacement-operation",
                    "replacement-operation",
                    "replacement-generation",
                    0,
                )
            val repository =
                FakeRepository(saveResult = MiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK))
            val controller = MiniHomeController(repository, SavedStateHandle())
            controller.start()
            controller.beginEditing()
            controller.rename("전송한 편집")
            controller.save()
            repository.pending =
                MiniHomePendingSave(
                    OperationId("replacement-operation"),
                    Revision(1),
                    authoritative(1).copy(name = "교체된 전송 편집"),
                    MiniHomePendingState.MAY_HAVE_COMMITTED,
                    MiniHomeSaveFailure.NETWORK,
                    discardHandle = replacementHandle,
                )

            val result = controller.discardChanges()

            assertEquals(MiniHomeDiscardResult.Consumed, result)
            assertEquals(listOf(replacementHandle), repository.abandonedHandles)
            assertEquals(null, repository.pending)
            assertTrue(controller.state.value is MiniHomeUiState.Viewing)
        }

    @Test
    fun `non cancellable conflict abandon is joined and cannot mutate loaded account B`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val handle = conflictHandle("joined-conflict")
            val savedState = SavedStateHandle()
            val repository =
                FakeRepository(
                    pending = conflictPending(handle, "A 지연 충돌"),
                    discardGate = gate,
                    discardNonCancellable = true,
                )
            val controller = MiniHomeController(repository, savedState)
            controller.start()
            val adopting = async { controller.adoptAuthoritativeAfterConflict() }
            repository.discardEntered.await()

            repository.account = AccountId("account-b")
            repository.pending = null
            val switching = async { controller.start() }
            runCurrent()
            assertFalse(switching.isCompleted)
            gate.complete(Unit)
            switching.await()
            assertTrue(runCatching { adopting.await() }.exceptionOrNull() is CancellationException)

            assertEquals(AccountId("account-b"), controller.ownerOrNull())
            assertTrue(controller.state.value is MiniHomeUiState.Viewing)
            assertEquals(null, savedState.get<String>("mini-home.draft"))
            assertEquals(listOf(handle), repository.abandonedHandles)
        }

    @Test
    fun `new controller epoch rejects old process recreation result before SavedState write`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val handle = conflictHandle("recreated-conflict")
            val savedState = SavedStateHandle()
            val repository =
                FakeRepository(
                    pending = conflictPending(handle, "A 프로세스 편집"),
                    discardGate = gate,
                    discardNonCancellable = true,
                )
            val oldController = MiniHomeController(repository, savedState)
            oldController.start()
            val adopting = async { oldController.adoptAuthoritativeAfterConflict() }
            repository.discardEntered.await()

            repository.account = AccountId("account-b")
            repository.pending = null
            val recreated = MiniHomeController(repository, savedState)
            recreated.start()
            recreated.beginEditing()
            recreated.rename("B 프로세스 편집")
            gate.complete(Unit)
            adopting.await()

            val persisted =
                requireNotNull(MiniHomeDraftCodec.decode(savedState.get<String>("mini-home.draft")))
            assertEquals(AccountId("account-b"), persisted.owner)
            assertEquals("B 프로세스 편집", persisted.layout.name)
            assertEquals(AccountId("account-b"), recreated.ownerOrNull())
        }

    @Test
    fun `out of order account B load cannot replace newer A load`() = runTest {
        val repository = FakeRepository()
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start()
        val loadB = repository.enqueueLoad()
        val loadA = repository.enqueueLoad()

        val loadingB = async { controller.start() }
        loadB.entered.await()
        val loadingA = async { controller.start() }
        loadA.entered.await()
        loadA.result.complete(repository.loadResult(AccountId("account-a")))
        loadingA.await()
        loadB.result.complete(repository.loadResult(AccountId("account-b")))
        loadingB.await()

        assertEquals(AccountId("account-a"), controller.ownerOrNull())
        assertTrue(controller.state.value is MiniHomeUiState.Viewing)
        assertEquals(null, savedState.get<String>("mini-home.draft"))
    }

    @Test
    fun `deferred logout clears only account A draft and rejects late owner work`() = runTest {
        val repository = FakeRepository()
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start()
        controller.beginEditing()
        controller.rename("A 로그아웃 전 편집")
        val logout = repository.enqueueLoad()

        val loggingOut = async { controller.start() }
        logout.entered.await()
        logout.result.complete(MiniHomeLoadResult.Forbidden)
        loggingOut.await()

        assertEquals(null, controller.ownerOrNull())
        assertEquals(MiniHomeUiState.Forbidden, controller.state.value)
        assertEquals(null, savedState.get<String>("mini-home.draft"))
    }

    @Test
    fun `non cancellable late save is joined without writing account B state or draft`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository(saveGate = gate, saveNonCancellable = true)
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start()
        controller.beginEditing()
        controller.rename("A 지연 저장")
        val saving = async { controller.save() }
        repository.saveEntered.await()

        repository.account = AccountId("account-b")
        val switching = async { controller.start() }
        runCurrent()
        assertFalse(switching.isCompleted)
        gate.complete(Unit)
        switching.await()
        assertTrue(runCatching { saving.await() }.exceptionOrNull() is CancellationException)

        assertEquals(listOf(AccountId("account-a")), repository.saved.map { it.accountId })
        assertEquals(AccountId("account-b"), controller.ownerOrNull())
        assertTrue(controller.state.value is MiniHomeUiState.Viewing)
        assertEquals(null, savedState.get<String>("mini-home.draft"))
    }

    @Test
    fun `non cancellable reconciliation is joined before account B becomes visible`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository =
            FakeRepository(
                saveResult =
                    MiniHomeSaveResult.RequiresReconciliation(
                        MiniHomeSaveFailure.UNAVAILABLE_ENTITY
                    ),
                reconcileResult =
                    MiniHomeSaveResult.Reconciled(
                        MiniHomeSaveFailure.UNAVAILABLE_ENTITY,
                        authoritative(2),
                        emptyList(),
                        emptyList(),
                        authoritative(2),
                        0,
                    ),
                reconcileGate = gate,
                reconcileNonCancellable = true,
            )
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start()
        controller.beginEditing()
        controller.save()
        val reconciling = async { controller.reconcileSaveFailure() }
        repository.reconcileEntered.await()

        repository.account = AccountId("account-b")
        repository.pending = null
        val switching = async { controller.start() }
        runCurrent()
        assertFalse(switching.isCompleted)
        gate.complete(Unit)
        switching.await()
        assertTrue(runCatching { reconciling.await() }.exceptionOrNull() is CancellationException)

        assertEquals(listOf(AccountId("account-a")), repository.reconciled.map { it.accountId })
        assertEquals(AccountId("account-b"), controller.ownerOrNull())
        assertTrue(controller.state.value is MiniHomeUiState.Viewing)
        assertEquals(null, savedState.get<String>("mini-home.draft"))
    }

    @Test
    fun `late inventory reload after stale discard cannot restore account A replacement`() =
        runTest {
            val handle = conflictHandle("inventory-discard")
            val replacement = conflictPending(handle, "A 교체 편집")
            val repository =
                FakeRepository(
                    pending = replacement,
                    discardResult = MiniHomeDiscardResult.StaleHandle(replacement),
                )
            val savedState = SavedStateHandle()
            val controller = MiniHomeController(repository, savedState)
            controller.start()
            val reload = repository.enqueueLoad(nonCancellable = true)
            val discarding = async { controller.discardChanges() }
            reload.entered.await()

            repository.account = AccountId("account-b")
            repository.pending = null
            val switching = async { controller.start() }
            runCurrent()
            assertFalse(switching.isCompleted)
            reload.result.complete(repository.loadResult(AccountId("account-a"), replacement))
            switching.await()
            assertTrue(
                runCatching { discarding.await() }.exceptionOrNull() is CancellationException
            )

            assertEquals(AccountId("account-b"), controller.ownerOrNull())
            assertTrue(controller.state.value is MiniHomeUiState.Viewing)
            assertEquals(null, savedState.get<String>("mini-home.draft"))
        }

    @Test
    fun `late save callback cannot cross an account change`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository(saveGate = gate)
        val controller = MiniHomeController(repository, SavedStateHandle())
        controller.start()
        controller.beginEditing()
        controller.addPlant(PersonalPlantId("plant-a"))
        val saving = async { controller.save() }
        repository.saveEntered.await()
        repository.account = AccountId("account-b")
        controller.start()
        gate.complete(Unit)
        assertTrue(runCatching { saving.await() }.exceptionOrNull() is CancellationException)

        assertNotEquals(AccountId("account-a"), controller.ownerOrNull())
        assertTrue(controller.state.value is MiniHomeUiState.Viewing)
    }

    @Test
    fun `same owner resume before remote commit waits for the correlated save publication`() =
        runTest {
            val saveGate = CompletableDeferred<Unit>()
            val repository = FakeRepository(saveGate = saveGate)
            val savedState = SavedStateHandle()
            val controller = MiniHomeController(repository, savedState)
            controller.start()
            val establishedSession = controller.session.value
            controller.beginEditing()
            controller.rename("resume 중 저장")
            val saving = async { controller.save() }
            repository.saveEntered.await()
            val staleLoad = repository.enqueueLoad()
            val resuming = async { controller.start() }
            staleLoad.entered.await()
            staleLoad.result.complete(repository.loadResult(AccountId("account-a")))
            runCurrent()

            assertFalse(resuming.isCompleted)
            assertEquals(establishedSession, controller.session.value)
            saveGate.complete(Unit)
            saving.await()
            resuming.await()

            val viewing = controller.state.value as MiniHomeUiState.Viewing
            assertEquals(Revision(2), viewing.committed.revision)
            assertEquals("resume 중 저장", viewing.committed.name)
            assertTrue(viewing.saved)
            assertEquals(null, savedState.get<String>("mini-home.draft"))
            assertEquals(establishedSession, controller.session.value)
        }

    @Test
    fun `same owner resume after commit cannot regress confirmed layout to a stale load`() =
        runTest {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            val controller = MiniHomeController(repository, savedState)
            controller.start()
            val establishedSession = controller.session.value
            controller.beginEditing()
            controller.rename("확정된 revision 2")
            controller.save()
            val staleLoad = repository.enqueueLoad()

            val resuming = async { controller.start() }
            staleLoad.entered.await()
            staleLoad.result.complete(repository.loadResult(AccountId("account-a")))
            resuming.await()

            val viewing = controller.state.value as MiniHomeUiState.Viewing
            assertEquals(Revision(2), viewing.committed.revision)
            assertEquals("확정된 revision 2", viewing.committed.name)
            assertTrue(viewing.saved)
            assertEquals(establishedSession, controller.session.value)
        }

    @Test
    fun `repeated same owner resumes preserve one session and confirmed receipt`() = runTest {
        val repository = FakeRepository()
        val controller = MiniHomeController(repository, SavedStateHandle())
        controller.start()
        val establishedSession = controller.session.value
        controller.beginEditing()
        controller.rename("반복 resume 확정")
        controller.save()

        repeat(3) {
            val staleLoad = repository.enqueueLoad()
            val resuming = async { controller.start() }
            staleLoad.entered.await()
            staleLoad.result.complete(repository.loadResult(AccountId("account-a")))
            resuming.await()
            val viewing = controller.state.value as MiniHomeUiState.Viewing
            assertEquals(Revision(2), viewing.committed.revision)
            assertEquals("반복 resume 확정", viewing.committed.name)
            assertEquals(establishedSession, controller.session.value)
        }
    }

    @Test
    fun `load captured before save cannot publish after the newer save receipt`() = runTest {
        val repository = FakeRepository()
        val controller = MiniHomeController(repository, SavedStateHandle())
        controller.start()
        val staleLoad = repository.enqueueLoad(nonCancellable = true)
        val resuming = async { controller.start() }
        staleLoad.entered.await()
        controller.beginEditing()
        controller.rename("load보다 최신 저장")
        controller.save()

        staleLoad.result.complete(repository.loadResult(AccountId("account-a")))
        resuming.await()

        val viewing = controller.state.value as MiniHomeUiState.Viewing
        assertEquals(Revision(2), viewing.committed.revision)
        assertEquals("load보다 최신 저장", viewing.committed.name)
        assertTrue(viewing.saved)
    }

    @Test
    fun `confirmed save correlation survives controller recreation with stale repository data`() =
        runTest {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            val first = MiniHomeController(repository, savedState)
            first.start()
            first.beginEditing()
            first.rename("재생성 전 확정 저장")
            first.save()
            val staleLoad = repository.enqueueLoad()

            val recreated = MiniHomeController(repository, savedState)
            val loading = async { recreated.start() }
            staleLoad.entered.await()
            staleLoad.result.complete(repository.loadResult(AccountId("account-a")))
            loading.await()

            val viewing = recreated.state.value as MiniHomeUiState.Viewing
            assertEquals(Revision(2), viewing.committed.revision)
            assertEquals("재생성 전 확정 저장", viewing.committed.name)
            assertTrue(viewing.saved)
            assertEquals(null, savedState.get<String>("mini-home.draft"))
        }

    @Test
    fun `same owner resume keeps a newer edit distinct from the previous confirmed operation`() =
        runTest {
            val repository = FakeRepository()
            val controller = MiniHomeController(repository, SavedStateHandle())
            controller.start()
            controller.beginEditing()
            controller.rename("첫 확정")
            controller.save()
            controller.beginEditing()
            controller.rename("확정 뒤 새 편집")
            val newer = controller.state.value as MiniHomeUiState.Editing
            val staleLoad = repository.enqueueLoad()

            val resuming = async { controller.start() }
            staleLoad.entered.await()
            staleLoad.result.complete(repository.loadResult(AccountId("account-a")))
            resuming.await()

            val restored = controller.state.value as MiniHomeUiState.Editing
            assertEquals(newer.operationId, restored.operationId)
            assertEquals(newer.draft, restored.draft)
            assertEquals(Revision(2), restored.committed.revision)
            assertEquals(MiniHomeSaveState.Idle, restored.saveState)
        }

    @Test
    fun `response loss resume adopts only the exact committed operation receipt`() = runTest {
        val repository =
            FakeRepository(saveResult = MiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK))
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start()
        controller.beginEditing()
        controller.rename("응답 유실 후 확정")
        val submitted = controller.state.value as MiniHomeUiState.Editing
        controller.save()
        val committed =
            submitted.draft.copy(
                revision = submitted.committed.revision.next(),
                updatedAt = Instant.parse("2026-08-12T00:01:00Z"),
            )
        val receipt =
            MiniHomeCommittedReceipt(
                submitted.operationId,
                submitted.committed.revision,
                committed.revision,
                MiniHomePayloadHash.create(submitted.committed.revision, submitted.draft),
            )
        val recoveredLoad = repository.enqueueLoad()
        val resuming = async { controller.start() }
        recoveredLoad.entered.await()
        recoveredLoad.result.complete(
            repository.loadResult(
                AccountId("account-a"),
                committedLayout = committed,
                committedReceipt = receipt,
            )
        )
        resuming.await()

        val viewing = controller.state.value as MiniHomeUiState.Viewing
        assertEquals(committed, viewing.committed)
        assertTrue(viewing.saved)
        assertEquals(submitted.operationId, viewing.exitOutcome?.operationId)
        assertEquals(null, savedState.get<String>("mini-home.draft"))
    }

    @Test
    fun `resume started before response loss adopts receipt after the save settles`() = runTest {
        val saveGate = CompletableDeferred<Unit>()
        val repository =
            FakeRepository(
                saveResult = MiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK),
                saveGate = saveGate,
            )
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start()
        controller.beginEditing()
        controller.rename("진행 중 응답 유실")
        val submitted = controller.state.value as MiniHomeUiState.Editing
        val saving = async { controller.save() }
        repository.saveEntered.await()
        val committed = submitted.draft.copy(revision = submitted.committed.revision.next())
        val receiptLoad = repository.enqueueLoad()
        val resuming = async { controller.start() }
        receiptLoad.entered.await()
        receiptLoad.result.complete(
            repository.loadResult(
                AccountId("account-a"),
                committedLayout = committed,
                committedReceipt =
                    MiniHomeCommittedReceipt(
                        submitted.operationId,
                        submitted.committed.revision,
                        committed.revision,
                        MiniHomePayloadHash.create(
                            submitted.committed.revision,
                            submitted.draft,
                        ),
                    ),
            )
        )
        runCurrent()
        assertFalse(resuming.isCompleted)

        saveGate.complete(Unit)
        saving.await()
        resuming.await()

        val viewing = controller.state.value as MiniHomeUiState.Viewing
        assertEquals(committed, viewing.committed)
        assertTrue(viewing.saved)
        assertEquals(submitted.operationId, viewing.exitOutcome?.operationId)
        assertEquals(null, savedState.get<String>("mini-home.draft"))
    }

    @Test
    fun `mismatched response loss receipt cannot clear the preserved draft`() = runTest {
        val repository =
            FakeRepository(saveResult = MiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK))
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start()
        controller.beginEditing()
        controller.rename("응답 유실 보존")
        val submitted = controller.state.value as MiniHomeUiState.Editing
        controller.save()
        val committed = submitted.draft.copy(revision = submitted.committed.revision.next())
        val recoveredLoad = repository.enqueueLoad()
        val resuming = async { controller.start() }
        recoveredLoad.entered.await()
        recoveredLoad.result.complete(
            repository.loadResult(
                AccountId("account-a"),
                committedLayout = committed,
                committedReceipt =
                    MiniHomeCommittedReceipt(
                        OperationId("different-operation"),
                        submitted.committed.revision,
                        committed.revision,
                        MiniHomePayloadHash.create(submitted.committed.revision, submitted.draft),
                    ),
            )
        )
        resuming.await()

        val editing = controller.state.value as MiniHomeUiState.Editing
        assertEquals(submitted.operationId, editing.operationId)
        assertEquals(submitted.draft, editing.draft)
        assertTrue(editing.saveState is MiniHomeSaveState.Conflict)
        assertTrue(savedState.get<String>("mini-home.draft") != null)
    }

    @Test
    fun `actual owner change still advances generation and rejects former confirmed state`() =
        runTest {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            val controller = MiniHomeController(repository, savedState)
            controller.start()
            controller.beginEditing()
            controller.rename("A 확정 저장")
            controller.save()
            val accountASession = controller.session.value
            repository.account = AccountId("account-b")

            controller.start()

            assertEquals(AccountId("account-b"), controller.ownerOrNull())
            assertEquals(accountASession.generation + 1, controller.session.value.generation)
            assertEquals(AccountId("account-b"), controller.session.value.owner)
            assertEquals(
                Revision(1),
                (controller.state.value as MiniHomeUiState.Viewing).committed.revision,
            )
            assertEquals(null, savedState.get<String>("mini-home.draft"))
        }

    @Test
    fun `A editing is removed before B load and B failure is owner scoped`() = runTest {
        val repository = FakeRepository()
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start(authenticated("account-a"))
        controller.beginEditing()
        controller.rename("A 비밀 편집")
        controller.addPlant(PersonalPlantId("a-private-plant"))
        val failedB = repository.enqueueLoad()
        repository.account = AccountId("account-b")

        val switching = async { controller.start(authenticated("account-b")) }
        failedB.entered.await()

        assertEquals(
            MiniHomeUiState.Loading(AccountId("account-b")),
            controller.state.value,
        )
        assertOwnerBHasNoAccountAData(controller, savedState)
        failedB.result.complete(MiniHomeLoadResult.Failed)
        switching.await()

        assertEquals(
            MiniHomeUiState.Unavailable(AccountId("account-b")),
            controller.state.value,
        )
        assertOwnerBHasNoAccountAData(controller, savedState)
    }

    @Test
    fun `A viewing is removed before B load failure`() = runTest {
        val repository = FakeRepository()
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start(authenticated("account-a"))
        controller.beginEditing()
        controller.rename("A 비밀 확정 화면")
        controller.save()
        val failedB = repository.enqueueLoad()
        repository.account = AccountId("account-b")

        val switching = async { controller.start(authenticated("account-b")) }
        failedB.entered.await()

        assertEquals(
            MiniHomeUiState.Loading(AccountId("account-b")),
            controller.state.value,
        )
        assertOwnerBHasNoAccountAData(controller, savedState)
        failedB.result.complete(MiniHomeLoadResult.Failed)
        switching.await()

        assertEquals(
            MiniHomeUiState.Unavailable(AccountId("account-b")),
            controller.state.value,
        )
        assertOwnerBHasNoAccountAData(controller, savedState)
    }

    @Test
    fun `A pending save is canceled and joined before B repository load`() = runTest {
        val saveGate = CompletableDeferred<Unit>()
        val repository = FakeRepository(saveGate = saveGate, saveNonCancellable = true)
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start(authenticated("account-a"))
        controller.beginEditing()
        controller.rename("A 비밀 저장 중")
        controller.addPlant(PersonalPlantId("a-private-plant"))
        val saving = async { controller.save() }
        repository.saveEntered.await()
        val failedB = repository.enqueueLoad()
        repository.account = AccountId("account-b")

        val switching = async { controller.start(authenticated("account-b")) }
        runCurrent()

        assertEquals(
            MiniHomeUiState.Loading(AccountId("account-b")),
            controller.state.value,
        )
        assertOwnerBHasNoAccountAData(controller, savedState)
        assertFalse(failedB.entered.isCompleted)
        assertFalse(switching.isCompleted)
        saveGate.complete(Unit)
        failedB.entered.await()
        failedB.result.complete(MiniHomeLoadResult.Failed)
        switching.await()
        assertTrue(runCatching { saving.await() }.exceptionOrNull() is CancellationException)

        assertEquals(
            MiniHomeUiState.Unavailable(AccountId("account-b")),
            controller.state.value,
        )
        assertOwnerBHasNoAccountAData(controller, savedState)
    }

    @Test
    fun `A to B failure then A return cannot resurrect A private state`() = runTest {
        val repository = FakeRepository()
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start(authenticated("account-a"))
        controller.beginEditing()
        controller.rename("A 비밀 ABA 편집")
        controller.addPlant(PersonalPlantId("a-private-plant"))
        val failedB = repository.enqueueLoad()
        repository.account = AccountId("account-b")
        val switchingB = async { controller.start(authenticated("account-b")) }
        failedB.entered.await()
        failedB.result.complete(MiniHomeLoadResult.Failed)
        switchingB.await()
        assertOwnerBHasNoAccountAData(controller, savedState)
        val returningA = repository.enqueueLoad()

        val switchingA = async { controller.start(authenticated("account-a")) }
        returningA.entered.await()
        returningA.result.complete(repository.loadResult(AccountId("account-a")))
        switchingA.await()

        assertTrue(controller.state.value is MiniHomeUiState.Viewing)
        val exposed = controller.state.value.toString()
        assertFalse(exposed.contains("A 비밀 ABA 편집"))
        assertFalse(exposed.contains("a-private-plant"))
        assertEquals(null, savedState.get<String>("mini-home.draft"))
    }

    @Test
    fun `B offline cache may replace neutral transition state after A is cleared`() = runTest {
        val repository = FakeRepository()
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start(authenticated("account-a"))
        controller.beginEditing()
        controller.rename("A 비밀 오프라인 전 편집")
        controller.addPlant(PersonalPlantId("a-private-plant"))
        val cachedB = repository.enqueueLoad()
        val bLayout =
            authoritative(4)
                .copy(
                    name = "B 오프라인 캐시",
                    placements =
                        listOf(
                            MiniHomePlacement(
                                PlacementId("b-placement"),
                                MiniHomePlacementTarget.Plant(PersonalPlantId("b-plant")),
                                GridPosition(1, 1),
                                MiniHomeZIndex(0),
                            )
                        ),
                )
        repository.account = AccountId("account-b")

        val switching = async { controller.start(authenticated("account-b")) }
        cachedB.entered.await()
        assertOwnerBHasNoAccountAData(controller, savedState)
        cachedB.result.complete(
            repository.loadResult(
                AccountId("account-b"),
                committedLayout = bLayout,
                stale = true,
            )
        )
        switching.await()

        val viewing = controller.state.value as MiniHomeUiState.Viewing
        assertEquals("B 오프라인 캐시", viewing.committed.name)
        assertEquals(listOf("b-placement"), viewing.committed.placements.map { it.id.value })
        assertTrue(viewing.stale)
        assertFalse(viewing.toString().contains("A 비밀 오프라인 전 편집"))
        assertFalse(viewing.toString().contains("a-private-plant"))
    }

    @Test
    fun `logout neutralizes A immediately before joining pending owner work`() = runTest {
        val saveGate = CompletableDeferred<Unit>()
        val repository = FakeRepository(saveGate = saveGate, saveNonCancellable = true)
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start(authenticated("account-a"))
        controller.beginEditing()
        controller.rename("A 비밀 로그아웃 편집")
        val saving = async { controller.save() }
        repository.saveEntered.await()

        val signingOut = async { controller.start(MiniHomeAuthOwnership.SignedOut) }
        runCurrent()

        assertEquals(MiniHomeUiState.Forbidden, controller.state.value)
        assertEquals(null, controller.ownerOrNull())
        assertFalse(controller.state.value.toString().contains("A 비밀 로그아웃 편집"))
        assertEquals(null, savedState.get<String>("mini-home.draft"))
        assertEquals(null, savedState.get<String>("mini-home.confirmed-save"))
        assertEquals(null, savedState.get<String>("mini-home.restoration-owner"))
        assertFalse(signingOut.isCompleted)
        saveGate.complete(Unit)
        signingOut.await()
        assertTrue(runCatching { saving.await() }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `restoring and unknown defer A while later B failure fails closed`() = runTest {
        val repository = FakeRepository()
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start(authenticated("account-a"))
        controller.beginEditing()
        controller.rename("A 비밀 인증 미확정 편집")
        val exactA = controller.state.value
        val untouchedA = savedState.get<String>("mini-home.draft")
        val failedB = repository.enqueueLoad()

        controller.start(MiniHomeAuthOwnership.Restoring)
        controller.start(MiniHomeAuthOwnership.Unknown)

        assertEquals(exactA, controller.state.value)
        assertEquals(untouchedA, savedState.get<String>("mini-home.draft"))
        assertFalse(failedB.entered.isCompleted)
        repository.account = AccountId("account-b")
        val switching = async { controller.start(authenticated("account-b")) }
        failedB.entered.await()
        assertOwnerBHasNoAccountAData(controller, savedState)
        failedB.result.complete(MiniHomeLoadResult.Failed)
        switching.await()
        assertEquals(
            MiniHomeUiState.Unavailable(AccountId("account-b")),
            controller.state.value,
        )
        assertOwnerBHasNoAccountAData(controller, savedState)
    }

    @Test
    fun `same owner load failure preserves correlated A editing state`() = runTest {
        val repository = FakeRepository()
        val savedState = SavedStateHandle()
        val controller = MiniHomeController(repository, savedState)
        controller.start(authenticated("account-a"))
        controller.beginEditing()
        controller.rename("A 동일 소유자 실패 보존")
        controller.addPlant(PersonalPlantId("a-private-plant"))
        val exactA = controller.state.value
        val exactDraft = savedState.get<String>("mini-home.draft")
        val establishedSession = controller.session.value
        val failedA = repository.enqueueLoad()

        val refreshing = async { controller.start(authenticated("account-a")) }
        failedA.entered.await()
        failedA.result.complete(MiniHomeLoadResult.Failed)
        refreshing.await()

        assertEquals(exactA, controller.state.value)
        assertEquals(exactDraft, savedState.get<String>("mini-home.draft"))
        assertEquals(establishedSession, controller.session.value)
    }

    @Test
    fun `account A private state cannot resurrect after process recreation into B then A`() =
        runTest {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            val accountA = MiniHomeAuthOwnership.Authenticated(AccountId("account-a"))
            val accountB = MiniHomeAuthOwnership.Authenticated(AccountId("account-b"))
            val first = MiniHomeController(repository, savedState)
            first.start(accountA)
            first.beginEditing()
            first.rename("A 비공개 복원 편집")

            repository.account = AccountId("account-b")
            val recreated = MiniHomeController(repository, savedState)
            recreated.start(accountB)
            assertEquals(null, savedState.get<String>("mini-home.draft"))
            assertTrue(recreated.state.value is MiniHomeUiState.Viewing)

            repository.account = AccountId("account-a")
            recreated.start(accountA)

            assertEquals(AccountId("account-a"), recreated.ownerOrNull())
            assertTrue(recreated.state.value is MiniHomeUiState.Viewing)
            assertEquals(null, savedState.get<String>("mini-home.draft"))
        }

    @Test
    fun `restoring auth defers private state decision until account B is authoritative`() =
        runTest {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            val first = MiniHomeController(repository, savedState)
            first.start(MiniHomeAuthOwnership.Authenticated(AccountId("account-a")))
            first.beginEditing()
            first.rename("복원 결정 전 A 편집")
            val restoredRaw = requireNotNull(savedState.get<String>("mini-home.draft"))
            val recreated = MiniHomeController(repository, savedState)

            recreated.start(MiniHomeAuthOwnership.Restoring)

            assertEquals(restoredRaw, savedState.get<String>("mini-home.draft"))
            assertEquals(MiniHomeUiState.Loading(null), recreated.state.value)
            repository.account = AccountId("account-b")
            recreated.start(MiniHomeAuthOwnership.Authenticated(AccountId("account-b")))
            assertEquals(null, savedState.get<String>("mini-home.draft"))
            assertTrue(recreated.state.value is MiniHomeUiState.Viewing)
        }

    @Test
    fun `restoring auth then authoritative account A preserves its private draft`() = runTest {
        val repository = FakeRepository()
        val savedState = SavedStateHandle()
        val accountA = MiniHomeAuthOwnership.Authenticated(AccountId("account-a"))
        val first = MiniHomeController(repository, savedState)
        first.start(accountA)
        first.beginEditing()
        first.rename("A 동일 소유자 복원")
        val expected = (first.state.value as MiniHomeUiState.Editing).draft
        val recreated = MiniHomeController(repository, savedState)

        recreated.start(MiniHomeAuthOwnership.Restoring)
        recreated.start(accountA)

        val restored = recreated.state.value as MiniHomeUiState.Editing
        assertEquals(expected, restored.draft)
        assertEquals(AccountId("account-a"), recreated.ownerOrNull())
    }

    @Test
    fun `authoritative signed out clears every restored private owner marker`() = runTest {
        val repository = FakeRepository()
        val savedState = SavedStateHandle()
        val first = MiniHomeController(repository, savedState)
        first.start(MiniHomeAuthOwnership.Authenticated(AccountId("account-a")))
        first.beginEditing()
        first.rename("로그아웃 전 비공개 편집")
        first.save()
        first.beginEditing()
        first.rename("확정 뒤 비공개 편집")
        val recreated = MiniHomeController(repository, savedState)

        recreated.start(MiniHomeAuthOwnership.SignedOut)

        assertEquals(MiniHomeUiState.Forbidden, recreated.state.value)
        assertEquals(null, savedState.get<String>("mini-home.draft"))
        assertEquals(null, savedState.get<String>("mini-home.confirmed-save"))
        assertEquals(null, savedState.get<String>("mini-home.restoration-owner"))
    }

    @Test
    fun `legacy private state without explicit restoration owner is cleared`() = runTest {
        val legacy =
            RestoredMiniHomeDraft(
                AccountId("account-a"),
                OperationId("legacy-private-operation"),
                Revision(1),
                authoritative(1).copy(name = "소유자 없는 레거시 편집"),
            )
        val savedState =
            SavedStateHandle(mapOf("mini-home.draft" to MiniHomeDraftCodec.encode(legacy)))
        val repository = FakeRepository()
        val recreated = MiniHomeController(repository, savedState)

        recreated.start(MiniHomeAuthOwnership.Authenticated(AccountId("account-a")))

        assertTrue(recreated.state.value is MiniHomeUiState.Viewing)
        assertEquals(null, savedState.get<String>("mini-home.draft"))
        assertEquals("account-a", savedState.get<String>("mini-home.restoration-owner"))
    }

    @Test
    fun `authoritative account switch during initial load clears A state before B publishes`() =
        runTest {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            val first = MiniHomeController(repository, savedState)
            first.start(MiniHomeAuthOwnership.Authenticated(AccountId("account-a")))
            first.beginEditing()
            first.rename("지연 로드 중 A 편집")
            val recreated = MiniHomeController(repository, savedState)
            val loadA = repository.enqueueLoad(nonCancellable = true)
            val loadingA = async {
                recreated.start(MiniHomeAuthOwnership.Authenticated(AccountId("account-a")))
            }
            loadA.entered.await()
            val loadB = repository.enqueueLoad()
            val loadingB = async {
                recreated.start(MiniHomeAuthOwnership.Authenticated(AccountId("account-b")))
            }
            runCurrent()

            assertEquals(
                MiniHomeUiState.Loading(AccountId("account-b")),
                recreated.state.value,
            )
            assertEquals(null, savedState.get<String>("mini-home.draft"))
            assertFalse(loadB.entered.isCompleted)
            loadA.result.complete(repository.loadResult(AccountId("account-a")))
            assertTrue(runCatching { loadingA.await() }.exceptionOrNull() is CancellationException)
            loadB.entered.await()
            loadB.result.complete(repository.loadResult(AccountId("account-b")))
            loadingB.await()

            assertEquals(AccountId("account-b"), recreated.ownerOrNull())
            assertTrue(recreated.state.value is MiniHomeUiState.Viewing)
        }

    @Test
    fun `same owner process restoration preserves exact draft and advances only controller epoch`() =
        runTest {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            val accountA = MiniHomeAuthOwnership.Authenticated(AccountId("account-a"))
            val first = MiniHomeController(repository, savedState)
            first.start(accountA)
            first.beginEditing()
            first.rename("동일 계정 exact draft")
            val expected = first.state.value as MiniHomeUiState.Editing
            val oldEpoch = first.session.value.controllerEpoch

            val recreated = MiniHomeController(repository, savedState)
            recreated.start(accountA)

            val restored = recreated.state.value as MiniHomeUiState.Editing
            assertEquals(expected.operationId, restored.operationId)
            assertEquals(expected.lineageId, restored.lineageId)
            assertEquals(expected.draft, restored.draft)
            assertEquals(oldEpoch + 1, recreated.session.value.controllerEpoch)
            assertEquals("account-a", savedState.get<String>("mini-home.restoration-owner"))
        }

    @Test
    fun `owner mismatch drops private draft but preserves B durable outbox restoration`() =
        runTest {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            val first = MiniHomeController(repository, savedState)
            first.start(MiniHomeAuthOwnership.Authenticated(AccountId("account-a")))
            first.beginEditing()
            first.rename("A SavedState 전용 편집")
            val durableHandle =
                MiniHomeDiscardHandle(
                    AccountId("account-b"),
                    "miniHomeLayouts",
                    "b-durable-operation",
                    "b-durable-operation",
                    "b-durable-row",
                    2,
                )
            repository.account = AccountId("account-b")
            repository.pending =
                MiniHomePendingSave(
                    OperationId("b-durable-operation"),
                    Revision(1),
                    authoritative(1).copy(name = "B durable outbox 편집"),
                    MiniHomePendingState.MAY_HAVE_COMMITTED,
                    MiniHomeSaveFailure.NETWORK,
                    discardHandle = durableHandle,
                )

            val recreated = MiniHomeController(repository, savedState)
            recreated.start(MiniHomeAuthOwnership.Authenticated(AccountId("account-b")))

            val restored = recreated.state.value as MiniHomeUiState.Editing
            assertEquals("B durable outbox 편집", restored.draft.name)
            assertEquals(OperationId("b-durable-operation"), restored.operationId)
            assertEquals(durableHandle, restored.discardHandle)
            assertEquals(AccountId("account-b"), recreated.ownerOrNull())
        }

    @Test
    fun `public controller session token advances only for owner identity and controller epoch`() =
        runTest {
            val savedState = SavedStateHandle()
            val repository = FakeRepository()
            val controller = MiniHomeController(repository, savedState)

            assertEquals(MiniHomeControllerSessionToken(1L, 0L, null), controller.session.value)
            controller.start()
            assertEquals(
                MiniHomeControllerSessionToken(1L, 1L, AccountId("account-a")),
                controller.session.value,
            )
            controller.start()
            assertEquals(
                MiniHomeControllerSessionToken(1L, 1L, AccountId("account-a")),
                controller.session.value,
            )

            repository.account = AccountId("account-b")
            controller.start()
            assertEquals(
                MiniHomeControllerSessionToken(1L, 2L, AccountId("account-b")),
                controller.session.value,
            )

            val replacement = MiniHomeController(repository, savedState)
            assertEquals(MiniHomeControllerSessionToken(2L, 0L, null), replacement.session.value)
            replacement.start()
            assertEquals(
                MiniHomeControllerSessionToken(2L, 1L, AccountId("account-b")),
                replacement.session.value,
            )
        }

    private fun authenticated(owner: String) = MiniHomeAuthOwnership.Authenticated(AccountId(owner))

    private fun assertOwnerBHasNoAccountAData(
        controller: MiniHomeController,
        savedState: SavedStateHandle,
    ) {
        assertEquals(AccountId("account-b"), controller.ownerOrNull())
        assertEquals(AccountId("account-b"), controller.session.value.owner)
        assertEquals("account-b", savedState.get<String>("mini-home.restoration-owner"))
        val exposed =
            listOf(
                    controller.state.value.toString(),
                    savedState.get<String>("mini-home.draft").orEmpty(),
                    savedState.get<String>("mini-home.confirmed-save").orEmpty(),
                )
                .joinToString("|")
        listOf(
                "A 비밀",
                "a-private-plant",
                "account-a",
            )
            .forEach { privateValue -> assertFalse(exposed.contains(privateValue)) }
    }

    private fun conflictHandle(id: String) =
        MiniHomeDiscardHandle(
            AccountId("account-a"),
            "miniHomeLayouts",
            id,
            id,
            "$id-row",
            1,
        )

    private fun conflictPending(
        handle: MiniHomeDiscardHandle,
        name: String,
    ) =
        MiniHomePendingSave(
            OperationId(handle.rowOperationId),
            Revision(0),
            authoritative(1).copy(name = name),
            MiniHomePendingState.PENDING,
            discardHandle = handle,
        )

    private fun authoritative(revision: Long) =
        MiniHomeLayout(
            MiniHomeId("home-a"),
            "서버 미니 식물원",
            emptyList(),
            Revision(revision),
            Instant.parse("2026-08-12T00:00:00Z"),
        )

    private class FakeRepository(
        var saveResult: MiniHomeSaveResult? = null,
        private val saveGate: CompletableDeferred<Unit>? = null,
        private val saveNonCancellable: Boolean = false,
        var pending: MiniHomePendingSave? = null,
        private val reconcileResult: MiniHomeSaveResult? = null,
        private val reconcileGate: CompletableDeferred<Unit>? = null,
        private val reconcileNonCancellable: Boolean = false,
        var discardResult: MiniHomeDiscardResult = MiniHomeDiscardResult.Consumed,
        private val discardGate: CompletableDeferred<Unit>? = null,
        private val discardNonCancellable: Boolean = false,
        private val pendingDiscardGate: CompletableDeferred<Unit>? = null,
        private val pendingDiscardThrows: Boolean = false,
    ) : MiniHomeRepository {
        var account = AccountId("account-a")
        val saved = mutableListOf<MiniHomeSaveRequest>()
        val saveEntered = CompletableDeferred<Unit>()
        val abandoned = mutableListOf<Triple<AccountId, OperationId, OperationId>>()
        val abandonedHandles = mutableListOf<MiniHomeDiscardHandle>()
        val discardEntered = CompletableDeferred<Unit>()
        val pendingDiscardEntered = CompletableDeferred<Unit>()
        var pendingDiscardCalls = 0
        val reconcileEntered = CompletableDeferred<Unit>()
        private val queuedLoads = ArrayDeque<LoadCall>()

        data class LoadCall(
            val entered: CompletableDeferred<Unit> = CompletableDeferred(),
            val result: CompletableDeferred<MiniHomeLoadResult> = CompletableDeferred(),
            val nonCancellable: Boolean = false,
        )

        fun enqueueLoad(nonCancellable: Boolean = false): LoadCall =
            LoadCall(nonCancellable = nonCancellable).also(queuedLoads::addLast)

        fun loadResult(
            owner: AccountId,
            ownerPending: MiniHomePendingSave? = null,
            committedLayout: MiniHomeLayout? = null,
            committedReceipt: MiniHomeCommittedReceipt? = null,
            stale: Boolean = false,
        ): MiniHomeLoadResult.Ready =
            ready(owner, ownerPending, committedLayout, committedReceipt, stale)

        override suspend fun load(): MiniHomeLoadResult {
            val queued = queuedLoads.removeFirstOrNull()
            if (queued != null) {
                queued.entered.complete(Unit)
                return awaitGate(queued.result, queued.nonCancellable)
            }
            return ready(account, pending)
        }

        val reconciled = mutableListOf<MiniHomeSaveRequest>()

        override suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult {
            saved += request
            saveEntered.complete(Unit)
            saveGate?.let { awaitGate(it, saveNonCancellable) }
            if (account != request.accountId) return MiniHomeSaveResult.Forbidden
            val result =
                saveResult
                    ?: MiniHomeSaveResult.Saved(
                        request.layout.copy(revision = request.expectedRevision.next())
                    )
            val persistedFailure =
                when (result) {
                    is MiniHomeSaveResult.RequiresCorrection -> result.failure
                    is MiniHomeSaveResult.RequiresReconciliation -> result.failure
                    else -> null
                }
            if (persistedFailure != null) {
                pending =
                    MiniHomePendingSave(
                        request.operationId,
                        request.expectedRevision,
                        request.layout,
                        MiniHomePendingState.RECONCILIATION_REQUIRED,
                        persistedFailure,
                        lineageId = request.lineageId,
                        supersedesOperationId = request.supersedesOperationId,
                        discardHandle =
                            MiniHomeDiscardHandle(
                                request.accountId,
                                "miniHomeLayouts",
                                request.operationId.value,
                                request.lineageId.value,
                                "fake-${request.operationId.value}",
                                1,
                            ),
                    )
            }
            val handle = pending?.discardHandle
            return when (result) {
                is MiniHomeSaveResult.RequiresCorrection ->
                    MiniHomeSaveResult.RequiresCorrection(
                        result.failure,
                        result.details,
                        handle,
                    )
                is MiniHomeSaveResult.RequiresReconciliation ->
                    MiniHomeSaveResult.RequiresReconciliation(result.failure, handle)
                else -> result
            }
        }

        override suspend fun reconcile(
            request: MiniHomeSaveRequest,
            failure: MiniHomeSaveFailure,
        ): MiniHomeSaveResult {
            reconciled += request
            reconcileEntered.complete(Unit)
            reconcileGate?.let { awaitGate(it, reconcileNonCancellable) }
            return requireNotNull(reconcileResult)
        }

        override suspend fun abandon(
            accountId: AccountId,
            operationId: OperationId,
            lineageId: OperationId,
        ): MiniHomeDiscardResult {
            abandoned += Triple(accountId, operationId, lineageId)
            if (discardResult is MiniHomeDiscardResult.Consumed) pending = null
            return discardResult
        }

        override suspend fun abandonPending(
            accountId: AccountId,
            operationId: OperationId?,
        ): MiniHomeDiscardResult {
            pendingDiscardCalls += 1
            pendingDiscardEntered.complete(Unit)
            pendingDiscardGate?.await()
            if (pendingDiscardThrows) error("forced pending query failure")
            if (account != accountId) return MiniHomeDiscardResult.OwnerMismatch
            val handle = pending?.discardHandle ?: return MiniHomeDiscardResult.Missing
            return abandon(handle)
        }

        override suspend fun abandon(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult {
            abandonedHandles += handle
            discardEntered.complete(Unit)
            discardGate?.let { awaitGate(it, discardNonCancellable) }
            if (discardResult is MiniHomeDiscardResult.Consumed) pending = null
            return discardResult
        }

        private suspend fun <T> awaitGate(
            gate: CompletableDeferred<T>,
            nonCancellable: Boolean,
        ): T =
            if (nonCancellable) {
                withContext(NonCancellable) { gate.await() }
            } else {
                gate.await()
            }

        private fun ready(
            owner: AccountId,
            ownerPending: MiniHomePendingSave?,
            committedLayout: MiniHomeLayout? = null,
            committedReceipt: MiniHomeCommittedReceipt? = null,
            stale: Boolean = false,
        ): MiniHomeLoadResult.Ready =
            MiniHomeLoadResult.Ready(
                owner,
                committedLayout
                    ?: MiniHomeLayout(
                        MiniHomeId("home-a"),
                        "나의 미니 식물원",
                        emptyList(),
                        Revision(1),
                        Instant.parse("2026-08-12T00:00:00Z"),
                    ),
                listOf(MiniHomePlantChoice(PersonalPlantId("plant-a"), "몬스테라", null)),
                emptyList(),
                stale = stale,
                pending = ownerPending,
                committedReceipt = committedReceipt,
            )
    }
}
