package com.planterior.helper.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsModelsTest {
    @Test
    fun `product event vocabulary exactly matches backend and client API exposes only eligible events`() {
        assertEquals(
            listOf(
                "APP_SESSION_STARTED",
                "IDENTIFICATION_REQUEST_SUBMITTED",
                "IDENTIFICATION_RESULT_AVAILABLE",
                "IDENTIFICATION_FAILED",
                "IDENTIFICATION_RESULT_CONFIRMED",
                "IDENTIFICATION_RESULT_EDITED",
                "PLANT_REGISTRATION_COMPLETED",
                "CARE_INFORMATION_VIEWED",
                "WATERING_NOTIFICATION_SENT",
                "WATERING_NOTIFICATION_OPENED",
                "WATERING_COMPLETED",
                "WEATHER_RISK_ALERT_CREATED",
                "WEATHER_RISK_NOTIFICATION_SENT",
                "WEATHER_RISK_ALERT_VIEWED",
                "MINI_HOME_LAYOUT_SAVED",
                "MINI_HOME_SHARE_LINK_CREATED",
                "MINI_HOME_SHARE_SHEET_OPENED",
                "MINI_HOME_ACQUISITION_SOURCE_VIEWED",
                "SYNC_COMPLETED",
                "SYNC_FAILED",
                "ACCOUNT_DELETION_REQUESTED",
                "ACCOUNT_DELETION_COMPLETED",
                "ACCOUNT_DELETION_FAILED",
            ),
            ProductEvent.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "APP_SESSION_STARTED",
                "IDENTIFICATION_REQUEST_SUBMITTED",
                "IDENTIFICATION_RESULT_AVAILABLE",
                "IDENTIFICATION_FAILED",
                "IDENTIFICATION_RESULT_CONFIRMED",
                "IDENTIFICATION_RESULT_EDITED",
                "PLANT_REGISTRATION_COMPLETED",
                "CARE_INFORMATION_VIEWED",
                "WATERING_COMPLETED",
                "WEATHER_RISK_ALERT_VIEWED",
                "MINI_HOME_SHARE_SHEET_OPENED",
                "MINI_HOME_ACQUISITION_SOURCE_VIEWED",
                "SYNC_COMPLETED",
                "SYNC_FAILED",
            ),
            ClientProductEvent.entries.map { it.name },
        )
        assertTrue(
            ClientProductEvent.entries
                .map { it.event }
                .toSet()
                .all {
                    it in ProductEvent.entries
                }
        )
        assertTrue(
            setOf(
                    ProductEvent.ACCOUNT_DELETION_COMPLETED,
                    ProductEvent.ACCOUNT_DELETION_FAILED,
                )
                .intersect(ClientProductEvent.entries.map { it.event }.toSet())
                .isEmpty()
        )
    }
}
