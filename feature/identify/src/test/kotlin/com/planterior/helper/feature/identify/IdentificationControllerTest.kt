package com.planterior.helper.feature.identify

import com.planterior.helper.core.model.ClientProductEvent
import com.planterior.helper.core.model.IdentificationRequestId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.ProductEventRecorder
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
    fun `only rendered candidates record result available while failure states record failed`() {
        val events = mutableListOf<ClientProductEvent>()
        val controller =
            IdentificationController(
                requestId,
                {},
                productEventRecorder = ProductEventRecorder(events::add),
            )

        controller.show(IdentificationResult.Failed(IdentificationFailureReason.RATE_LIMITED))
        controller.show(IdentificationResult.NoCandidates)
        controller.show(IdentificationResult.Pending)
        controller.show(IdentificationResult.Candidates(listOf(monstera)))

        assertEquals(
            listOf(
                ClientProductEvent.IDENTIFICATION_FAILED,
                ClientProductEvent.IDENTIFICATION_RESULT_AVAILABLE,
            ),
            events,
        )
    }

    @Test
    fun `visible transport failure records failed once across retry and restore`() {
        val events = mutableListOf<ClientProductEvent>()
        val recorder = ProductEventRecorder(events::add)
        val original = IdentificationController(requestId, {}, productEventRecorder = recorder)

        original.showTransportFailure(IdentificationFailureReason.PROVIDER_UNAVAILABLE)
        original.show(IdentificationResult.Pending)
        original.showTransportFailure(IdentificationFailureReason.PROVIDER_UNAVAILABLE)
        val restored =
            IdentificationController(
                requestId,
                {},
                original.snapshot(),
                recorder,
                original.resultAvailableWasRecorded(),
                original.resolutionWasAccepted(),
                original.failureWasRecorded(),
            )
        restored.showTransportFailure(IdentificationFailureReason.PROVIDER_UNAVAILABLE)

        assertEquals(listOf(ClientProductEvent.IDENTIFICATION_FAILED), events)
    }

    @Test
    fun `failure then rendered candidates are each recorded once across retry and restore`() {
        val events = mutableListOf<ClientProductEvent>()
        val recorder = ProductEventRecorder(events::add)
        val original = IdentificationController(requestId, {}, productEventRecorder = recorder)

        original.show(IdentificationResult.NoCandidates)
        original.show(IdentificationResult.Pending)
        original.show(IdentificationResult.Candidates(listOf(monstera)))
        val restored =
            IdentificationController(
                requestId,
                {},
                original.snapshot(),
                recorder,
                original.resultAvailableWasRecorded(),
                original.resolutionWasAccepted(),
                original.failureWasRecorded(),
            )
        restored.show(IdentificationResult.Failed(IdentificationFailureReason.RATE_LIMITED))

        assertEquals(
            listOf(
                ClientProductEvent.IDENTIFICATION_FAILED,
                ClientProductEvent.IDENTIFICATION_RESULT_AVAILABLE,
            ),
            events,
        )
    }

    @Test
    fun `authoritative persisted failure and no-result failure each record failed once`() {
        listOf<IdentificationResult>(
                IdentificationResult.Failed(IdentificationFailureReason.RATE_LIMITED),
                IdentificationResult.NoCandidates,
            )
            .forEach { result ->
                val events = mutableListOf<ClientProductEvent>()
                val controller =
                    IdentificationController(
                        requestId,
                        {},
                        productEventRecorder = ProductEventRecorder(events::add),
                    )

                controller.show(result)
                controller.show(IdentificationResult.Pending)
                controller.show(result)

                assertEquals(
                    listOf(ClientProductEvent.IDENTIFICATION_FAILED),
                    events,
                )
            }
    }

    @Test
    fun `telemetry failure cannot replace an authoritative identification failure`() {
        val controller =
            IdentificationController(
                requestId,
                {},
                productEventRecorder = ProductEventRecorder { error("telemetry unavailable") },
            )

        controller.show(IdentificationResult.Failed(IdentificationFailureReason.RATE_LIMITED))

        assertEquals(
            IdentificationUiState.Failed(IdentificationFailureReason.RATE_LIMITED),
            controller.state,
        )
    }

    @Test
    fun `transport failure emits failed but no result available event`() {
        val events = mutableListOf<ClientProductEvent>()
        val controller =
            IdentificationController(
                requestId,
                {},
                productEventRecorder = ProductEventRecorder(events::add),
            )

        controller.showTransportFailure(IdentificationFailureReason.PROVIDER_UNAVAILABLE)

        assertEquals(listOf(ClientProductEvent.IDENTIFICATION_FAILED), events)
        assertEquals(
            IdentificationUiState.Failed(IdentificationFailureReason.PROVIDER_UNAVAILABLE),
            controller.state,
        )
    }

    @Test
    fun `accepted confirmation records exactly once while invalid confirmation records zero`() {
        val events = mutableListOf<ClientProductEvent>()
        val controller =
            IdentificationController(
                requestId,
                {},
                productEventRecorder = ProductEventRecorder(events::add),
            )
        controller.show(IdentificationResult.Candidates(listOf(monstera)))

        controller.confirm()
        controller.select(monstera.publicContentId)
        controller.confirm()
        controller.confirm()

        assertEquals(
            listOf(
                ClientProductEvent.IDENTIFICATION_RESULT_AVAILABLE,
                ClientProductEvent.IDENTIFICATION_RESULT_CONFIRMED,
            ),
            events,
        )
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
