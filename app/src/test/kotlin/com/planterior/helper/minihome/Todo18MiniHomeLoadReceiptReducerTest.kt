package com.planterior.helper.minihome

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.minihome.MiniHomeLoadIdentity
import com.planterior.helper.feature.minihome.MiniHomePendingReadIdentity
import kotlinx.coroutines.CancellationException
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

    @Test
    fun `pending read terminal outcomes preserve query identity`() {
        listOf(
                Todo18MiniHomeLoadDiagnostic.PendingReadReturned(
                    AccountId(ACCOUNT),
                    pendingReadIdentity(),
                ),
                Todo18MiniHomeLoadDiagnostic.PendingReadThrew(
                    AccountId(ACCOUNT),
                    pendingReadIdentity(),
                    IllegalStateException("pending query failed"),
                ),
                Todo18MiniHomeLoadDiagnostic.PendingReadCancelled(
                    AccountId(ACCOUNT),
                    pendingReadIdentity(),
                    CancellationException("pending query cancelled"),
                ),
            )
            .forEach { terminal ->
                val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
                val load = recorder.startLoad()
                load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
                load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
                load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
                load.record(Todo18MiniHomeLoadDiagnostic.CacheApplyEntered(AccountId(ACCOUNT)))
                load.record(
                    Todo18MiniHomeLoadDiagnostic.CacheApplyReturned(AccountId(ACCOUNT), true)
                )
                val firstReadId = load.recordPublicationRead()
                recorder.record(
                    load.id,
                    Todo18MiniHomeLoadDiagnostic.PublicationReadReturned,
                    firstReadId,
                )
                load.record(
                    Todo18MiniHomeLoadDiagnostic.PendingReadEntered(
                        AccountId(ACCOUNT),
                        pendingReadIdentity(),
                    )
                )
                load.record(terminal)
                val secondReadId = load.recordPublicationRead()
                recorder.record(
                    load.id,
                    Todo18MiniHomeLoadDiagnostic.PublicationReadReturned,
                    secondReadId,
                )
                load.record(Todo18MiniHomeLoadDiagnostic.Ready)
                val progress = recorder.snapshot()

                assertEquals(
                    emptyList<String>(),
                    Todo18MiniHomeLoadReceiptReducer.problems(
                        ACCOUNT,
                        progress,
                        progress.toStages(),
                    ),
                )
            }
    }

    @Test
    fun `pending read duplicate terminal is rejected for the same query identity`() {
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()
        val identity = pendingReadIdentity()
        load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.PendingReadEntered(AccountId(ACCOUNT), identity))
        load.record(Todo18MiniHomeLoadDiagnostic.PendingReadReturned(AccountId(ACCOUNT), identity))
        load.record(Todo18MiniHomeLoadDiagnostic.PendingReadReturned(AccountId(ACCOUNT), identity))
        val progress = recorder.snapshot()

        val problems =
            Todo18MiniHomeLoadReceiptReducer.problems(ACCOUNT, progress, progress.toStages())

        assertTrue("pending-read-identity-mismatch" in problems)
        assertTrue(progress.progressionProblems().any { it.contains("duplicate-stage") })
    }

    @Test
    fun `pending read is required in the publication read window`() {
        val fixture = completeFixture()

        val problems =
            Todo18MiniHomeLoadReceiptReducer.problems(
                ACCOUNT,
                fixture.progress,
                fixture.stages.filterNot { it.kind?.startsWith("pending-read-") == true },
            )

        assertTrue("pending-read-cardinality-mismatch" in problems)
    }

    @Test
    fun `pending read outside publication read window is rejected`() {
        val fixture = pendingReadFixture(pendingBeforeFirstPublication = true)

        val problems =
            Todo18MiniHomeLoadReceiptReducer.problems(ACCOUNT, fixture.progress, fixture.stages)

        assertTrue("pending-read-order-mismatch" in problems)
    }

    @Test
    fun `multiple pending read identities in publication read window are rejected`() {
        val fixture = pendingReadFixture(pendingIdentityCount = 2)

        val problems =
            Todo18MiniHomeLoadReceiptReducer.problems(ACCOUNT, fixture.progress, fixture.stages)

        assertTrue("pending-read-cardinality-mismatch" in problems)
    }

    @Test
    fun `pending read terminal is required for the selected query`() {
        val fixture = pendingReadFixture()
        val stages = fixture.stages.filterNot { it.kind == "pending-read-returned" }

        val problems = Todo18MiniHomeLoadReceiptReducer.problems(ACCOUNT, fixture.progress, stages)

        assertTrue("pending-read-identity-mismatch" in problems)
        assertTrue("pending-read-cardinality-mismatch" in problems)
    }

    @Test
    fun `duplicate pending read terminal is rejected inside the publication read window`() {
        val fixture = pendingReadFixture()
        val terminalIndex = fixture.stages.indexOfFirst { it.kind == "pending-read-returned" }
        val stages =
            fixture.stages.toMutableList().apply {
                add(terminalIndex + 1, this[terminalIndex])
            }

        val problems = Todo18MiniHomeLoadReceiptReducer.problems(ACCOUNT, fixture.progress, stages)

        assertTrue("pending-read-identity-mismatch" in problems)
        assertTrue("pending-read-cardinality-mismatch" in problems)
    }

    @Test
    fun `publication read identities must increase around the selected pending query`() {
        val fixture = pendingReadFixture()
        val secondPublicationReadId =
            fixture.stages.first { it.kind == "publication-read-entered" && it.readId != 1L }.readId
        val stages =
            fixture.stages.map { stage ->
                if (stage.readId == secondPublicationReadId) stage.copy(readId = 0L) else stage
            }

        val problems = Todo18MiniHomeLoadReceiptReducer.problems(ACCOUNT, fixture.progress, stages)

        assertTrue("pending-read-order-mismatch" in problems)
    }

    private fun completeFixture(): Fixture {
        return pendingReadFixture()
    }

    private fun pendingReadFixture(
        pendingBeforeFirstPublication: Boolean = false,
        pendingIdentityCount: Int = 1,
    ): Fixture {
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()
        val pendingIdentities =
            (1..pendingIdentityCount).map { ordinal ->
                MiniHomePendingReadIdentity(MiniHomeLoadIdentity(1L), ordinal.toLong())
            }
        load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
        load.record(Todo18MiniHomeLoadDiagnostic.CacheApplyEntered(AccountId(ACCOUNT)))
        load.record(Todo18MiniHomeLoadDiagnostic.CacheApplyReturned(AccountId(ACCOUNT), true))
        if (pendingBeforeFirstPublication) recordPendingReads(load, pendingIdentities)
        val firstReadId = load.recordPublicationRead()
        recorder.record(load.id, Todo18MiniHomeLoadDiagnostic.PublicationReadReturned, firstReadId)
        if (!pendingBeforeFirstPublication) recordPendingReads(load, pendingIdentities)
        val secondReadId = load.recordPublicationRead()
        recorder.record(load.id, Todo18MiniHomeLoadDiagnostic.PublicationReadReturned, secondReadId)
        load.record(Todo18MiniHomeLoadDiagnostic.Ready)
        val progress = recorder.snapshot()
        return Fixture(progress, progress.toStages())
    }

    private fun recordPendingReads(
        load: Todo18MiniHomeLoad,
        identities: List<MiniHomePendingReadIdentity>,
    ) {
        identities.forEach { identity ->
            load.record(
                Todo18MiniHomeLoadDiagnostic.PendingReadEntered(AccountId(ACCOUNT), identity)
            )
            load.record(
                Todo18MiniHomeLoadDiagnostic.PendingReadReturned(AccountId(ACCOUNT), identity)
            )
        }
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
                pendingReadLoadId = observation.pendingReadId?.loadId?.value,
                pendingReadId = observation.pendingReadId?.queryOrdinal,
                pendingReadOutcome =
                    when (diagnostic) {
                        is Todo18MiniHomeLoadDiagnostic.PendingReadReturned -> "returned"
                        is Todo18MiniHomeLoadDiagnostic.PendingReadThrew -> "threw"
                        is Todo18MiniHomeLoadDiagnostic.PendingReadCancelled -> "cancelled"
                        else -> null
                    },
            )
        }

    private fun pendingReadIdentity() = MiniHomePendingReadIdentity(MiniHomeLoadIdentity(1L), 1L)

    private data class Fixture(
        val progress: Todo18MiniHomeLoadProgress,
        val stages: List<Todo18MiniHomeLoadBoundaryStage>,
    )

    private companion object {
        const val ACCOUNT = "account-a"
    }
}
