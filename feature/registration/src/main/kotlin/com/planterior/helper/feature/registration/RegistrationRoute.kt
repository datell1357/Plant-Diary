package com.planterior.helper.feature.registration

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.planterior.helper.core.model.PersonalPlant
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.feature.camera.ContentResolverPhotoUriReader
import com.planterior.helper.feature.camera.PhotoPreparationException
import com.planterior.helper.feature.camera.PhotoPreparer
import com.planterior.helper.feature.camera.PhotoSource
import com.planterior.helper.feature.camera.PhotoValidator
import com.planterior.helper.feature.camera.PrivatePhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class RegistrationViewModel(val controller: RegistrationController) : ViewModel()

@Composable
fun RegistrationRoute(
    seed: RegistrationSeed,
    repository: RegistrationRepository,
    onOpenExisting: (PersonalPlantId) -> Unit,
    onCompleted: (PersonalPlant) -> Unit,
    onCancel: () -> Unit,
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
                                onOpenExisting = onOpenExisting,
                                savedStateHandle = createSavedStateHandle(),
                            )
                        )
                    }
                },
        )
    val controller = model.controller
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
    LaunchedEffect(state) {
        (state as? RegistrationUiState.Completed)?.plant?.let(onCompleted)
    }
    val requestId = (seed as? RegistrationSeed.Identified)?.requestId
    RegistrationScreen(
        state = state,
        identifiedRequestId = requestId,
        onName = controller::changeName,
        onDate = controller::changeLastWateredDate,
        onSearch = { scope.launch { controller.search(it) } },
        onSelectContent = controller::selectContent,
        onUseIdentificationPhoto = { selected ->
            controller.setPhoto(
                if (selected && requestId != null)
                    RepresentativePhoto.IdentificationOriginal(requestId)
                else null
            )
        },
        onPickPhoto = { photoPicker.launch("image/*") },
        onSubmit = { scope.launch { controller.submit() } },
        onOpenExisting = controller::openExisting,
        onAddAnother = { scope.launch { controller.addAnother() } },
        onCancelDuplicate = onCancel,
        onRetry = { scope.launch { controller.retry() } },
        onCancel = onCancel,
    )
}
