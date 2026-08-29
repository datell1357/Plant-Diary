package com.planterior.helper.diagnostic

import com.planterior.helper.Todo18RenderedStateSink
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.MiniHomeControllerIdentity
import com.planterior.helper.feature.minihome.MiniHomeDiagnosticEvent
import com.planterior.helper.feature.minihome.MiniHomeLayout
import com.planterior.helper.feature.minihome.MiniHomeRuntimeDiagnosticBinding
import com.planterior.helper.feature.minihome.MiniHomeSaveState
import com.planterior.helper.feature.minihome.MiniHomeUiState
import java.time.Instant
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18OfflineRuntimeFaultIsolationTest {
    @Test
    fun `pipeline diagnostic faults preserve displayed publication`() {
        val sink = faultingSink()
        val capture = sink.startDiagnosticCapture(Todo18WaitId.OFFLINE_BEGIN_EDIT)
        val state = editingState()
        var delivered: MiniHomeUiState? = null
        val listener = sink.subscribeToDisplayedMiniHomeStates { delivered = it.state }

        sink.onMiniHomeDiagnosticEvent(callbackEntry(state))
        sink.onMiniHomeDisplayedState(state)

        listener.close()
        val failures = capture.snapshot().failures
        capture.close()
        assertSame(state, delivered)
        assertTrue(Todo18DiagnosticFailure.RECORDER_CALLBACK_FAILED in failures)
    }

    @Test
    fun `pipeline diagnostic faults preserve exact primary exception identity`() {
        val sink = faultingSink()
        val capture = sink.startDiagnosticCapture(Todo18WaitId.OFFLINE_BEGIN_EDIT)
        val state = editingState()
        val primary = IllegalArgumentException("primary displayed listener failure")
        val listener = sink.subscribeToDisplayedMiniHomeStates { throw primary }
        sink.onMiniHomeDiagnosticEvent(callbackEntry(state))

        val actual =
            try {
                sink.onMiniHomeDisplayedState(state)
                error("primary listener failure did not escape")
            } catch (failure: IllegalArgumentException) {
                failure
            }

        listener.close()
        capture.close()
        assertSame(primary, actual)
    }

    private fun faultingSink() =
        Todo18RenderedStateSink(
            Todo18RecorderFaultInjector { kind ->
                if (kind == Todo18DiagnosticRecordKind.PIPELINE) {
                    throw AssertionError("injected runtime-boundary recorder fault")
                }
            }
        )

    private fun callbackEntry(state: MiniHomeUiState) =
        MiniHomeDiagnosticEvent.DisplayedCallbackEntry(
            controllerIdentity = MiniHomeControllerIdentity(41),
            state = state,
            runtimeBinding = runtimeBinding(),
        )

    private fun runtimeBinding() =
        MiniHomeRuntimeDiagnosticBinding(
            controllerIdentity = MiniHomeControllerIdentity(41),
            controllerEpoch = 1L,
            controllerGeneration = 1L,
            collectorGeneration = 2L,
            callbackGeneration = 3L,
            attachGeneration = 1L,
            disposeGeneration = 0L,
            lifecycleOwnerIdentity = "lifecycle-1",
            lifecycleState = "RESUMED",
            activityIdentity = "activity-1",
            navHostIdentity = "nav-host-1",
            callbackSinkIdentity = "sink-1",
        )

    private fun editingState(): MiniHomeUiState.Editing {
        val layout =
            MiniHomeLayout(
                id = MiniHomeId("offline-fault-layout"),
                name = "Offline fault layout",
                placements = emptyList(),
                revision = Revision(1L),
                updatedAt = Instant.EPOCH,
            )
        return MiniHomeUiState.Editing(
            committed = layout,
            draft = layout,
            plants = emptyList(),
            decorations = emptyList(),
            selectedPlacementId = null,
            operationId = OperationId("offline-fault-operation"),
            saveState = MiniHomeSaveState.Idle,
            owner = AccountId("offline-fault-owner"),
        )
    }
}
