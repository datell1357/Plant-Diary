package com.planterior.helper.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthRouteGuardTest {
    @Test
    fun `authenticated route is held behind login and restored after success`() {
        assertEquals(
            PlanteriorRoute.Login("planterior://storage"),
            AuthRouteGuard.destination(PlanteriorRoute.Storage, false),
        )
        assertEquals(
            PlanteriorRoute.Storage,
            AuthRouteGuard.destination(PlanteriorRoute.Storage, true),
        )
    }

    @Test
    fun `home is reachable before login so the logged out home can be shown`() {
        // Figma `home-screen-logged-out`은 로그인 전에도 보여줘야 하는 화면이다.
        assertEquals(PlanteriorRoute.Home, AuthRouteGuard.destination(PlanteriorRoute.Home, false))
        assertEquals(PlanteriorRoute.Home, AuthRouteGuard.destination(PlanteriorRoute.Home, true))
    }

    @Test
    fun `every destination other than home still requires a session`() {
        listOf(
                PlanteriorRoute.Collection,
                PlanteriorRoute.Storage,
                PlanteriorRoute.Settings,
                PlanteriorRoute.Camera,
                PlanteriorRoute.MiniHome,
                PlanteriorRoute.Notifications,
                PlanteriorRoute.PlantDetail("plant-1"),
            )
            .forEach { route ->
                val guarded = AuthRouteGuard.destination(route, false)
                assertEquals(
                    "$route must be held behind login",
                    PlanteriorRoute.Login(AuthRouteGuard.externalRoute(route)),
                    guarded,
                )
            }
    }

    @Test
    fun `external hostile return route is never preserved`() {
        assertEquals(
            PlanteriorRoute.Home,
            AuthRouteGuard.returnDestination("https://evil.example/steal"),
        )
    }
}
