package com.planterior.helper

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.feature.collection.PlantDetailTestTags
import com.planterior.helper.feature.registration.RegistrationTestTags
import com.planterior.helper.home.SESSION_SIGNED_IN
import com.planterior.helper.navigation.PlanteriorRoute
import com.planterior.helper.navigation.toPlanteriorRoute
import com.planterior.helper.registration.setDebugRegistrationDuplicateFixture
import java.util.concurrent.TimeUnit
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
class RegistrationNavigationMainActivityTest {
    @get:Rule(order = 0) val localNetworkPermission = Api37LocalNetworkPermissionRule()
    @get:Rule(order = 1) val homeSession = DebugHomeSessionRule(SESSION_SIGNED_IN, ACCOUNT)
    @get:Rule(order = 2) val duplicateFixture = DuplicateRegistrationFixtureRule()
    @get:Rule(order = 3) val runtimeCleanup = RuntimeCleanupRule()
    @get:Rule(order = 4) val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun duplicatePlantNavigation_usesCurrentController_afterTwoRotations() {
        composeRule.runOnUiThread {
            composeRule.activity.navigationController.navigate(PlanteriorRoute.Registration)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RegistrationTestTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(RegistrationTestTags.NAME).performTextInput("회전 몬스테라")
        composeRule.onNodeWithTag(RegistrationTestTags.SEARCH_ACTION).performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(RegistrationTestTags.content(CONTENT_ID))
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(RegistrationTestTags.SUBMIT).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RegistrationTestTags.existing(EXISTING_ID)).assertIsDisplayed()

        val originalActivity = composeRule.activity
        val registrationEntryId =
            requireNotNull(originalActivity.navigationController.currentBackStackEntry).id
        repeat(2) {
            recreateRegistration(registrationEntryId)
            composeRule
                .onNodeWithTag(RegistrationTestTags.existing(EXISTING_ID))
                .assertIsDisplayed()
        }
        assertEquals(Lifecycle.State.DESTROYED, originalActivity.lifecycle.currentState)

        awaitRoute(PlanteriorRoute.PlantDetail(EXISTING_ID)) {
            composeRule.onNodeWithTag(RegistrationTestTags.existing(EXISTING_ID)).performClick()
        }
        composeRule.waitForIdle()

        assertEquals(
            PlanteriorRoute.PlantDetail(EXISTING_ID),
            composeRule.activity.navigationController.currentBackStackEntry.toPlanteriorRoute(),
        )
        composeRule.onNodeWithTag(PlantDetailTestTags.SCREEN).assertIsDisplayed()
        assertFalse(
            composeRule.activity.navigationController.currentBackStack.value
                .mapNotNull { it.toPlanteriorRoute() }
                .contains(PlanteriorRoute.Registration)
        )
    }

    private fun recreateRegistration(expectedEntryId: String) {
        val previousActivity = composeRule.activity
        ExactEventSubscription<RestoredRegistrationEvent>(
                matches = {
                    it.activity !== previousActivity &&
                        it.entryId == expectedEntryId &&
                        it.activity.lifecycle.currentState == Lifecycle.State.RESUMED
                },
                subscribe = { receiver ->
                    subscribeToRestoredRegistration(previousActivity, receiver)
                },
            )
            .use { subscription ->
                subscription.arm()
                composeRule.activityRule.scenario.recreate()
                val restored =
                    subscription.await(
                        EVENT_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                        "restored registration entry",
                    )
                assertNotSame(previousActivity, restored.activity)
                assertSame(restored.activity, composeRule.activity)
                assertEquals(expectedEntryId, restored.entryId)
            }
        composeRule.waitForIdle()
        assertEquals(
            PlanteriorRoute.Registration,
            composeRule.activity.navigationController.currentBackStackEntry.toPlanteriorRoute(),
        )
        composeRule.onNodeWithTag(RegistrationTestTags.SCREEN).assertIsDisplayed()
    }

    private fun subscribeToRestoredRegistration(
        previousActivity: MainActivity,
        receiver: (RestoredRegistrationEvent) -> Unit,
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
                            if (entry.toPlanteriorRoute() == PlanteriorRoute.Registration) {
                                dispatch(RestoredRegistrationEvent(activity, entry.id))
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
                composeRule.waitForIdle()
                val observed =
                    try {
                        subscription.await(
                            EVENT_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS,
                            "duplicate plant destination",
                        )
                    } catch (error: ExactEventException) {
                        throw AssertionError(
                            "current route after duplicate event: " +
                                controller.currentBackStackEntry.toPlanteriorRoute(),
                            error,
                        )
                    }
                assertEquals(expected, observed)
            }
    }

    private data class RestoredRegistrationEvent(
        val activity: MainActivity,
        val entryId: String,
    )

    class DuplicateRegistrationFixtureRule : ExternalResource() {
        override fun before() {
            setDebugRegistrationDuplicateFixture(
                ApplicationProvider.getApplicationContext(),
                true,
            )
        }

        override fun after() {
            setDebugRegistrationDuplicateFixture(
                ApplicationProvider.getApplicationContext(),
                false,
            )
        }
    }

    class RuntimeCleanupRule : ExternalResource() {
        override fun after() {
            val application = ApplicationProvider.getApplicationContext<PlanteriorApplication>()
            val runtime = application.repositoryRuntimeOrNull() ?: return
            val receipt = application.shutdownRepositoryRuntime()
            assertTrue(receipt.closed)
            assertFalse(runtime.isDatabaseOpen)
            assertFalse(application.repositoryRuntimeSnapshot().active)
        }
    }

    private companion object {
        const val ACCOUNT = "qa-registration-navigation"
        const val CONTENT_ID = "species-registration-lifecycle"
        const val EXISTING_ID = "existing-registration-lifecycle"
        const val EVENT_TIMEOUT_SECONDS = 15L
    }
}
