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
    fun `external hostile return route is never preserved`() {
        assertEquals(
            PlanteriorRoute.Home,
            AuthRouteGuard.returnDestination("https://evil.example/steal"),
        )
    }
}
