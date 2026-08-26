package com.planterior.helper.feature.settings

import com.planterior.helper.feature.weather.WeatherConsentConflictException
import com.planterior.helper.feature.weather.WeatherConsentMutationResult
import com.planterior.helper.feature.weather.WeatherLocationGateway
import com.planterior.helper.feature.weather.WeatherPermissionCapabilityState
import com.planterior.helper.feature.weather.WeatherPermissionCapabilityStore
import com.planterior.helper.feature.weather.WeatherRepository
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SettingsLocationController(
    initialRegion: SettingsRegion,
    private val accountId: String,
    private val repository: WeatherRepository,
    private val permissionCapabilities: WeatherPermissionCapabilityStore,
    private val locationGateway: WeatherLocationGateway,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineContext = Dispatchers.Main.immediate,
) {
    private val mutableState =
        MutableStateFlow(
            SettingsLocationState(
                region = initialRegion,
                consent = permissionCapabilities.read(accountId).toPresentationState(),
            )
        )
    val state: StateFlow<SettingsLocationState> = mutableState.asStateFlow()
    private val consentMutex = Mutex()
    private var generation = 0L
    private var activeRequest: LocationRequestGeneration? = null

    init {
        val capability = permissionCapabilities.read(accountId)
        if (capability == null) {
            permissionCapabilities.write(
                accountId,
                WeatherPermissionCapabilityState(
                    desiredGranted = false,
                    acknowledgedGranted = null,
                    revocationPending = true,
                    commandGeneration = 1,
                ),
            )
            convergeInBackground()
        } else if (capability.revocationPending) {
            convergeInBackground()
        }
    }

    fun beginCurrentLocationRequest(): LocationRequestGeneration =
        LocationRequestGeneration(++generation).also { activeRequest = it }

    fun acceptCurrentLocation(request: LocationRequestGeneration, name: String): Boolean {
        if (
            activeRequest != request ||
                mutableState.value.consent !is SettingsLocationConsentState.Enabled
        ) {
            return false
        }
        activeRequest = null
        mutableState.value = mutableState.value.copy(region = SettingsRegion.Current(name))
        return true
    }

    fun setLocationConsent(granted: Boolean) {
        if (!granted) {
            generation += 1
            activeRequest = null
            locationGateway.cancel()
        }
        markDesired(granted)
        mutableState.value =
            mutableState.value.copy(
                consent =
                    if (granted) SettingsLocationConsentState.Enabling
                    else SettingsLocationConsentState.Disabling
            )
        convergeInBackground()
    }

    fun revokeCurrentLocationConsent() {
        setLocationConsent(false)
    }

    fun retryLocationConsent() {
        val capability = permissionCapabilities.read(accountId) ?: return
        if (!capability.revocationPending) {
            mutableState.value = mutableState.value.copy(consent = capability.toPresentationState())
            return
        }
        mutableState.value =
            mutableState.value.copy(
                consent =
                    if (capability.desiredGranted) SettingsLocationConsentState.Enabling
                    else SettingsLocationConsentState.Disabling
            )
        convergeInBackground()
    }

    private fun markDesired(granted: Boolean) {
        val previous = permissionCapabilities.read(accountId)
        if (
            previous != null &&
                previous.commandGeneration > 0 &&
                previous.desiredGranted == granted &&
                (previous.revocationPending || previous.acknowledgedGranted == granted)
        ) {
            return
        }
        permissionCapabilities.write(
            accountId,
            WeatherPermissionCapabilityState(
                desiredGranted = granted,
                acknowledgedGranted = previous?.acknowledgedGranted,
                revocationPending = true,
                commandGeneration = nextConsentGeneration(previous?.commandGeneration ?: 0),
                osPermissionGranted = previous?.osPermissionGranted,
            ),
        )
    }

    private fun convergeInBackground() {
        scope.launch(dispatcher) {
            try {
                convergeLocationConsent()
                mutableState.value =
                    mutableState.value.copy(
                        consent = permissionCapabilities.read(accountId).toPresentationState()
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value =
                    mutableState.value.copy(consent = SettingsLocationConsentState.FailedOff)
            }
        }
    }

    private suspend fun convergeLocationConsent() {
        consentMutex.withLock {
            repeat(MAX_CONSENT_CONVERGENCE_ATTEMPTS) {
                val command = permissionCapabilities.read(accountId) ?: return
                if (!command.revocationPending) return
                val authoritative =
                    try {
                        repository.recordLocationConsent(
                            accountId = accountId,
                            granted = command.desiredGranted,
                            commandGeneration = command.commandGeneration,
                        )
                    } catch (conflict: WeatherConsentConflictException) {
                        WeatherConsentMutationResult(
                            authoritativeGeneration = conflict.authoritativeGeneration,
                            authoritativeGranted = conflict.authoritativeGranted,
                        )
                    }
                val current = permissionCapabilities.read(accountId) ?: return
                val converged = authoritative.authoritativeGranted == current.desiredGranted
                permissionCapabilities.write(
                    accountId,
                    current.copy(
                        acknowledgedGranted = authoritative.authoritativeGranted,
                        revocationPending = !converged,
                        commandGeneration =
                            if (converged) authoritative.authoritativeGeneration
                            else nextConsentGeneration(authoritative.authoritativeGeneration),
                    ),
                )
                if (converged) return
            }
            if (permissionCapabilities.read(accountId)?.revocationPending == true) {
                error("Location consent convergence exceeded the retry bound")
            }
        }
    }

    private fun WeatherPermissionCapabilityState?.toPresentationState():
        SettingsLocationConsentState =
        when {
            this == null -> SettingsLocationConsentState.Loading
            revocationPending && desiredGranted -> SettingsLocationConsentState.Enabling
            revocationPending -> SettingsLocationConsentState.Disabling
            desiredGranted && acknowledgedGranted == true ->
                SettingsLocationConsentState.Enabled(commandGeneration)
            else -> SettingsLocationConsentState.Disabled
        }

    private companion object {
        const val MAX_SAFE_CONSENT_GENERATION = 9_007_199_254_740_991L
        const val MAX_CONSENT_CONVERGENCE_ATTEMPTS = 8

        fun nextConsentGeneration(current: Long): Long =
            if (current >= MAX_SAFE_CONSENT_GENERATION) MAX_SAFE_CONSENT_GENERATION + 1
            else current + 1
    }
}
