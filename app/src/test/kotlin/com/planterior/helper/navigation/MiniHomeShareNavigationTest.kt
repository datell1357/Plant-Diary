package com.planterior.helper.navigation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 미니홈 공유 route는 인증 목적지이며 어떤 인자도 싣지 않는다. */
class MiniHomeShareNavigationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `share route is authenticated and never a bottom tab`() {
        val route: PlanteriorRoute = PlanteriorRoute.MiniHomeShare

        assertTrue(route is PlanteriorRoute.Authenticated)
        assertTrue(route !is PlanteriorRoute.TopLevel)
        assertTrue(route !is PlanteriorRoute.Public)
    }

    @Test
    fun `share route carries no arguments at all`() {
        val encoded =
            json.encodeToJsonElement(PlanteriorRoute.serializer(), PlanteriorRoute.MiniHomeShare)
                as JsonObject

        assertEquals(setOf("type"), encoded.keys)
    }

    @Test
    fun `signed out access to the share route is guarded to login`() {
        val guarded =
            AuthRouteGuard.destination(PlanteriorRoute.MiniHomeShare, authenticated = false)

        assertTrue(guarded is PlanteriorRoute.Login)
        assertEquals(
            "planterior://minihome/share",
            (guarded as PlanteriorRoute.Login).returnRoute,
        )
    }

    @Test
    fun `share route external form carries no owner or link data`() {
        val external = AuthRouteGuard.externalRoute(PlanteriorRoute.MiniHomeShare)

        assertEquals("planterior://minihome/share", external)
        assertTrue(external.none { it == '?' })
    }

    @Test
    fun `the guarded return route resolves back to the share destination`() {
        val external = AuthRouteGuard.externalRoute(PlanteriorRoute.MiniHomeShare)

        assertEquals(PlanteriorRoute.MiniHomeShare, PlanteriorRouteResolver.resolve(external))
        assertEquals(
            PlanteriorRoute.MiniHomeShare,
            PlanteriorRouteResolver.resolveReturnRoute(external),
        )
    }

    @Test
    fun `unknown mini home deep link segments fall back to home instead of the share screen`() {
        assertEquals(
            PlanteriorRoute.Home,
            PlanteriorRouteResolver.resolve("planterior://minihome/share/secret-token"),
        )
        assertEquals(
            PlanteriorRoute.Home,
            PlanteriorRouteResolver.resolve("planterior://minihome/unknown"),
        )
        assertEquals(
            PlanteriorRoute.MiniHome,
            PlanteriorRouteResolver.resolve("planterior://minihome"),
        )
    }

    @Test
    fun `cold start into share keeps a reachable mini home back stack`() {
        assertEquals(
            listOf(
                PlanteriorRoute.Home,
                PlanteriorRoute.MiniHome,
                PlanteriorRoute.MiniHomeShare,
            ),
            PlanteriorRouteResolver.backStackFor(PlanteriorRoute.MiniHomeShare),
        )
    }

    @Test
    fun `share route round trips through serialization`() {
        val encoded =
            json.encodeToString(PlanteriorRoute.serializer(), PlanteriorRoute.MiniHomeShare)

        assertEquals(
            PlanteriorRoute.MiniHomeShare,
            json.decodeFromString(PlanteriorRoute.serializer(), encoded),
        )
    }
}
