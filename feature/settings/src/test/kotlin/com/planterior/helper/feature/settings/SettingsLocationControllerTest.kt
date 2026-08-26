package com.planterior.helper.feature.settings

import com.planterior.helper.feature.weather.ApproximateLocation
import com.planterior.helper.feature.weather.LocationPermission
import com.planterior.helper.feature.weather.WeatherConsentMutationResult
import com.planterior.helper.feature.weather.WeatherLoad
import com.planterior.helper.feature.weather.WeatherLocationGateway
import com.planterior.helper.feature.weather.WeatherPermissionCapabilityState
import com.planterior.helper.feature.weather.WeatherPermissionCapabilityStore
import com.planterior.helper.feature.weather.WeatherRegion
import com.planterior.helper.feature.weather.WeatherRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsLocationControllerTest {
    @Test
    fun `revoke cancels immediately sends exact next and preserves manual region`() = runTest {
        val repository = FakeWeatherRepository()
        val capabilities =
            FakeCapabilityStore(
                WeatherPermissionCapabilityState(
                    desiredGranted = true,
                    acknowledgedGranted = true,
                    revocationPending = false,
                    commandGeneration = 7,
                    osPermissionGranted = true,
                )
            )
        val gateway = FakeLocationGateway()
        val controller = controller(repository, capabilities, gateway)
        val request = controller.beginCurrentLocationRequest()
        repository.pauseNextConsent = true

        controller.setLocationConsent(false)
        testScheduler.runCurrent()

        assertEquals(1, gateway.cancelCalls)
        assertEquals(SettingsRegion.Manual("서울특별시"), controller.state.value.region)
        assertTrue(controller.state.value.consent is SettingsLocationConsentState.Disabling)
        assertFalse(controller.acceptCurrentLocation(request, "현재 위치"))
        assertEquals(listOf(8L to false), repository.consentCommands)

        repository.releaseConsent.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(SettingsLocationConsentState.Disabled, controller.state.value.consent)
        assertEquals(
            WeatherPermissionCapabilityState(false, false, false, 8, true),
            capabilities.read("account-a"),
        )
    }

    @Test
    fun `failed revoke remains safely off and retry replays the same command`() = runTest {
        val repository =
            FakeWeatherRepository(initialGeneration = 4).apply { failuresRemaining = 1 }
        val capabilities =
            FakeCapabilityStore(WeatherPermissionCapabilityState(true, true, false, 4, true))
        val gateway = FakeLocationGateway()
        val controller = controller(repository, capabilities, gateway)

        controller.setLocationConsent(false)
        testScheduler.runCurrent()

        assertEquals(SettingsLocationConsentState.FailedOff, controller.state.value.consent)
        assertEquals(listOf(5L to false), repository.consentCommands)
        assertEquals(true, capabilities.read("account-a")?.revocationPending)
        assertEquals(false, capabilities.read("account-a")?.desiredGranted)
        assertEquals(1, gateway.cancelCalls)

        controller.retryLocationConsent()
        testScheduler.runCurrent()

        assertEquals(listOf(5L to false, 5L to false), repository.consentCommands)
        assertEquals(SettingsLocationConsentState.Disabled, controller.state.value.consent)
        assertEquals(0, gateway.locationRequests)
    }

    @Test
    fun `grant uses the same canonical exact next command without collecting location`() = runTest {
        val repository = FakeWeatherRepository(initialGeneration = 11, initialGranted = false)
        val capabilities =
            FakeCapabilityStore(WeatherPermissionCapabilityState(false, false, false, 11, true))
        val gateway = FakeLocationGateway()
        val controller = controller(repository, capabilities, gateway)
        repository.pauseNextConsent = true

        controller.setLocationConsent(true)
        testScheduler.runCurrent()

        assertTrue(controller.state.value.consent is SettingsLocationConsentState.Enabling)
        assertEquals(listOf(12L to true), repository.consentCommands)
        assertEquals(0, gateway.locationRequests)

        repository.releaseConsent.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(SettingsLocationConsentState.Enabled(12), controller.state.value.consent)
        assertEquals(0, gateway.locationRequests)
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        repository: FakeWeatherRepository,
        capabilities: FakeCapabilityStore,
        gateway: FakeLocationGateway,
    ) =
        SettingsLocationController(
            initialRegion = SettingsRegion.Manual("서울특별시"),
            accountId = "account-a",
            repository = repository,
            permissionCapabilities = capabilities,
            locationGateway = gateway,
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    private class FakeCapabilityStore(initial: WeatherPermissionCapabilityState) :
        WeatherPermissionCapabilityStore {
        private val values = mutableMapOf("account-a" to initial)

        override fun read(accountId: String): WeatherPermissionCapabilityState? = values[accountId]

        override fun write(accountId: String, state: WeatherPermissionCapabilityState) {
            values[accountId] = state
        }
    }

    private class FakeLocationGateway : WeatherLocationGateway {
        var cancelCalls = 0
        var locationRequests = 0

        override fun permission() = LocationPermission.GrantedApproximate

        override suspend fun approximateLocation(): ApproximateLocation? {
            locationRequests += 1
            return ApproximateLocation(37.56, 126.98)
        }

        override fun cancel() {
            cancelCalls += 1
        }
    }

    private class FakeWeatherRepository(
        initialGeneration: Long = 7,
        initialGranted: Boolean = true,
    ) : WeatherRepository {
        private var generation = initialGeneration
        private var granted = initialGranted
        var failuresRemaining = 0
        var pauseNextConsent = false
        val releaseConsent = CompletableDeferred<Unit>()
        val consentCommands = mutableListOf<Pair<Long, Boolean>>()

        override fun accounts() = flowOf("account-a")

        override suspend fun load(accountId: String) = WeatherLoad.NotConfigured

        override suspend fun recordLocationConsent(
            accountId: String,
            granted: Boolean,
            commandGeneration: Long,
        ): WeatherConsentMutationResult {
            consentCommands += commandGeneration to granted
            if (pauseNextConsent) {
                pauseNextConsent = false
                releaseConsent.await()
            }
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                throw IllegalStateException("deterministic consent failure")
            }
            if (commandGeneration == generation + 1) {
                generation = commandGeneration
                this.granted = granted
            }
            return WeatherConsentMutationResult(generation, this.granted)
        }

        override suspend fun refresh(accountId: String, location: ApproximateLocation?) =
            WeatherLoad.NotConfigured

        override suspend fun searchRegions(accountId: String, query: String) =
            emptyList<WeatherRegion>()

        override suspend fun selectManualRegion(
            accountId: String,
            region: WeatherRegion,
            expectedRevision: Long,
        ) = WeatherLoad.NotConfigured

        override suspend fun saveAlerts(
            accountId: String,
            globalEnabled: Boolean,
            plants: Map<String, Boolean>,
            expectedRevision: Long,
        ) = WeatherLoad.NotConfigured
    }
}
