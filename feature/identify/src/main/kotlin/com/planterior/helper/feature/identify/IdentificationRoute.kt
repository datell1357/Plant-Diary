package com.planterior.helper.feature.identify

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
    val controller =
        rememberSaveable(requestIdValue, saver = controllerSaver(requestId, onConfirmed)) {
            IdentificationController(requestId, onConfirmed)
        }
    var attempt by rememberSaveable(requestIdValue) { mutableIntStateOf(0) }
    val operationId =
        OperationId(
            rememberSaveable(requestIdValue, attempt) {
                OperationId.stable(
                        accountId = AccountId.LEGACY,
                        aggregateId = requestId.value,
                        action = "identify",
                        nonce = "$attempt-${UUID.randomUUID()}",
                    )
                    .value
            }
        )
    LaunchedEffect(requestId, operationId) {
        val selectedId = (controller.state as? IdentificationUiState.Candidates)?.selectedId
        val result = runCatching {
            gateway.identify(requestId, operationId)
        }
            .getOrElse { IdentificationResult.Failed(it.toIdentificationFailure()) }
        controller.show(result)
        selectedId?.let(controller::select)
    }
    BackHandler(onBack = onExit)
    IdentificationScreen(
        state = controller.state,
        onSelect = { controller.select(it.publicContentId) },
        onConfirm = { controller.confirm() },
        onFallback = { fallback ->
            when (fallback) {
                IdentificationFallback.RETRY -> {
                    controller.show(IdentificationResult.Pending)
                    attempt += 1
                }
                IdentificationFallback.RETAKE_PHOTO -> onRetakePhoto()
                IdentificationFallback.CHANGE_PHOTO -> onChangePhoto()
                IdentificationFallback.EDIT_MANUALLY -> onEditManually()
                IdentificationFallback.REGISTER_MANUALLY -> onRegisterManually()
            }
        },
        onBack = onExit,
    )
}

private fun controllerSaver(
    requestId: IdentificationRequestId,
    onConfirmed: (ConfirmedIdentification) -> Unit,
): Saver<IdentificationController, Bundle> =
    Saver(
        save = { it.snapshot().toBundle() },
        restore = {
            IdentificationController(requestId, onConfirmed, it.toIdentificationUiState())
        },
    )

private fun IdentificationUiState.toBundle(): Bundle =
    Bundle().apply {
        when (val current = this@toBundle) {
            IdentificationUiState.Pending -> putString("kind", "pending")
            is IdentificationUiState.Candidates -> {
                putString("kind", "candidates")
                putInt("candidateCount", current.candidates.size)
                current.candidates.forEachIndexed { index, candidate ->
                    putBundle(
                        "candidate-$index",
                        Bundle().apply {
                            putString("contentId", candidate.publicContentId.value)
                            putString("koreanName", candidate.koreanName)
                            putString("commonName", candidate.commonName)
                            putString("scientificName", candidate.scientificName)
                            putDouble("confidence", candidate.confidence)
                            putString("thumbnailUrl", candidate.thumbnailUrl)
                        },
                    )
                }
                putString("selectedId", current.selectedId?.value)
            }
            IdentificationUiState.NoCandidates -> putString("kind", "noCandidates")
            is IdentificationUiState.Failed -> {
                putString("kind", "failed")
                putString("reason", current.reason.name)
            }
        }
    }

private fun Bundle.toIdentificationUiState(): IdentificationUiState =
    when (requireNotNull(getString("kind"))) {
        "pending" -> IdentificationUiState.Pending
        "candidates" ->
            IdentificationUiState.Candidates(
                candidates =
                    List(getInt("candidateCount")) { index ->
                        val candidate = requireNotNull(getBundle("candidate-$index"))
                        IdentificationCandidate(
                            publicContentId =
                                com.planterior.helper.core.model.PlantContentId(
                                    requireNotNull(candidate.getString("contentId"))
                                ),
                            koreanName = candidate.getString("koreanName"),
                            commonName = candidate.getString("commonName"),
                            scientificName = requireNotNull(candidate.getString("scientificName")),
                            confidence = candidate.getDouble("confidence"),
                            thumbnailUrl = candidate.getString("thumbnailUrl"),
                        )
                    },
                selectedId =
                    getString("selectedId")?.let {
                        com.planterior.helper.core.model.PlantContentId(it)
                    },
            )
        "noCandidates" -> IdentificationUiState.NoCandidates
        "failed" ->
            IdentificationUiState.Failed(
                IdentificationFailureReason.valueOf(requireNotNull(getString("reason")))
            )
        else -> error("Unknown identification state")
    }
