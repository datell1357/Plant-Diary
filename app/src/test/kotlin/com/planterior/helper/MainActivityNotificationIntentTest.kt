package com.planterior.helper

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.navigation.toRoute
import com.planterior.helper.home.SESSION_SIGNED_IN
import com.planterior.helper.home.setDebugHomeSession
import com.planterior.helper.navigation.PlanteriorRoute
import com.planterior.helper.navigation.toPlanteriorRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MainActivityNotificationIntentTest {
    private val wateringUri =
        "planterior://collection/plant/deleted-plant?deliveryId=123e4567-e89b-12d3-a456-426614174000"
    private val weatherUri = "planterior://weather/plant/plant-a"

    @Test
    fun `non-notification in-app destination keeps its restored entry across recreation`() {
        val context =
            androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        setDebugHomeSession(context, SESSION_SIGNED_IN, accountUid = "qa-recreation-account")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            controller
                .get()
                .navigationController
                .navigate(PlanteriorRoute.Identification("fixture-success"))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val restoredEntryId = controller.get().navigationController.currentBackStackEntry!!.id

            controller.recreate()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            assertEquals(
                PlanteriorRoute.Identification("fixture-success"),
                controller.get().navigationController.currentBackStackEntry.toPlanteriorRoute(),
            )
            assertEquals(
                restoredEntryId,
                controller.get().navigationController.currentBackStackEntry!!.id,
            )
        } finally {
            controller.destroy()
            setDebugHomeSession(context, "")
        }
    }

    @Test
    fun `cold logged-out notification preserves the exact deleted target for post-login resume`() {
        val controller =
            Robolectric.buildActivity(MainActivity::class.java, wateringIntent()).setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val route =
            controller
                .get()
                .navigationController
                .currentBackStackEntry!!
                .toRoute<PlanteriorRoute.Login>()

        assertEquals(wateringUri, route.returnRoute)
        controller.destroy()
    }

    @Test
    fun `warm notification replaces the stack with logged-out exact return`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        controller.newIntent(wateringIntent())
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val route =
            controller
                .get()
                .navigationController
                .currentBackStackEntry!!
                .toRoute<PlanteriorRoute.Login>()

        assertEquals(wateringUri, route.returnRoute)
        controller.destroy()
    }

    @Test
    fun `warm logged-out weather notification preserves exact target`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        controller.newIntent(notificationIntent(weatherUri))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val route =
            controller
                .get()
                .navigationController
                .currentBackStackEntry!!
                .toRoute<PlanteriorRoute.Login>()
        assertEquals(weatherUri, route.returnRoute)
        controller.destroy()
    }

    @Test
    fun `weather notification received after activity recreation keeps exact logged-out return`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        controller.recreate()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        controller.newIntent(notificationIntent(weatherUri))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val route =
            controller
                .get()
                .navigationController
                .currentBackStackEntry!!
                .toRoute<PlanteriorRoute.Login>()
        assertEquals(weatherUri, route.returnRoute)
        controller.destroy()
    }

    @Test
    fun `notification arriving before navigation is ready is queued and consumed once`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()

        controller.newIntent(wateringIntent())
        assertEquals(1, controller.get().pendingNotificationIntentCount)
        controller.start().resume().visible()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(0, controller.get().pendingNotificationIntentCount)
        assertEquals(wateringUri, controller.loginRoute().returnRoute)
        controller.destroy()
    }

    @Test
    fun `logged-out weather recreation restores one login target without appending it again`() {
        val controller =
            Robolectric.buildActivity(
                    MainActivity::class.java,
                    notificationIntent(weatherUri, "weather-alert-a"),
                )
                .setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val restoredEntryId = controller.get().navigationController.currentBackStackEntry!!.id

        controller.recreate()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(weatherUri, controller.loginRoute().returnRoute)
        assertEquals(
            restoredEntryId,
            controller.get().navigationController.currentBackStackEntry!!.id,
        )
        assertEquals(
            listOf(PlanteriorRoute.Home, PlanteriorRoute.Login(weatherUri)),
            controller.routes(),
        )
        controller.destroy()
    }

    @Test
    fun `watering saved-state process recreation restores deleted target exactly once`() {
        val intent = notificationIntent(wateringUri, "watering-delivery-a")
        val first = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val savedState = Bundle()
        val restoredEntryId = first.get().navigationController.currentBackStackEntry!!.id
        first.saveInstanceState(savedState).pause().stop().destroy()

        val restored =
            Robolectric.buildActivity(MainActivity::class.java, intent)
                .create(savedState)
                .start()
                .resume()
                .visible()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(wateringUri, restored.loginRoute().returnRoute)
        assertEquals(
            restoredEntryId,
            restored.get().navigationController.currentBackStackEntry!!.id,
        )
        assertEquals(
            listOf(PlanteriorRoute.Home, PlanteriorRoute.Login(wateringUri)),
            restored.routes(),
        )
        restored.destroy()
    }

    @Test
    fun `same warm delivery is deduped but new identity for the same weather target applies`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val first = notificationIntent(weatherUri, "weather-alert-a")

        controller.newIntent(first)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(weatherUri, controller.loginRoute().returnRoute)
        val firstEntryId = controller.get().navigationController.currentBackStackEntry!!.id

        controller.newIntent(first)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(
            firstEntryId,
            controller.get().navigationController.currentBackStackEntry!!.id,
        )

        controller.newIntent(notificationIntent(weatherUri, "weather-alert-b"))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(weatherUri, controller.loginRoute().returnRoute)
        assertNotEquals(
            firstEntryId,
            controller.get().navigationController.currentBackStackEntry!!.id,
        )
        controller.destroy()
    }

    @Test
    fun `multiple distinct notification targets apply once and an older delivery stays consumed`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val weather = notificationIntent(weatherUri, "weather-alert-a")
        val watering = notificationIntent(wateringUri, "watering-delivery-a")

        controller.newIntent(weather)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(weatherUri, controller.loginRoute().returnRoute)

        controller.newIntent(watering)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(wateringUri, controller.loginRoute().returnRoute)
        val wateringEntryId = controller.get().navigationController.currentBackStackEntry!!.id

        controller.newIntent(weather)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(wateringUri, controller.loginRoute().returnRoute)
        assertEquals(
            wateringEntryId,
            controller.get().navigationController.currentBackStackEntry!!.id,
        )

        controller.recreate()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(wateringUri, controller.loginRoute().returnRoute)
        assertEquals(
            wateringEntryId,
            controller.get().navigationController.currentBackStackEntry!!.id,
        )
        assertEquals(
            listOf(PlanteriorRoute.Home, PlanteriorRoute.Login(wateringUri)),
            controller.routes(),
        )
        controller.destroy()
    }

    private fun wateringIntent() = notificationIntent(wateringUri, "watering-delivery-a")

    private fun notificationIntent(uri: String, identity: String = uri) =
        Intent(Intent.ACTION_VIEW, uri.toUri())
            .setIdentifier("planterior-notification:$identity")
            .setClassName(
                "com.planterior.helper",
                MainActivity::class.java.name,
            )

    private fun org.robolectric.android.controller.ActivityController<MainActivity>.loginRoute() =
        get().navigationController.currentBackStackEntry!!.toRoute<PlanteriorRoute.Login>()

    private fun org.robolectric.android.controller.ActivityController<MainActivity>.routes() =
        get().navigationController.currentBackStack.value.mapNotNull { it.toPlanteriorRoute() }
}
