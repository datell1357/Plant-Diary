package com.planterior.helper.feature.minihome

import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.Revision
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MiniHomeRetryDiagnosticsTest {
    @Test
    fun `observer faults do not alter lifecycle and installation is scoped`() {
        val installation = MiniHomeRetryDiagnostics.install {
            throw AssertionError("diagnostic fault")
        }

        try {
            MiniHomeRetryDiagnostics.observe(
                MiniHomeRetryObservation(MiniHomeRetryStage.CALLBACK_ENTRY)
            )
            assertEquals(1, MiniHomeRetryDiagnostics.listenerCount())
        } finally {
            installation.close()
        }

        assertEquals(0, MiniHomeRetryDiagnostics.listenerCount())
    }

    @Test
    fun `assertion error from repository preserves exact identity and is observed once`() =
        runTest {
            val failure = AssertionError("save assertion failed")
            val repository = RetryRepository(failure = failure)
            val controller = MiniHomeController(repository, SavedStateHandle())
            val observations = capture {
                controller.start()
                controller.beginEditing()
                try {
                    controller.save()
                } catch (error: AssertionError) {
                    assertSame(failure, error)
                }
            }

            val terminals = observations.filter { it.stage.isRepositoryTerminal() }
            assertEquals(1, terminals.size)
            assertEquals(MiniHomeRetryStage.REPOSITORY_SAVE_THROWN, terminals.single().stage)
            assertSame(failure, terminals.single().failure)
            assertEquals(
                1,
                observations.count { it.stage == MiniHomeRetryStage.REPOSITORY_SAVE_ENTRY },
            )
        }

    @Test
    fun `assertion error from retry coroutine preserves exact identity and is observed once`() =
        runTest {
            val failure = AssertionError("retry assertion failed")
            val operation =
                com.planterior.helper.core.model.OperationId("retry-assertion-operation")
            val observations = capture {
                try {
                    runMiniHomeRetryCoroutine(operation) { throw failure }
                } catch (error: AssertionError) {
                    assertSame(failure, error)
                }
            }

            val terminal = observations.single { it.stage == MiniHomeRetryStage.COROUTINE_THROWN }
            assertSame(failure, terminal.failure)
            assertEquals(1, observations.count { it.stage.isCoroutineTerminal() })
        }

    @Test
    fun `returned save has one terminal with exact result and token identities`() = runTest {
        val repository = RetryRepository()
        val controller = MiniHomeController(repository, SavedStateHandle())
        val observations = capture {
            controller.start()
            controller.beginEditing()
            controller.save()
        }

        val terminals = observations.filter { it.stage.isRepositoryTerminal() }
        assertEquals(1, terminals.size)
        assertEquals(MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED, terminals.single().stage)
        assertSame(repository.returned, terminals.single().result)
        assertEquals(repository.requestOperation, terminals.single().operationId)
        assertTrue(terminals.single().controllerEpoch != null)
        assertTrue(terminals.single().controllerGeneration != null)
        assertTrue(terminals.single().saveGeneration != null)
    }

    @Test
    fun `all save result subtypes record stable semantic details`() {
        val layout = layout(Revision(2))
        val pending =
            MiniHomePendingSave(
                operationId = OperationId("pending-operation"),
                expectedRevision = Revision(1),
                layout = layout,
                state = MiniHomePendingState.MAY_HAVE_COMMITTED,
                failure = MiniHomeSaveFailure.NETWORK,
                failureDetails = "offline",
            )
        val expected =
            listOf(
                MiniHomeSaveResult.Saved(layout) to
                    MiniHomeSaveResultDetails.Saved("retry-details-home", 2),
                MiniHomeSaveResult.Conflict(layout) to
                    MiniHomeSaveResultDetails.Conflict(
                        "retry-details-home",
                        2,
                        plantCount = 0,
                        decorationCount = 0,
                    ),
                MiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK) to
                    MiniHomeSaveResultDetails.Failed(
                        MiniHomeSaveFailure.NETWORK,
                        hasDiscardHandle = false,
                    ),
                MiniHomeSaveResult.RequiresCorrection(
                    MiniHomeSaveFailure.INVALID_REQUEST,
                    "field=name",
                ) to
                    MiniHomeSaveResultDetails.RequiresCorrection(
                        MiniHomeSaveFailure.INVALID_REQUEST,
                        "field=name",
                        hasDiscardHandle = false,
                    ),
                MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.REVISION_CONFLICT) to
                    MiniHomeSaveResultDetails.RequiresReconciliation(
                        MiniHomeSaveFailure.REVISION_CONFLICT,
                        hasDiscardHandle = false,
                    ),
                MiniHomeSaveResult.Reconciled(
                    MiniHomeSaveFailure.REVISION_CONFLICT,
                    authoritative = layout,
                    plants = emptyList(),
                    decorations = emptyList(),
                    correctedDraft = layout,
                    removedTargets = 1,
                ) to
                    MiniHomeSaveResultDetails.Reconciled(
                        MiniHomeSaveFailure.REVISION_CONFLICT,
                        authoritativeLayoutId = "retry-details-home",
                        authoritativeRevision = 2,
                        correctedDraftLayoutId = "retry-details-home",
                        correctedDraftRevision = 2,
                        plantCount = 0,
                        decorationCount = 0,
                        removedTargets = 1,
                    ),
                MiniHomeSaveResult.PendingChanged(pending) to
                    MiniHomeSaveResultDetails.PendingChanged(
                        currentOperationPresent = true,
                        operationId = "pending-operation",
                        expectedRevision = 1,
                        layoutId = "retry-details-home",
                        layoutRevision = 2,
                        state = MiniHomePendingState.MAY_HAVE_COMMITTED,
                        failure = MiniHomeSaveFailure.NETWORK,
                        failureDetails = "offline",
                        hasDiscardHandle = false,
                    ),
                MiniHomeSaveResult.Forbidden to MiniHomeSaveResultDetails.Forbidden,
            )

        expected.forEach { (result, details) ->
            assertEquals(
                details,
                MiniHomeRetryObservation(
                        MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED,
                        result = result,
                    )
                    .resultDetails,
            )
        }
        assertEquals(
            MiniHomeSaveResultDetails.PendingChanged(
                currentOperationPresent = false,
                operationId = null,
                expectedRevision = null,
                layoutId = null,
                layoutRevision = null,
                state = null,
                failure = null,
                failureDetails = null,
                hasDiscardHandle = false,
            ),
            MiniHomeRetryObservation(
                    MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED,
                    result = MiniHomeSaveResult.PendingChanged(null),
                )
                .resultDetails,
        )
    }

    @Test
    fun `result details mapping has no fallback for future save subtypes`() {
        val source =
            Files.readString(
                repositoryRoot()
                    .resolve(
                        "feature/minihome/src/main/kotlin/com/planterior/helper/feature/minihome/" +
                            "MiniHomeRetryDiagnostics.kt"
                    )
            )
        val mapping =
            source
                .substringAfter("private fun MiniHomeSaveResult.toRetryResultDetails()")
                .substringBefore("fun interface MiniHomeRetrySink")

        listOf(
                "is MiniHomeSaveResult.Saved ->",
                "is MiniHomeSaveResult.Conflict ->",
                "is MiniHomeSaveResult.Failed ->",
                "is MiniHomeSaveResult.RequiresCorrection ->",
                "is MiniHomeSaveResult.RequiresReconciliation ->",
                "is MiniHomeSaveResult.Reconciled ->",
                "is MiniHomeSaveResult.PendingChanged ->",
                "MiniHomeSaveResult.Forbidden ->",
            )
            .forEach { branch -> assertTrue("missing $branch", mapping.contains(branch)) }
        assertFalse(mapping.contains("else ->"))
        assertFalse(mapping.contains("System.identityHashCode"))
    }

    @Test
    fun `thrown and cancelled saves preserve exact failure identity`() = runTest {
        val thrown = IllegalStateException("save failed")
        val thrownRepository = RetryRepository(failure = thrown)
        val thrownController = MiniHomeController(thrownRepository, SavedStateHandle())
        val thrownObservations = capture {
            thrownController.start()
            thrownController.beginEditing()
            thrownController.save()
        }
        val thrownTerminal = thrownObservations.single {
            it.stage == MiniHomeRetryStage.REPOSITORY_SAVE_THROWN
        }
        assertSame(thrown, thrownTerminal.failure)
        assertEquals(1, thrownObservations.count { it.stage.isRepositoryTerminal() })

        val cancelled = kotlinx.coroutines.CancellationException("save cancelled")
        val cancelledRepository = RetryRepository(failure = cancelled)
        val cancelledController = MiniHomeController(cancelledRepository, SavedStateHandle())
        val cancelledObservations = mutableListOf<MiniHomeRetryObservation>()
        val installation = MiniHomeRetryDiagnostics.install(cancelledObservations::add)
        try {
            cancelledController.start()
            cancelledController.beginEditing()
            try {
                cancelledController.save()
            } catch (error: kotlinx.coroutines.CancellationException) {
                assertSame(cancelled, error)
            }
        } finally {
            installation.close()
        }
        val cancelledTerminal = cancelledObservations.single {
            it.stage == MiniHomeRetryStage.REPOSITORY_SAVE_CANCELLED
        }
        assertSame(cancelled, cancelledTerminal.failure)
        assertEquals(1, cancelledObservations.count { it.stage.isRepositoryTerminal() })
    }

    @Test
    fun `observer cancellation does not replace repository cancellation`() = runTest {
        val primary = kotlinx.coroutines.CancellationException("repository cancellation")
        val repository = RetryRepository(failure = primary)
        val controller = MiniHomeController(repository, SavedStateHandle())
        val installation = MiniHomeRetryDiagnostics.install {
            throw kotlinx.coroutines.CancellationException("observer cancellation")
        }

        try {
            controller.start()
            controller.beginEditing()
            try {
                controller.save()
            } catch (error: kotlinx.coroutines.CancellationException) {
                assertSame(primary, error)
            }
        } finally {
            installation.close()
        }
    }

    @Test
    fun `stale saved result records apply rejection without state application`() = runTest {
        val savedState = SavedStateHandle()
        val repository = RetryRepository(gateSave = true)
        val controller = MiniHomeController(repository, savedState)
        val observations = mutableListOf<MiniHomeRetryObservation>()
        val installation = MiniHomeRetryDiagnostics.install(observations::add)
        try {
            controller.start()
            controller.beginEditing()
            val saveJob = async { controller.save() }
            runCurrent()
            assertTrue(repository.saveEntered.isCompleted)
            assertTrue(observations.none { it.stage.isRepositoryTerminal() })

            val replacement = MiniHomeController(repository, savedState)
            replacement.start()
            repository.saveGate.complete(Unit)
            saveJob.await()
        } finally {
            installation.close()
        }

        val savedApply = observations.filter { it.stage == MiniHomeRetryStage.SAVED_APPLY_ENTRY }
        val rejected = observations.single { it.stage == MiniHomeRetryStage.SAVED_APPLY_REJECTED }
        assertEquals(1, savedApply.size)
        assertEquals("stale-token", rejected.outcome)
        assertEquals(savedApply.single().operationId, rejected.operationId)
        assertEquals(savedApply.single().controllerGeneration, rejected.controllerGeneration)
        assertTrue(
            observations.none {
                it.stage == MiniHomeRetryStage.SET_STATE_APPLIED &&
                    it.operationId == rejected.operationId &&
                    it.revision == 2L
            }
        )
    }

    private suspend fun capture(block: suspend () -> Unit): List<MiniHomeRetryObservation> {
        val observations = mutableListOf<MiniHomeRetryObservation>()
        val installation = MiniHomeRetryDiagnostics.install(observations::add)
        try {
            block()
        } finally {
            installation.close()
        }
        return observations
    }

    private class RetryRepository(
        private val failure: Throwable? = null,
        private val gateSave: Boolean = false,
    ) : MiniHomeRepository {
        val saveEntered = CompletableDeferred<Unit>()
        val saveGate = CompletableDeferred<Unit>()
        var returned: MiniHomeSaveResult? = null
        var requestOperation: com.planterior.helper.core.model.OperationId? = null
        private val calls = AtomicInteger()

        override suspend fun load(): MiniHomeLoadResult =
            MiniHomeLoadResult.Ready(
                accountId = OWNER,
                committed =
                    MiniHomeLayout(
                        MiniHomeId("retry-diagnostics-home"),
                        "Retry diagnostics",
                        emptyList(),
                        Revision(1),
                        Instant.EPOCH,
                    ),
                plants = listOf(MiniHomePlantChoice(PersonalPlantId("plant"), "Plant", null)),
                decorations = emptyList(),
                stale = false,
                pending = null,
            )

        override suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult {
            requestOperation = request.operationId
            if (gateSave && calls.incrementAndGet() == 1) {
                saveEntered.complete(Unit)
                withContext(NonCancellable) { saveGate.await() }
            }
            failure?.let { throw it }
            return MiniHomeSaveResult.Saved(request.layout.copy(revision = Revision(2))).also {
                returned = it
            }
        }

        override suspend fun abandon(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult =
            MiniHomeDiscardResult.Rejected
    }

    private fun MiniHomeRetryStage.isRepositoryTerminal(): Boolean =
        this == MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED ||
            this == MiniHomeRetryStage.REPOSITORY_SAVE_THROWN ||
            this == MiniHomeRetryStage.REPOSITORY_SAVE_CANCELLED

    private fun MiniHomeRetryStage.isCoroutineTerminal(): Boolean =
        this == MiniHomeRetryStage.COROUTINE_RETURNED ||
            this == MiniHomeRetryStage.COROUTINE_THROWN ||
            this == MiniHomeRetryStage.COROUTINE_CANCELLED

    private fun layout(revision: Revision) =
        MiniHomeLayout(
            id = MiniHomeId("retry-details-home"),
            name = "Retry details",
            placements = emptyList(),
            revision = revision,
            updatedAt = Instant.EPOCH,
        )

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Repository root unavailable")
        }
        return current
    }

    private companion object {
        val OWNER = AccountId("retry-diagnostics-owner")
    }
}
