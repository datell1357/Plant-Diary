package com.planterior.helper.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.planterior.helper.feature.weather.ApproximateLocation
import com.planterior.helper.feature.weather.LocationPermission
import com.planterior.helper.feature.weather.WeatherLocationGateway
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class AndroidWeatherLocationGateway(
    context: Context,
    private val permissionState: () -> LocationPermission,
    private val requestLocationPermission: suspend () -> LocationPermission,
) : WeatherLocationGateway {
    private val applicationContext = context.applicationContext
    private val locationManager =
        requireNotNull(applicationContext.getSystemService(LocationManager::class.java))
    private var cancellationSignal: CancellationSignal? = null

    override fun permission(): LocationPermission = permissionState()

    override suspend fun requestPermission(): LocationPermission = requestLocationPermission()

    override suspend fun approximateLocation(): ApproximateLocation? {
        if (
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val provider =
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull {
                LocationManagerCompat.hasProvider(locationManager, it) &&
                    locationManager.isProviderEnabled(it)
            } ?: return null
        val location =
            withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    cancellationSignal = signal
                    LocationManagerCompat.getCurrentLocation(
                        locationManager,
                        provider,
                        signal,
                        ContextCompat.getMainExecutor(applicationContext),
                    ) { value ->
                        cancellationSignal = null
                        if (continuation.isActive) continuation.resume(value)
                    }
                    continuation.invokeOnCancellation {
                        signal.cancel()
                        if (cancellationSignal === signal) cancellationSignal = null
                    }
                }
            } ?: return null
        if (!location.isFresh()) return null
        return ApproximateLocation(
            latitude = roundApproximate(location.latitude),
            longitude = roundApproximate(location.longitude),
        )
    }

    override fun cancel() {
        cancellationSignal?.cancel()
        cancellationSignal = null
    }

    private fun Location.isFresh(): Boolean {
        if (elapsedRealtimeNanos <= 0) return false
        val ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
        return ageNanos in 0..MAX_LOCATION_AGE_NANOS
    }

    private fun roundApproximate(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

    private companion object {
        const val LOCATION_TIMEOUT_MILLIS = 10_000L
        const val MAX_LOCATION_AGE_NANOS = 10L * 60L * 1_000_000_000L
    }
}
