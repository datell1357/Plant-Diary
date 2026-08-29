package com.planterior.helper.minihome

import com.planterior.helper.feature.minihome.MiniHomeDiscardHandle
import com.planterior.helper.feature.minihome.MiniHomeDiscardResult
import com.planterior.helper.feature.minihome.MiniHomeLoadResult
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomeSaveRequest
import com.planterior.helper.feature.minihome.MiniHomeSaveResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18MiniHomeLoadReadIdentityTest {
    @Test
    fun `overlapping loads keep stages and read counters isolated`() {
        // Given
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val first = recorder.startLoad()
        val second = recorder.startLoad()

        // When
        first.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        first.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
        second.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        second.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
        second.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
        val secondRead = second.recordPublicationRead()
        first.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
        val firstRead = first.recordPublicationRead()
        val firstSecondRead = first.recordPublicationRead()
        second.record(Todo18MiniHomeLoadDiagnostic.Forbidden)
        first.record(Todo18MiniHomeLoadDiagnostic.Ready)

        // Then
        val progress = recorder.snapshot()
        assertTrue(progress.valid)
        assertNotEquals(first.id, second.id)
        assertEquals(listOf(1L, 2L), listOf(firstRead.ordinal, firstSecondRead.ordinal))
        assertEquals(1L, secondRead.ordinal)
        assertEquals(first.id, firstRead.loadId)
        assertEquals(second.id, secondRead.loadId)
        assertEquals(
            listOf(
                first.id,
                first.id,
                second.id,
                second.id,
                second.id,
                second.id,
                first.id,
                first.id,
                first.id,
                second.id,
                first.id,
            ),
            progress.observations.map { it.loadId },
        )
    }

    @Test
    fun `cancelling one overlapping repository load terminates only that load`() = runTest {
        // Given
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val firstEntered = CompletableDeferred<Unit>()
        val delegate = OverlappingRepository(recorder, firstEntered)
        val repository = Todo18MiniHomeLoadDiagnosticRepository(delegate, recorder)
        val first = backgroundScope.async { repository.load() }
        firstEntered.await()

        // When
        val secondResult = repository.load()
        first.cancelAndJoin()

        // Then
        assertEquals(MiniHomeLoadResult.Forbidden, secondResult)
        val progress = recorder.snapshot()
        assertTrue(progress.valid)
        assertEquals(2, progress.loads.size)
        assertEquals("terminal-cancelled", progress.loads[0].lastReachedStage)
        assertEquals("terminal-forbidden", progress.loads[1].lastReachedStage)
        assertEquals(
            emptyList<Todo18MiniHomePublicationReadId>(),
            progress.loads[0].publicationReadIds,
        )
        assertEquals(listOf(1L), progress.loads[1].publicationReadIds.map { it.ordinal })
    }

    private class OverlappingRepository(
        private val recorder: Todo18MiniHomeLoadDiagnosticRecorder,
        private val firstEntered: CompletableDeferred<Unit>,
    ) : MiniHomeRepository {
        private val invocation = AtomicInteger()

        override suspend fun load(): MiniHomeLoadResult {
            recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
            if (invocation.incrementAndGet() == 1) {
                firstEntered.complete(Unit)
                awaitCancellation()
            }
            recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
            recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.PublicationReadEntered)
            return MiniHomeLoadResult.Forbidden
        }

        override suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult =
            MiniHomeSaveResult.Forbidden

        override suspend fun abandon(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult =
            MiniHomeDiscardResult.Rejected
    }
}
