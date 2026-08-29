package com.planterior.helper.minihome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Todo18MiniHomeLoadTerminalPermanenceTest {
    @Test
    fun `non adjacent second terminal is a dedicated multiple terminal violation`() {
        // Given
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()
        load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.Ready)
        load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)

        // When
        load.record(Todo18MiniHomeLoadDiagnostic.Cancelled)

        // Then
        val progress = recorder.snapshot()
        assertEquals(
            listOf(
                Todo18MiniHomeLoadViolationKind.STAGE_AFTER_TERMINAL,
                Todo18MiniHomeLoadViolationKind.MULTIPLE_TERMINAL,
            ),
            progress.progressionViolations.map { it.kind },
        )
        assertEquals(
            "remote-load-entered",
            progress.progressionViolations.last().previousStage,
        )
    }

    @Test
    fun `non adjacent publication remains stage after terminal`() {
        // Given
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()
        load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.Ready)
        load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)

        // When
        load.recordPublicationRead()

        // Then
        assertEquals(
            listOf(
                Todo18MiniHomeLoadViolationKind.STAGE_AFTER_TERMINAL,
                Todo18MiniHomeLoadViolationKind.STAGE_AFTER_TERMINAL,
            ),
            recorder.snapshot().progressionViolations.map { it.kind },
        )
    }

    @Test
    fun `ready terminal remains permanent across later terminal and non terminal observations`() {
        // Given / When / Then
        assertTerminalPermanent(
            Todo18MiniHomeLoadDiagnostic.Ready,
            Todo18MiniHomeLoadDiagnostic.Cancelled,
        )
    }

    @Test
    fun `forbidden terminal remains permanent across later terminal and non terminal observations`() {
        // Given / When / Then
        assertTerminalPermanent(
            Todo18MiniHomeLoadDiagnostic.Forbidden,
            Todo18MiniHomeLoadDiagnostic.Ready,
        )
    }

    @Test
    fun `failed terminal remains permanent across later terminal and non terminal observations`() {
        // Given / When / Then
        assertTerminalPermanent(
            Todo18MiniHomeLoadDiagnostic.Failed,
            Todo18MiniHomeLoadDiagnostic.Forbidden,
        )
    }

    @Test
    fun `cancelled terminal remains permanent across later terminal and non terminal observations`() {
        // Given / When / Then
        assertTerminalPermanent(
            Todo18MiniHomeLoadDiagnostic.Cancelled,
            Todo18MiniHomeLoadDiagnostic.Failed,
        )
    }

    private fun assertTerminalPermanent(
        initialTerminal: Todo18MiniHomeLoadDiagnostic.Terminal,
        laterTerminal: Todo18MiniHomeLoadDiagnostic.Terminal,
    ) {
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()
        val observations =
            listOf(
                Todo18MiniHomeLoadDiagnostic.LoadEntered,
                initialTerminal,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered,
                laterTerminal,
                Todo18MiniHomeLoadDiagnostic.PublicationReadEntered,
            )

        observations.forEach { diagnostic ->
            if (diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadEntered) {
                load.recordPublicationRead()
            } else {
                load.record(diagnostic)
            }
        }

        val progress = recorder.snapshot()
        assertFalse(progress.valid)
        assertEquals(observations.map { it.receiptStage }, progress.reachedStages)
        assertEquals(
            listOf(
                Todo18MiniHomeLoadViolationKind.STAGE_AFTER_TERMINAL,
                Todo18MiniHomeLoadViolationKind.MULTIPLE_TERMINAL,
                Todo18MiniHomeLoadViolationKind.STAGE_AFTER_TERMINAL,
            ),
            progress.progressionViolations.map { it.kind },
        )
        assertEquals(
            listOf(
                "remote-load-entered",
                laterTerminal.receiptStage,
                "publication-read-entered",
            ),
            progress.progressionViolations.map { it.observedStage },
        )
    }
}
