package com.planterior.helper.identify

import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.feature.identify.IdentificationCandidate
import com.planterior.helper.feature.identify.IdentificationFailureReason
import com.planterior.helper.feature.identify.IdentificationGateway
import com.planterior.helper.feature.identify.IdentificationResult
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

internal data class DebugIdentificationEvent(
    val requestId: String,
    val candidateIds: List<String>,
)

internal object DebugIdentificationEvents {
    private val listeners = CopyOnWriteArraySet<(DebugIdentificationEvent) -> Unit>()

    fun subscribe(listener: (DebugIdentificationEvent) -> Unit): Closeable {
        listeners += listener
        return Closeable { listeners -= listener }
    }

    fun emit(event: DebugIdentificationEvent) {
        listeners.forEach { it(event) }
    }
}

fun debugIdentificationGateway(requestId: String): IdentificationGateway? =
    when (requestId) {
        "fixture-success" -> successFixture(requestId)
        "fixture-rate-limited" ->
            fixture(
                requestId,
                IdentificationResult.Failed(IdentificationFailureReason.RATE_LIMITED),
            )
        "fixture-provider-unavailable" ->
            fixture(
                requestId,
                IdentificationResult.Failed(IdentificationFailureReason.PROVIDER_UNAVAILABLE),
            )
        "fixture-no-candidates" -> fixture(requestId, IdentificationResult.NoCandidates)
        else -> if (CAMERA_REQUEST_ID.matches(requestId)) successFixture(requestId) else null
    }

private val CAMERA_REQUEST_ID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

private fun successFixture(requestId: String): IdentificationGateway =
    fixture(
        requestId,
        IdentificationResult.Candidates(
            listOf(
                IdentificationCandidate(
                    publicContentId = PlantContentId("species-monstera"),
                    koreanName = "몬스테라",
                    commonName = "Swiss cheese plant",
                    scientificName = "Monstera deliciosa",
                    confidence = 0.93,
                    thumbnailUrl = null,
                ),
                IdentificationCandidate(
                    publicContentId = PlantContentId("species-pothos"),
                    koreanName = "스킨답서스",
                    commonName = "Golden pothos",
                    scientificName = "Epipremnum aureum",
                    confidence = 0.67,
                    thumbnailUrl = null,
                ),
                IdentificationCandidate(
                    publicContentId = PlantContentId("species-snake-plant"),
                    koreanName = "스투키",
                    commonName = "Snake plant",
                    scientificName = "Dracaena angolensis",
                    confidence = 0.41,
                    thumbnailUrl = null,
                ),
            )
        ),
    )

private fun fixture(
    requestId: String,
    result: IdentificationResult,
): IdentificationGateway = IdentificationGateway { _, _ ->
    DebugIdentificationEvents.emit(
        DebugIdentificationEvent(
            requestId = requestId,
            candidateIds =
                (result as? IdentificationResult.Candidates)?.candidates.orEmpty().map {
                    it.publicContentId.value
                },
        )
    )
    result
}
