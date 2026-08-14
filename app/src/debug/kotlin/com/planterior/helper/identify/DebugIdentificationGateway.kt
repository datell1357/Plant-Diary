package com.planterior.helper.identify

import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.feature.identify.IdentificationCandidate
import com.planterior.helper.feature.identify.IdentificationFailureReason
import com.planterior.helper.feature.identify.IdentificationGateway
import com.planterior.helper.feature.identify.IdentificationResult

fun debugIdentificationGateway(requestId: String): IdentificationGateway? =
    when (requestId) {
        "fixture-success" -> successFixture()
        "fixture-rate-limited" ->
            IdentificationGateway { _, _ ->
                IdentificationResult.Failed(IdentificationFailureReason.RATE_LIMITED)
            }
        "fixture-no-candidates" ->
            IdentificationGateway { _, _ -> IdentificationResult.NoCandidates }
        else -> if (CAMERA_REQUEST_ID.matches(requestId)) successFixture() else null
    }

private val CAMERA_REQUEST_ID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

private fun successFixture(): IdentificationGateway = IdentificationGateway { _, _ ->
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
    )
}
