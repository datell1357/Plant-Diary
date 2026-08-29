package com.planterior.helper.feature.minihome

@JvmInline value class MiniHomeControllerIdentity(val value: Int)

data class MiniHomeHostDiagnosticIdentity(
    val activityIdentity: String,
    val navHostIdentity: String,
    val callbackSinkIdentity: String,
)

data class MiniHomeRuntimeDiagnosticBinding(
    val controllerIdentity: MiniHomeControllerIdentity,
    val controllerEpoch: Long,
    val controllerGeneration: Long,
    val collectorGeneration: Long,
    val callbackGeneration: Long,
    val attachGeneration: Long,
    val disposeGeneration: Long,
    val lifecycleOwnerIdentity: String,
    val lifecycleState: String,
    val activityIdentity: String,
    val navHostIdentity: String,
    val callbackSinkIdentity: String,
)

class MiniHomeDiagnosticGenerations {
    private var current = 0L

    var latestDisposeGeneration: Long = 0L
        private set

    fun next(): Long {
        current += 1L
        return current
    }

    fun markDisposed() {
        latestDisposeGeneration = next()
    }
}

sealed interface MiniHomeDiagnosticEvent {
    val controllerIdentity: MiniHomeControllerIdentity

    data class BeginEditScreen(override val controllerIdentity: MiniHomeControllerIdentity) :
        MiniHomeDiagnosticEvent

    data class BeginEditControllerTransition(
        override val controllerIdentity: MiniHomeControllerIdentity,
        val before: MiniHomeUiState,
        val after: MiniHomeUiState,
    ) : MiniHomeDiagnosticEvent

    data class RouteStateAudit(
        override val controllerIdentity: MiniHomeControllerIdentity,
        val state: MiniHomeUiState,
        val runtimeBinding: MiniHomeRuntimeDiagnosticBinding? = null,
    ) : MiniHomeDiagnosticEvent

    data class DisplayedCallbackEntry(
        override val controllerIdentity: MiniHomeControllerIdentity,
        val state: MiniHomeUiState,
        val runtimeBinding: MiniHomeRuntimeDiagnosticBinding,
    ) : MiniHomeDiagnosticEvent

    data class DisplayedCallbackReturn(
        override val controllerIdentity: MiniHomeControllerIdentity,
        val state: MiniHomeUiState,
        val runtimeBinding: MiniHomeRuntimeDiagnosticBinding,
    ) : MiniHomeDiagnosticEvent
}

internal data class MiniHomeDisplayedStateDiagnostic(
    val observer: ((MiniHomeDiagnosticEvent) -> Unit)?,
    val binding: MiniHomeRuntimeDiagnosticBinding,
)

internal fun publishMiniHomeDisplayedState(
    state: MiniHomeUiState,
    diagnostic: MiniHomeDisplayedStateDiagnostic?,
    publish: () -> Unit,
) {
    diagnostic?.let {
        safeMiniHomeDiagnostic(it.observer) {
            MiniHomeDiagnosticEvent.DisplayedCallbackEntry(
                it.binding.controllerIdentity,
                state,
                it.binding,
            )
        }
    }
    try {
        publish()
    } finally {
        diagnostic?.let {
            safeMiniHomeDiagnostic(it.observer) {
                MiniHomeDiagnosticEvent.DisplayedCallbackReturn(
                    it.binding.controllerIdentity,
                    state,
                    it.binding,
                )
            }
        }
    }
}

internal fun performMiniHomeBeginEdit(
    controller: MiniHomeController,
    diagnosticObserver: ((MiniHomeDiagnosticEvent) -> Unit)?,
) {
    safeMiniHomeDiagnostic(diagnosticObserver) {
        MiniHomeDiagnosticEvent.BeginEditScreen(controller.diagnosticIdentity)
    }
    controller.beginEditing(diagnosticObserver)
}

internal fun publishMiniHomeRouteState(
    controllerIdentity: MiniHomeControllerIdentity,
    state: MiniHomeUiState,
    diagnosticObserver: ((MiniHomeDiagnosticEvent) -> Unit)?,
    runtimeBinding: MiniHomeRuntimeDiagnosticBinding? = null,
    publish: () -> Unit,
) {
    safeMiniHomeDiagnostic(diagnosticObserver) {
        MiniHomeDiagnosticEvent.RouteStateAudit(controllerIdentity, state, runtimeBinding)
    }
    publish()
}

internal fun safeMiniHomeDiagnostic(
    observer: ((MiniHomeDiagnosticEvent) -> Unit)?,
    event: () -> MiniHomeDiagnosticEvent,
) {
    try {
        observer?.invoke(event())
    } catch (_: AssertionError) {
        // Diagnostics cannot alter the product transition.
    } catch (_: Exception) {
        // Diagnostics cannot alter the product transition.
    }
}
