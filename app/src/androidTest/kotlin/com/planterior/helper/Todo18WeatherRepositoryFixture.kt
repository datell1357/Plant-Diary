package com.planterior.helper

import com.planterior.helper.feature.weather.ApproximateLocation
import com.planterior.helper.feature.weather.WeatherConsentMutationResult
import com.planterior.helper.feature.weather.WeatherDashboard
import com.planterior.helper.feature.weather.WeatherLoad
import com.planterior.helper.feature.weather.WeatherPermissionCapabilityState
import com.planterior.helper.feature.weather.WeatherPermissionCapabilityStore
import com.planterior.helper.feature.weather.WeatherRegion
import com.planterior.helper.feature.weather.WeatherRepository
import com.planterior.helper.feature.weather.WeatherRisk
import com.planterior.helper.feature.weather.WeatherRiskType
import com.planterior.helper.feature.weather.WeatherSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Stale-weather remote fixture for the production weather controller. */
internal class Todo18WeatherRepositoryFixture(private val scenario: Todo18Scenario) :
    WeatherRepository {
    private val accounts = MutableStateFlow<String?>(scenario.accountId.value)
    private var dashboard =
        WeatherDashboard(
            snapshot =
                WeatherSnapshot(
                    "KR-11",
                    "서울",
                    31.0,
                    38,
                    0.0,
                    scenario.now().minusSeconds(4 * 60 * 60),
                    "Asia/Seoul",
                ),
            risks =
                listOf(
                    WeatherRisk(
                        "todo18-risk",
                        "missing-weather-plant",
                        "삭제된 식물",
                        WeatherRiskType.HIGH_TEMPERATURE,
                        "직사광선을 피하세요.",
                        scenario.now(),
                        active = true,
                    )
                ),
            unavailablePlants = emptyList(),
            stale = true,
            globalAlertsEnabled = true,
            plantAlerts = mapOf("missing-weather-plant" to true),
            revision = 1,
            plantNames = mapOf("missing-weather-plant" to "삭제된 식물"),
        )

    override fun accounts(): Flow<String?> = accounts

    override suspend fun load(accountId: String): WeatherLoad {
        scenario.emit("weather-loaded", accountId)
        return WeatherLoad.Stale(dashboard)
    }

    override suspend fun recordLocationConsent(
        accountId: String,
        granted: Boolean,
        commandGeneration: Long,
    ) = WeatherConsentMutationResult(commandGeneration, granted)

    override suspend fun refresh(
        accountId: String,
        location: ApproximateLocation?,
    ): WeatherLoad = WeatherLoad.Stale(dashboard)

    override suspend fun searchRegions(accountId: String, query: String): List<WeatherRegion> =
        listOf(WeatherRegion("KR-11", "서울", 37.5, 127.0))

    override suspend fun selectManualRegion(
        accountId: String,
        region: WeatherRegion,
        expectedRevision: Long,
    ): WeatherLoad = WeatherLoad.Stale(dashboard)

    override suspend fun saveAlerts(
        accountId: String,
        globalEnabled: Boolean,
        plants: Map<String, Boolean>,
        expectedRevision: Long,
    ): WeatherLoad {
        dashboard =
            dashboard.copy(
                globalAlertsEnabled = globalEnabled,
                plantAlerts = plants,
                revision = expectedRevision + 1,
            )
        return WeatherLoad.Stale(dashboard)
    }
}

internal class Todo18WeatherCapabilityStore : WeatherPermissionCapabilityStore {
    private val values = mutableMapOf<String, WeatherPermissionCapabilityState>()

    override fun read(accountId: String): WeatherPermissionCapabilityState? = values[accountId]

    override fun write(accountId: String, state: WeatherPermissionCapabilityState) {
        values[accountId] = state
    }
}
