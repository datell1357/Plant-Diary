package com.planterior.helper.navigation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** route가 개인정보나 큰 payload를 옮기지 않는다는 계약을 고정한다. */
class PlanteriorRouteContractTest {
    private val json = Json { encodeDefaults = true }

    private val forbiddenArgumentNames =
        listOf("userid", "uid", "account", "email", "token", "photo", "image", "bytes", "body")

    private val allRoutes: List<PlanteriorRoute> =
        listOf(
            PlanteriorRoute.Home,
            PlanteriorRoute.Collection,
            PlanteriorRoute.Storage,
            PlanteriorRoute.Settings,
            PlanteriorRoute.Camera,
            PlanteriorRoute.MiniHome,
            PlanteriorRoute.Notifications,
            PlanteriorRoute.PlantDetail("plant-1"),
            PlanteriorRoute.WateringConfirmation("plant-1"),
            PlanteriorRoute.Login(returnRoute = "planterior://collection"),
        )

    @Test
    fun `route arguments never carry user identity photo bytes or body content`() {
        allRoutes.forEach { route ->
            val encoded = json.encodeToJsonElement(PlanteriorRoute.serializer(), route)
            val keys = (encoded as JsonObject).keys.map { it.lowercase() }
            keys.forEach { key ->
                forbiddenArgumentNames.forEach { forbidden ->
                    assertTrue(
                        "route ${route::class.simpleName} argument '$key' must not expose '$forbidden'",
                        !key.contains(forbidden),
                    )
                }
            }
        }
    }

    @Test
    fun `plant detail carries only an opaque identifier`() {
        val encoded =
            json.encodeToJsonElement(
                PlanteriorRoute.serializer(),
                PlanteriorRoute.PlantDetail("plant-1"),
            ) as JsonObject
        assertEquals(setOf("type", "plantId"), encoded.keys)
        val watering =
            json.encodeToJsonElement(
                PlanteriorRoute.serializer(),
                PlanteriorRoute.WateringConfirmation("plant-1"),
            ) as JsonObject
        assertEquals(setOf("type", "plantId"), watering.keys)
    }

    @Test
    fun `every top level tab destination is authenticated`() {
        val tabs: List<PlanteriorRoute> =
            listOf(
                PlanteriorRoute.Home,
                PlanteriorRoute.Collection,
                PlanteriorRoute.Storage,
                PlanteriorRoute.Settings,
            )
        tabs.forEach { tab ->
            assertTrue("$tab must be a top level destination", tab is PlanteriorRoute.TopLevel)
            assertTrue("$tab must be authenticated", tab is PlanteriorRoute.Authenticated)
        }
    }

    @Test
    fun `camera is authenticated but not a top level tab destination`() {
        val camera: PlanteriorRoute = PlanteriorRoute.Camera
        assertTrue(camera is PlanteriorRoute.Authenticated)
        assertTrue(camera !is PlanteriorRoute.TopLevel)
    }

    @Test
    fun `home entry points are authenticated destinations outside the tab bar`() {
        listOf<PlanteriorRoute>(PlanteriorRoute.MiniHome, PlanteriorRoute.Notifications).forEach {
            route ->
            assertTrue("$route must be authenticated", route is PlanteriorRoute.Authenticated)
            assertTrue("$route must not be a bottom tab", route !is PlanteriorRoute.TopLevel)
        }
    }

    @Test
    fun `login lives in the public graph and is never an authenticated target`() {
        val login: PlanteriorRoute = PlanteriorRoute.Login()
        assertTrue(login is PlanteriorRoute.Public)
        assertTrue(login !is PlanteriorRoute.Authenticated)
    }

    @Test
    fun `routes round trip through serialization so navigation state survives process death`() {
        allRoutes.forEach { route ->
            val encoded = json.encodeToString(PlanteriorRoute.serializer(), route)
            assertEquals(route, json.decodeFromString(PlanteriorRoute.serializer(), encoded))
        }
    }
}
