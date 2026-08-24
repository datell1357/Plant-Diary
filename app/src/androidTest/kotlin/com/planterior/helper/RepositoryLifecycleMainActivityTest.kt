package com.planterior.helper

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.feature.auth.ACCOUNT_SCREEN_TAG
import com.planterior.helper.feature.collection.CollectionTestTags
import com.planterior.helper.feature.collection.PlantDetailTestTags
import com.planterior.helper.feature.home.HomeTestTags
import com.planterior.helper.feature.home.HomeUiState
import com.planterior.helper.feature.minihome.MiniHomeTestTags
import com.planterior.helper.feature.registration.RegistrationTestTags
import com.planterior.helper.feature.watering.WateringTestTags
import com.planterior.helper.feature.weather.WeatherTestTags
import com.planterior.helper.home.SESSION_SIGNED_IN
import com.planterior.helper.home.setDebugHomeSession
import com.planterior.helper.navigation.PlanteriorRoute
import com.planterior.helper.navigation.toPlanteriorRoute
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryLifecycleMainActivityTest {
    @get:Rule(order = 0) val localNetworkPermission = Api37LocalNetworkPermissionRule()
    @get:Rule(order = 1) val homeSession = DebugHomeSessionRule(SESSION_SIGNED_IN, ACCOUNT_A)
    @get:Rule(order = 2)
    val runtimeCleanup = RepositoryRuntimeCleanupRule(setOf(ACCOUNT_A, ACCOUNT_B))
    @get:Rule(order = 3) val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun retainedRoutes_surviveRotation_clearOnLogout_andPartitionAccountSwitch() {
        val application = ApplicationProvider.getApplicationContext<PlanteriorApplication>()
        val initialRuntime = application.repositoryRuntimeOrNull()
        requireNotNull(initialRuntime)
        val generation = application.repositoryRuntimeSnapshot().generation
        val retainedEntries = mutableListOf<NavBackStackEntry>()
        runBlocking {
            initialRuntime.database.cacheDao().clearVisibleAccount(ACCOUNT_A)
            initialRuntime.database.cacheDao().clearVisibleAccount(ACCOUNT_B)
            initialRuntime.database.cacheDao().upsertPlant(cachedPlant(ACCOUNT_A, PLANT_A))
            initialRuntime.database.cacheDao().upsertPlant(cachedPlant(ACCOUNT_B, PLANT_B))
        }

        val destinations =
            listOf(
                Destination(PlanteriorRoute.Collection, CollectionTestTags.SCREEN),
                Destination(
                    PlanteriorRoute.PlantDetail("lifecycle-plant"),
                    PlantDetailTestTags.SCREEN,
                ),
                Destination(PlanteriorRoute.Registration, RegistrationTestTags.SCREEN),
                Destination(
                    PlanteriorRoute.WateringConfirmation("lifecycle-plant"),
                    WateringTestTags.SCREEN,
                ),
                Destination(PlanteriorRoute.Weather, WeatherTestTags.SCREEN),
                Destination(PlanteriorRoute.MiniHome, MiniHomeTestTags.SCREEN),
            )

        destinations.forEach { destination ->
            composeRule.runOnUiThread {
                composeRule.activity.navigationController.navigate(destination.route)
            }
            composeRule.waitForIdle()
            assertCurrentDestination(destination)
            retainedEntries +=
                requireNotNull(composeRule.activity.navigationController.currentBackStackEntry)
            repeat(2) {
                recreateAt(destination)
                assertSame(initialRuntime, application.repositoryRuntimeOrNull())
                assertEquals(generation, application.repositoryRuntimeSnapshot().generation)
                assertTrue(initialRuntime.isDatabaseOpen)
            }
        }

        composeRule.runOnUiThread {
            composeRule.activity.navigationController.navigate(PlanteriorRoute.Settings)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ACCOUNT_SCREEN_TAG).assertIsDisplayed()
        retainedEntries +=
            requireNotNull(composeRule.activity.navigationController.currentBackStackEntry)
        awaitRoute(PlanteriorRoute.Login()) {
            composeRule.onNodeWithTag("account-logout").performScrollTo().performClick()
        }
        composeRule.waitForIdle()

        assertEquals(
            PlanteriorRoute.Login(),
            composeRule.activity.navigationController.currentBackStackEntry.toPlanteriorRoute(),
        )
        retainedEntries.forEach { entry ->
            assertEquals(Lifecycle.State.DESTROYED, entry.lifecycle.currentState)
        }
        assertSame(initialRuntime, application.repositoryRuntimeOrNull())
        assertTrue(initialRuntime.isDatabaseOpen)
        assertEquals(generation, application.repositoryRuntimeSnapshot().generation)

        setDebugHomeSession(application, SESSION_SIGNED_IN, ACCOUNT_B)
        recreateAt(Destination(PlanteriorRoute.Login(), "auth-google"))
        val switchedState =
            runBlocking {
                withTimeout(TimeUnit.SECONDS.toMillis(EVENT_TIMEOUT_SECONDS)) {
                    composeRule.activity.homeViewModel.state.first { state ->
                        state is HomeUiState.Content &&
                            state.careItems.any { it.plantId == PLANT_B }
                    }
                }
            }
                as HomeUiState.Content
        assertEquals(listOf(PLANT_B), switchedState.careItems.map { it.plantId })
        composeRule.runOnUiThread {
            composeRule.activity.navigationController.navigate(PlanteriorRoute.Home)
        }
        composeRule.waitForIdle()
        assertEquals(
            PlanteriorRoute.Home,
            composeRule.activity.navigationController.currentBackStackEntry.toPlanteriorRoute(),
        )
        composeRule.onNodeWithTag(HomeTestTags.CARE_SECTION).assertIsDisplayed()
        assertSame(initialRuntime, application.repositoryRuntimeOrNull())
        assertEquals(generation, application.repositoryRuntimeSnapshot().generation)
    }

    private fun cachedPlant(account: String, plantId: String) =
        CachedPlantEntity(
            accountId = account,
            plantId = plantId,
            displayName = plantId,
            representativePhotoPath = null,
            revision = 1,
            updatedAtEpochMillis = 1,
        )

    private fun recreateAt(destination: Destination) {
        val previousActivity = composeRule.activity
        val entryId = requireNotNull(previousActivity.navigationController.currentBackStackEntry).id
        ExactEventSubscription<RestoredRouteEvent>(
                matches = {
                    it.activity !== previousActivity &&
                        it.route == destination.route &&
                        it.entryId == entryId &&
                        it.activity.lifecycle.currentState == Lifecycle.State.RESUMED
                },
                subscribe = { receiver ->
                    subscribeToRestoredRoute(previousActivity, destination.route, receiver)
                },
            )
            .use { subscription ->
                subscription.arm()
                composeRule.activityRule.scenario.recreate()
                val restored =
                    subscription.await(
                        EVENT_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                        "restored ${destination.route} route",
                    )
                assertNotSame(previousActivity, restored.activity)
                assertSame(restored.activity, composeRule.activity)
                assertEquals(entryId, restored.entryId)
            }
        composeRule.waitForIdle()
        assertCurrentDestination(destination)
    }

    private fun subscribeToRestoredRoute(
        previousActivity: MainActivity,
        route: PlanteriorRoute,
        receiver: (RestoredRouteEvent) -> Unit,
    ): ExactEventRegistration {
        val application = ApplicationProvider.getApplicationContext<Application>()
        lateinit var callbacks: Application.ActivityLifecycleCallbacks
        return LeasedExactEventRegistration(
            receiver = receiver,
            register = { dispatch ->
                callbacks =
                    object : Application.ActivityLifecycleCallbacks {
                        override fun onActivityPostResumed(activity: Activity) {
                            if (activity !is MainActivity || activity === previousActivity) return
                            val entry =
                                activity.navigationController.currentBackStackEntry ?: return
                            val restoredRoute = entry.toPlanteriorRoute()
                            if (restoredRoute == route) {
                                dispatch(RestoredRouteEvent(activity, restoredRoute, entry.id))
                            }
                        }

                        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit

                        override fun onActivityStarted(activity: Activity) = Unit

                        override fun onActivityResumed(activity: Activity) = Unit

                        override fun onActivityPaused(activity: Activity) = Unit

                        override fun onActivityStopped(activity: Activity) = Unit

                        override fun onActivitySaveInstanceState(
                            activity: Activity,
                            state: Bundle,
                        ) = Unit

                        override fun onActivityDestroyed(activity: Activity) = Unit
                    }
                application.registerActivityLifecycleCallbacks(callbacks)
            },
            unregister = { application.unregisterActivityLifecycleCallbacks(callbacks) },
        )
    }

    private fun awaitRoute(expected: PlanteriorRoute, trigger: () -> Unit) {
        val controller = composeRule.activity.navigationController
        ExactEventSubscription<PlanteriorRoute>(
                matches = { it == expected },
                subscribe = { receiver ->
                    lateinit var listener: NavController.OnDestinationChangedListener
                    LeasedExactEventRegistration(
                        receiver = receiver,
                        register = { dispatch ->
                            listener = NavController.OnDestinationChangedListener { nav, _, _ ->
                                nav.currentBackStackEntry.toPlanteriorRoute()?.let(dispatch)
                            }
                            controller.addOnDestinationChangedListener(listener)
                        },
                        unregister = { controller.removeOnDestinationChangedListener(listener) },
                    )
                },
            )
            .use { subscription ->
                subscription.arm()
                trigger()
                assertEquals(
                    expected,
                    subscription.await(
                        EVENT_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                        "canonical logout route",
                    ),
                )
            }
    }

    private fun assertCurrentDestination(destination: Destination) {
        assertEquals(
            destination.route,
            composeRule.activity.navigationController.currentBackStackEntry.toPlanteriorRoute(),
        )
        composeRule.onNodeWithTag(destination.screenTag).assertIsDisplayed()
    }

    private data class Destination(val route: PlanteriorRoute, val screenTag: String)

    private data class RestoredRouteEvent(
        val activity: MainActivity,
        val route: PlanteriorRoute,
        val entryId: String,
    )

    class RepositoryRuntimeCleanupRule(private val accountIds: Set<String>) : ExternalResource() {
        override fun after() {
            val application = ApplicationProvider.getApplicationContext<PlanteriorApplication>()
            val runtime = application.repositoryRuntimeOrNull()
            requireNotNull(runtime)
            runBlocking {
                accountIds.forEach { account ->
                    runtime.database.cacheDao().clearVisibleAccount(account)
                }
            }
            val before = application.repositoryRuntimeSnapshot()
            val receipt = application.shutdownRepositoryRuntime()
            val after = application.repositoryRuntimeSnapshot()
            assertTrue(receipt.closed)
            assertEquals(before.generation, receipt.generation)
            assertFalse(runtime.isDatabaseOpen)
            assertEquals(before.generation, after.generation)
            assertFalse(after.active)
        }
    }

    private companion object {
        const val ACCOUNT_A = "qa-repository-lifecycle-a"
        const val ACCOUNT_B = "qa-repository-lifecycle-b"
        const val PLANT_A = "owner-a-plant"
        const val PLANT_B = "owner-b-plant"
        const val EVENT_TIMEOUT_SECONDS = 15L
    }
}
