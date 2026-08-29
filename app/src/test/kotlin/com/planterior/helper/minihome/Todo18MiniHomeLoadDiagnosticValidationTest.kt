package com.planterior.helper.minihome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18MiniHomeLoadDiagnosticValidationTest {
    @Test
    fun `normal sequence remains valid`() {
        // Given
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()

        // When
        NORMAL_SEQUENCE.forEach { load.recordDiagnostic(it) }

        // Then
        assertTrue(recorder.snapshot().valid)
        assertEquals(
            emptyList<Todo18MiniHomeLoadProgressionViolation>(),
            recorder.snapshot().progressionViolations,
        )
    }

    @Test
    fun `duplicate load entry reaches observer and makes progress invalid`() {
        // Given / When / Then
        assertDuplicate(
            Todo18MiniHomeLoadDiagnostic.LoadEntered,
            listOf(Todo18MiniHomeLoadDiagnostic.LoadEntered),
        )
    }

    @Test
    fun `duplicate remote entry reaches observer and makes progress invalid`() {
        // Given / When / Then
        assertDuplicate(
            Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered,
            listOf(
                Todo18MiniHomeLoadDiagnostic.LoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered,
            ),
        )
    }

    @Test
    fun `duplicate remote return reaches observer and makes progress invalid`() {
        // Given / When / Then
        assertDuplicate(
            Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned,
            listOf(
                Todo18MiniHomeLoadDiagnostic.LoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned,
            ),
        )
    }

    @Test
    fun `two coherence publication callbacks in one load remain valid and ordered`() {
        // Given
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()
        NORMAL_SEQUENCE.dropLast(1).forEach { load.recordDiagnostic(it) }

        // When
        load.recordPublicationRead()

        // Then
        val progress = recorder.snapshot()
        assertTrue(progress.valid)
        assertEquals(listOf(1L, 2L), progress.loads.single().publicationReadIds.map { it.ordinal })
        assertEquals((1L..5L).toList(), progress.observations.map { it.order })
    }

    @Test
    fun `same explicit publication read identity is an invalid duplicate`() {
        // Given
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()
        listOf(
                Todo18MiniHomeLoadDiagnostic.LoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned,
            )
            .forEach { load.recordDiagnostic(it) }
        val readId = load.recordPublicationRead()

        // When
        load.recordPublicationRead(readId)

        // Then
        val violation = recorder.snapshot().progressionViolations.single()
        assertEquals(Todo18MiniHomeLoadViolationKind.DUPLICATE_STAGE, violation.kind)
        assertEquals(load.id, violation.loadId)
        assertEquals(readId, violation.readId)
    }

    @Test
    fun `publication before remote return is a typed out of order violation`() {
        // Given
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()
        load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)

        // When
        load.recordPublicationRead()

        // Then
        assertViolation(
            recorder.snapshot(),
            Todo18MiniHomeLoadViolationKind.OUT_OF_ORDER_STAGE,
            Todo18MiniHomeLoadDiagnostic.PublicationReadEntered,
        )
    }

    @Test
    fun `stage after terminal reaches observer as a typed violation`() {
        // Given
        val observed = mutableListOf<Todo18MiniHomeLoadObservation>()
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder(observed::add)
        val load = recorder.startLoad()
        load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.Forbidden)

        // When
        load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)

        // Then
        assertEquals(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered, observed.last().diagnostic)
        assertViolation(
            recorder.snapshot(),
            Todo18MiniHomeLoadViolationKind.STAGE_AFTER_TERMINAL,
            Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered,
        )
    }

    @Test
    fun `second terminal reaches observer as a typed multiple terminal violation`() {
        // Given
        val observed = mutableListOf<Todo18MiniHomeLoadObservation>()
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder(observed::add)
        val load = recorder.startLoad()
        load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.Ready)

        // When
        load.record(Todo18MiniHomeLoadDiagnostic.Cancelled)

        // Then
        assertEquals(Todo18MiniHomeLoadDiagnostic.Cancelled, observed.last().diagnostic)
        assertViolation(
            recorder.snapshot(),
            Todo18MiniHomeLoadViolationKind.MULTIPLE_TERMINAL,
            Todo18MiniHomeLoadDiagnostic.Cancelled,
        )
    }

    private fun assertDuplicate(
        duplicate: Todo18MiniHomeLoadDiagnostic,
        sequence: List<Todo18MiniHomeLoadDiagnostic>,
    ) {
        val observed = mutableListOf<Todo18MiniHomeLoadObservation>()
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder(observed::add)
        val load = recorder.startLoad()
        sequence.forEach { load.recordDiagnostic(it) }

        load.record(duplicate)

        assertEquals(sequence + duplicate, observed.map { it.diagnostic })
        assertViolation(
            recorder.snapshot(),
            Todo18MiniHomeLoadViolationKind.DUPLICATE_STAGE,
            duplicate,
        )
    }

    private fun assertViolation(
        progress: Todo18MiniHomeLoadProgress,
        expectedKind: Todo18MiniHomeLoadViolationKind,
        expectedDiagnostic: Todo18MiniHomeLoadDiagnostic,
    ) {
        assertFalse(progress.valid)
        val violation = progress.progressionViolations.single()
        assertEquals(expectedKind, violation.kind)
        assertEquals(expectedDiagnostic.receiptStage, violation.observedStage)
        assertTrue(progress.progressionProblems().single().startsWith("invalid-load-progression:"))
    }

    private fun Todo18MiniHomeLoad.recordDiagnostic(diagnostic: Todo18MiniHomeLoadDiagnostic) {
        if (diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadEntered) {
            recordPublicationRead()
        } else {
            record(diagnostic)
        }
    }

    private companion object {
        val NORMAL_SEQUENCE =
            listOf(
                Todo18MiniHomeLoadDiagnostic.LoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned,
                Todo18MiniHomeLoadDiagnostic.PublicationReadEntered,
                Todo18MiniHomeLoadDiagnostic.Ready,
            )
    }
}
