package com.planterior.helper

import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.diagnostic.Todo18CaptureFreshness
import com.planterior.helper.diagnostic.Todo18StateChannel
import com.planterior.helper.diagnostic.Todo18StateKind
import com.planterior.helper.diagnostic.Todo18StateSnapshot
import com.planterior.helper.feature.minihome.MiniHomeUiState
import com.planterior.helper.feature.registration.RegistrationUiState

internal fun Todo18RenderedStateSink.captureFreshness() =
    Todo18CaptureFreshness(
        initialSequence = sequenceValue(),
        initialCurrentsEmpty =
            currentRawMiniHomeState() == null &&
                currentDisplayedMiniHomeState() == null &&
                currentRegistrationState() == null,
        initialListenerCount = primaryListenerCount(),
        isolatedInstance = true,
    )

internal fun Todo18MiniHomeStateEvent.snapshot(channel: Todo18StateChannel): Todo18StateSnapshot =
    Todo18StateSnapshot(
        sequence = sequence,
        channel = channel,
        state = state.kind(),
        owner = state.owner,
        selectedContentId = null,
    )

internal fun Todo18RegistrationStateEvent.snapshot(): Todo18StateSnapshot =
    Todo18StateSnapshot(
        sequence = sequence,
        channel = Todo18StateChannel.REGISTRATION,
        state = state.kind(),
        owner = null,
        selectedContentId = state.selectedContentId(),
    )

internal fun MiniHomeUiState.kind(): Todo18StateKind =
    when (this) {
        is MiniHomeUiState.Loading -> Todo18StateKind.MINI_HOME_LOADING
        is MiniHomeUiState.Unavailable -> Todo18StateKind.MINI_HOME_UNAVAILABLE
        is MiniHomeUiState.Viewing -> Todo18StateKind.MINI_HOME_VIEWING
        is MiniHomeUiState.Editing -> Todo18StateKind.MINI_HOME_EDITING
        MiniHomeUiState.Forbidden -> Todo18StateKind.MINI_HOME_FORBIDDEN
        MiniHomeUiState.Error -> Todo18StateKind.MINI_HOME_ERROR
    }

internal fun RegistrationUiState.kind(): Todo18StateKind =
    when (this) {
        RegistrationUiState.LoadingSession -> Todo18StateKind.REGISTRATION_LOADING_SESSION
        is RegistrationUiState.SessionFailed -> Todo18StateKind.REGISTRATION_SESSION_FAILED
        is RegistrationUiState.Editing -> Todo18StateKind.REGISTRATION_EDITING
        is RegistrationUiState.CheckingDuplicates ->
            Todo18StateKind.REGISTRATION_CHECKING_DUPLICATES
        is RegistrationUiState.DuplicateFound -> Todo18StateKind.REGISTRATION_DUPLICATE_FOUND
        is RegistrationUiState.Saving -> Todo18StateKind.REGISTRATION_SAVING
        is RegistrationUiState.SaveFailed -> Todo18StateKind.REGISTRATION_SAVE_FAILED
        is RegistrationUiState.Completed -> Todo18StateKind.REGISTRATION_COMPLETED
    }

internal fun RegistrationUiState.selectedContentId(): PlantContentId? =
    when (this) {
        is RegistrationUiState.Editing -> draft.selectedContent?.id
        is RegistrationUiState.CheckingDuplicates -> draft.selectedContent?.id
        is RegistrationUiState.DuplicateFound -> draft.selectedContent?.id
        is RegistrationUiState.Saving -> submission.contentId
        is RegistrationUiState.SaveFailed -> submission.contentId
        is RegistrationUiState.Completed -> plant.contentId
        RegistrationUiState.LoadingSession,
        is RegistrationUiState.SessionFailed -> null
    }
