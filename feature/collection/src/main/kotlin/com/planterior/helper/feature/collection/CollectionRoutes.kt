package com.planterior.helper.feature.collection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.planterior.helper.core.model.PersonalPlantId
import java.time.Clock
import kotlinx.coroutines.launch

private class CollectionViewModel(val controller: CollectionController) : ViewModel()

private class PlantDetailViewModel(val controller: PlantDetailController) : ViewModel()

@Composable
fun CollectionRoute(
    repository: CollectionRepository,
    onOpenPlant: (PersonalPlantId) -> Unit,
    onIdentify: () -> Unit,
    onRegisterDirectly: () -> Unit,
    bottomBar: @Composable () -> Unit,
    thumbnailLoader: PlantThumbnailLoader = PlaceholderPlantThumbnailLoader,
) {
    val model =
        viewModel<CollectionViewModel>(
            factory =
                viewModelFactory {
                    initializer {
                        CollectionViewModel(
                            CollectionController(repository, createSavedStateHandle())
                        )
                    }
                }
        )
    val controller = model.controller
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.start() }
    CollectionScreen(
        state = state,
        listPosition = controller.listPosition,
        onListPositionChanged = controller::updateListPosition,
        onOpenPlant = onOpenPlant,
        onIdentify = onIdentify,
        onRegisterDirectly = onRegisterDirectly,
        onRetry = { scope.launch { controller.retry() } },
        bottomBar = bottomBar,
        thumbnailLoader = thumbnailLoader,
    )
}

@Composable
fun PlantDetailRoute(
    plantId: PersonalPlantId,
    repository: CollectionRepository,
    onBack: () -> Unit,
    onNotificationSettings: () -> Unit = {},
    clock: Clock = Clock.systemDefaultZone(),
    onRecordWatering: (() -> Unit)? = null,
    refreshAfterWatering: String? = null,
    onWateringRefreshConsumed: () -> Unit = {},
) {
    val model =
        viewModel<PlantDetailViewModel>(
            key = "plant-detail-${plantId.value}",
            factory =
                viewModelFactory {
                    initializer {
                        PlantDetailViewModel(
                            PlantDetailController(
                                plantId,
                                repository,
                                clock,
                                createSavedStateHandle(),
                            )
                        )
                    }
                },
        )
    val controller = model.controller
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) {
        if (refreshAfterWatering == null) controller.start()
    }
    LaunchedEffect(controller, refreshAfterWatering) {
        if (refreshAfterWatering != null) {
            controller.retry()
            onWateringRefreshConsumed()
        }
    }
    LifecycleResumeEffect(controller) {
        controller.onResume()
        onPauseOrDispose {}
    }
    LaunchedEffect(controller, state::class) {
        while (controller.reclassifyAtNextAccountMidnight()) {
            // Re-arm at each account-zone midnight while this detail remains visible.
        }
    }
    PlantDetailScreen(
        state = state,
        onBack = onBack,
        onRetry = { scope.launch { controller.retry() } },
        onBeginEditing = controller::beginEditing,
        onLastWateredDate = controller::changeLastWateredDate,
        onLocation = controller::changeLocation,
        onPrivateNote = controller::changePrivateNote,
        onSave = { scope.launch { controller.saveEdit() } },
        onCancelEdit = controller::cancelEdit,
        onReconcileEdit = { scope.launch { controller.reconcileFailedEdit() } },
        onRecordWatering = onRecordWatering,
        onNotificationSettings = onNotificationSettings,
    )
}
