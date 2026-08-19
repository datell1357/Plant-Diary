package com.planterior.helper.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class WateringNotificationNavigationTest {
    private val uri =
        "planterior://collection/plant/plant-a?deliveryId=123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `cold notification tap builds home collection detail stack`() {
        assertEquals(
            listOf(
                PlanteriorRoute.Home,
                PlanteriorRoute.Collection,
                PlanteriorRoute.PlantDetail("plant-a"),
            ),
            NotificationTapRouter.coldStartStack(uri, authenticated = true),
        )
    }

    @Test
    fun `warm notification tap replaces unrelated history with the canonical detail stack`() {
        val navigator =
            RecordingStackNavigator(listOf(PlanteriorRoute.Home, PlanteriorRoute.Settings))

        NotificationTapRouter.openWarm(uri, authenticated = true, navigator)

        assertEquals(
            listOf(
                PlanteriorRoute.Home,
                PlanteriorRoute.Collection,
                PlanteriorRoute.PlantDetail("plant-a"),
            ),
            navigator.stack,
        )
    }

    @Test
    fun `logged out tap preserves exact return and resumes detail after login`() {
        assertEquals(
            listOf(PlanteriorRoute.Home, PlanteriorRoute.Login(uri)),
            NotificationTapRouter.coldStartStack(uri, authenticated = false),
        )
        assertEquals(
            listOf(
                PlanteriorRoute.Home,
                PlanteriorRoute.Collection,
                PlanteriorRoute.PlantDetail("plant-a"),
            ),
            NotificationTapRouter.resumeAfterLogin(uri),
        )
        assertEquals(
            "planterior://collection/plant/plant-a",
            AuthRouteGuard.externalRoute(PlanteriorRoute.PlantDetail("plant-a")),
        )
    }

    @Test
    fun `deleted notification target remains on detail with a direct collection escape`() {
        assertEquals(
            PlanteriorRoute.PlantDetail("deleted-plant"),
            PlanteriorRouteResolver.resolve("planterior://collection/plant/deleted-plant"),
        )
    }

    private class RecordingStackNavigator(initial: List<PlanteriorRoute>) :
        NotificationStackNavigator {
        var stack = initial

        override fun replaceWith(routes: List<PlanteriorRoute>) {
            stack = routes
        }
    }
}
