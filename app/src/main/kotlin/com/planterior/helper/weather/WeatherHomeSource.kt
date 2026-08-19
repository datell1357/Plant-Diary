package com.planterior.helper.weather

import com.google.firebase.auth.FirebaseAuth
import com.planterior.helper.feature.home.HomeWeather
import com.planterior.helper.feature.home.HomeWeatherRisk
import com.planterior.helper.feature.weather.WeatherLoad
import com.planterior.helper.feature.weather.WeatherRepository
import com.planterior.helper.feature.weather.WeatherRisk
import com.planterior.helper.feature.weather.WeatherRiskType
import com.planterior.helper.home.HomeWeatherSource
import kotlinx.coroutines.CancellationException

class WeatherHomeSource(
    private val auth: FirebaseAuth,
    private val repository: WeatherRepository,
) : HomeWeatherSource {
    override suspend fun current(): Result<HomeWeather?> =
        try {
            val ownerUid = auth.currentUser?.uid ?: return Result.success(null)
            val load = repository.load(ownerUid)
            val dashboard =
                when (load) {
                    is WeatherLoad.Fresh -> load.dashboard
                    is WeatherLoad.Stale -> load.dashboard
                    WeatherLoad.NotConfigured -> return Result.success(null)
                    is WeatherLoad.Failed ->
                        load.cached
                            ?: return Result.failure(
                                IllegalStateException("Weather is unavailable")
                            )
                }
            Result.success(
                HomeWeather(
                    regionName = dashboard.snapshot.regionName,
                    temperatureCelsius = dashboard.snapshot.temperatureCelsius,
                    observedAt = dashboard.snapshot.observedAt,
                    risks = dashboard.risks.filter(WeatherRisk::active).map(WeatherRisk::homeRisk),
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
}

private fun WeatherRisk.homeRisk(): HomeWeatherRisk {
    val message = "$plantName: $action"
    return when (type) {
        WeatherRiskType.HIGH_TEMPERATURE -> HomeWeatherRisk.HighTemperature(message)
        WeatherRiskType.LOW_TEMPERATURE -> HomeWeatherRisk.LowTemperature(message)
        WeatherRiskType.DRY -> HomeWeatherRisk.Dry(message)
        WeatherRiskType.OVERHUMID -> HomeWeatherRisk.Overwatered(message)
    }
}
