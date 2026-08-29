package com.planterior.helper.feature.registration

import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId

@JvmInline value class RegistrationControllerIdentity(val value: Int)

sealed interface RegistrationDiagnosticEvent {
    val controllerIdentity: RegistrationControllerIdentity

    data class SelectContentScreen(
        override val controllerIdentity: RegistrationControllerIdentity,
        val requestedContentId: PlantContentId,
    ) : RegistrationDiagnosticEvent

    data class SelectContentControllerTransition(
        override val controllerIdentity: RegistrationControllerIdentity,
        val requestedContentId: PlantContentId,
        val before: RegistrationUiState,
        val after: RegistrationUiState,
    ) : RegistrationDiagnosticEvent

    data class RouteStateAudit(
        override val controllerIdentity: RegistrationControllerIdentity,
        val state: RegistrationUiState,
    ) : RegistrationDiagnosticEvent

    data class SubmitCallback(override val controllerIdentity: RegistrationControllerIdentity) :
        RegistrationDiagnosticEvent

    data class SubmitControllerEntry(
        override val controllerIdentity: RegistrationControllerIdentity,
        val state: RegistrationUiState,
    ) : RegistrationDiagnosticEvent

    data class SubmitValidation(
        override val controllerIdentity: RegistrationControllerIdentity,
        val outcome: RegistrationSubmitValidationOutcome,
    ) : RegistrationDiagnosticEvent

    data class DuplicateLookupBegin(
        override val controllerIdentity: RegistrationControllerIdentity,
        val contentId: PlantContentId,
    ) : RegistrationDiagnosticEvent

    data class DuplicateLookupResult(
        override val controllerIdentity: RegistrationControllerIdentity,
        val outcome: RegistrationDuplicateLookupOutcome,
    ) : RegistrationDiagnosticEvent

    data class CompletedPublication(
        override val controllerIdentity: RegistrationControllerIdentity,
        val plantId: PersonalPlantId,
    ) : RegistrationDiagnosticEvent

    data class NavigationEnqueued(
        override val controllerIdentity: RegistrationControllerIdentity,
        val navigationIdentity: String,
        val plantId: PersonalPlantId,
    ) : RegistrationDiagnosticEvent

    data class NavigationDispatched(
        override val controllerIdentity: RegistrationControllerIdentity,
        val navigationIdentity: String,
        val plantId: PersonalPlantId,
    ) : RegistrationDiagnosticEvent
}

sealed interface RegistrationSubmitValidationOutcome {
    data object Accepted : RegistrationSubmitValidationOutcome

    data class Rejected(val errors: Set<RegistrationValidationError>) :
        RegistrationSubmitValidationOutcome

    data object SessionUnavailable : RegistrationSubmitValidationOutcome

    data object NotEditable : RegistrationSubmitValidationOutcome
}

sealed interface RegistrationDuplicateLookupOutcome {
    data object Empty : RegistrationDuplicateLookupOutcome

    data class Found(val count: Int) : RegistrationDuplicateLookupOutcome

    data object Failed : RegistrationDuplicateLookupOutcome

    data object Cancelled : RegistrationDuplicateLookupOutcome
}

internal suspend fun performRegistrationSubmit(
    controller: RegistrationController,
    diagnosticObserver: ((RegistrationDiagnosticEvent) -> Unit)?,
) {
    safeRegistrationDiagnostic(diagnosticObserver) {
        RegistrationDiagnosticEvent.SubmitCallback(controller.diagnosticIdentity)
    }
    controller.submit()
}

internal fun performRegistrationSelectContent(
    controller: RegistrationController,
    content: RegistrationContent,
    diagnosticObserver: ((RegistrationDiagnosticEvent) -> Unit)?,
) {
    safeRegistrationDiagnostic(diagnosticObserver) {
        RegistrationDiagnosticEvent.SelectContentScreen(
            controller.diagnosticIdentity,
            content.id,
        )
    }
    controller.selectContent(content, diagnosticObserver)
}

internal fun publishRegistrationRouteState(
    controllerIdentity: RegistrationControllerIdentity,
    state: RegistrationUiState,
    diagnosticObserver: ((RegistrationDiagnosticEvent) -> Unit)?,
    publish: () -> Unit,
) {
    safeRegistrationDiagnostic(diagnosticObserver) {
        RegistrationDiagnosticEvent.RouteStateAudit(controllerIdentity, state)
    }
    publish()
}

internal fun safeRegistrationDiagnostic(
    observer: ((RegistrationDiagnosticEvent) -> Unit)?,
    event: () -> RegistrationDiagnosticEvent,
) {
    try {
        observer?.invoke(event())
    } catch (_: AssertionError) {
        // Diagnostics cannot alter the product transition.
    } catch (_: Exception) {
        // Diagnostics cannot alter the product transition.
    }
}
