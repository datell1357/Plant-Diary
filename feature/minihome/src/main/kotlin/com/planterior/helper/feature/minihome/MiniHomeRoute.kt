package com.planterior.helper.feature.minihome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch

private class MiniHomeViewModel(val controller: MiniHomeController) : ViewModel()

internal fun MiniHomeUiState.displayedFor(authOwnership: MiniHomeAuthOwnership): MiniHomeUiState =
    when (authOwnership) {
        MiniHomeAuthOwnership.Unmanaged -> this
        MiniHomeAuthOwnership.Restoring,
        MiniHomeAuthOwnership.Unknown -> MiniHomeUiState.Loading(null)
        MiniHomeAuthOwnership.SignedOut -> MiniHomeUiState.Forbidden
        is MiniHomeAuthOwnership.Authenticated ->
            if (owner == authOwnership.accountId) {
                this
            } else {
                MiniHomeUiState.Loading(authOwnership.accountId)
            }
    }

@Composable
internal fun MiniHomeOwnershipGate(
    state: MiniHomeUiState,
    authOwnership: MiniHomeAuthOwnership,
    onStateObserved: (MiniHomeUiState) -> Unit = {},
    content: @Composable (MiniHomeUiState) -> Unit,
) {
    val displayedState = state.displayedFor(authOwnership)
    SideEffect { onStateObserved(displayedState) }
    content(displayedState)
}

@Composable
fun MiniHomeRoute(
    repository: MiniHomeRepository,
    onBack: () -> Unit,
    onOpenCollection: () -> Unit,
    onOpenShare: (() -> Unit)? = null,
    photoLoader: MiniHomePhotoLoader = PlaceholderMiniHomePhotoLoader,
    authOwnership: MiniHomeAuthOwnership = MiniHomeAuthOwnership.Unmanaged,
    onStateObserved: (MiniHomeUiState) -> Unit = {},
) {
    val model =
        viewModel<MiniHomeViewModel>(
            factory =
                viewModelFactory {
                    initializer {
                        MiniHomeViewModel(MiniHomeController(repository, createSavedStateHandle()))
                    }
                }
        )
    val controller = model.controller
    val state by controller.state.collectAsState()
    val session by controller.session.collectAsState()
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(controller, authOwnership) {
        val load = scope.launch { controller.start(authOwnership) }
        onPauseOrDispose { load.cancel() }
    }
    MiniHomeOwnershipGate(state, authOwnership, onStateObserved) { displayedState ->
        val displayedSession =
            if (displayedState === state) session else session.copy(owner = displayedState.owner)
        MiniHomeScreen(
            state = displayedState,
            session = displayedSession,
            onBack = onBack,
            onRetryLoad = { controller.start(authOwnership) },
            onBeginEditing = controller::beginEditing,
            onRename = controller::rename,
            onAddPlant = controller::addPlant,
            onAddDecoration = controller::addDecoration,
            onSelect = controller::select,
            onMove = controller::moveSelected,
            onMoveBy = controller::moveSelectedBy,
            onRemove = controller::removeSelected,
            onSave = controller::save,
            onDiscard = controller::discardChanges,
            onAdoptConflict = controller::adoptAuthoritativeAfterConflict,
            onReconcileSaveFailure = controller::reconcileSaveFailure,
            onOpenCollection = onOpenCollection,
            onOpenShare = onOpenShare,
            photoLoader = photoLoader,
            authOwnership = authOwnership,
        )
    }
}
