package com.planterior.helper.feature.identify

import com.planterior.helper.core.model.IdentificationRequestId
import com.planterior.helper.core.model.PlantContentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentificationControllerTest {
    private val requestId = IdentificationRequestId("request_12345678")
    private val monstera =
        IdentificationCandidate(
            publicContentId = PlantContentId("species-monstera"),
            koreanName = "몬스테라",
            commonName = "Swiss cheese plant",
            scientificName = "Monstera deliciosa",
            confidence = 0.93,
            thumbnailUrl = "https://example.com/monstera.jpg",
        )
    private val pothos =
        IdentificationCandidate(
            publicContentId = PlantContentId("species-pothos"),
            koreanName = "스킨답서스",
            commonName = "Golden pothos",
            scientificName = "Epipremnum aureum",
            confidence = 0.67,
            thumbnailUrl = null,
        )

    @Test
    fun `candidate must be explicitly selected before confirmation`() {
        // Given
        val confirmed = mutableListOf<ConfirmedIdentification>()
        val controller = IdentificationController(requestId, confirmed::add)
        controller.show(IdentificationResult.Candidates(listOf(monstera, pothos)))

        // When
        val beforeSelection = controller.confirm()
        controller.select(pothos.publicContentId)
        val afterSelection = controller.confirm()

        // Then
        assertFalse(beforeSelection)
        assertTrue(afterSelection)
        assertEquals(listOf(ConfirmedIdentification(requestId, pothos)), confirmed)
    }

    @Test
    fun `response enforces one to three candidate bounds`() {
        // Given
        val controller = IdentificationController(requestId, onConfirmed = {})

        // When / Then
        runCatching { controller.show(IdentificationResult.Candidates(emptyList())) }
            .onSuccess { throw AssertionError("empty candidates must be rejected") }
        controller.show(IdentificationResult.Candidates(List(3) { monstera }))
        runCatching { controller.show(IdentificationResult.Candidates(List(4) { monstera })) }
            .onSuccess { throw AssertionError("more than three candidates must be rejected") }
    }

    @Test
    fun `failure and no-candidate states never confirm or create a plant`() {
        // Given
        var confirmationCount = 0
        val controller =
            IdentificationController(requestId, onConfirmed = { confirmationCount += 1 })

        // When
        controller.show(IdentificationResult.Failed(IdentificationFailureReason.RATE_LIMITED))
        val failedConfirmation = controller.confirm()
        controller.show(IdentificationResult.NoCandidates)
        val emptyConfirmation = controller.confirm()

        // Then
        assertFalse(failedConfirmation)
        assertFalse(emptyConfirmation)
        assertEquals(0, confirmationCount)
    }

    @Test
    fun `selected candidate survives controller resume and confirms only once`() {
        // Given
        val original = IdentificationController(requestId, onConfirmed = {})
        original.show(IdentificationResult.Candidates(listOf(monstera, pothos)))
        original.select(monstera.publicContentId)
        val confirmed = mutableListOf<ConfirmedIdentification>()
        val resumed = IdentificationController(requestId, confirmed::add, original.snapshot())

        // When
        val first = resumed.confirm()
        val duplicate = resumed.confirm()

        // Then
        assertTrue(first)
        assertFalse(duplicate)
        assertEquals(listOf(ConfirmedIdentification(requestId, monstera)), confirmed)
    }
}
