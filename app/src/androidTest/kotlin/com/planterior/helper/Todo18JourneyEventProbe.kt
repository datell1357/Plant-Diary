package com.planterior.helper

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.navigation.NavController
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.planterior.helper.feature.camera.Todo18DebugCameraBoundary
import com.planterior.helper.feature.camera.Todo18DebugPhotoPreparationEvent
import com.planterior.helper.navigation.PlanteriorRoute
import com.planterior.helper.navigation.toPlanteriorRoute
import java.io.Closeable
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals

internal typealias Todo18ComposeRule =
    AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

/** Exact-event probes used to synchronize Todo18 journeys without polling or sleeps. */
internal class Todo18JourneyEventProbe(
    private val runtime: Todo18IntegratedRuntimeRule,
    private val compose: Todo18ComposeRule,
) {
    fun navigateAndAwaitBoundary(route: PlanteriorRoute, boundaryKind: String) {
        val boundary = boundarySubscription(boundaryKind)
        val destination = routeSubscription { it == route }
        boundary.use { boundaryEvent ->
            destination.use { routeEvent ->
                boundaryEvent.arm()
                routeEvent.arm()
                compose.runOnIdle { compose.activity.navigationController.navigate(route) }
                routeEvent.await(
                    EVENT_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS,
                    "Todo18 route $route",
                )
                boundaryEvent.await(
                    EVENT_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS,
                    "Todo18 boundary $boundaryKind",
                )
            }
        }
        compose.waitForIdle()
    }

    fun navigateTo(expected: PlanteriorRoute, trigger: () -> Unit) {
        routeSubscription { it == expected }
            .use { subscription ->
                subscription.arm()
                trigger()
                subscription.await(
                    EVENT_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS,
                    "Todo18 route $expected",
                )
            }
        compose.waitForIdle()
    }

    fun awaitBoundary(kind: String, trigger: () -> Unit): Todo18BoundaryEvent =
        boundarySubscription(kind).use { subscription ->
            subscription.arm()
            triggerSettleAndAwait(
                trigger = { subscription.trigger(trigger) },
                settle = compose::waitForIdle,
                await = {
                    subscription.await(
                        EVENT_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS,
                        "Todo18 boundary $kind",
                    )
                },
            )
        }

    fun awaitRegistrationCommit(trigger: () -> Unit): String {
        val boundary = boundarySubscription("registration-committed")
        val route = routeSubscription { it is PlanteriorRoute.PlantDetail }
        boundary.use { boundaryEvent ->
            route.use { routeEvent ->
                boundaryEvent.arm()
                routeEvent.arm()
                trigger()
                val committed =
                    boundaryEvent.await(
                        EVENT_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS,
                        "Todo18 registration commit",
                    )
                val destination =
                    routeEvent.await(
                        EVENT_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS,
                        "Todo18 registration navigation",
                    ) as PlanteriorRoute.PlantDetail
                assertEquals(committed.identity, destination.plantId)
                return destination.plantId
            }
        }
    }

    fun awaitRejectedPhoto(trigger: () -> Unit): Todo18DebugPhotoPreparationEvent =
        cameraPreparationSubscription().use { subscription ->
            subscription.arm()
            trigger()
            subscription.await(
                EVENT_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS,
                "Todo18 malformed photo validation",
            )
        }

    private fun cameraPreparationSubscription() =
        ExactEventSubscription<Todo18DebugPhotoPreparationEvent>(
            matches = { !it.accepted },
            subscribe = { receiver ->
                lateinit var closeable: Closeable
                LeasedExactEventRegistration(
                    receiver = receiver,
                    register = { dispatch ->
                        closeable = Todo18DebugCameraBoundary.subscribe(dispatch)
                    },
                    unregister = { closeable.close() },
                )
            },
        )

    private fun boundarySubscription(kind: String) =
        ExactEventSubscription<Todo18BoundaryEvent>(
            matches = { it.kind == kind },
            subscribe = { receiver ->
                lateinit var closeable: Closeable
                LeasedExactEventRegistration(
                    receiver = receiver,
                    register = { dispatch -> closeable = runtime.boundary.subscribe(dispatch) },
                    unregister = { closeable.close() },
                )
            },
        )

    private fun routeSubscription(matches: (PlanteriorRoute) -> Boolean) =
        ExactEventSubscription(
            matches = matches,
            subscribe = { receiver -> subscribeToDestinations(receiver) },
        )

    private fun subscribeToDestinations(
        receiver: (PlanteriorRoute) -> Unit
    ): ExactEventRegistration {
        val controller = compose.activity.navigationController
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
