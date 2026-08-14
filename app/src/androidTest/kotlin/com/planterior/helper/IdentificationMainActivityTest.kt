package com.planterior.helper

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.planterior.helper.feature.identify.IdentificationTestTags
import com.planterior.helper.navigation.PlanteriorRoute
import com.planterior.helper.navigation.toPlanteriorRoute
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentificationMainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun grantApi37LocalNetworkPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .grantRuntimePermission(
                    compose.activity.packageName,
                    "android.permission.ACCESS_LOCAL_NETWORK",
                )
        }
    }

    @Test
    fun selectionAndConfirmedHandoffSurviveRealActivityRecreation() {
        navigateTo(PlanteriorRoute.Identification("fixture-success")) {
            compose.runOnIdle {
                compose.activity.navigationController.navigate(
                    PlanteriorRoute.Identification("fixture-success")
                )
            }
        }
        compose
            .onNodeWithTag(IdentificationTestTags.candidate("species-snake-plant"))
            .assertExists()
        compose
            .onNodeWithTag(IdentificationTestTags.candidate("species-pothos"))
            .performScrollTo()
            .performClick()

        recreateActivity()

        compose.runOnIdle {
            assertEquals(
                PlanteriorRoute.Identification("fixture-success"),
                compose.activity.navigationController.currentBackStackEntry.toPlanteriorRoute(),
            )
        }
        compose.onNodeWithTag(IdentificationTestTags.CONFIRM).assertIsEnabled()
        compose
            .onNode(
                hasTestTag(IdentificationTestTags.candidate("species-pothos")) and
                    hasAnyDescendant(
                        SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
                    )
            )
            .assertExists()

        navigateTo(PlanteriorRoute.Registration) {
            compose.onNodeWithTag(IdentificationTestTags.CONFIRM).performScrollTo().performClick()
        }
        compose.onNodeWithText("스킨답서스", substring = true).assertExists()

        recreateActivity()

        compose.runOnIdle {
            assertEquals(
                PlanteriorRoute.Registration,
                compose.activity.navigationController.currentBackStackEntry.toPlanteriorRoute(),
            )
        }
        compose.onNodeWithText("스킨답서스", substring = true).assertExists()
    }

    private fun recreateActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previous = compose.activity
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            compose.activityRule.scenario.recreate()
            val recreated = instrumentation.waitForMonitorWithTimeout(monitor, EVENT_TIMEOUT_MILLIS)
            assertNotNull(recreated)
            assertNotSame(previous, recreated)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        compose.waitForIdle()
    }

    private fun navigateTo(expected: PlanteriorRoute, trigger: () -> Unit) {
        val controller = compose.activity.navigationController
        ExactEventSubscription<PlanteriorRoute>(
                matches = { it == expected },
                subscribe = { receiver -> subscribeToDestinations(controller, receiver) },
            )
            .use { subscription ->
                subscription.arm()
                trigger()
                subscription.await(
                    EVENT_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS,
                    expected.toString(),
                )
            }
        compose.waitForIdle()
    }

    private fun subscribeToDestinations(
        controller: NavController,
        receiver: (PlanteriorRoute) -> Unit,
    ): ExactEventRegistration {
        lateinit var listener: NavController.OnDestinationChangedListener
        return LeasedExactEventRegistration(
            receiver = receiver,
            register = { dispatch ->
                listener = NavController.OnDestinationChangedListener { current, _, _ ->
                    current.currentBackStackEntry.toPlanteriorRoute()?.let(dispatch)
                }
                compose.runOnIdle { controller.addOnDestinationChangedListener(listener) }
            },
            unregister = {
                compose.runOnIdle { controller.removeOnDestinationChangedListener(listener) }
            },
        )
    }

    private companion object {
        const val EVENT_TIMEOUT_MILLIS = 10_000L
    }
}
