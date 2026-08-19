package com.planterior.helper.feature.weather

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

data class ApproximateLocation(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

sealed interface LocationPermission {
    val canAskAgain: Boolean

    data object GrantedApproximate : LocationPermission {
        override val canAskAgain = false
    }

    data class Denied(override val canAskAgain: Boolean) : LocationPermission
}

data class WeatherPermissionCapabilityState(
    val desiredGranted: Boolean,
    val acknowledgedGranted: Boolean?,
    val revocationPending: Boolean,
    val commandGeneration: Long = 0,
    val osPermissionGranted: Boolean? = null,
) {
    init {
        require(commandGeneration >= 0)
    }
}

data class WeatherConsentMutationResult(
    val authoritativeGeneration: Long,
    val authoritativeGranted: Boolean,
    val recovered: Boolean = false,
) {
    init {
        require(authoritativeGeneration >= 0)
    }
}

interface WeatherPermissionCapabilityStore {
    fun read(accountId: String): WeatherPermissionCapabilityState?

    fun write(accountId: String, state: WeatherPermissionCapabilityState)
}

interface WeatherLocationGateway {
    fun permission(): LocationPermission

    suspend fun requestPermission(): LocationPermission = permission()

    suspend fun approximateLocation(): ApproximateLocation?

    fun cancel() = Unit
}

data class WeatherRegion(
    val regionCode: String,
    val regionName: String,
    val latitude: Double,
    val longitude: Double,
)

data class WeatherSnapshot(
    val regionCode: String,
    val regionName: String,
    val temperatureCelsius: Double,
    val humidityPercent: Int,
    val precipitationMillimeters: Double,
    val observedAt: Instant,
    val zoneId: String,
) {
    init {
        require(regionCode.isNotBlank() && regionName.isNotBlank())
        require(temperatureCelsius.isFinite())
        require(humidityPercent in 0..100)
        require(precipitationMillimeters.isFinite() && precipitationMillimeters >= 0)
        ZoneId.of(zoneId)
    }

    fun isStaleAt(now: Instant): Boolean {
        val age = Duration.between(observedAt, now)
        return age.isNegative || age > WEATHER_FRESHNESS
    }
}

enum class WeatherRiskType {
    HIGH_TEMPERATURE,
    LOW_TEMPERATURE,
    DRY,
    OVERHUMID,
}

data class WeatherRisk(
    val riskId: String,
    val plantId: String,
    val plantName: String,
    val type: WeatherRiskType,
    val action: String,
    val detectedAt: Instant,
    val active: Boolean,
    val reason: String = "",
)

data class WeatherDashboard(
    val snapshot: WeatherSnapshot,
    val risks: List<WeatherRisk>,
    val unavailablePlants: List<String>,
    val stale: Boolean,
    val globalAlertsEnabled: Boolean,
    val plantAlerts: Map<String, Boolean>,
    val revision: Long,
    val plantNames: Map<String, String> = emptyMap(),
) {
    init {
        require(revision >= 0)
    }
}

sealed interface WeatherLoad {
    data class Fresh(val dashboard: WeatherDashboard) : WeatherLoad

    data class Stale(val dashboard: WeatherDashboard) : WeatherLoad

    data object NotConfigured : WeatherLoad

    data class Failed(val cached: WeatherDashboard? = null) : WeatherLoad
}

interface WeatherRepository {
    fun accounts(): Flow<String?>

    suspend fun load(accountId: String): WeatherLoad

    suspend fun recordLocationConsent(
        accountId: String,
        granted: Boolean,
        commandGeneration: Long,
    ): WeatherConsentMutationResult

    suspend fun refresh(accountId: String, location: ApproximateLocation?): WeatherLoad

    suspend fun switchToCurrentLocation(
        accountId: String,
        location: ApproximateLocation,
    ): WeatherLoad = refresh(accountId, location)

    suspend fun searchRegions(accountId: String, query: String): List<WeatherRegion>

    suspend fun selectManualRegion(
        accountId: String,
        region: WeatherRegion,
        expectedRevision: Long,
    ): WeatherLoad

    suspend fun saveAlerts(
        accountId: String,
        globalEnabled: Boolean,
        plants: Map<String, Boolean>,
        expectedRevision: Long,
    ): WeatherLoad
}

class WeatherRevisionConflictException : Exception("Weather settings revision conflict")

class WeatherConsentConflictException(
    val authoritativeGeneration: Long,
    val authoritativeGranted: Boolean,
) : Exception("Weather consent generation conflict")

sealed interface WeatherFailure {
    data object LocationUnavailable : WeatherFailure

    data object ProviderUnavailable : WeatherFailure

    data object SaveFailed : WeatherFailure

    data object RevisionConflict : WeatherFailure

    data object ConsentConflict : WeatherFailure
}

sealed interface WeatherUiState {
    data object Loading : WeatherUiState

    data object SignedOut : WeatherUiState

    data class Ready(
        val accountId: String,
        val dashboard: WeatherDashboard?,
        val locationPermission: LocationPermission,
        val appLocationConsentGranted: Boolean,
        val searchQuery: String,
        val searchResults: List<WeatherRegion> = emptyList(),
        val refreshing: Boolean = false,
        val saving: Boolean = false,
        val failure: WeatherFailure? = null,
    ) : WeatherUiState {
        val stale: Boolean
            get() = dashboard?.stale == true

        val canChooseManualRegion: Boolean
            get() = true
    }
}

fun localWeatherDay(instant: Instant, zoneId: String): LocalDate =
    instant.atZone(ZoneId.of(zoneId)).toLocalDate()

val WEATHER_FRESHNESS: Duration = Duration.ofHours(3)
