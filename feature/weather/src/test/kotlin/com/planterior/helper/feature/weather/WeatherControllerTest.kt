package com.planterior.helper.feature.weather

import androidx.lifecycle.SavedStateHandle
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherControllerTest {
    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue(
            "Expected ${T::class.java.simpleName}, was ${value?.javaClass?.simpleName}",
            value is T,
        )
        return value as T
    }

    private val clock = Clock.fixed(Instant.parse("2026-08-12T03:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `denied capability observed before delayed account load revokes when identity arrives`() =
        runTest {
            val repository = FakeWeatherRepository(delayedAccounts = true)
            val location = FakeLocationGateway(LocationPermission.Denied(true))
            val controller =
                WeatherController(
                    repository,
                    location,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                )

            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            repository.emitAccount("account-a")
            testScheduler.runCurrent()

            assertEquals(listOf("consent:false"), repository.events)
            assertEquals(
                "account-a",
                assertIs<WeatherUiState.Ready>(controller.state.value).accountId,
            )
        }

    @Test
    fun `granted to denied capability during delayed load cancels stale join and revokes once`() =
        runTest {
            val load = CompletableDeferred<WeatherLoad>()
            val repository = FakeWeatherRepository(delayedAccounts = true, loadDeferred = load)
            val location = FakeLocationGateway(LocationPermission.GrantedApproximate)
            val controller =
                WeatherController(
                    repository,
                    location,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                )
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            repository.emitAccount("account-a")
            testScheduler.runCurrent()

            location.permission = LocationPermission.Denied(false)
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            assertEquals(listOf("consent:false"), repository.events)

            load.complete(WeatherLoad.NotConfigured)
            testScheduler.runCurrent()
            assertEquals(
                "account-a",
                assertIs<WeatherUiState.Ready>(controller.state.value).accountId,
            )
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            assertEquals(listOf("consent:false"), repository.events)
        }

    @Test
    fun `account switch while loading joins latest denied capability to each account exactly once`() =
        runTest {
            val load = CompletableDeferred<WeatherLoad>()
            val repository = FakeWeatherRepository(delayedAccounts = true, loadDeferred = load)
            val capabilities = FakePermissionCapabilityStore()
            val controller =
                WeatherController(
                    repository,
                    FakeLocationGateway(LocationPermission.Denied(true)),
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            repository.emitAccount("account-a")
            testScheduler.runCurrent()
            repository.emitAccount("account-b")
            testScheduler.runCurrent()

            assertEquals(listOf("account-a:false", "account-b:false"), repository.consentEvents)
            assertEquals(false, capabilities.read("account-a")?.revocationPending)
            assertEquals(false, capabilities.read("account-b")?.revocationPending)

            load.complete(WeatherLoad.NotConfigured)
            testScheduler.runCurrent()
            assertEquals(
                "account-b",
                assertIs<WeatherUiState.Ready>(controller.state.value).accountId,
            )
        }

    @Test
    fun `resume event while account load is pending reconciles without waiting for ready`() =
        runTest {
            val load = CompletableDeferred<WeatherLoad>()
            val repository = FakeWeatherRepository(delayedAccounts = true, loadDeferred = load)
            val location = FakeLocationGateway(LocationPermission.Denied(true))
            val controller =
                WeatherController(
                    repository,
                    location,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                )
            testScheduler.runCurrent()
            repository.emitAccount("account-a")
            testScheduler.runCurrent()
            assertIs<WeatherUiState.Loading>(controller.state.value)

            controller.reconcileLocationPermission()
            testScheduler.runCurrent()

            assertEquals(listOf("consent:false"), repository.events)
            load.complete(WeatherLoad.NotConfigured)
            testScheduler.runCurrent()
            assertIs<WeatherUiState.Ready>(controller.state.value)
        }

    @Test
    fun `failed joined revoke stays pending and same capability resume retries after load`() =
        runTest {
            val repository =
                FakeWeatherRepository(delayedAccounts = true).apply {
                    consentFailuresRemaining = 1
                }
            val capabilities = FakePermissionCapabilityStore()
            val controller =
                WeatherController(
                    repository,
                    FakeLocationGateway(LocationPermission.Denied(false)),
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            repository.emitAccount("account-a")
            testScheduler.runCurrent()
            assertEquals(true, capabilities.read("account-a")?.revocationPending)

            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()

            assertEquals(listOf("consent:false", "consent:false"), repository.events)
            assertEquals(false, capabilities.read("account-a")?.revocationPending)
        }

    @Test
    fun `normal ready account and repeated resume join revokes once`() = runTest {
        val repository = FakeWeatherRepository()
        val controller =
            WeatherController(
                repository,
                FakeLocationGateway(LocationPermission.Denied(true)),
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
            )
        testScheduler.runCurrent()

        controller.reconcileLocationPermission()
        testScheduler.runCurrent()
        controller.reconcileLocationPermission()
        testScheduler.runCurrent()

        assertEquals(listOf("consent:false"), repository.events)
        assertEquals("account-a", assertIs<WeatherUiState.Ready>(controller.state.value).accountId)
    }

    @Test
    fun `rotation during permission request resumes once through recreated gateway`() = runTest {
        val repository = FakeWeatherRepository()
        val oldPermission = CompletableDeferred<LocationPermission>()
        val newPermission = CompletableDeferred<LocationPermission>()
        val oldGateway =
            LifecycleFakeLocationGateway(
                permission = LocationPermission.Denied(true),
                permissionResult = oldPermission,
            )
        val newGateway =
            LifecycleFakeLocationGateway(
                permission = LocationPermission.Denied(true),
                permissionResult = newPermission,
                location = ApproximateLocation(37.57, 126.99),
            )
        val controller =
            WeatherController(
                repository,
                oldGateway,
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
            )
        testScheduler.runCurrent()

        controller.useCurrentLocation()
        testScheduler.runCurrent()
        assertEquals(1, oldGateway.permissionRequests)

        controller.updateLocationGateway(newGateway)
        controller.reconcileLocationPermission()
        testScheduler.runCurrent()
        assertEquals(1, newGateway.permissionRequests)
        newPermission.complete(LocationPermission.GrantedApproximate)
        testScheduler.runCurrent()

        assertEquals(1, repository.events.count { it == "consent:true" })
        assertEquals(1, repository.events.count { it == "refresh:37.57,126.99" })
        assertEquals(1, newGateway.locationRequests)
        assertTrue(assertIs<WeatherUiState.Ready>(controller.state.value).failure == null)
    }

    @Test
    fun `old permission callback after recreated result is ignored`() = runTest {
        val repository = FakeWeatherRepository()
        val oldPermission = CompletableDeferred<LocationPermission>()
        val newPermission = CompletableDeferred<LocationPermission>()
        val oldGateway =
            LifecycleFakeLocationGateway(
                permission = LocationPermission.Denied(true),
                permissionResult = oldPermission,
            )
        val newGateway =
            LifecycleFakeLocationGateway(
                permission = LocationPermission.Denied(true),
                permissionResult = newPermission,
                location = ApproximateLocation(35.18, 129.08),
            )
        val controller =
            WeatherController(
                repository,
                oldGateway,
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
            )
        testScheduler.runCurrent()
        controller.useCurrentLocation()
        testScheduler.runCurrent()

        controller.updateLocationGateway(newGateway)
        testScheduler.runCurrent()
        newPermission.complete(LocationPermission.GrantedApproximate)
        testScheduler.runCurrent()
        val completedEvents = repository.events.toList()

        oldPermission.complete(LocationPermission.Denied(false))
        testScheduler.runCurrent()

        assertEquals(completedEvents, repository.events)
        assertEquals(1, repository.events.count { it.startsWith("refresh:") })
        assertTrue(oldGateway.cancelCount >= 1)
    }

    @Test
    fun `current location acquisition switches to recreated gateway and suppresses old result`() =
        runTest {
            val repository = FakeWeatherRepository()
            val oldLocation = CompletableDeferred<ApproximateLocation?>()
            val oldGateway =
                LifecycleFakeLocationGateway(
                    permission = LocationPermission.GrantedApproximate,
                    locationResult = oldLocation,
                )
            val newGateway =
                LifecycleFakeLocationGateway(
                    permission = LocationPermission.GrantedApproximate,
                    location = ApproximateLocation(37.56, 126.98),
                )
            val controller =
                WeatherController(
                    repository,
                    oldGateway,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                )
            testScheduler.runCurrent()
            controller.useCurrentLocation()
            testScheduler.runCurrent()
            assertEquals(1, oldGateway.locationRequests)

            controller.updateLocationGateway(newGateway)
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            assertEquals(1, newGateway.locationRequests)
            oldLocation.complete(ApproximateLocation(33.45, 126.57))
            testScheduler.runCurrent()

            assertEquals(1, repository.events.count { it == "consent:true" })
            assertEquals(1, repository.events.count { it == "refresh:37.56,126.98" })
            assertFalse(repository.events.any { it == "refresh:33.45,126.57" })
        }

    @Test
    fun `revoke started before recreated grant converges true after stale revoke response`() =
        runTest {
            val repository = FakeWeatherRepository().apply { blockedConsentValue = false }
            val capabilities = FakePermissionCapabilityStore()
            val oldGateway =
                LifecycleFakeLocationGateway(permission = LocationPermission.Denied(true))
            val recreatedGateway =
                LifecycleFakeLocationGateway(
                    permission = LocationPermission.GrantedApproximate,
                    location = ApproximateLocation(37.56, 126.98),
                )
            val controller =
                WeatherController(
                    repository,
                    oldGateway,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()

            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            assertTrue(repository.consentCallStarted.isCompleted)

            controller.updateLocationGateway(recreatedGateway)
            controller.useCurrentLocation()
            testScheduler.runCurrent()
            assertEquals(true, capabilities.read("account-a")?.desiredGranted)
            assertEquals(2L, capabilities.read("account-a")?.commandGeneration)

            repository.releaseConsentCall.complete(Unit)
            testScheduler.runCurrent()

            assertEquals(
                listOf("consent:false", "consent:true", "refresh:37.56,126.98"),
                repository.events,
            )
            assertEquals(2L to true, repository.serverConsent("account-a"))
            assertEquals(
                WeatherPermissionCapabilityState(true, true, false, 2, true),
                capabilities.read("account-a"),
            )
        }

    @Test
    fun `grant started before denial converges false and cannot refresh with stale grant`() =
        runTest {
            val repository = FakeWeatherRepository().apply { blockedConsentValue = true }
            val capabilities = FakePermissionCapabilityStore()
            val gateway =
                LifecycleFakeLocationGateway(
                    permission = LocationPermission.GrantedApproximate,
                    location = ApproximateLocation(37.56, 126.98),
                )
            val controller =
                WeatherController(
                    repository,
                    gateway,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()

            controller.useCurrentLocation()
            testScheduler.runCurrent()
            assertTrue(repository.consentCallStarted.isCompleted)

            gateway.permission = LocationPermission.Denied(false)
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            assertEquals(false, capabilities.read("account-a")?.desiredGranted)
            assertEquals(2L, capabilities.read("account-a")?.commandGeneration)

            repository.releaseConsentCall.complete(Unit)
            testScheduler.runCurrent()

            assertEquals(listOf("consent:true", "consent:false"), repository.events)
            assertEquals(2L to false, repository.serverConsent("account-a"))
            assertEquals(
                WeatherPermissionCapabilityState(false, false, false, 2, false),
                capabilities.read("account-a"),
            )
            assertEquals(0, gateway.locationRequests)
        }

    @Test
    fun `lost grant response remains durable and same generation retry converges exactly once`() =
        runTest {
            val repository = FakeWeatherRepository().apply { consentResponseLossesRemaining = 1 }
            val capabilities = FakePermissionCapabilityStore()
            val gateway =
                LifecycleFakeLocationGateway(
                    permission = LocationPermission.GrantedApproximate,
                    location = ApproximateLocation(35.18, 129.08),
                )
            val controller =
                WeatherController(
                    repository,
                    gateway,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()

            controller.useCurrentLocation()
            testScheduler.runCurrent()
            assertEquals(
                WeatherPermissionCapabilityState(true, null, true, 1, true),
                capabilities.read("account-a"),
            )
            assertEquals(1L to true, repository.serverConsent("account-a"))

            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            controller.useCurrentLocation()
            testScheduler.runCurrent()

            assertEquals(2, repository.events.count { it == "consent:true" })
            assertEquals(1, repository.events.count { it == "refresh:35.18,129.08" })
            assertEquals(
                WeatherPermissionCapabilityState(true, true, false, 1, true),
                capabilities.read("account-a"),
            )
        }

    @Test
    fun `repeated resume during location fetch preserves one consent and refresh completion`() =
        runTest {
            val repository = FakeWeatherRepository()
            val pendingLocation = CompletableDeferred<ApproximateLocation?>()
            val gateway =
                LifecycleFakeLocationGateway(
                    permission = LocationPermission.GrantedApproximate,
                    locationResult = pendingLocation,
                )
            val controller =
                WeatherController(
                    repository,
                    gateway,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                )
            testScheduler.runCurrent()
            controller.useCurrentLocation()
            testScheduler.runCurrent()

            repeat(3) {
                controller.reconcileLocationPermission()
                testScheduler.runCurrent()
            }
            pendingLocation.complete(ApproximateLocation(37.56, 126.98))
            testScheduler.runCurrent()

            assertEquals(1, repository.events.count { it == "consent:true" })
            assertEquals(1, repository.events.count { it == "refresh:37.56,126.98" })
            assertEquals(1, gateway.locationRequests)
        }

    @Test
    fun `successful denial supersedes current location refresh without cancelling acquisition`() =
        runTest {
            val repository = FakeWeatherRepository()
            val pendingLocation = CompletableDeferred<ApproximateLocation?>()
            val gateway =
                LifecycleFakeLocationGateway(
                    permission = LocationPermission.GrantedApproximate,
                    locationResult = pendingLocation,
                )
            val controller =
                WeatherController(
                    repository,
                    gateway,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                )
            testScheduler.runCurrent()
            controller.useCurrentLocation()
            testScheduler.runCurrent()

            gateway.permission = LocationPermission.Denied(false)
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            pendingLocation.complete(ApproximateLocation(35.18, 129.08))
            testScheduler.runCurrent()

            assertEquals(1, repository.events.count { it == "consent:true" })
            assertEquals(1, repository.events.count { it == "consent:false" })
            assertEquals(0, repository.events.count { it == "refresh:35.18,129.08" })
        }

    @Test
    fun `failed denial remains pending then supersedes current location refresh on retry`() =
        runTest {
            val repository = FakeWeatherRepository()
            val pendingLocation = CompletableDeferred<ApproximateLocation?>()
            val gateway =
                LifecycleFakeLocationGateway(
                    permission = LocationPermission.GrantedApproximate,
                    locationResult = pendingLocation,
                )
            val controller =
                WeatherController(
                    repository,
                    gateway,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                )
            testScheduler.runCurrent()
            controller.useCurrentLocation()
            testScheduler.runCurrent()

            repository.consentFailuresRemaining = 1
            gateway.permission = LocationPermission.Denied(false)
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            pendingLocation.complete(ApproximateLocation(35.18, 129.08))
            testScheduler.runCurrent()

            assertEquals(1, repository.events.count { it == "consent:true" })
            assertEquals(2, repository.events.count { it == "consent:false" })
            assertEquals(0, repository.events.count { it == "refresh:35.18,129.08" })
        }

    @Test
    fun `same uid account emission during location fetch does not cancel action`() = runTest {
        val repository = FakeWeatherRepository(delayedAccounts = true)
        val pendingLocation = CompletableDeferred<ApproximateLocation?>()
        val gateway =
            LifecycleFakeLocationGateway(
                permission = LocationPermission.GrantedApproximate,
                locationResult = pendingLocation,
            )
        val controller =
            WeatherController(
                repository,
                gateway,
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
            )
        testScheduler.runCurrent()
        repository.emitAccount("account-a")
        testScheduler.runCurrent()
        controller.useCurrentLocation()
        testScheduler.runCurrent()

        repository.emitAccount("account-a")
        testScheduler.runCurrent()
        pendingLocation.complete(ApproximateLocation(37.56, 126.98))
        testScheduler.runCurrent()

        assertEquals(1, repository.events.count { it == "consent:true" })
        assertEquals(1, repository.events.count { it == "refresh:37.56,126.98" })
    }

    @Test
    fun `account switch during recreation cancels old request and new owner uses recreated gateway`() =
        runTest {
            val repository = FakeWeatherRepository()
            val oldLocation = CompletableDeferred<ApproximateLocation?>()
            val oldGateway =
                LifecycleFakeLocationGateway(
                    permission = LocationPermission.GrantedApproximate,
                    locationResult = oldLocation,
                )
            val newGateway =
                LifecycleFakeLocationGateway(
                    permission = LocationPermission.GrantedApproximate,
                    location = ApproximateLocation(35.18, 129.08),
                )
            val controller =
                WeatherController(
                    repository,
                    oldGateway,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                )
            testScheduler.runCurrent()
            controller.useCurrentLocation()
            testScheduler.runCurrent()

            repository.accounts.value = "account-b"
            controller.updateLocationGateway(newGateway)
            testScheduler.runCurrent()
            oldLocation.complete(ApproximateLocation(33.45, 126.57))
            testScheduler.runCurrent()
            controller.useCurrentLocation()
            testScheduler.runCurrent()

            assertFalse(repository.events.any { it == "refresh:33.45,126.57" })
            assertEquals(1, repository.events.count { it == "refresh:35.18,129.08" })
            assertEquals(
                "account-b",
                assertIs<WeatherUiState.Ready>(controller.state.value).accountId,
            )
        }

    @Test
    fun `process recreation creates a fresh gateway boundary while preserving durable capability`() =
        runTest {
            val repository = FakeWeatherRepository()
            val capabilities = FakePermissionCapabilityStore()
            val oldGateway = LifecycleFakeLocationGateway(LocationPermission.Denied(true))
            var controller =
                WeatherController(
                    repository,
                    oldGateway,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()

            val recreatedGateway =
                LifecycleFakeLocationGateway(
                    permission = LocationPermission.GrantedApproximate,
                    location = ApproximateLocation(37.56, 126.98),
                )
            controller =
                WeatherController(
                    repository,
                    recreatedGateway,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()
            controller.useCurrentLocation()
            testScheduler.runCurrent()

            assertEquals(1, recreatedGateway.locationRequests)
            assertEquals(0, oldGateway.locationRequests)
            assertEquals(false, capabilities.read("account-a")?.revocationPending)
        }

    @Test
    fun `no rotation uses one gateway once without duplicate callbacks`() = runTest {
        val repository = FakeWeatherRepository()
        val gateway =
            LifecycleFakeLocationGateway(
                permission = LocationPermission.GrantedApproximate,
                location = ApproximateLocation(37.56, 126.98),
            )
        val controller =
            WeatherController(
                repository,
                gateway,
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
            )
        testScheduler.runCurrent()

        controller.useCurrentLocation()
        testScheduler.runCurrent()

        assertEquals(1, gateway.locationRequests)
        assertEquals(1, repository.events.count { it == "consent:true" })
        assertEquals(1, repository.events.count { it.startsWith("refresh:") })
    }

    @Test
    fun `permission denial and permanent denial preserve manual region alternative`() = runTest {
        val repository = FakeWeatherRepository()
        val location = FakeLocationGateway(LocationPermission.Denied(canAskAgain = true))
        val controller =
            WeatherController(
                repository,
                location,
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
            )
        testScheduler.runCurrent()

        controller.useCurrentLocation()
        testScheduler.runCurrent()
        assertEquals(
            LocationPermission.Denied(canAskAgain = true),
            assertIs<WeatherUiState.Ready>(controller.state.value).locationPermission,
        )
        assertTrue(assertIs<WeatherUiState.Ready>(controller.state.value).canChooseManualRegion)

        location.permission = LocationPermission.Denied(canAskAgain = false)
        controller.useCurrentLocation()
        testScheduler.runCurrent()
        assertFalse(
            assertIs<WeatherUiState.Ready>(controller.state.value).locationPermission.canAskAgain
        )
        assertEquals(0, location.locationRequests)
    }

    @Test
    fun `explicit consent is recorded before approximate location acquisition`() = runTest {
        val repository = FakeWeatherRepository()
        val location = FakeLocationGateway(LocationPermission.GrantedApproximate)
        val controller =
            WeatherController(
                repository,
                location,
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
            )
        testScheduler.runCurrent()

        controller.useCurrentLocation()
        testScheduler.runCurrent()
        assertEquals(listOf("consent:true", "refresh:37.56,126.98"), repository.events)
        assertEquals(1, location.locationRequests)
    }

    @Test
    fun `app consent presentation remains owner scoped across account switches`() = runTest {
        val repository = FakeWeatherRepository()
        val capabilities =
            FakePermissionCapabilityStore().apply {
                write(
                    "account-a",
                    WeatherPermissionCapabilityState(
                        desiredGranted = false,
                        acknowledgedGranted = false,
                        revocationPending = false,
                        commandGeneration = 2,
                        osPermissionGranted = true,
                    ),
                )
                write(
                    "account-b",
                    WeatherPermissionCapabilityState(
                        desiredGranted = true,
                        acknowledgedGranted = true,
                        revocationPending = false,
                        commandGeneration = 3,
                        osPermissionGranted = true,
                    ),
                )
            }
        val controller =
            WeatherController(
                repository,
                FakeLocationGateway(LocationPermission.GrantedApproximate),
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
                capabilities,
            )
        testScheduler.runCurrent()
        assertEquals(
            false,
            assertIs<WeatherUiState.Ready>(controller.state.value).appLocationConsentGranted,
        )

        repository.accounts.value = "account-b"
        testScheduler.runCurrent()
        assertEquals(
            true,
            assertIs<WeatherUiState.Ready>(controller.state.value).appLocationConsentGranted,
        )

        repository.accounts.value = "account-a"
        testScheduler.runCurrent()
        assertEquals(
            false,
            assertIs<WeatherUiState.Ready>(controller.state.value).appLocationConsentGranted,
        )
    }

    @Test
    fun `automatic os denial adopts concurrent generation two grant and submits generation three revoke`() =
        runTest {
            val repository = FakeWeatherRepository().apply { consentConflict = 2L to true }
            val capabilities =
                FakePermissionCapabilityStore().apply {
                    write(
                        "account-a",
                        WeatherPermissionCapabilityState(true, true, false, 1, true),
                    )
                }
            val location = FakeLocationGateway(LocationPermission.GrantedApproximate)
            val controller =
                WeatherController(
                    repository,
                    location,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()

            location.permission = LocationPermission.Denied(false)
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()

            assertEquals(
                listOf(2L to false, 3L to false),
                repository.consentCommands,
            )
            assertEquals(3L to false, repository.serverConsent("account-a"))
            assertEquals(
                WeatherPermissionCapabilityState(false, false, false, 3, false),
                capabilities.read("account-a"),
            )
            assertEquals(
                false,
                assertIs<WeatherUiState.Ready>(controller.state.value).appLocationConsentGranted,
            )
        }

    @Test
    fun `automatic denial converges through multiple consecutive consent conflicts`() = runTest {
        val repository =
            FakeWeatherRepository().apply {
                consentConflicts.addAll(listOf(2L to true, 3L to true, 4L to true))
            }
        val capabilities =
            FakePermissionCapabilityStore().apply {
                write(
                    "account-a",
                    WeatherPermissionCapabilityState(true, true, false, 1, true),
                )
            }
        val location = FakeLocationGateway(LocationPermission.GrantedApproximate)
        val controller =
            WeatherController(
                repository,
                location,
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
                capabilities,
            )
        testScheduler.runCurrent()

        location.permission = LocationPermission.Denied(false)
        controller.reconcileLocationPermission()
        testScheduler.runCurrent()

        assertEquals(
            listOf(2L to false, 3L to false, 4L to false, 5L to false),
            repository.consentCommands,
        )
        assertEquals(5L to false, repository.serverConsent("account-a"))
        assertEquals(false, capabilities.read("account-a")?.revocationPending)
    }

    @Test
    fun `automatic conflict loop is bounded and remains retryable`() = runTest {
        val repository =
            FakeWeatherRepository().apply {
                consentConflicts.addAll((2L..11L).map { it to true })
            }
        val capabilities =
            FakePermissionCapabilityStore().apply {
                write(
                    "account-a",
                    WeatherPermissionCapabilityState(true, true, false, 1, true),
                )
            }
        val location = FakeLocationGateway(LocationPermission.GrantedApproximate)
        val controller =
            WeatherController(
                repository,
                location,
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
                capabilities,
            )
        testScheduler.runCurrent()

        location.permission = LocationPermission.Denied(false)
        controller.reconcileLocationPermission()
        testScheduler.runCurrent()

        assertEquals(8, repository.consentCommands.size)
        assertEquals(10L, capabilities.read("account-a")?.commandGeneration)
        assertEquals(true, capabilities.read("account-a")?.revocationPending)
        assertEquals(
            WeatherFailure.ConsentConflict,
            assertIs<WeatherUiState.Ready>(controller.state.value).failure,
        )

        repository.consentConflicts.clear()
        controller.reconcileLocationPermission()
        testScheduler.runCurrent()
        assertEquals(10L to false, repository.serverConsent("account-a"))
        assertEquals(false, capabilities.read("account-a")?.revocationPending)
        assertEquals(null, assertIs<WeatherUiState.Ready>(controller.state.value).failure)
    }

    @Test
    fun `authoritative conflict already matching denial converges without another command`() =
        runTest {
            val repository = FakeWeatherRepository().apply { consentConflict = 2L to false }
            val capabilities =
                FakePermissionCapabilityStore().apply {
                    write(
                        "account-a",
                        WeatherPermissionCapabilityState(false, true, false, 1, true),
                    )
                }
            val location = FakeLocationGateway(LocationPermission.GrantedApproximate)
            val controller =
                WeatherController(
                    repository,
                    location,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()

            location.permission = LocationPermission.Denied(false)
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()

            assertEquals(listOf(2L to false), repository.consentCommands)
            assertEquals(
                WeatherPermissionCapabilityState(false, false, false, 2, false),
                capabilities.read("account-a"),
            )
        }

    @Test
    fun `grant intent survives authoritative denial conflict and retries exact next`() = runTest {
        val repository = FakeWeatherRepository().apply { consentConflict = 2L to false }
        val capabilities =
            FakePermissionCapabilityStore().apply {
                write(
                    "account-a",
                    WeatherPermissionCapabilityState(false, false, false, 1, true),
                )
            }
        val controller =
            WeatherController(
                repository,
                FakeLocationGateway(LocationPermission.GrantedApproximate),
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
                capabilities,
            )
        testScheduler.runCurrent()

        controller.useCurrentLocation()
        testScheduler.runCurrent()

        assertEquals(listOf(2L to true, 3L to true), repository.consentCommands)
        assertEquals(3L to true, repository.serverConsent("account-a"))
        assertEquals(true, capabilities.read("account-a")?.desiredGranted)
        assertEquals(false, capabilities.read("account-a")?.revocationPending)
    }

    @Test
    fun `automatic conflict then response loss replays generation after recreation`() = runTest {
        val repository =
            FakeWeatherRepository().apply {
                consentConflict = 2L to true
                consentResponseLossesRemaining = 1
            }
        val capabilities =
            FakePermissionCapabilityStore().apply {
                write(
                    "account-a",
                    WeatherPermissionCapabilityState(true, true, false, 1, true),
                )
            }
        val location = FakeLocationGateway(LocationPermission.GrantedApproximate)
        var controller =
            WeatherController(
                repository,
                location,
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
                capabilities,
            )
        testScheduler.runCurrent()

        location.permission = LocationPermission.Denied(false)
        controller.reconcileLocationPermission()
        testScheduler.runCurrent()
        assertEquals(3L to false, repository.serverConsent("account-a"))
        assertEquals(true, capabilities.read("account-a")?.revocationPending)
        assertEquals(3L, capabilities.read("account-a")?.commandGeneration)

        controller =
            WeatherController(
                repository,
                location,
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
                capabilities,
            )
        testScheduler.runCurrent()
        controller.reconcileLocationPermission()
        testScheduler.runCurrent()

        assertEquals(
            listOf(2L to false, 3L to false, 3L to false),
            repository.consentCommands,
        )
        assertEquals(false, capabilities.read("account-a")?.revocationPending)
        assertEquals(false, capabilities.read("account-a")?.desiredGranted)
    }

    @Test
    fun `uid switch cancels stale automatic conflict without mutating the new owner`() = runTest {
        val repository =
            FakeWeatherRepository().apply {
                consentConflict = 2L to true
                pauseConsentConflict = true
            }
        val capabilities =
            FakePermissionCapabilityStore().apply {
                write(
                    "account-a",
                    WeatherPermissionCapabilityState(true, true, false, 1, true),
                )
            }
        val location = FakeLocationGateway(LocationPermission.GrantedApproximate)
        val controller =
            WeatherController(
                repository,
                location,
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
                capabilities,
            )
        testScheduler.runCurrent()

        location.permission = LocationPermission.Denied(false)
        controller.reconcileLocationPermission()
        testScheduler.runCurrent()
        assertTrue(repository.consentConflictStarted.isCompleted)

        repository.accounts.value = "account-b"
        testScheduler.runCurrent()
        repository.releaseConsentConflict.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(
            "account-b",
            assertIs<WeatherUiState.Ready>(controller.state.value).accountId,
        )
        assertEquals(
            WeatherPermissionCapabilityState(false, false, false, 1, false),
            capabilities.read("account-b"),
        )
        assertEquals(
            WeatherPermissionCapabilityState(false, true, true, 2, false),
            capabilities.read("account-a"),
        )
        assertEquals(listOf("account-a:false", "account-b:false"), repository.consentEvents)
    }

    @Test
    fun `legacy exhausted granted consent revoke adopts recovered server generation`() = runTest {
        val repository = FakeWeatherRepository().apply { consentRecoveriesRemaining = 1 }
        val capabilities =
            FakePermissionCapabilityStore().apply {
                write(
                    "account-a",
                    WeatherPermissionCapabilityState(
                        desiredGranted = true,
                        acknowledgedGranted = true,
                        revocationPending = false,
                        commandGeneration = 9_007_199_254_740_991L,
                        osPermissionGranted = true,
                    ),
                )
            }
        val controller =
            WeatherController(
                repository,
                FakeLocationGateway(LocationPermission.GrantedApproximate),
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
                capabilities,
            )
        testScheduler.runCurrent()

        controller.revokeLocationConsent()
        testScheduler.runCurrent()

        assertEquals(
            WeatherPermissionCapabilityState(false, false, false, 1, true),
            capabilities.read("account-a"),
        )
        assertEquals(
            listOf(9_007_199_254_740_992L to false),
            repository.consentCommands,
        )
    }

    @Test
    fun `legacy exhausted denied consent recovers then explicit grant adopts exact next generation`() =
        runTest {
            val repository = FakeWeatherRepository().apply { consentRecoveriesRemaining = 1 }
            val capabilities =
                FakePermissionCapabilityStore().apply {
                    write(
                        "account-a",
                        WeatherPermissionCapabilityState(
                            desiredGranted = false,
                            acknowledgedGranted = false,
                            revocationPending = false,
                            commandGeneration = 9_007_199_254_740_991L,
                            osPermissionGranted = true,
                        ),
                    )
                }
            val controller =
                WeatherController(
                    repository,
                    FakeLocationGateway(
                        LocationPermission.GrantedApproximate,
                        result = ApproximateLocation(37.56, 126.98),
                    ),
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()

            controller.useCurrentLocation()
            testScheduler.runCurrent()

            assertEquals(
                WeatherPermissionCapabilityState(true, true, false, 2, true),
                capabilities.read("account-a"),
            )
            assertEquals(
                listOf(
                    9_007_199_254_740_992L to true,
                    2L to true,
                ),
                repository.consentCommands,
            )
            assertEquals(2L to true, repository.serverConsent("account-a"))
        }

    @Test
    fun `explicit current location switches Busan manual to Seoul device and manual can win again`() =
        runTest {
            val seoul = dashboard(Instant.parse("2026-08-12T02:00:00Z"), stale = false)
            val busan =
                seoul.copy(
                    snapshot = seoul.snapshot.copy(regionCode = "kr-busan", regionName = "부산")
                )
            val repository =
                FakeWeatherRepository(
                    initial = WeatherLoad.Fresh(busan),
                    refreshResult = WeatherLoad.Fresh(seoul),
                )
            val controller =
                WeatherController(
                    repository,
                    FakeLocationGateway(
                        LocationPermission.GrantedApproximate,
                        result = ApproximateLocation(37.56, 126.98),
                    ),
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                )
            testScheduler.runCurrent()

            controller.useCurrentLocation()
            testScheduler.runCurrent()

            assertEquals(
                listOf(ApproximateLocation(37.56, 126.98)),
                repository.deviceSwitchLocations,
            )
            assertEquals(
                "서울",
                assertIs<WeatherUiState.Ready>(controller.state.value)
                    .dashboard
                    ?.snapshot
                    ?.regionName,
            )

            controller.chooseManualRegion(WeatherRegion("kr-busan", "부산", 35.18, 129.08))
            testScheduler.runCurrent()

            assertEquals(
                "부산",
                assertIs<WeatherUiState.Ready>(controller.state.value)
                    .dashboard
                    ?.snapshot
                    ?.regionName,
            )
        }

    @Test
    fun `device switch response loss reloads committed source after restart without consent generation bump`() =
        runTest {
            val seoul = dashboard(Instant.parse("2026-08-12T02:00:00Z"), stale = false)
            val busan =
                seoul.copy(
                    snapshot = seoul.snapshot.copy(regionCode = "kr-busan", regionName = "부산")
                )
            val repository =
                FakeWeatherRepository(
                        initial = WeatherLoad.Fresh(busan),
                        refreshResult = WeatherLoad.Fresh(seoul),
                    )
                    .apply { deviceSwitchResponseLossesRemaining = 1 }
            val capabilities = FakePermissionCapabilityStore()
            val location =
                FakeLocationGateway(
                    LocationPermission.GrantedApproximate,
                    result = ApproximateLocation(37.56, 126.98),
                )
            var controller =
                WeatherController(
                    repository,
                    location,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()
            controller.useCurrentLocation()
            testScheduler.runCurrent()
            assertEquals(
                "부산",
                assertIs<WeatherUiState.Ready>(controller.state.value)
                    .dashboard
                    ?.snapshot
                    ?.regionName,
            )

            controller =
                WeatherController(
                    repository,
                    location,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()

            assertEquals(
                "서울",
                assertIs<WeatherUiState.Ready>(controller.state.value)
                    .dashboard
                    ?.snapshot
                    ?.regionName,
            )
            assertEquals(1L, capabilities.read("account-a")?.commandGeneration)
            assertEquals(1, repository.events.count { it == "consent:true" })
        }

    @Test
    fun `explicit revoke remains false across granted resumes until explicit current location reenable`() =
        runTest {
            val repository = FakeWeatherRepository()
            val capabilities = FakePermissionCapabilityStore()
            val location =
                FakeLocationGateway(
                    permission = LocationPermission.GrantedApproximate,
                    result = ApproximateLocation(37.56, 126.98),
                )
            val controller =
                WeatherController(
                    repository,
                    location,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()

            controller.useCurrentLocation()
            testScheduler.runCurrent()
            controller.revokeLocationConsent()
            testScheduler.runCurrent()
            repeat(3) {
                controller.reconcileLocationPermission()
                testScheduler.runCurrent()
            }

            assertEquals(2L to false, repository.serverConsent("account-a"))
            assertEquals(
                WeatherPermissionCapabilityState(false, false, false, 2, true),
                capabilities.read("account-a"),
            )
            assertEquals(1, repository.events.count { it == "consent:true" })
            assertEquals(1, repository.events.count { it == "consent:false" })

            controller.useCurrentLocation()
            testScheduler.runCurrent()

            assertEquals(3L to true, repository.serverConsent("account-a"))
            assertEquals(
                WeatherPermissionCapabilityState(true, true, false, 3, true),
                capabilities.read("account-a"),
            )
            assertEquals(2, repository.events.count { it == "consent:true" })
        }

    @Test
    fun `explicit revoke survives controller restart and os denial regrant without granting`() =
        runTest {
            val repository = FakeWeatherRepository()
            val capabilities = FakePermissionCapabilityStore()
            val location =
                FakeLocationGateway(
                    permission = LocationPermission.GrantedApproximate,
                    result = ApproximateLocation(35.18, 129.08),
                )
            var controller =
                WeatherController(
                    repository,
                    location,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()
            controller.useCurrentLocation()
            testScheduler.runCurrent()
            controller.revokeLocationConsent()
            testScheduler.runCurrent()

            controller =
                WeatherController(
                    repository,
                    location,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            location.permission = LocationPermission.Denied(false)
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            location.permission = LocationPermission.GrantedApproximate
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()

            assertEquals(2L to false, repository.serverConsent("account-a"))
            assertEquals(
                WeatherPermissionCapabilityState(false, false, false, 2, true),
                capabilities.read("account-a"),
            )
            assertEquals(1, repository.events.count { it == "consent:true" })
            assertEquals(1, repository.events.count { it == "consent:false" })
        }

    @Test
    fun `os regrant after denial does not renew app consent across resume and recreation`() =
        runTest {
            val saved = SavedStateHandle()
            val repository =
                FakeWeatherRepository(
                    initial =
                        WeatherLoad.Fresh(dashboard(Instant.parse("2026-08-12T02:00:00Z"), false))
                )
            val location = FakeLocationGateway(LocationPermission.GrantedApproximate)
            var controller =
                WeatherController(
                    repository,
                    location,
                    saved,
                    clock,
                    StandardTestDispatcher(testScheduler),
                )
            testScheduler.runCurrent()

            controller.reconcileLocationPermission()
            location.permission = LocationPermission.Denied(true)
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            assertEquals(listOf("consent:false"), repository.events)

            location.permission = LocationPermission.GrantedApproximate
            controller.reconcileLocationPermission()
            location.permission = LocationPermission.Denied(false)
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            assertEquals(listOf("consent:false"), repository.events)

            controller =
                WeatherController(
                    repository,
                    location,
                    saved,
                    clock,
                    StandardTestDispatcher(testScheduler),
                )
            testScheduler.runCurrent()
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            assertEquals(listOf("consent:false"), repository.events)
            assertEquals(
                "서울",
                assertIs<WeatherUiState.Ready>(controller.state.value)
                    .dashboard
                    ?.snapshot
                    ?.regionName,
            )
        }

    @Test
    fun `failed permission revoke remains pending and retries on later resume exactly once`() =
        runTest {
            val repository = FakeWeatherRepository().apply { consentFailuresRemaining = 1 }
            val capabilities = FakePermissionCapabilityStore()
            val controller =
                WeatherController(
                    repository,
                    FakeLocationGateway(LocationPermission.Denied(true)),
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()

            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            assertEquals(
                WeatherPermissionCapabilityState(
                    desiredGranted = false,
                    acknowledgedGranted = null,
                    revocationPending = true,
                    commandGeneration = 1,
                    osPermissionGranted = false,
                ),
                capabilities.read("account-a"),
            )

            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()

            assertEquals(listOf("consent:false", "consent:false"), repository.events)
            assertEquals(
                WeatherPermissionCapabilityState(
                    desiredGranted = false,
                    acknowledgedGranted = false,
                    revocationPending = false,
                    commandGeneration = 1,
                    osPermissionGranted = false,
                ),
                capabilities.read("account-a"),
            )
        }

    @Test
    fun `failed permission revoke survives restart and retries for only the original account`() =
        runTest {
            val repository = FakeWeatherRepository().apply { consentFailuresRemaining = 1 }
            val capabilities = FakePermissionCapabilityStore()
            val location = FakeLocationGateway(LocationPermission.Denied(false))
            var controller =
                WeatherController(
                    repository,
                    location,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()

            repository.accounts.value = "account-b"
            testScheduler.runCurrent()
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            assertEquals(true, capabilities.read("account-a")?.revocationPending)
            assertEquals(false, capabilities.read("account-b")?.revocationPending)

            repository.accounts.value = "account-a"
            controller =
                WeatherController(
                    repository,
                    location,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()

            assertEquals(false, capabilities.read("account-a")?.revocationPending)
            assertEquals(false, capabilities.read("account-a")?.acknowledgedGranted)
            assertEquals(3, repository.events.count { it == "consent:false" })
        }

    @Test
    fun `denied capability is durable across route recreation with a new saved state handle`() =
        runTest {
            val repository =
                FakeWeatherRepository(
                    initial =
                        WeatherLoad.Fresh(dashboard(Instant.parse("2026-08-12T02:00:00Z"), false))
                )
            val location = FakeLocationGateway(LocationPermission.Denied(true))
            val capabilities = FakePermissionCapabilityStore()
            var controller =
                WeatherController(
                    repository,
                    location,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()

            controller =
                WeatherController(
                    repository,
                    location,
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                    capabilities,
                )
            testScheduler.runCurrent()
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()

            assertEquals(listOf("consent:false"), repository.events)
        }

    @Test
    fun `permission lifecycle revokes server consent once and reloads authoritative manual region`() =
        runTest {
            val manual = dashboard(Instant.parse("2026-08-12T02:00:00Z"), stale = false)
            val repository = FakeWeatherRepository(initial = WeatherLoad.Fresh(manual))
            val controller =
                WeatherController(
                    repository,
                    FakeLocationGateway(LocationPermission.Denied(canAskAgain = true)),
                    SavedStateHandle(),
                    clock,
                    StandardTestDispatcher(testScheduler),
                )
            testScheduler.runCurrent()

            controller.reconcileLocationPermission()
            testScheduler.runCurrent()
            controller.reconcileLocationPermission()
            testScheduler.runCurrent()

            assertEquals(listOf("consent:false"), repository.events)
            assertEquals(
                "서울",
                assertIs<WeatherUiState.Ready>(controller.state.value)
                    .dashboard
                    ?.snapshot
                    ?.regionName,
            )
        }

    @Test
    fun `revision conflict reloads authoritative settings and remains typed`() = runTest {
        val authoritative = dashboard(Instant.parse("2026-08-12T02:00:00Z"), stale = false)
        val repository = FakeWeatherRepository(initial = WeatherLoad.Fresh(authoritative))
        repository.saveFailure = WeatherRevisionConflictException()
        val controller =
            WeatherController(
                repository,
                FakeLocationGateway(LocationPermission.GrantedApproximate),
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
            )
        testScheduler.runCurrent()

        controller.saveAlerts(false, mapOf("plant-a" to false))
        testScheduler.runCurrent()

        val ready = assertIs<WeatherUiState.Ready>(controller.state.value)
        assertEquals(WeatherFailure.RevisionConflict, ready.failure)
        assertEquals(authoritative.revision, ready.dashboard?.revision)
    }

    @Test
    fun `provider failure crossing freshness boundary returns stale dashboard with error`() =
        runTest {
            val mutableClock = MutableClock(Instant.parse("2026-08-12T02:59:00Z"))
            val retained =
                dashboard(observedAt = Instant.parse("2026-08-12T00:00:00Z"), stale = false)
            val repository = FakeWeatherRepository(initial = WeatherLoad.Fresh(retained))
            repository.refreshFailure = IllegalStateException("deterministic provider failure")
            val controller =
                WeatherController(
                    repository,
                    FakeLocationGateway(LocationPermission.GrantedApproximate),
                    SavedStateHandle(),
                    mutableClock,
                    StandardTestDispatcher(testScheduler),
                )
            testScheduler.runCurrent()
            assertFalse(assertIs<WeatherUiState.Ready>(controller.state.value).stale)

            mutableClock.instant = Instant.parse("2026-08-12T03:01:00Z")
            controller.refresh()
            testScheduler.runCurrent()

            val ready = assertIs<WeatherUiState.Ready>(controller.state.value)
            assertTrue(ready.stale)
            assertEquals(retained.snapshot.observedAt, ready.dashboard?.snapshot?.observedAt)
            assertEquals(retained.risks, ready.dashboard?.risks)
            assertEquals(WeatherFailure.ProviderUnavailable, ready.failure)
        }

    @Test
    fun `location unavailable and provider failure retain timestamped stale snapshot`() = runTest {
        val stale = dashboard(observedAt = Instant.parse("2026-08-11T23:59:59Z"), stale = true)
        val repository =
            FakeWeatherRepository(
                initial = WeatherLoad.Stale(stale),
                refreshResult = WeatherLoad.Stale(stale),
            )
        val location = FakeLocationGateway(LocationPermission.GrantedApproximate, result = null)
        val controller =
            WeatherController(
                repository,
                location,
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
            )
        testScheduler.runCurrent()

        assertEquals(stale, assertIs<WeatherUiState.Ready>(controller.state.value).dashboard)
        controller.useCurrentLocation()
        testScheduler.runCurrent()
        val ready = assertIs<WeatherUiState.Ready>(controller.state.value)
        assertEquals(stale.snapshot.observedAt, ready.dashboard?.snapshot?.observedAt)
        assertTrue(ready.stale)
        assertEquals(WeatherFailure.LocationUnavailable, ready.failure)
    }

    @Test
    fun `account switch cancels old work and suppresses stale callback`() = runTest {
        val repository = FakeWeatherRepository()
        val pending = CompletableDeferred<ApproximateLocation?>()
        val location =
            FakeLocationGateway(LocationPermission.GrantedApproximate, deferred = pending)
        val controller =
            WeatherController(
                repository,
                location,
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
            )
        testScheduler.runCurrent()

        controller.useCurrentLocation()
        testScheduler.runCurrent()
        repository.accounts.value = "account-b"
        testScheduler.runCurrent()
        pending.complete(ApproximateLocation(35.18, 129.08))
        testScheduler.runCurrent()

        assertFalse(repository.events.any { it == "refresh:35.18,129.08" })
        assertEquals("account-b", assertIs<WeatherUiState.Ready>(controller.state.value).accountId)
    }

    @Test
    fun `recreation reloads unavailable criteria without presenting the plant as safe`() = runTest {
        val retained =
            dashboard(Instant.parse("2026-08-12T02:00:00Z"), stale = false)
                .copy(
                    risks = emptyList(),
                    unavailablePlants = listOf("plant-a"),
                )
        val repository = FakeWeatherRepository(initial = WeatherLoad.Fresh(retained))
        var controller =
            WeatherController(
                repository,
                FakeLocationGateway(LocationPermission.Denied(true)),
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
            )
        testScheduler.runCurrent()
        assertEquals(
            listOf("plant-a"),
            assertIs<WeatherUiState.Ready>(controller.state.value).dashboard?.unavailablePlants,
        )

        controller =
            WeatherController(
                repository,
                FakeLocationGateway(LocationPermission.Denied(true)),
                SavedStateHandle(),
                clock,
                StandardTestDispatcher(testScheduler),
            )
        testScheduler.runCurrent()

        val recreated = assertIs<WeatherUiState.Ready>(controller.state.value).dashboard
        assertEquals(listOf("plant-a"), recreated?.unavailablePlants)
        assertTrue(recreated?.risks.orEmpty().isEmpty())
    }

    @Test
    fun `recreation restores exact search draft and refresh generation`() = runTest {
        val saved = SavedStateHandle()
        val repository = FakeWeatherRepository()
        var controller =
            WeatherController(
                repository,
                FakeLocationGateway(LocationPermission.Denied(true)),
                saved,
                clock,
                StandardTestDispatcher(testScheduler),
            )
        testScheduler.runCurrent()
        controller.changeSearchQuery("서울 성동구")
        controller =
            WeatherController(
                repository,
                FakeLocationGateway(LocationPermission.Denied(true)),
                saved,
                clock,
                StandardTestDispatcher(testScheduler),
            )
        testScheduler.runCurrent()

        val ready = assertIs<WeatherUiState.Ready>(controller.state.value)
        assertEquals("서울 성동구", ready.searchQuery)
    }

    @Test
    fun `three hour boundary and account timezone day rollover are deterministic`() {
        val snapshot =
            WeatherSnapshot(
                "region",
                "서울",
                27.0,
                55,
                0.0,
                Instant.parse("2026-08-12T00:00:00Z"),
                "Asia/Seoul",
            )
        assertFalse(snapshot.isStaleAt(Instant.parse("2026-08-12T03:00:00Z")))
        assertTrue(snapshot.isStaleAt(Instant.parse("2026-08-12T03:00:00.001Z")))
        assertEquals(
            "2026-08-13",
            localWeatherDay(Instant.parse("2026-08-12T15:00:00Z"), "Asia/Seoul").toString(),
        )
    }

    private fun dashboard(observedAt: Instant, stale: Boolean) =
        WeatherDashboard(
            snapshot = WeatherSnapshot("region", "서울", 35.0, 30, 0.0, observedAt, "Asia/Seoul"),
            risks =
                listOf(
                    WeatherRisk(
                        "risk-a",
                        "plant-a",
                        "몬스테라",
                        WeatherRiskType.HIGH_TEMPERATURE,
                        "직사광선을 피해 옮겨 주세요.",
                        observedAt,
                        true,
                    )
                ),
            unavailablePlants = emptyList(),
            stale = stale,
            globalAlertsEnabled = true,
            plantAlerts = mapOf("plant-a" to true),
            revision = 1,
        )

    private class MutableClock(var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = instant
    }

    private class FakePermissionCapabilityStore : WeatherPermissionCapabilityStore {
        private val values = mutableMapOf<String, WeatherPermissionCapabilityState>()

        override fun read(accountId: String): WeatherPermissionCapabilityState? = values[accountId]

        override fun write(accountId: String, state: WeatherPermissionCapabilityState) {
            values[accountId] = state
        }
    }

    private class FakeLocationGateway(
        var permission: LocationPermission,
        private val result: ApproximateLocation? = ApproximateLocation(37.56, 126.98),
        private val deferred: CompletableDeferred<ApproximateLocation?>? = null,
    ) : WeatherLocationGateway {
        var locationRequests = 0

        override fun permission(): LocationPermission = permission

        override suspend fun requestPermission(): LocationPermission = permission

        override suspend fun approximateLocation(): ApproximateLocation? {
            locationRequests += 1
            return deferred?.await() ?: result
        }

        override fun cancel() = Unit
    }

    private class LifecycleFakeLocationGateway(
        var permission: LocationPermission,
        private val permissionResult: CompletableDeferred<LocationPermission>? = null,
        private val locationResult: CompletableDeferred<ApproximateLocation?>? = null,
        private val location: ApproximateLocation? = null,
    ) : WeatherLocationGateway {
        var permissionRequests = 0
        var locationRequests = 0
        var cancelCount = 0

        override fun permission(): LocationPermission = permission

        override suspend fun requestPermission(): LocationPermission {
            permissionRequests += 1
            return (permissionResult?.await() ?: permission).also { permission = it }
        }

        override suspend fun approximateLocation(): ApproximateLocation? {
            locationRequests += 1
            return locationResult?.await() ?: location
        }

        override fun cancel() {
            cancelCount += 1
        }
    }

    private class FakeWeatherRepository(
        initial: WeatherLoad = WeatherLoad.NotConfigured,
        private val refreshResult: WeatherLoad =
            WeatherLoad.Fresh(
                WeatherDashboard(
                    WeatherSnapshot(
                        "region",
                        "서울",
                        28.0,
                        50,
                        0.0,
                        Instant.parse("2026-08-12T02:00:00Z"),
                        "Asia/Seoul",
                    ),
                    emptyList(),
                    emptyList(),
                    false,
                    true,
                    emptyMap(),
                    1,
                )
            ),
        private val delayedAccounts: Boolean = false,
        private val loadDeferred: CompletableDeferred<WeatherLoad>? = null,
    ) : WeatherRepository {
        val accounts = MutableStateFlow<String?>("account-a")
        private val delayedAccountEvents = MutableSharedFlow<String?>(extraBufferCapacity = 1)
        val events = mutableListOf<String>()
        val consentEvents = mutableListOf<String>()
        var current = initial
        var saveFailure: Exception? = null
        var refreshFailure: Exception? = null
        var deviceSwitchResponseLossesRemaining = 0
        val deviceSwitchLocations = mutableListOf<ApproximateLocation>()
        var consentFailuresRemaining = 0
        var consentResponseLossesRemaining = 0
        var consentRecoveriesRemaining = 0
        var consentConflict: Pair<Long, Boolean>? = null
        val consentConflicts = ArrayDeque<Pair<Long, Boolean>>()
        var pauseConsentConflict = false
        val consentConflictStarted = CompletableDeferred<Unit>()
        val releaseConsentConflict = CompletableDeferred<Unit>()
        val consentCommands = mutableListOf<Pair<Long, Boolean>>()
        var blockedConsentValue: Boolean? = null
        val consentCallStarted = CompletableDeferred<Unit>()
        val releaseConsentCall = CompletableDeferred<Unit>()

        override fun accounts() = if (delayedAccounts) delayedAccountEvents else accounts

        suspend fun emitAccount(accountId: String?) {
            delayedAccountEvents.emit(accountId)
        }

        override suspend fun load(accountId: String): WeatherLoad = loadDeferred?.await() ?: current

        private val consentGenerations = mutableMapOf<String, Long>()
        private val consentValues = mutableMapOf<String, Boolean>()

        override suspend fun recordLocationConsent(
            accountId: String,
            granted: Boolean,
            commandGeneration: Long,
        ): WeatherConsentMutationResult {
            events += "consent:$granted"
            consentEvents += "$accountId:$granted"
            consentCommands += commandGeneration to granted
            val nextConflict =
                consentConflict?.also { consentConflict = null }
                    ?: consentConflicts.removeFirstOrNull()
            nextConflict?.let { (generation, authoritativeGranted) ->
                consentGenerations[accountId] = generation
                consentValues[accountId] = authoritativeGranted
                if (pauseConsentConflict) {
                    pauseConsentConflict = false
                    consentConflictStarted.complete(Unit)
                    releaseConsentConflict.await()
                }
                throw WeatherConsentConflictException(generation, authoritativeGranted)
            }
            if (consentRecoveriesRemaining > 0) {
                consentRecoveriesRemaining -= 1
                consentGenerations[accountId] = 1
                consentValues[accountId] = false
                return WeatherConsentMutationResult(
                    authoritativeGeneration = 1,
                    authoritativeGranted = false,
                    recovered = true,
                )
            }
            if (blockedConsentValue == granted) {
                blockedConsentValue = null
                consentCallStarted.complete(Unit)
                releaseConsentCall.await()
            }
            if (consentFailuresRemaining > 0) {
                consentFailuresRemaining -= 1
                throw IllegalStateException("deterministic consent failure")
            }
            val currentGeneration = consentGenerations[accountId] ?: 0
            if (commandGeneration > currentGeneration) {
                consentGenerations[accountId] = commandGeneration
                consentValues[accountId] = granted
            }
            if (consentResponseLossesRemaining > 0) {
                consentResponseLossesRemaining -= 1
                throw IllegalStateException("deterministic consent response loss")
            }
            return WeatherConsentMutationResult(
                authoritativeGeneration = consentGenerations[accountId] ?: 0,
                authoritativeGranted = consentValues[accountId] == true,
            )
        }

        fun serverConsent(accountId: String): Pair<Long, Boolean> =
            (consentGenerations[accountId] ?: 0) to (consentValues[accountId] == true)

        override suspend fun refresh(
            accountId: String,
            location: ApproximateLocation?,
        ): WeatherLoad {
            events += "refresh:${location?.latitude},${location?.longitude}"
            refreshFailure?.let { throw it }
            current = refreshResult
            return refreshResult
        }

        override suspend fun switchToCurrentLocation(
            accountId: String,
            location: ApproximateLocation,
        ): WeatherLoad {
            deviceSwitchLocations += location
            val result = refresh(accountId, location)
            if (deviceSwitchResponseLossesRemaining > 0) {
                deviceSwitchResponseLossesRemaining -= 1
                throw IllegalStateException("deterministic device switch response loss")
            }
            return result
        }

        override suspend fun searchRegions(accountId: String, query: String): List<WeatherRegion> =
            emptyList()

        override suspend fun selectManualRegion(
            accountId: String,
            region: WeatherRegion,
            expectedRevision: Long,
        ): WeatherLoad {
            val load = current
            val dashboard =
                when (load) {
                    is WeatherLoad.Fresh -> load.dashboard
                    is WeatherLoad.Stale -> load.dashboard
                    is WeatherLoad.Failed -> load.cached
                    WeatherLoad.NotConfigured -> null
                } ?: return load
            val manualDashboard =
                dashboard.copy(
                    snapshot =
                        dashboard.snapshot.copy(
                            regionCode = region.regionCode,
                            regionName = region.regionName,
                        ),
                    revision = dashboard.revision + 1,
                )
            return WeatherLoad.Fresh(manualDashboard).also { current = it }
        }

        override suspend fun saveAlerts(
            accountId: String,
            globalEnabled: Boolean,
            plants: Map<String, Boolean>,
            expectedRevision: Long,
        ): WeatherLoad {
            saveFailure?.let { throw it }
            return current
        }
    }
}
