package com.planterior.helper.minihome

import com.planterior.helper.core.model.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18MiniHomeLoadReceiptReducerTest {
    @Test
    fun `complete cache-bound load receipt is accepted`() {
        val fixture = completeFixture()

        assertEquals(
            emptyList<String>(),
            Todo18MiniHomeLoadReceiptReducer.problems(ACCOUNT, fixture.progress, fixture.stages),
        )
    }

    @Test
    fun `missing cache return is rejected`() {
        val fixture = completeFixture()

        val problems =
            Todo18MiniHomeLoadReceiptReducer.problems(
                ACCOUNT,
                fixture.progress,
                fixture.stages.filterNot { it.kind == "cache-apply-returned" },
            )

        assertTrue("missing-cache-apply-returned" in problems)
    }

    @Test
    fun `missing publication read return is rejected`() {
        val fixture = completeFixture()

        val problems =
            Todo18MiniHomeLoadReceiptReducer.problems(
                ACCOUNT,
                fixture.progress,
                fixture.stages.filterNot { it.kind == "publication-read-returned" },
            )

        assertTrue("missing-publication-read-returned" in problems)
    }

    @Test
    fun `publication read return with a different read identity is rejected`() {
        val fixture = completeFixture()
        val stages =
            fixture.stages.map { stage ->
                if (stage.kind == "publication-read-returned") stage.copy(readId = 99L) else stage
            }

        val problems = Todo18MiniHomeLoadReceiptReducer.problems(ACCOUNT, fixture.progress, stages)

        assertTrue("publication-read-identity-mismatch" in problems)
    }

    @Test
    fun `duplicate cache entry is rejected by actual progression`() {
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()
        load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
        load.record(Todo18MiniHomeLoadDiagnostic.CacheApplyEntered(AccountId(ACCOUNT)))
        load.record(Todo18MiniHomeLoadDiagnostic.CacheApplyEntered(AccountId(ACCOUNT)))
        val progress = recorder.snapshot()

        assertTrue(
            Todo18MiniHomeLoadReceiptReducer.problems(ACCOUNT, progress, progress.toStages()).any {
                it.contains("duplicate-stage")
            }
        )
    }

    @Test
    fun `cache return before cache entry is rejected by actual progression`() {
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()
        load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
        load.record(Todo18MiniHomeLoadDiagnostic.CacheApplyReturned(AccountId(ACCOUNT), true))
        val progress = recorder.snapshot()

        assertTrue(
            Todo18MiniHomeLoadReceiptReducer.problems(ACCOUNT, progress, progress.toStages()).any {
                it.contains("out-of-order-stage")
            }
        )
    }

    @Test
    fun `boundary load identity mismatch is rejected`() {
        val fixture = completeFixture()
        val stages =
            fixture.stages.mapIndexed { index, stage ->
                if (index == 3) stage.copy(loadId = 99L) else stage
            }

        val problems = Todo18MiniHomeLoadReceiptReducer.problems(ACCOUNT, fixture.progress, stages)

        assertTrue("load-stage-load-mismatch" in problems)
    }

    @Test
    fun `unclosed load is rejected`() {
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        recorder.startLoad().record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        val progress = recorder.snapshot()

        assertTrue(
            "unclosed-load" in
                Todo18MiniHomeLoadReceiptReducer.problems(
                    ACCOUNT,
                    progress,
                    progress.toStages(),
                )
        )
    }

    @Test
    fun `malformed cache outcome is rejected`() {
        val fixture = completeFixture()
        val stages =
            fixture.stages.map { stage ->
                if (stage.kind == "cache-apply-returned") {
                    stage.copy(cacheOutcome = "unknown")
                } else {
                    stage
                }
            }

        val problems = Todo18MiniHomeLoadReceiptReducer.problems(ACCOUNT, fixture.progress, stages)

        assertTrue("load-diagnostic-malformed" in problems)
    }

    private fun completeFixture(): Fixture {
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()
        load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
        load.record(Todo18MiniHomeLoadDiagnostic.CacheApplyEntered(AccountId(ACCOUNT)))
        load.record(Todo18MiniHomeLoadDiagnostic.CacheApplyReturned(AccountId(ACCOUNT), true))
        val readId = load.recordPublicationRead()
        recorder.record(load.id, Todo18MiniHomeLoadDiagnostic.PublicationReadReturned, readId)
        load.record(Todo18MiniHomeLoadDiagnostic.Ready)
        val progress = recorder.snapshot()
        return Fixture(progress, progress.toStages())
    }

    private fun Todo18MiniHomeLoadProgress.toStages(): List<Todo18MiniHomeLoadBoundaryStage> =
        observations.map { observation ->
            val diagnostic = observation.diagnostic
            Todo18MiniHomeLoadBoundaryStage(
                kind =
                    if (diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal) {
                        "load-terminal"
                    } else {
                        observation.receiptStage
                    },
                identity =
                    when (diagnostic) {
                        is Todo18MiniHomeLoadDiagnostic.CacheApplyEntered ->
                            diagnostic.accountId.value
                        is Todo18MiniHomeLoadDiagnostic.CacheApplyReturned ->
                            diagnostic.accountId.value
                        Todo18MiniHomeLoadDiagnostic.Ready -> "Ready"
                        Todo18MiniHomeLoadDiagnostic.Forbidden -> "Forbidden"
                        Todo18MiniHomeLoadDiagnostic.Failed -> "Failed"
                        Todo18MiniHomeLoadDiagnostic.Cancelled -> "Cancelled"
                        else -> ACCOUNT
                    },
                loadId = observation.loadId.value,
                readId = observation.readId?.ordinal,
                diagnosticOrder = observation.order,
                cacheOutcome =
                    (diagnostic as? Todo18MiniHomeLoadDiagnostic.CacheApplyReturned)?.let {
                        if (it.current) "current" else "conflict"
                    },
            )
        }

    private data class Fixture(
        val progress: Todo18MiniHomeLoadProgress,
        val stages: List<Todo18MiniHomeLoadBoundaryStage>,
    )

    private companion object {
        const val ACCOUNT = "account-a"
    }
}
