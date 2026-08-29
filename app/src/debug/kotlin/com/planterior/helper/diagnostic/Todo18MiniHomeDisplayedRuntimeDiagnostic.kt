package com.planterior.helper.diagnostic

import com.planterior.helper.feature.minihome.MiniHomeDiagnosticEvent
import com.planterior.helper.feature.minihome.MiniHomeRuntimeDiagnosticBinding
import com.planterior.helper.feature.minihome.MiniHomeUiState
import java.util.concurrent.atomic.AtomicReference

internal class Todo18MiniHomeDisplayedRuntimeDiagnostic(
    private val recorder: Todo18WaitDiagnosticRecorder,
    private val productPipeline: Todo18ProductPipelineDiagnostic,
) {
    private val callbackBinding = AtomicReference<MiniHomeRuntimeDiagnosticBinding?>()

    fun onDiagnosticEvent(event: MiniHomeDiagnosticEvent) {
        when (event) {
            is MiniHomeDiagnosticEvent.DisplayedCallbackEntry ->
                callbackBinding.set(event.runtimeBinding)
            is MiniHomeDiagnosticEvent.DisplayedCallbackReturn -> Unit
            is MiniHomeDiagnosticEvent.BeginEditScreen,
            is MiniHomeDiagnosticEvent.BeginEditControllerTransition,
            is MiniHomeDiagnosticEvent.RouteStateAudit -> Unit
        }
        try {
            productPipeline.onMiniHomeEvent(event)
        } finally {
            if (event is MiniHomeDiagnosticEvent.DisplayedCallbackReturn) {
                callbackBinding.compareAndSet(event.runtimeBinding, null)
            }
        }
    }

    fun onSinkEntry(state: MiniHomeUiState): MiniHomeRuntimeDiagnosticBinding? {
        val binding = callbackBinding.get()
        if (productPipeline.isTarget(state) && binding != null) {
            recorder.recordPipeline(
                kind = Todo18PipelineEventKind.DISPLAYED_SINK_ENTRY,
                controllerIdentity = binding.controllerIdentity.value,
                runtimeBinding = binding.toTodo18RuntimeBinding(),
            )
        }
        return binding
    }

    fun onSinkReturn(
        state: MiniHomeUiState,
        sourceSequence: Long,
        binding: MiniHomeRuntimeDiagnosticBinding?,
    ) {
        if (productPipeline.isTarget(state) && binding != null) {
            recorder.recordPipeline(
                kind = Todo18PipelineEventKind.DISPLAYED_SINK_RETURN,
                sourceSequence = sourceSequence,
                controllerIdentity = binding.controllerIdentity.value,
                runtimeBinding = binding.toTodo18RuntimeBinding(),
            )
        }
    }
}
