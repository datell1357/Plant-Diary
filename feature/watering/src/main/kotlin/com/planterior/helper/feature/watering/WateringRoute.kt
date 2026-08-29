package com.planterior.helper.feature.watering

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.ProductEventRecorder
import java.time.Clock
import kotlinx.coroutines.launch

private class WateringConfirmationViewModel(val controller: WateringConfirmationController) :
    ViewModel()

suspend fun runWateringConfirmationAction(
    controller: WateringConfirmationController,
    publishCompleted: () -> Unit = {},
) {
    val ready = controller.state.value as? WateringConfirmationUiState.Ready
    WateringConfirmActionDiagnostics.observe(
        WateringConfirmActionObservation(
            WateringConfirmActionStage.COROUTINE_ENTRY,
            ready?.snapshot?.plantId,
            ready?.draft?.operationId,
        )
    )
    controller.confirm()
    publishCompleted()
}

@Composable
fun WateringConfirmationRoute(
    plantId: PersonalPlantId,
    repository: WateringRepository,
    onBack: () -> Unit,
    onDone: (WateringCompletionReceipt) -> Unit,
    clock: Clock = Clock.systemDefaultZone(),
    onCompleted: (WateringCompletionReceipt) -> Unit = {},
    productEventRecorder: ProductEventRecorder = ProductEventRecorder {},
) {
    val model =
        viewModel<WateringConfirmationViewModel>(
            key = "watering-confirmation-${plantId.value}",
            factory =
                viewModelFactory {
                    initializer {
                        WateringConfirmationViewModel(
                            WateringConfirmationController(
                                plantId,
                                repository,
                                clock,
                                createSavedStateHandle(),
                                productEventRecorder = productEventRecorder,
                            )
                        )
                    }
                },
        )
    val controller = model.controller
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val publishCompleted: () -> Unit = {
        val receipt = (controller.state.value as? WateringConfirmationUiState.Completed)?.receipt
        if (receipt != null) onCompleted(receipt)
    }
    LaunchedEffect(controller) { controller.start() }
    LaunchedEffect(state) { publishCompleted() }
    WateringConfirmationScreen(
        state = state,
        onBack = {
            (state as? WateringConfirmationUiState.Completed)?.receipt?.let(onCompleted)
            onBack()
        },
        onWateredDate = controller::changeWateredDate,
        onConfirm = {
            scope.launch { runWateringConfirmationAction(controller, publishCompleted) }
        },
        onRetry = {
            scope.launch {
                controller.confirm()
                publishCompleted()
            }
        },
        onDone = {
            (state as? WateringConfirmationUiState.Completed)?.receipt?.let { receipt ->
                onCompleted(receipt)
                onDone(receipt)
            }
        },
        onRetryLoad = { scope.launch { controller.retryLoad() } },
        onReconcile = {
            scope.launch {
                controller.reconcile()
                publishCompleted()
            }
        },
    )
}
