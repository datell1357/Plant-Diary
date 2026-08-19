package com.planterior.helper.feature.weather

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Clock
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WeatherController(
    private val repository: WeatherRepository,
    locationGateway: WeatherLocationGateway,
    private val savedStateHandle: SavedStateHandle,
    private val clock: Clock,
    private val dispatcher: CoroutineContext = Dispatchers.Main.immediate,
    permissionCapabilities: WeatherPermissionCapabilityStore? = null,
) : ViewModel() {
    private val permissionCapabilities =
        permissionCapabilities ?: SavedStatePermissionCapabilityStore(savedStateHandle)
    private val mutableState = MutableStateFlow<WeatherUiState>(WeatherUiState.Loading)
    val state: StateFlow<WeatherUiState> = mutableState.asStateFlow()
    private val locationGateway = RebindableWeatherLocationGateway(locationGateway)

    private var actionJob: Job? = null
    private var reconciliationJob: Job? = null
    private var accountGeneration = 0L
    private var activeAccountId: String? = null
    private var latestPermissionCapability: Boolean? = null
    private var permissionReconciliationFailed = false
    private val permissionReconciliationRequests = Channel<Unit>(Channel.UNLIMITED)
    private val consentMutexes = mutableMapOf<String, Mutex>()

    init {
        viewModelScope.launch(dispatcher) {
            repository.accounts().distinctUntilChanged().collectLatest { accountId ->
                accountGeneration += 1
                actionJob?.cancel()
                reconciliationJob?.cancelAndJoin()
                locationGateway.cancel()
                activeAccountId = null
                permissionReconciliationFailed = false
                if (accountId == null) {
                    mutableState.value = WeatherUiState.SignedOut
                } else {
                    mutableState.value = WeatherUiState.Loading
                    var reconciledCapability: Boolean? = null
                    var reconciliationFailure: WeatherFailure? = null
                    latestPermissionCapability?.let { granted ->
                        reconciliationFailure = reconcileForOwnerLoad(accountId, granted)
                        reconciledCapability = granted
                    }
                    activeAccountId = accountId
                    if (latestPermissionCapability != reconciledCapability) {
                        permissionReconciliationRequests.trySend(Unit)
                    }
                    val load = safeLoad(accountId)
                    mutableState.value =
                        ready(
                            accountId = accountId,
                            load = load,
                            permission = locationGateway.permission(),
                            searchQuery = savedStateHandle.get<String>(SEARCH_QUERY).orEmpty(),
                            failure = reconciliationFailure,
                        )
                }
            }
        }
        viewModelScope.launch(dispatcher) {
            for (ignored in permissionReconciliationRequests) {
                val accountId = activeAccountId ?: continue
                val granted = latestPermissionCapability ?: continue
                val generation = accountGeneration
                val job =
                    launch(dispatcher) {
                        reconcileObservedPermission(accountId, generation, granted)
                    }
                reconciliationJob = job
                job.join()
                if (reconciliationJob === job) reconciliationJob = null
            }
        }
    }

    internal fun updateLocationGateway(gateway: WeatherLocationGateway) {
        locationGateway.update(gateway)
    }

    internal fun removeLocationGateway(gateway: WeatherLocationGateway) {
        locationGateway.remove(gateway)
    }

    fun refresh() {
        val current = mutableState.value as? WeatherUiState.Ready ?: return
        launchAction(current) { accountId, generation ->
            showRefreshing(current)
            applyIfCurrent(accountId, generation, repository.refresh(accountId, null))
        }
    }

    fun useCurrentLocation() {
        val current = mutableState.value as? WeatherUiState.Ready ?: return
        launchAction(current) { accountId, generation ->
            val permission =
                when (val existing = locationGateway.permission()) {
                    LocationPermission.GrantedApproximate -> existing
                    is LocationPermission.Denied ->
                        if (existing.canAskAgain) locationGateway.requestPermission() else existing
                }
            if (permission !is LocationPermission.GrantedApproximate) {
                latestPermissionCapability = false
                recordOsPermissionCapability(accountId, false)
                markConsentDesired(accountId, false)
                convergeLocationConsent(accountId)
                updateReady(accountId, generation) {
                    it.copy(locationPermission = permission, refreshing = false, failure = null)
                }
                return@launchAction
            }
            showRefreshing(current.copy(locationPermission = permission))
            latestPermissionCapability = true
            recordOsPermissionCapability(accountId, true)
            markConsentDesired(accountId, true)
            convergeLocationConsent(accountId)
            if (!isConsentGranted(accountId)) {
                updateReady(accountId, generation) {
                    it.copy(refreshing = false, locationPermission = locationGateway.permission())
                }
                return@launchAction
            }
            updateReady(accountId, generation) {
                it.copy(appLocationConsentGranted = true, locationPermission = permission)
            }
            val location = locationGateway.approximateLocation()
            if (location == null) {
                updateReady(accountId, generation) {
                    it.copy(
                        locationPermission = permission,
                        refreshing = false,
                        failure = WeatherFailure.LocationUnavailable,
                    )
                }
                return@launchAction
            }
            convergeLocationConsent(accountId)
            if (!isConsentGranted(accountId)) {
                updateReady(accountId, generation) {
                    it.copy(refreshing = false, locationPermission = locationGateway.permission())
                }
                return@launchAction
            }
            applyIfCurrent(
                accountId,
                generation,
                repository.switchToCurrentLocation(accountId, location),
            )
        }
    }

    fun revokeLocationConsent() {
        val current = mutableState.value as? WeatherUiState.Ready ?: return
        locationGateway.cancel()
        beginLocationRevocation(current, desiredGranted = false)
    }

    fun reconcileLocationPermission() {
        val granted = locationGateway.permission() is LocationPermission.GrantedApproximate
        latestPermissionCapability = granted
        activeAccountId?.let { accountId ->
            val hadState = permissionCapabilities.read(accountId) != null
            recordOsPermissionCapability(accountId, granted)
            if (!granted || !hadState) {
                markConsentDesired(accountId, false)
                val current = mutableState.value as? WeatherUiState.Ready
                if (current?.accountId == accountId) {
                    mutableState.value = current.copy(appLocationConsentGranted = false)
                }
            }
        }
        permissionReconciliationRequests.trySend(Unit)
    }

    private suspend fun reconcileForOwnerLoad(
        accountId: String,
        granted: Boolean,
    ): WeatherFailure? =
        try {
            reconcileAccountPermission(accountId, granted)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (_: WeatherConsentConvergenceException) {
            WeatherFailure.ConsentConflict
        } catch (_: Exception) {
            WeatherFailure.ProviderUnavailable
        }

    private suspend fun reconcileObservedPermission(
        accountId: String,
        generation: Long,
        granted: Boolean,
    ) {
        try {
            reconcileAccountPermission(accountId, granted)
            updateReady(accountId, generation) { current ->
                val clearFailure =
                    (permissionReconciliationFailed &&
                        current.failure == WeatherFailure.ProviderUnavailable ||
                        current.failure == WeatherFailure.ConsentConflict) &&
                        !current.refreshing &&
                        !current.saving
                permissionReconciliationFailed = false
                current.copy(
                    locationPermission = locationGateway.permission(),
                    failure = if (clearFailure) null else current.failure,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: WeatherConsentConvergenceException) {
            updateReady(accountId, generation) { current ->
                current.copy(
                    locationPermission = locationGateway.permission(),
                    failure = WeatherFailure.ConsentConflict,
                )
            }
        } catch (_: Exception) {
            updateReady(accountId, generation) { current ->
                permissionReconciliationFailed = true
                current.copy(
                    locationPermission = locationGateway.permission(),
                    failure = WeatherFailure.ProviderUnavailable,
                )
            }
        }
    }

    private fun recordOsPermissionCapability(
        accountId: String,
        granted: Boolean,
    ) {
        val previous = permissionCapabilities.read(accountId)
        val next =
            previous?.copy(osPermissionGranted = granted)
                ?: WeatherPermissionCapabilityState(
                    desiredGranted = false,
                    acknowledgedGranted = null,
                    revocationPending = false,
                    osPermissionGranted = granted,
                )
        if (next != previous) permissionCapabilities.write(accountId, next)
    }

    private fun markConsentDesired(
        accountId: String,
        granted: Boolean,
    ): WeatherPermissionCapabilityState {
        val previous = permissionCapabilities.read(accountId)
        if (
            previous != null &&
                previous.commandGeneration > 0 &&
                previous.desiredGranted == granted &&
                (previous.revocationPending || previous.acknowledgedGranted == granted)
        ) {
            return previous
        }
        val next =
            WeatherPermissionCapabilityState(
                desiredGranted = granted,
                acknowledgedGranted = previous?.acknowledgedGranted,
                revocationPending = true,
                commandGeneration = nextConsentGeneration(previous?.commandGeneration ?: 0),
                osPermissionGranted = previous?.osPermissionGranted,
            )
        permissionCapabilities.write(accountId, next)
        return next
    }

    private suspend fun reconcileAccountPermission(accountId: String, granted: Boolean) {
        val hadState = permissionCapabilities.read(accountId) != null
        recordOsPermissionCapability(accountId, granted)
        if (!granted || !hadState) markConsentDesired(accountId, false)
        convergeLocationConsent(accountId)
    }

    private suspend fun convergeLocationConsent(accountId: String) {
        val mutex = consentMutexes.getOrPut(accountId) { Mutex() }
        mutex.withLock {
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
                val converged = adoptAuthoritativeConsent(accountId, current, authoritative)
                if (converged) return
            }
            if (permissionCapabilities.read(accountId)?.revocationPending == true) {
                throw WeatherConsentConvergenceException()
            }
        }
    }

    private fun adoptAuthoritativeConsent(
        accountId: String,
        current: WeatherPermissionCapabilityState,
        authoritative: WeatherConsentMutationResult,
    ): Boolean {
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
        return converged
    }

    private fun isConsentGranted(accountId: String): Boolean {
        val capability = permissionCapabilities.read(accountId) ?: return false
        return capability.desiredGranted &&
            capability.acknowledgedGranted == true &&
            !capability.revocationPending
    }

    private fun beginLocationRevocation(
        current: WeatherUiState.Ready,
        desiredGranted: Boolean,
    ) {
        markConsentDesired(current.accountId, desiredGranted)
        mutableState.value = current.copy(appLocationConsentGranted = desiredGranted)
        launchAction(current.copy(appLocationConsentGranted = desiredGranted)) {
            accountId,
            generation ->
            convergeLocationConsent(accountId)
            applyIfCurrent(accountId, generation, safeLoad(accountId))
        }
    }

    fun changeSearchQuery(value: String) {
        val current = mutableState.value as? WeatherUiState.Ready ?: return
        val normalized = value.take(MAX_SEARCH_LENGTH)
        savedStateHandle[SEARCH_QUERY] = normalized
        mutableState.value = current.copy(searchQuery = normalized, searchResults = emptyList())
    }

    fun searchRegions() {
        val current = mutableState.value as? WeatherUiState.Ready ?: return
        val query = current.searchQuery.trim()
        if (query.length < MIN_SEARCH_LENGTH) return
        launchAction(current) { accountId, generation ->
            val results = repository.searchRegions(accountId, query)
            updateReady(accountId, generation) {
                it.copy(searchResults = results, failure = null)
            }
        }
    }

    fun chooseManualRegion(region: WeatherRegion) {
        val current = mutableState.value as? WeatherUiState.Ready ?: return
        launchAction(current) { accountId, generation ->
            val revision = current.dashboard?.revision ?: 0
            val result = repository.selectManualRegion(accountId, region, revision)
            savedStateHandle[SEARCH_QUERY] = ""
            applyIfCurrent(accountId, generation, result, searchQuery = "")
        }
    }

    fun saveAlerts(globalEnabled: Boolean, plants: Map<String, Boolean>) {
        val current = mutableState.value as? WeatherUiState.Ready ?: return
        launchAction(current) { accountId, generation ->
            mutableState.value = current.copy(saving = true, failure = null)
            try {
                val result =
                    repository.saveAlerts(
                        accountId,
                        globalEnabled,
                        plants,
                        current.dashboard?.revision ?: 0,
                    )
                applyIfCurrent(accountId, generation, result)
            } catch (_: WeatherRevisionConflictException) {
                applyIfCurrent(
                    accountId,
                    generation,
                    safeLoad(accountId),
                    explicitFailure = WeatherFailure.RevisionConflict,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                updateReady(accountId, generation) {
                    it.copy(saving = false, failure = WeatherFailure.SaveFailed)
                }
            }
        }
    }

    private fun launchAction(
        current: WeatherUiState.Ready,
        block: suspend (String, Long) -> Unit,
    ) {
        actionJob?.cancel()
        val generation = accountGeneration
        actionJob =
            viewModelScope.launch(dispatcher) {
                try {
                    block(current.accountId, generation)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: WeatherConsentConvergenceException) {
                    applyIfCurrent(
                        current.accountId,
                        generation,
                        safeLoad(current.accountId),
                        explicitFailure = WeatherFailure.ConsentConflict,
                    )
                } catch (_: Exception) {
                    updateReady(current.accountId, generation) {
                        it.copy(
                            dashboard =
                                it.dashboard?.let { dashboard ->
                                    dashboard.copy(
                                        stale = dashboard.snapshot.isStaleAt(clock.instant())
                                    )
                                },
                            refreshing = false,
                            saving = false,
                            failure = WeatherFailure.ProviderUnavailable,
                        )
                    }
                }
            }
    }

    private suspend fun safeLoad(accountId: String): WeatherLoad =
        try {
            repository.load(accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            WeatherLoad.Failed()
        }

    private fun showRefreshing(current: WeatherUiState.Ready) {
        mutableState.value = current.copy(refreshing = true, failure = null)
    }

    private fun applyIfCurrent(
        accountId: String,
        generation: Long,
        load: WeatherLoad,
        searchQuery: String? = null,
        explicitFailure: WeatherFailure? = null,
    ) {
        val current = mutableState.value as? WeatherUiState.Ready ?: return
        if (current.accountId != accountId || accountGeneration != generation) return
        mutableState.value =
            ready(
                accountId,
                load,
                locationGateway.permission(),
                searchQuery ?: current.searchQuery,
                failure =
                    explicitFailure
                        ?: if (load is WeatherLoad.Failed) WeatherFailure.ProviderUnavailable
                        else null,
            )
    }

    private fun updateReady(
        accountId: String,
        generation: Long,
        transform: (WeatherUiState.Ready) -> WeatherUiState.Ready,
    ) {
        val current = mutableState.value as? WeatherUiState.Ready ?: return
        if (current.accountId == accountId && accountGeneration == generation) {
            mutableState.value = transform(current)
        }
    }

    private fun ready(
        accountId: String,
        load: WeatherLoad,
        permission: LocationPermission,
        searchQuery: String,
        failure: WeatherFailure? = null,
    ): WeatherUiState.Ready {
        val dashboard =
            when (load) {
                is WeatherLoad.Fresh ->
                    load.dashboard.copy(stale = load.dashboard.snapshot.isStaleAt(clock.instant()))
                is WeatherLoad.Stale -> load.dashboard.copy(stale = true)
                is WeatherLoad.Failed -> load.cached?.copy(stale = true)
                WeatherLoad.NotConfigured -> null
            }
        return WeatherUiState.Ready(
            accountId = accountId,
            dashboard = dashboard,
            locationPermission = permission,
            appLocationConsentGranted = isConsentGranted(accountId),
            searchQuery = searchQuery,
            failure =
                failure
                    ?: if (load is WeatherLoad.Failed) WeatherFailure.ProviderUnavailable else null,
        )
    }

    override fun onCleared() {
        actionJob?.cancel()
        reconciliationJob?.cancel()
        locationGateway.cancel()
        permissionReconciliationRequests.close()
    }

    companion object {
        private const val SEARCH_QUERY = "weather.search-query"
        private const val MAX_SEARCH_LENGTH = 80
        private const val MIN_SEARCH_LENGTH = 2
        private const val MAX_SAFE_CONSENT_GENERATION = 9_007_199_254_740_991L
        private const val MAX_CONSENT_CONVERGENCE_ATTEMPTS = 8

        private fun nextConsentGeneration(current: Long): Long =
            if (current >= MAX_SAFE_CONSENT_GENERATION) MAX_SAFE_CONSENT_GENERATION + 1
            else current + 1
    }
}

private class WeatherConsentConvergenceException : Exception()

private class SavedStatePermissionCapabilityStore(private val savedStateHandle: SavedStateHandle) :
    WeatherPermissionCapabilityStore {
    override fun read(accountId: String): WeatherPermissionCapabilityState? {
        val prefix = "weather.permission-capability.$accountId"
        val desired = savedStateHandle.get<Boolean>("$prefix.desired") ?: return null
        return WeatherPermissionCapabilityState(
            desiredGranted = desired,
            acknowledgedGranted = savedStateHandle.get<Boolean>("$prefix.acknowledged"),
            revocationPending = savedStateHandle.get<Boolean>("$prefix.pending") == true,
            commandGeneration = savedStateHandle.get<Long>("$prefix.generation") ?: 0,
            osPermissionGranted = savedStateHandle.get<Boolean>("$prefix.os-granted"),
        )
    }

    override fun write(accountId: String, state: WeatherPermissionCapabilityState) {
        val prefix = "weather.permission-capability.$accountId"
        savedStateHandle["$prefix.desired"] = state.desiredGranted
        if (state.acknowledgedGranted == null) {
            savedStateHandle.remove<Boolean>("$prefix.acknowledged")
        } else {
            savedStateHandle["$prefix.acknowledged"] = state.acknowledgedGranted
        }
        savedStateHandle["$prefix.pending"] = state.revocationPending
        savedStateHandle["$prefix.generation"] = state.commandGeneration
        if (state.osPermissionGranted == null) {
            savedStateHandle.remove<Boolean>("$prefix.os-granted")
        } else {
            savedStateHandle["$prefix.os-granted"] = state.osPermissionGranted
        }
    }
}
