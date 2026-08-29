package com.planterior.helper.minihome

import com.planterior.helper.feature.minihome.MiniHomeDiscardHandle
import com.planterior.helper.feature.minihome.MiniHomeDiscardResult
import com.planterior.helper.feature.minihome.MiniHomeLoadResult
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomeSaveRequest
import com.planterior.helper.feature.minihome.MiniHomeSaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class Todo18MiniHomeLoadDiagnosticFaultIdentityTest {
    @Test
    fun `invalid progression cannot replace the exact delegate exception`() = runTest {
        // Given
        val expected = IllegalStateException("delegate failed after invalid progression")
        lateinit var recorder: Todo18MiniHomeLoadDiagnosticRecorder
        val delegate = ActionRepository {
            recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.PublicationReadEntered)
            throw expected
        }
        recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val repository = Todo18MiniHomeLoadDiagnosticRepository(delegate, recorder)

        // When
        val actual =
            try {
                repository.load()
                fail("Expected delegate exception")
            } catch (failure: IllegalStateException) {
                failure
            }

        // Then
        assertSame(expected, actual)
        assertSame(
            Todo18MiniHomeLoadViolationKind.OUT_OF_ORDER_STAGE,
            repository.loadProgressSnapshot().progressionViolations.single().kind,
        )
    }

    @Test
    fun `recorder observer fault cannot replace the exact delegate cancellation`() = runTest {
        // Given
        val expected = CancellationException("lifecycle cancelled")
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {
            throw IllegalStateException("diagnostic observer failed")
        }
        val repository =
            Todo18MiniHomeLoadDiagnosticRepository(
                ActionRepository { throw expected },
                recorder,
            )

        // When
        val actual =
            try {
                repository.load()
                fail("Expected delegate cancellation")
            } catch (failure: CancellationException) {
                failure
            }

        // Then
        assertSame(expected, actual)
        assertEquals(2, repository.loadProgressSnapshot().recorderFailures.size)
    }

    @Test
    fun `recorder observer fault cannot replace the exact delegate assertion`() = runTest {
        // Given
        val expected = AssertionError("load assertion remained primary")
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {
            throw IllegalStateException("diagnostic observer failed")
        }
        val repository =
            Todo18MiniHomeLoadDiagnosticRepository(
                ActionRepository { throw expected },
                recorder,
            )

        // When
        val actual =
            try {
                repository.load()
                fail("Expected delegate assertion")
            } catch (failure: AssertionError) {
                failure
            }

        // Then
        assertSame(expected, actual)
        assertEquals(
            listOf(
                Todo18MiniHomeLoadDiagnostic.LoadEntered,
                Todo18MiniHomeLoadDiagnostic.Failed,
            ),
            repository.loadProgressSnapshot().observations.map { it.diagnostic },
        )
        assertEquals(2, repository.loadProgressSnapshot().recorderFailures.size)
    }

    private class ActionRepository(private val loadAction: suspend () -> MiniHomeLoadResult) :
        MiniHomeRepository {
        override suspend fun load(): MiniHomeLoadResult = loadAction()

        override suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult =
            MiniHomeSaveResult.Forbidden

        override suspend fun abandon(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult =
            MiniHomeDiscardResult.Rejected
    }
}
