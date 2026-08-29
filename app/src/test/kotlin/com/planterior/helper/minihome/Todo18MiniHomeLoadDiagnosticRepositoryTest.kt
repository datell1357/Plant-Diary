package com.planterior.helper.minihome

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.MiniHomeDiscardHandle
import com.planterior.helper.feature.minihome.MiniHomeDiscardResult
import com.planterior.helper.feature.minihome.MiniHomeLayout
import com.planterior.helper.feature.minihome.MiniHomeLoadResult
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomeSaveRequest
import com.planterior.helper.feature.minihome.MiniHomeSaveResult
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class Todo18MiniHomeLoadDiagnosticRepositoryTest {
    @Test
    fun `ready load records every stage once and preserves the delegate value`() = runTest {
        // Given
        val expected = ready()
        val events = mutableListOf<Todo18MiniHomeLoadObservation>()
        val diagnostics = Todo18MiniHomeLoadDiagnosticRecorder(events::add)
        val delegate =
            RecordingMiniHomeRepository(
                loadAction = {
                    diagnostics.recordCurrent(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
                    diagnostics.recordCurrent(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
                    diagnostics.recordCurrent(Todo18MiniHomeLoadDiagnostic.PublicationReadEntered)
                    expected
                }
            )
        val repository = Todo18MiniHomeLoadDiagnosticRepository(delegate, diagnostics)

        // When
        val actual = repository.load()

        // Then
        assertSame(expected, actual)
        assertEquals(1, delegate.loadCount)
        assertEquals(
            listOf(
                Todo18MiniHomeLoadDiagnostic.LoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned,
                Todo18MiniHomeLoadDiagnostic.PublicationReadEntered,
                Todo18MiniHomeLoadDiagnostic.Ready,
            ),
            events.map { it.diagnostic },
        )
        val progress = repository.loadProgressSnapshot()
        assertEquals(true, progress.valid)
        assertEquals(null, progress.activeStage)
        assertEquals("terminal-ready", progress.lastReachedStage)
    }

    @Test
    fun `forbidden load records one terminal and preserves the typed outcome`() = runTest {
        // Given
        val expected = MiniHomeLoadResult.Forbidden
        val events = mutableListOf<Todo18MiniHomeLoadDiagnostic>()
        val delegate = RecordingMiniHomeRepository(loadAction = { expected })
        val repository = Todo18MiniHomeLoadDiagnosticRepository(delegate, events::add)

        // When
        val actual = repository.load()

        // Then
        assertSame(expected, actual)
        assertEquals(1, delegate.loadCount)
        assertEquals(
            listOf(
                Todo18MiniHomeLoadDiagnostic.LoadEntered,
                Todo18MiniHomeLoadDiagnostic.Forbidden,
            ),
            events,
        )
    }

    @Test
    fun `failed load records one terminal and preserves the typed outcome`() = runTest {
        // Given
        val expected = MiniHomeLoadResult.Failed
        val events = mutableListOf<Todo18MiniHomeLoadDiagnostic>()
        val delegate = RecordingMiniHomeRepository(loadAction = { expected })
        val repository = Todo18MiniHomeLoadDiagnosticRepository(delegate, events::add)

        // When
        val actual = repository.load()

        // Then
        assertSame(expected, actual)
        assertEquals(
            listOf(
                Todo18MiniHomeLoadDiagnostic.LoadEntered,
                Todo18MiniHomeLoadDiagnostic.Failed,
            ),
            events,
        )
    }

    @Test
    fun `cancellation records one terminal and rethrows the same cancellation`() = runTest {
        // Given
        val expected = CancellationException("Todo18 load cancelled")
        val events = mutableListOf<Todo18MiniHomeLoadDiagnostic>()
        val delegate = RecordingMiniHomeRepository(loadAction = { throw expected })
        val repository = Todo18MiniHomeLoadDiagnosticRepository(delegate, events::add)

        // When
        val actual =
            try {
                repository.load()
                fail("Expected cancellation")
            } catch (error: CancellationException) {
                error
            }

        // Then
        assertSame(expected, actual)
        assertEquals(
            listOf(
                Todo18MiniHomeLoadDiagnostic.LoadEntered,
                Todo18MiniHomeLoadDiagnostic.Cancelled,
            ),
            events,
        )
    }

    @Test
    fun `exception records one failed terminal and rethrows the same exception`() = runTest {
        // Given
        val expected = IllegalStateException("Todo18 unexpected load failure")
        val events = mutableListOf<Todo18MiniHomeLoadDiagnostic>()
        val delegate = RecordingMiniHomeRepository(loadAction = { throw expected })
        val repository = Todo18MiniHomeLoadDiagnosticRepository(delegate, events::add)

        // When
        val actual =
            try {
                repository.load()
                fail("Expected load failure")
            } catch (error: IllegalStateException) {
                error
            }

        // Then
        assertSame(expected, actual)
        assertEquals(
            listOf(
                Todo18MiniHomeLoadDiagnostic.LoadEntered,
                Todo18MiniHomeLoadDiagnostic.Failed,
            ),
            events,
        )
    }

    @Test
    fun `save delegates once without load diagnostics`() = runTest {
        // Given
        val expected = MiniHomeSaveResult.Forbidden
        val delegate = RecordingMiniHomeRepository(loadAction = { ready() }, saveResult = expected)
        val events = mutableListOf<Todo18MiniHomeLoadDiagnostic>()
        val repository = Todo18MiniHomeLoadDiagnosticRepository(delegate, events::add)
        val request = saveRequest()

        // When
        val actual = repository.save(request)

        // Then
        assertSame(expected, actual)
        assertEquals(0, delegate.loadCount)
        assertEquals(1, delegate.saveCount)
        assertSame(request, delegate.savedRequest)
        assertEquals(emptyList<Todo18MiniHomeLoadDiagnostic>(), events)
    }

    private fun ready(): MiniHomeLoadResult.Ready =
        MiniHomeLoadResult.Ready(
            accountId = ACCOUNT_ID,
            committed = layout(),
            plants = emptyList(),
            decorations = emptyList(),
            stale = false,
            pending = null,
        )

    private fun saveRequest(): MiniHomeSaveRequest =
        MiniHomeSaveRequest(
            accountId = ACCOUNT_ID,
            operationId = OperationId("todo18-diagnostic-operation"),
            expectedRevision = Revision(1),
            layout = layout(),
        )

    private fun layout(): MiniHomeLayout =
        MiniHomeLayout(
            id = MiniHomeId("todo18-diagnostic-home"),
            name = "Todo18 diagnostic room",
            placements = emptyList(),
            revision = Revision(1),
            updatedAt = Instant.EPOCH,
        )

    private class RecordingMiniHomeRepository(
        private val loadAction: suspend () -> MiniHomeLoadResult,
        private val saveResult: MiniHomeSaveResult = MiniHomeSaveResult.Forbidden,
    ) : MiniHomeRepository {
        var loadCount = 0
            private set

        var saveCount = 0
            private set

        var savedRequest: MiniHomeSaveRequest? = null
            private set

        override suspend fun load(): MiniHomeLoadResult {
            loadCount += 1
            return loadAction()
        }

        override suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult {
            saveCount += 1
            savedRequest = request
            return saveResult
        }

        override suspend fun abandon(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult =
            MiniHomeDiscardResult.Rejected
    }

    private companion object {
        val ACCOUNT_ID = AccountId("todo18-diagnostic-owner")
    }
}
