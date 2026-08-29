package com.planterior.helper.feature.minihome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
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
    diagnosticObserver: ((MiniHomeDiagnosticEvent) -> Unit)? = null,
    runtimeBinding: MiniHomeRuntimeDiagnosticBinding? = null,
    content: @Composable (MiniHomeUiState) -> Unit,
) {
    val displayedState = state.displayedFor(authOwnership)
    SideEffect {
        publishMiniHomeDisplayedState(
            state = displayedState,
            diagnostic =
                runtimeBinding?.let {
                    MiniHomeDisplayedStateDiagnostic(diagnosticObserver, it)
                },
            publish = { onStateObserved(displayedState) },
        )
    }
    content(displayedState)
}

@Composable
internal fun MiniHomeRouteStateObserver(
    controller: MiniHomeController,
    onRawStateObserved: (MiniHomeUiState) -> Unit,
    diagnosticObserver: ((MiniHomeDiagnosticEvent) -> Unit)?,
    runtimeBinding: MiniHomeRuntimeDiagnosticBinding? = null,
) {
    val currentRawStateObserved by rememberUpdatedState(onRawStateObserved)
    val currentDiagnosticObserver by rememberUpdatedState(diagnosticObserver)
    val currentRuntimeBinding by rememberUpdatedState(runtimeBinding)
    LaunchedEffect(controller) {
        controller.state.collect { observed ->
            publishMiniHomeRouteState(
                controller.diagnosticIdentity,
                observed,
                currentDiagnosticObserver,
                currentRuntimeBinding,
            ) {
                currentRawStateObserved(observed)
            }
        }
    }
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
    onRawStateObserved: (MiniHomeUiState) -> Unit = {},
    diagnosticObserver: ((MiniHomeDiagnosticEvent) -> Unit)? = null,
    diagnosticGenerations: MiniHomeDiagnosticGenerations? = null,
    hostDiagnosticIdentity: MiniHomeHostDiagnosticIdentity? = null,
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
    val runtimeBinding =
        miniHomeRuntimeDiagnosticBinding(
            controller,
            session,
            diagnosticGenerations,
            hostDiagnosticIdentity,
        )
    MiniHomeRouteStateObserver(
        controller,
        onRawStateObserved,
        diagnosticObserver,
        runtimeBinding,
    )
    LifecycleResumeEffect(controller, authOwnership) {
        val load = scope.launch { controller.start(authOwnership) }
        onPauseOrDispose { load.cancel() }
    }
    MiniHomeOwnershipGate(
        state = state,
        authOwnership = authOwnership,
        onStateObserved = onStateObserved,
        diagnosticObserver = diagnosticObserver,
        runtimeBinding = runtimeBinding,
    ) { displayedState ->
        val displayedSession =
            if (displayedState === state) session else session.copy(owner = displayedState.owner)
        MiniHomeScreen(
            state = displayedState,
            session = displayedSession,
            onBack = onBack,
            onRetryLoad = { controller.start(authOwnership) },
            onBeginEditing = { performMiniHomeBeginEdit(controller, diagnosticObserver) },
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

@Composable
private fun miniHomeRuntimeDiagnosticBinding(
    controller: MiniHomeController,
    session: MiniHomeControllerSessionToken,
    generations: MiniHomeDiagnosticGenerations?,
    hostIdentity: MiniHomeHostDiagnosticIdentity?,
): MiniHomeRuntimeDiagnosticBinding? {
    if (generations == null || hostIdentity == null) return null
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val attachGeneration = remember(controller, lifecycleOwner, generations) { generations.next() }
    val collectorGeneration =
        remember(controller, attachGeneration, generations) { generations.next() }
    val callbackGeneration =
        remember(attachGeneration, generations, hostIdentity.callbackSinkIdentity) {
            generations.next()
        }
    DisposableEffect(controller, lifecycleOwner, generations, attachGeneration) {
        onDispose { generations.markDisposed() }
    }
    return MiniHomeRuntimeDiagnosticBinding(
        controllerIdentity = controller.diagnosticIdentity,
        controllerEpoch = session.controllerEpoch,
        controllerGeneration = session.generation,
        collectorGeneration = collectorGeneration,
        callbackGeneration = callbackGeneration,
        attachGeneration = attachGeneration,
        disposeGeneration = generations.latestDisposeGeneration,
        lifecycleOwnerIdentity = lifecycleOwner.runtimeIdentity(),
        lifecycleState = lifecycleState.name,
        activityIdentity = hostIdentity.activityIdentity,
        navHostIdentity = hostIdentity.navHostIdentity,
        callbackSinkIdentity = hostIdentity.callbackSinkIdentity,
    )
}

private fun Any.runtimeIdentity(): String =
    "${javaClass.name}@${Integer.toHexString(System.identityHashCode(this))}"
