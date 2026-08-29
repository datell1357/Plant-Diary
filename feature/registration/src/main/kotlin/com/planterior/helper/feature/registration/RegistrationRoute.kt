package com.planterior.helper.feature.registration

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.ProductEventRecorder
import com.planterior.helper.feature.camera.ContentResolverPhotoUriReader
import com.planterior.helper.feature.camera.PhotoPreparationException
import com.planterior.helper.feature.camera.PhotoPreparer
import com.planterior.helper.feature.camera.PhotoSource
import com.planterior.helper.feature.camera.PhotoValidator
import com.planterior.helper.feature.camera.PrivatePhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class RegistrationViewModel(val controller: RegistrationController) : ViewModel() {
    override fun onCleared() {
        controller.cancelNavigationEvents()
    }
}

@Composable
fun RegistrationRoute(
    seed: RegistrationSeed,
    repository: RegistrationRepository,
    onOpenExisting: (PersonalPlantId) -> Unit,
    onCompleted: (PersonalPlantId) -> Unit,
    onCancel: () -> Unit,
    authOwnership: RegistrationAuthOwnership = RegistrationAuthOwnership.Unknown,
    onStateObserved: (RegistrationUiState) -> Unit = {},
    diagnosticObserver: ((RegistrationDiagnosticEvent) -> Unit)? = null,
    productEventRecorder: ProductEventRecorder = ProductEventRecorder {},
) {
    val model =
        viewModel<RegistrationViewModel>(
            key = "registration-${seed.hashCode()}",
            factory =
                viewModelFactory {
                    initializer {
                        RegistrationViewModel(
                            RegistrationController(
                                seed,
                                repository,
                                savedStateHandle = createSavedStateHandle(),
                                productEventRecorder = productEventRecorder,
                                diagnosticObserver = diagnosticObserver,
                            )
                        )
                    }
                },
        )
    val controller = model.controller
    val state by controller.state.collectAsState()
    val navigationEvent by controller.navigationEvent.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOpenExisting by rememberUpdatedState(onOpenExisting)
    val currentCompleted by rememberUpdatedState(onCompleted)
    val currentStateObserved by rememberUpdatedState(onStateObserved)
    val currentDiagnosticObserver by rememberUpdatedState(diagnosticObserver)
    val collector = remember(controller) { controller.attachNavigationCollector() }
    DisposableEffect(controller, collector) {
        onDispose { controller.detachNavigationCollector(collector) }
    }
    val photoPreparer =
        remember(context) {
            PhotoPreparer(
                PhotoValidator(ContentResolverPhotoUriReader(context.contentResolver)),
                PrivatePhotoStore(context),
            )
        }
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            photoPreparer.prepare(uri.toString(), PhotoSource.Picker)
                        }
                    result.fold(
                        onSuccess = { controller.setPhoto(RepresentativePhoto.Prepared(it)) },
                        onFailure = {
                            controller.rejectPhoto(
                                (it as? PhotoPreparationException)?.photoError
                                    ?: com.planterior.helper.feature.camera.PhotoError.Unreadable
                            )
                        },
                    )
                }
            }
        }
    LaunchedEffect(controller) { controller.start() }
    LaunchedEffect(controller) {
        controller.state.collect { state ->
            publishRegistrationRouteState(
                controller.diagnosticIdentity,
                state,
                currentDiagnosticObserver,
            ) {
                currentStateObserved(state)
            }
        }
    }
    LaunchedEffect(
        controller,
        collector,
        navigationEvent?.identity,
        lifecycleOwner,
        authOwnership,
    ) {
        val pending = navigationEvent ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            controller.dispatchNavigationEvent(
                collector,
                pending.identity,
                authOwnership,
            ) { event ->
                when (event.kind) {
                    RegistrationNavigationKind.OPEN_EXISTING -> currentOpenExisting(event.plantId)
                    RegistrationNavigationKind.REGISTRATION_COMPLETED ->
                        currentCompleted(event.plantId)
                }
            }
        }
    }
    val requestId = (seed as? RegistrationSeed.Identified)?.requestId
    RegistrationScreen(
        state = state,
        identifiedRequestId = requestId,
        onName = controller::changeName,
        onDate = controller::changeLastWateredDate,
        onSearch = { scope.launch { controller.search(it) } },
        onSelectContent = { content ->
            performRegistrationSelectContent(controller, content, currentDiagnosticObserver)
        },
        onUseIdentificationPhoto = { selected ->
            controller.setPhoto(
                if (selected && requestId != null)
                    RepresentativePhoto.IdentificationOriginal(requestId)
                else null
            )
        },
        onPickPhoto = { photoPicker.launch("image/*") },
        onSubmit = {
            scope.launch { performRegistrationSubmit(controller, currentDiagnosticObserver) }
        },
        onOpenExisting = controller::openExisting,
        onAddAnother = { scope.launch { controller.addAnother() } },
        onCancelDuplicate = onCancel,
        onRetry = { scope.launch { controller.retry() } },
        onCancel = onCancel,
    )
}
