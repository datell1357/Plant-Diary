package com.planterior.helper.diagnostic

import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.feature.minihome.MiniHomeDiagnosticEvent
import com.planterior.helper.feature.minihome.MiniHomeRuntimeDiagnosticBinding
import com.planterior.helper.feature.minihome.MiniHomeUiState
import com.planterior.helper.feature.registration.RegistrationDiagnosticEvent
import com.planterior.helper.feature.registration.RegistrationDuplicateLookupOutcome
import com.planterior.helper.feature.registration.RegistrationSubmitValidationOutcome
import com.planterior.helper.feature.registration.RegistrationUiState
import com.planterior.helper.kind

internal class Todo18ProductPipelineDiagnostic(private val recorder: Todo18WaitDiagnosticRecorder) {
    fun onMiniHomeEvent(event: MiniHomeDiagnosticEvent) {
        when (event) {
            is MiniHomeDiagnosticEvent.BeginEditScreen ->
                record(
                    Todo18PipelineEventKind.SCREEN_CALLBACK,
                    event.controllerIdentity.value,
                )
            is MiniHomeDiagnosticEvent.BeginEditControllerTransition -> {
                recordTransition(
                    Todo18PipelineEventKind.CONTROLLER_ENTRY,
                    event.controllerIdentity.value,
                    event.before.kind(),
                    event.after.kind(),
                )
                if (event.after is MiniHomeUiState.Editing) {
                    recordTransition(
                        Todo18PipelineEventKind.CONTROLLER_TARGET_STATE,
                        event.controllerIdentity.value,
                        event.before.kind(),
                        event.after.kind(),
                    )
                }
            }
            is MiniHomeDiagnosticEvent.RouteStateAudit ->
                if (isTarget(event.state)) {
                    record(
                        Todo18PipelineEventKind.ROUTE_STATE_OBSERVED,
                        event.controllerIdentity.value,
                        runtimeBinding = event.runtimeBinding?.toTodo18RuntimeBinding(),
                    )
                }
            is MiniHomeDiagnosticEvent.DisplayedCallbackEntry ->
                if (isTarget(event.state)) {
                    record(
                        Todo18PipelineEventKind.DISPLAYED_CALLBACK_ENTRY,
                        event.controllerIdentity.value,
                        runtimeBinding = event.runtimeBinding.toTodo18RuntimeBinding(),
                    )
                }
            is MiniHomeDiagnosticEvent.DisplayedCallbackReturn ->
                if (isTarget(event.state)) {
                    record(
                        Todo18PipelineEventKind.DISPLAYED_CALLBACK_RETURN,
                        event.controllerIdentity.value,
                        runtimeBinding = event.runtimeBinding.toTodo18RuntimeBinding(),
                    )
                }
        }
    }

