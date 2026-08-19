package com.planterior.helper

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.planterior.helper.feature.identify.IdentificationTestTags
import com.planterior.helper.feature.registration.RegistrationTestTags
import com.planterior.helper.home.SESSION_SIGNED_IN
import com.planterior.helper.identify.DebugIdentificationEvent
import com.planterior.helper.identify.DebugIdentificationEvents
import com.planterior.helper.navigation.PlanteriorRoute
import com.planterior.helper.navigation.toPlanteriorRoute
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentificationMainActivityTest {
    @get:Rule(order = 0) val localNetworkPermission = Api37LocalNetworkPermissionRule()
    @get:Rule(order = 1)
    val signedInSession = DebugHomeSessionRule(SESSION_SIGNED_IN, "qa-identification-account")
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun selectionAndConfirmedHandoffSurviveRealActivityRecreation() {
        navigateToIdentificationReady {
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

        recreateActivity(expectedIdentificationRequest = "fixture-success")

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
        compose
            .onNodeWithTag(RegistrationTestTags.NAME)
            .assertTextContains("스킨답서스", substring = true)

        recreateActivity()

        compose.runOnIdle {
            assertEquals(
                PlanteriorRoute.Registration,
                compose.activity.navigationController.currentBackStackEntry.toPlanteriorRoute(),
            )
        }
        compose
            .onNodeWithTag(RegistrationTestTags.NAME)
            .assertTextContains("스킨답서스", substring = true)
    }

    private fun recreateActivity(expectedIdentificationRequest: String? = null) {
        val identification = expectedIdentificationRequest?.let { requestId ->
            ExactEventSubscription<DebugIdentificationEvent>(
                matches = { it.requestId == requestId && it.candidateIds == CANDIDATE_IDS },
                subscribe = ::subscribeToIdentificationEvents,
            )
        }
        identification?.arm()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previous = compose.activity
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            compose.activityRule.scenario.recreate()
            val recreated = instrumentation.waitForMonitorWithTimeout(monitor, EVENT_TIMEOUT_MILLIS)
            assertNotNull(recreated)
            assertNotSame(previous, recreated)
            identification?.await(
                EVENT_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS,
                expectedIdentificationRequest.orEmpty(),
            )
        } finally {
            instrumentation.removeMonitor(monitor)
            identification?.close()
        }
        compose.waitForIdle()
    }

    private fun navigateToIdentificationReady(trigger: () -> Unit) {
        val controller = compose.activity.navigationController
        ExactEventSubscription<PlanteriorRoute>(
                matches = { it == PlanteriorRoute.Identification("fixture-success") },
                subscribe = { receiver -> subscribeToDestinations(controller, receiver) },
            )
            .use { route ->
                ExactEventSubscription<DebugIdentificationEvent>(
                        matches = {
                            it.requestId == "fixture-success" && it.candidateIds == CANDIDATE_IDS
                        },
                        subscribe = ::subscribeToIdentificationEvents,
                    )
                    .use { identification ->
                        route.arm()
                        identification.arm()
                        trigger()
                        route.await(
                            EVENT_TIMEOUT_MILLIS,
                            TimeUnit.MILLISECONDS,
                            "fixture-success route",
                        )
                        identification.await(
                            EVENT_TIMEOUT_MILLIS,
                            TimeUnit.MILLISECONDS,
                            "fixture-success candidates",
                        )
                    }
            }
        compose.waitForIdle()
    }

    private fun subscribeToIdentificationEvents(
        receiver: (DebugIdentificationEvent) -> Unit
    ): ExactEventRegistration {
        lateinit var closeable: java.io.Closeable
        return LeasedExactEventRegistration(
            receiver = receiver,
            register = { dispatch -> closeable = DebugIdentificationEvents.subscribe(dispatch) },
            unregister = { closeable.close() },
        )
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
        val CANDIDATE_IDS = listOf("species-monstera", "species-pothos", "species-snake-plant")
        const val EVENT_TIMEOUT_MILLIS = 10_000L
    }
}
