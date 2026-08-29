package com.planterior.helper.diagnostic

import com.planterior.helper.Todo18RenderedStateSink
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.feature.registration.RegistrationContent
import com.planterior.helper.feature.registration.RegistrationDraft
import com.planterior.helper.feature.registration.RegistrationUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18DiagnosticFaultIsolationTest {
    @Test
    fun `recorder fault preserves primary delivery and makes receipt invalid`() {
        val sink =
            Todo18RenderedStateSink(
                Todo18RecorderFaultInjector { kind ->
                    if (kind == Todo18DiagnosticRecordKind.PIPELINE) {
                        throw AssertionError("injected recorder fault")
                    }
                }
            )
        val capture = sink.startDiagnosticCapture(Todo18WaitId.REGISTRATION_SELECT_CONTENT)
        var delivered: RegistrationUiState? = null
        val listener = sink.subscribeToRegistrationStates { delivered = it.state }
        val state = selectedState()

        sink.onRegistrationState(state)

        listener.close()
        val snapshot = capture.snapshot()
        capture.close()
        assertSame(state, delivered)
        assertTrue(Todo18DiagnosticFailure.RECORDER_CALLBACK_FAILED in snapshot.failures)
        assertEquals(
            Todo18DiagnosticClassification.INVALID_CAPTURE,
            classifyWith(snapshot.failures),
        )
    }

    @Test
    fun `recorder fault preserves exact primary listener exception identity`() {
        val sink =
            Todo18RenderedStateSink(
                Todo18RecorderFaultInjector { kind ->
                    if (kind == Todo18DiagnosticRecordKind.STATE_DISPATCH) {
                        throw IllegalStateException("injected recorder fault")
                    }
                }
            )
        val capture = sink.startDiagnosticCapture(Todo18WaitId.REGISTRATION_SELECT_CONTENT)
        val primary = IllegalArgumentException("primary listener failure")
        val listener = sink.subscribeToRegistrationStates { throw primary }

        val actual =
            try {
                sink.onRegistrationState(selectedState())
                error("primary listener failure did not escape")
            } catch (failure: IllegalArgumentException) {
                failure
            }

        listener.close()
        val snapshot = capture.snapshot()
        capture.close()
        assertSame(primary, actual)
        assertTrue(Todo18DiagnosticFailure.RECORDER_CALLBACK_FAILED in snapshot.failures)
        assertEquals(
            Todo18DiagnosticClassification.INVALID_CAPTURE,
            classifyWith(snapshot.failures),
        )
    }

    private fun classifyWith(failures: List<Todo18DiagnosticFailure>) =
        Todo18DiagnosticReducer.classify(
            Todo18DiagnosticReceiptFixtures.valid(Todo18WaitId.OFFLINE_BEGIN_EDIT).let { receipt ->
                receipt.copy(envelope = receipt.envelope.copy(diagnosticFailures = failures))
            }
        )

    private fun selectedState() =
        RegistrationUiState.Editing(
            RegistrationDraft(
                plantId = PersonalPlantId("diagnostic-plant"),
                operationId = null,
                name = "Monstera",
                selectedContent =
                    RegistrationContent(PlantContentId("species-monstera"), "Monstera"),
                photo = null,
                lastWateredDate = null,
            )
        )
}