    fun onRegistrationEvent(event: RegistrationDiagnosticEvent) {
        when (event) {
            is RegistrationDiagnosticEvent.SelectContentScreen ->
                recorder.recordPipeline(
                    Todo18PipelineEventKind.SCREEN_CALLBACK,
                    controllerIdentity = event.controllerIdentity.value,
                    requestedContentId = event.requestedContentId,
                )
            is RegistrationDiagnosticEvent.SelectContentControllerTransition -> {
                recordTransition(
                    Todo18PipelineEventKind.CONTROLLER_ENTRY,
                    event.controllerIdentity.value,
                    event.before.kind(),
                    event.after.kind(),
                    event.requestedContentId,
                )
                if (isTarget(event.after)) {
                    recordTransition(
                        Todo18PipelineEventKind.CONTROLLER_TARGET_STATE,
                        event.controllerIdentity.value,
                        event.before.kind(),
                        event.after.kind(),
                        event.requestedContentId,
                    )
                }
            }
            is RegistrationDiagnosticEvent.RouteStateAudit ->
                if (isTarget(event.state)) {
                    record(
                        Todo18PipelineEventKind.ROUTE_STATE_OBSERVED,
                        event.controllerIdentity.value,
                    )
                }
            is RegistrationDiagnosticEvent.SubmitCallback ->
                record(Todo18PipelineEventKind.SUBMIT_CALLBACK, event.controllerIdentity.value)
            is RegistrationDiagnosticEvent.SubmitControllerEntry ->
                record(
                    Todo18PipelineEventKind.REGISTRATION_CONTROLLER_ENTRY,
                    event.controllerIdentity.value,
                )
            is RegistrationDiagnosticEvent.SubmitValidation ->
                record(
                    when (event.outcome) {
                        RegistrationSubmitValidationOutcome.Accepted ->
                            Todo18PipelineEventKind.REGISTRATION_VALIDATION_ACCEPTED
                        is RegistrationSubmitValidationOutcome.Rejected,
                        RegistrationSubmitValidationOutcome.SessionUnavailable,
                        RegistrationSubmitValidationOutcome.NotEditable ->
                            Todo18PipelineEventKind.REGISTRATION_VALIDATION_REJECTED
                    },
                    event.controllerIdentity.value,
                )
            is RegistrationDiagnosticEvent.DuplicateLookupBegin ->
                record(
                    Todo18PipelineEventKind.DUPLICATE_LOOKUP_BEGIN,
                    event.controllerIdentity.value,
                )
            is RegistrationDiagnosticEvent.DuplicateLookupResult ->
                record(
                    when (event.outcome) {
                        RegistrationDuplicateLookupOutcome.Empty ->
                            Todo18PipelineEventKind.DUPLICATE_LOOKUP_EMPTY
                        is RegistrationDuplicateLookupOutcome.Found ->
                            Todo18PipelineEventKind.DUPLICATE_LOOKUP_FOUND
                        RegistrationDuplicateLookupOutcome.Failed ->
                            Todo18PipelineEventKind.DUPLICATE_LOOKUP_FAILED
                        RegistrationDuplicateLookupOutcome.Cancelled ->
                            Todo18PipelineEventKind.DUPLICATE_LOOKUP_CANCELLED
                    },
                    event.controllerIdentity.value,
                )
            is RegistrationDiagnosticEvent.CompletedPublication ->
                record(
                    Todo18PipelineEventKind.REGISTRATION_COMPLETED_PUBLICATION,
                    event.controllerIdentity.value,
                    registrationPlantId = event.plantId,
                )
            is RegistrationDiagnosticEvent.NavigationEnqueued ->
                record(
                    Todo18PipelineEventKind.REGISTRATION_NAVIGATION_ENQUEUED,
                    event.controllerIdentity.value,
                    registrationPlantId = event.plantId,
                    navigationIdentity = event.navigationIdentity,
                )
            is RegistrationDiagnosticEvent.NavigationDispatched ->
                record(
                    Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DISPATCHED,
                    event.controllerIdentity.value,
                    registrationPlantId = event.plantId,
                    navigationIdentity = event.navigationIdentity,
                )
        }
    }

    fun isTarget(state: MiniHomeUiState): Boolean =
        when (recorder.activeWaitId()) {
            Todo18WaitId.CONFLICT_BEGIN_EDIT,
            Todo18WaitId.OFFLINE_BEGIN_EDIT -> state is MiniHomeUiState.Editing
            Todo18WaitId.OFFLINE_INITIAL_VIEWING -> state is MiniHomeUiState.Viewing
            else -> false
        }

    fun isTarget(state: RegistrationUiState): Boolean =
        recorder.activeWaitId() == Todo18WaitId.REGISTRATION_SELECT_CONTENT &&
            state is RegistrationUiState.Editing &&
            state.draft.selectedContent != null

    private fun record(
        kind: Todo18PipelineEventKind,
        controllerIdentity: Int,
        registrationPlantId: com.planterior.helper.core.model.PersonalPlantId? = null,
        navigationIdentity: String? = null,
        runtimeBinding: Todo18RuntimeBinding? = null,
    ) {
        recorder.recordPipeline(
            kind = kind,
            controllerIdentity = controllerIdentity,
            registrationPlantId = registrationPlantId,
            navigationIdentity = navigationIdentity,
            runtimeBinding = runtimeBinding,
        )
    }

    private fun recordTransition(
        kind: Todo18PipelineEventKind,
        controllerIdentity: Int,
        beforeState: Todo18StateKind,
        afterState: Todo18StateKind,
        requestedContentId: PlantContentId? = null,
    ) {
        recorder.recordPipeline(
            kind,
            controllerIdentity = controllerIdentity,
            requestedContentId = requestedContentId,
            beforeState = beforeState,
            afterState = afterState,
        )
    }
}

internal fun MiniHomeRuntimeDiagnosticBinding.toTodo18RuntimeBinding(): Todo18RuntimeBinding =
    Todo18RuntimeBinding(
        controllerIdentity = controllerIdentity.value.toString(),
        controllerEpoch = controllerEpoch,
        controllerGeneration = controllerGeneration,
        collectorGeneration = collectorGeneration,
        callbackGeneration = callbackGeneration,
        attachGeneration = attachGeneration,
        disposeGeneration = disposeGeneration,
        lifecycleOwnerIdentity = lifecycleOwnerIdentity,
        lifecycleState = lifecycleState,
        activityIdentity = activityIdentity,
        navHostIdentity = navHostIdentity,
        callbackSinkIdentity = callbackSinkIdentity,
    )
