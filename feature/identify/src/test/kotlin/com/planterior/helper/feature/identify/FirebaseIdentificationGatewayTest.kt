package com.planterior.helper.feature.identify

import com.planterior.helper.core.model.PlantContentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseIdentificationGatewayTest {
    @Test
    fun `callable candidates retain provider species id as public content key`() {
        // Given
        val response =
            mapOf(
                "kind" to "candidates",
                "candidates" to
                    listOf(
                        mapOf(
                            "publicContentId" to "species-monstera",
                            "koreanName" to "몬스테라",
                            "commonName" to "Swiss cheese plant",
                            "scientificName" to "Monstera deliciosa",
                            "confidence" to 0.93,
                            "thumbnailUrl" to "https://example.com/monstera.jpg",
                        )
                    ),
            )

        // When
        val result = parseIdentificationResult(response)

        // Then
        assertTrue(result is IdentificationResult.Candidates)
        assertEquals(
            PlantContentId("species-monstera"),
            (result as IdentificationResult.Candidates).candidates.single().publicContentId,
        )
    }

    @Test
    fun `malformed and oversized callable payloads fail closed`() {
        // Given
        val malformed = mapOf("kind" to "candidates", "candidates" to listOf(mapOf("confidence" to 3.0)))
        val oversized =
            mapOf(
                "kind" to "candidates",
                "candidates" to
                    List(6) {
                        mapOf(
                            "publicContentId" to "species-$it",
                            "scientificName" to "Species $it",
                            "confidence" to 0.5,
                        )
                    },
            )

        // When
        val malformedResult = parseIdentificationResult(malformed)
        val oversizedResult = parseIdentificationResult(oversized)

        // Then
        assertEquals(
            IdentificationResult.Failed(IdentificationFailureReason.MALFORMED_RESPONSE),
            malformedResult,
        )
        assertEquals(
            IdentificationResult.Failed(IdentificationFailureReason.MALFORMED_RESPONSE),
            oversizedResult,
        )
    }
}
