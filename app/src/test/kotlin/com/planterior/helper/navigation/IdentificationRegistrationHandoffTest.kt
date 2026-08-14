package com.planterior.helper.navigation

import com.planterior.helper.core.model.IdentificationRequestId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.feature.identify.ConfirmedIdentification
import com.planterior.helper.feature.identify.IdentificationCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentificationRegistrationHandoffTest {
    @Test
    fun `confirmed candidate is preserved until registration consumes it`() {
        val candidate =
            IdentificationCandidate(
                publicContentId = PlantContentId("species-monstera"),
                koreanName = "몬스테라",
                commonName = "Swiss cheese plant",
                scientificName = "Monstera deliciosa",
                confidence = 0.93,
                thumbnailUrl = null,
            )
        val confirmed =
            ConfirmedIdentification(IdentificationRequestId("request_12345678"), candidate)
        val handoff = IdentificationRegistrationHandoff()

        handoff.accept(confirmed)

        assertEquals(confirmed, handoff.confirmed)
        handoff.clear()
        assertNull(handoff.confirmed)
    }
}
