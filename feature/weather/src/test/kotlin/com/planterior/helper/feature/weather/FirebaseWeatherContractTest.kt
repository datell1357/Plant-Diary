package com.planterior.helper.feature.weather

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseWeatherContractTest {
    @Test
    fun `canonical callable response maps all current risk and alert fields without defaults`() {
        val response =
            mapOf<String, Any?>(
                "snapshot" to
                    mapOf(
                        "regionCode" to "kr-seoul",
                        "regionName" to "서울",
                        "temperatureCelsius" to 35.0,
                        "humidityPercent" to 30,
                        "precipitationMillimeters" to 1.2,
                        "observedAt" to "2026-08-12T02:00:00Z",
                        "zoneId" to "Asia/Seoul",
                    ),
                "risks" to
                    listOf(
                        mapOf(
                            "plantId" to "plant-a",
                            "plantName" to "몬스테라",
                            "type" to "HIGH_TEMPERATURE",
                            "reason" to "적정 온도보다 높아요.",
                            "action" to "직사광선을 피해 옮겨 주세요.",
                            "detectedAt" to "2026-08-12T03:00:00Z",
                        )
                    ),
                "unavailablePlantIds" to listOf("plant-b"),
                "stale" to false,
                "globalAlertsEnabled" to true,
                "plantAlerts" to mapOf("plant-a" to false),
                "plants" to mapOf("plant-a" to "몬스테라", "plant-b" to "선인장"),
                "revision" to 3,
            )
        val load = response.toWeatherLoad()
        val dashboard = (load as WeatherLoad.Fresh).dashboard
        assertEquals(Instant.parse("2026-08-12T02:00:00Z"), dashboard.snapshot.observedAt)
        assertEquals(WeatherRiskType.HIGH_TEMPERATURE, dashboard.risks.single().type)
        assertEquals(false, dashboard.plantAlerts.getValue("plant-a"))
        assertEquals(listOf("plant-b"), dashboard.unavailablePlants)
    }

    @Test
    fun `persisted unavailable criteria ids reload only current owned plants`() {
        assertEquals(
            listOf("plant-a", "plant-b"),
            persistedUnavailablePlantIds(
                listOf("plant-b", "deleted-plant", "plant-a", "plant-b", "bad/id"),
                setOf("plant-a", "plant-b"),
            ),
        )
        assertEquals(emptyList<String>(), persistedUnavailablePlantIds(null, setOf("plant-a")))
    }

    @Test
    fun `malformed humidity and unknown risk types fail closed`() {
        val malformed =
            mapOf<String, Any?>(
                "snapshot" to
                    mapOf(
                        "regionCode" to "x",
                        "regionName" to "x",
                        "temperatureCelsius" to 20,
                        "humidityPercent" to 101,
                        "precipitationMillimeters" to 0,
                        "observedAt" to "2026-08-12T02:00:00Z",
                        "zoneId" to "Asia/Seoul",
                    ),
                "risks" to emptyList<Any>(),
                "unavailablePlantIds" to emptyList<Any>(),
                "stale" to false,
                "globalAlertsEnabled" to true,
                "plantAlerts" to emptyMap<String, Boolean>(),
                "plants" to emptyMap<String, String>(),
                "revision" to 1,
            )
        assertTrue(runCatching { malformed.toWeatherLoad() }.isFailure)
    }
}
