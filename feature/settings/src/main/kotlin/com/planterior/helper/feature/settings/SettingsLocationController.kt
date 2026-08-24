package com.planterior.helper.feature.settings

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsLocationController(
    initialRegion: SettingsRegion,
    private val boundary: CurrentLocationConsentBoundary,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineContext = Dispatchers.Main.immediate,
) {
    private val mutableState = MutableStateFlow(SettingsLocationState(initialRegion))
    val state: StateFlow<SettingsLocationState> = mutableState.asStateFlow()
    private var generation = 0L
    private var activeRequest: LocationRequestGeneration? = null

    fun beginCurrentLocationRequest(): LocationRequestGeneration =
        LocationRequestGeneration(++generation).also {
            activeRequest = it
            mutableState.value = mutableState.value.copy(consentRevoked = false)
        }

    fun acceptCurrentLocation(
        request: LocationRequestGeneration,
        name: String,
    ): Boolean {
        if (activeRequest != request || mutableState.value.consentRevoked) return false
        activeRequest = null
        mutableState.value = SettingsLocationState(SettingsRegion.Current(name))
        return true
    }

    fun revokeCurrentLocationConsent() {
        generation += 1
        activeRequest = null
        boundary.cancelInFlightLocation()
        val retainedRegion = mutableState.value.region
        mutableState.value = SettingsLocationState(retainedRegion, consentRevoked = true)
        scope.launch(dispatcher) {
            try {
                boundary.revokeConsent()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(revocationFailed = true)
            }
        }
    }
}
