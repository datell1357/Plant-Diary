package com.planterior.helper.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanteriorRouteResolverTest {
    @Test
    fun `each bottom tab deep link resolves to its top level route`() {
        assertEquals(PlanteriorRoute.Home, PlanteriorRouteResolver.resolve("planterior://home"))
        assertEquals(
            PlanteriorRoute.Collection,
            PlanteriorRouteResolver.resolve("planterior://collection"),
        )
        assertEquals(
            PlanteriorRoute.Storage,
            PlanteriorRouteResolver.resolve("planterior://storage"),
        )
        assertEquals(
            PlanteriorRoute.Settings,
            PlanteriorRouteResolver.resolve("planterior://settings"),
        )
        assertEquals(PlanteriorRoute.Camera, PlanteriorRouteResolver.resolve("planterior://camera"))
    }

    @Test
    fun `home entry point deep links resolve to their own destinations`() {
        assertEquals(
            PlanteriorRoute.MiniHome,
            PlanteriorRouteResolver.resolve("planterior://minihome"),
        )
        assertEquals(
            PlanteriorRoute.Notifications,
            PlanteriorRouteResolver.resolve("planterior://notifications"),
        )
    }

    @Test
    fun `plant detail deep link keeps the opaque identifier`() {
        assertEquals(
            PlanteriorRoute.PlantDetail("plant-abc_123"),
            PlanteriorRouteResolver.resolve("planterior://collection/plant/plant-abc_123"),
        )
    }

    @Test
    fun `unknown and malformed external routes fall back to home`() {
        val hostile =
            listOf(
                null,
                "",
                "   ",
                "planterior://",
                "planterior://unknown",
                "planterior://collection/plant",
                "planterior://collection/plant/",
                "planterior://collection/plant/../../etc/passwd",
                "planterior://collection/plant/id with space",
                "planterior://collection/plant/" + "a".repeat(65),
                "https://evil.example.com/planterior://home",
                "javascript:alert(1)",
                "intent://home#Intent;scheme=planterior;end",
                "planteriorX://home",
            )
        hostile.forEach { uri ->
            assertEquals(
                "expected safe home fallback for: $uri",
                PlanteriorRoute.Home,
                PlanteriorRouteResolver.resolve(uri),
            )
        }
    }

    @Test
    fun `query and fragment are ignored so injected parameters cannot change the destination`() {
        assertEquals(
            PlanteriorRoute.Settings,
            PlanteriorRouteResolver.resolve("planterior://settings?userId=secret#token"),
        )
        assertEquals(
            PlanteriorRoute.PlantDetail("abc"),
            PlanteriorRouteResolver.resolve("planterior://collection/plant/abc?photo=raw"),
        )
    }

    @Test
    fun `login return route only restores authenticated destinations`() {
        assertEquals(
            PlanteriorRoute.Collection,
            PlanteriorRouteResolver.resolveReturnRoute("planterior://collection"),
        )
        assertEquals(
            PlanteriorRoute.PlantDetail("abc"),
            PlanteriorRouteResolver.resolveReturnRoute("planterior://collection/plant/abc"),
        )
        assertEquals(
            PlanteriorRoute.Home,
            PlanteriorRouteResolver.resolveReturnRoute("planterior://unknown"),
        )
        assertEquals(PlanteriorRoute.Home, PlanteriorRouteResolver.resolveReturnRoute(null))
    }

    @Test
    fun `cold start deep link builds a parent back stack`() {
        assertEquals(
            listOf(PlanteriorRoute.Home),
            PlanteriorRouteResolver.backStackFor(PlanteriorRoute.Home),
        )
        assertEquals(
            listOf(PlanteriorRoute.Home, PlanteriorRoute.Storage),
            PlanteriorRouteResolver.backStackFor(PlanteriorRoute.Storage),
        )
        assertEquals(
            listOf(
                PlanteriorRoute.Home,
                PlanteriorRoute.Collection,
                PlanteriorRoute.PlantDetail("abc"),
            ),
            PlanteriorRouteResolver.backStackFor(PlanteriorRoute.PlantDetail("abc")),
        )
    }

    @Test
    fun `every back stack starts at home so back always reaches a valid root`() {
        val routes =
            listOf(
                PlanteriorRoute.Home,
                PlanteriorRoute.Collection,
                PlanteriorRoute.Storage,
                PlanteriorRoute.Settings,
                PlanteriorRoute.Camera,
                PlanteriorRoute.MiniHome,
                PlanteriorRoute.Notifications,
                PlanteriorRoute.PlantDetail("abc"),
            )
        routes.forEach { route ->
            val stack = PlanteriorRouteResolver.backStackFor(route)
            assertEquals("back stack root for $route", PlanteriorRoute.Home, stack.first())
            assertEquals("back stack target for $route", route, stack.last())
        }
    }
}
