package com.planterior.helper.minihome

import com.planterior.helper.feature.minihome.MiniHomeDiscardHandle
import com.planterior.helper.feature.minihome.MiniHomeDiscardResult
import com.planterior.helper.feature.minihome.MiniHomeLoadResult
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomeSaveRequest
import com.planterior.helper.feature.minihome.MiniHomeSaveResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class Todo18MiniHomeLoadDiagnosticProgressTest {
    @Test
    fun `delegate suspension reports load entered as active and last reached`() = runTest {
        // Given / When / Then
        assertSuspendedAt("load-entered", emptyList())
    }

    @Test
    fun `remote entry suspension reports remote entered as active and last reached`() = runTest {
        // Given / When / Then
        assertSuspendedAt(
            "remote-load-entered",
            listOf(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered),
        )
    }

    @Test
    fun `remote return suspension reports remote returned as active and last reached`() = runTest {
        // Given / When / Then
        assertSuspendedAt(
            "remote-load-returned",
            listOf(
                Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned,
            ),
        )
    }

    @Test
    fun `publication read suspension reports publication entry as active and last reached`() =
        runTest {
            // Given / When / Then
            assertSuspendedAt(
                "publication-read-entered",
                listOf(
                    Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered,
                    Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned,
                    Todo18MiniHomeLoadDiagnostic.PublicationReadEntered,
                ),
            )
        }

    @Test
    fun `lifecycle cancellation finalizes the active publication read once`() = runTest {
        // Given
        val delegateEntered = CompletableDeferred<Unit>()
        val releaseDelegate = CompletableDeferred<Unit>()
        val events = mutableListOf<Todo18MiniHomeLoadObservation>()
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder(events::add)
        val delegate =
            SuspendingRepository(delegateEntered, releaseDelegate) {
                recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
                recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
                recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.PublicationReadEntered)
            }
        val repository = Todo18MiniHomeLoadDiagnosticRepository(delegate, recorder)
        val load = backgroundScope.async { repository.load() }
        delegateEntered.await()

        // When
        load.cancelAndJoin()

        // Then
        assertEquals(Todo18MiniHomeLoadDiagnostic.Cancelled, events.last().diagnostic)
        assertEquals(
            1,
            events.count { it.diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal },
        )
        assertEquals(null, repository.loadProgressSnapshot().activeStage)
        assertEquals("terminal-cancelled", repository.loadProgressSnapshot().lastReachedStage)
    }

    @Test
    fun `recorder exposes duplicate and post terminal observations as invalid`() {
        // Given
        val events = mutableListOf<Todo18MiniHomeLoadObservation>()
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder(events::add)
        val diagnosticLoad = recorder.startLoad()

        // When
        diagnosticLoad.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        diagnosticLoad.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
        diagnosticLoad.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
        diagnosticLoad.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
        val readId = diagnosticLoad.recordPublicationRead()
        diagnosticLoad.recordPublicationRead(readId)
        diagnosticLoad.record(Todo18MiniHomeLoadDiagnostic.Ready)
        diagnosticLoad.record(Todo18MiniHomeLoadDiagnostic.Cancelled)

        // Then
        assertEquals(
            listOf(
                Todo18MiniHomeLoadDiagnostic.LoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned,
                Todo18MiniHomeLoadDiagnostic.PublicationReadEntered,
                Todo18MiniHomeLoadDiagnostic.PublicationReadEntered,
                Todo18MiniHomeLoadDiagnostic.Ready,
                Todo18MiniHomeLoadDiagnostic.Cancelled,
            ),
            events.map { it.diagnostic },
        )
        val progress = recorder.snapshot()
        assertEquals(null, progress.activeStage)
        assertEquals("terminal-cancelled", progress.lastReachedStage)
        assertEquals(events.map { it.receiptStage }, progress.reachedStages)
        assertEquals(
            listOf(
                Todo18MiniHomeLoadViolationKind.DUPLICATE_STAGE,
                Todo18MiniHomeLoadViolationKind.DUPLICATE_STAGE,
                Todo18MiniHomeLoadViolationKind.MULTIPLE_TERMINAL,
            ),
            progress.progressionViolations.map { it.kind },
        )
    }

    @Test
    fun `recorder failure cannot replace the delegate result`() = runTest {
        // Given
        val recorderFailure = IllegalStateException("diagnostic sink unavailable")
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder { throw recorderFailure }
        val expected = MiniHomeLoadResult.Forbidden
        val repository =
            Todo18MiniHomeLoadDiagnosticRepository(
                FixedRepository(expected),
                recorder,
            )

        // When
        val actual = repository.load()

        // Then
        assertSame(expected, actual)
        assertEquals(
            listOf(
                "load-entered:${IllegalStateException::class.java.name}",
                "terminal-forbidden:${IllegalStateException::class.java.name}",
            ),
            recorder.snapshot().recorderFailures,
        )
    }

    private suspend fun TestScope.assertSuspendedAt(
        expectedStage: String,
        stagesBeforeSuspension: List<Todo18MiniHomeLoadDiagnostic>,
    ) {
        val delegateEntered = CompletableDeferred<Unit>()
        val releaseDelegate = CompletableDeferred<Unit>()
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val delegate =
            SuspendingRepository(delegateEntered, releaseDelegate) {
                stagesBeforeSuspension.forEach { diagnostic ->
                    recorder.recordCurrent(diagnostic)
                }
            }
        val repository = Todo18MiniHomeLoadDiagnosticRepository(delegate, recorder)
        val load = backgroundScope.async { repository.load() }

        delegateEntered.await()

        try {
            val progress = repository.loadProgressSnapshot()
            assertEquals(expectedStage, progress.activeStage)
            assertEquals(expectedStage, progress.lastReachedStage)
            assertEquals(
                listOf("load-entered") + stagesBeforeSuspension.map { it.receiptStage },
                progress.reachedStages,
            )
        } finally {
            load.cancelAndJoin()
        }
    }

    private class SuspendingRepository(
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
        private val beforeSuspension: suspend () -> Unit,
    ) : MiniHomeRepository {
        override suspend fun load(): MiniHomeLoadResult {
            beforeSuspension()
            entered.complete(Unit)
            release.await()
            return MiniHomeLoadResult.Forbidden
        }

        override suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult =
            MiniHomeSaveResult.Forbidden

        override suspend fun abandon(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult =
            MiniHomeDiscardResult.Rejected
    }

    private class FixedRepository(private val result: MiniHomeLoadResult) : MiniHomeRepository {
        override suspend fun load(): MiniHomeLoadResult = result

        override suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult =
            MiniHomeSaveResult.Forbidden

        override suspend fun abandon(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult =
            MiniHomeDiscardResult.Rejected
    }
}
