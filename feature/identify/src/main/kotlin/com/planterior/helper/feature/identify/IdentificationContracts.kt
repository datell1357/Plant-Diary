package com.planterior.helper.feature.identify

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.planterior.helper.core.model.IdentificationRequestId
import com.planterior.helper.core.model.PlantContentId

data class IdentificationCandidate(
    val publicContentId: PlantContentId,
    val koreanName: String?,
    val commonName: String?,
    val scientificName: String,
    val confidence: Double,
    val thumbnailUrl: String?,
) {
    init {
        require(scientificName.isNotBlank())
        require(confidence in 0.0..1.0)
        require(thumbnailUrl == null || thumbnailUrl.startsWith("https://"))
    }
}

sealed interface IdentificationResult {
    data object Pending : IdentificationResult

    data class Candidates(val candidates: List<IdentificationCandidate>) : IdentificationResult {
        init {
            require(candidates.size in 1..3)
            require(candidates.zipWithNext().all { (first, second) -> first.confidence >= second.confidence })
        }
    }

    data object NoCandidates : IdentificationResult

    data class Failed(val reason: IdentificationFailureReason) : IdentificationResult
}

enum class IdentificationFailureReason {
    TIMEOUT,
    RATE_LIMITED,
    PROVIDER_UNAVAILABLE,
    MALFORMED_RESPONSE,
}

data class ConfirmedIdentification(
    val requestId: IdentificationRequestId,
    val candidate: IdentificationCandidate,
)

enum class IdentificationFallback {
    RETRY,
    RETAKE_PHOTO,
    CHANGE_PHOTO,
    EDIT_MANUALLY,
    REGISTER_MANUALLY,
}

sealed interface IdentificationUiState {
    data object Pending : IdentificationUiState

    data class Candidates(
        val candidates: List<IdentificationCandidate>,
        val selectedId: PlantContentId? = null,
    ) : IdentificationUiState

    data object NoCandidates : IdentificationUiState

    data class Failed(val reason: IdentificationFailureReason) : IdentificationUiState
}

class IdentificationController(
    private val requestId: IdentificationRequestId,
    private val onConfirmed: (ConfirmedIdentification) -> Unit,
    restoredState: IdentificationUiState = IdentificationUiState.Pending,
) {
    var state: IdentificationUiState by mutableStateOf(restoredState)
        private set
    private var confirmed = false

    fun show(result: IdentificationResult) {
        if (confirmed) return
        state =
            when (result) {
                IdentificationResult.Pending -> IdentificationUiState.Pending
                is IdentificationResult.Candidates ->
                    IdentificationUiState.Candidates(result.candidates)
                IdentificationResult.NoCandidates -> IdentificationUiState.NoCandidates
                is IdentificationResult.Failed -> IdentificationUiState.Failed(result.reason)
            }
    }

    fun select(contentId: PlantContentId) {
        if (confirmed) return
        val candidates = state as? IdentificationUiState.Candidates ?: return
        if (candidates.candidates.none { it.publicContentId == contentId }) return
        state = candidates.copy(selectedId = contentId)
    }

    fun confirm(): Boolean {
        if (confirmed) return false
        val candidates = state as? IdentificationUiState.Candidates ?: return false
        val selected =
            candidates.candidates.firstOrNull { it.publicContentId == candidates.selectedId }
                ?: return false
        confirmed = true
        onConfirmed(ConfirmedIdentification(requestId, selected))
        return true
    }

    fun snapshot(): IdentificationUiState = state
}
