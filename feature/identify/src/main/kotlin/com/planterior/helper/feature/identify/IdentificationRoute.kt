package com.planterior.helper.feature.identify

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.IdentificationRequestId
import com.planterior.helper.core.model.OperationId
import java.util.UUID

@Composable
fun IdentificationRoute(
    requestIdValue: String,
    onExit: () -> Unit,
    onRetakePhoto: () -> Unit,
    onChangePhoto: () -> Unit,
    onEditManually: () -> Unit,
    onRegisterManually: () -> Unit,
    onConfirmed: (ConfirmedIdentification) -> Unit,
    gateway: IdentificationGateway = remember { FirebaseIdentificationGateway() },
) {
    val requestId = remember(requestIdValue) { IdentificationRequestId(requestIdValue) }
    val controller = remember(requestId) { IdentificationController(requestId, onConfirmed) }
    var attempt by remember(requestId) { mutableIntStateOf(0) }
    val operationId =
        remember(requestId, attempt) {
            OperationId.stable(
                accountId = AccountId.LEGACY,
                aggregateId = requestId.value,
                action = "identify",
                nonce = "$attempt-${UUID.randomUUID()}",
            )
        }
    LaunchedEffect(requestId, operationId) {
        controller.show(IdentificationResult.Pending)
        val result =
            runCatching { gateway.identify(requestId, operationId) }
                .getOrElse { IdentificationResult.Failed(it.toIdentificationFailure()) }
        controller.show(result)
    }
    BackHandler(onBack = onExit)
    IdentificationScreen(
        state = controller.state,
        onSelect = { controller.select(it.publicContentId) },
        onConfirm = { controller.confirm() },
        onFallback = { fallback ->
            when (fallback) {
                IdentificationFallback.RETRY -> attempt += 1
                IdentificationFallback.RETAKE_PHOTO -> onRetakePhoto()
                IdentificationFallback.CHANGE_PHOTO -> onChangePhoto()
                IdentificationFallback.EDIT_MANUALLY -> onEditManually()
                IdentificationFallback.REGISTER_MANUALLY -> onRegisterManually()
            }
        },
        onBack = onExit,
    )
}
