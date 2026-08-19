package com.planterior.helper.notification

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeatherNotificationRendererTest {
    @Test
    fun `visible weather titles localize every risk type with plant name`() {
        assertEquals("몬스테라 고온 주의", weatherNotificationTitle("몬스테라", "HIGH_TEMPERATURE"))
        assertEquals("몬스테라 저온 주의", weatherNotificationTitle("몬스테라", "LOW_TEMPERATURE"))
        assertEquals("몬스테라 건조 주의", weatherNotificationTitle("몬스테라", "DRY"))
        assertEquals("몬스테라 과습 주의", weatherNotificationTitle("몬스테라", "OVERHUMID"))
    }

    @Test
    fun `renderer keeps all simultaneous localized risk notifications visible`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WeatherNotificationRenderer.createChannel(context)
        val types = listOf("HIGH_TEMPERATURE", "LOW_TEMPERATURE", "DRY", "OVERHUMID")

        types.forEachIndexed { index, type ->
            WeatherNotificationRenderer.post(
                context,
                "planterior://weather/plant/plant-a",
                weatherNotificationTitle("몬스테라", type),
                "행동 안내 $index",
                "alert-$index",
            )
        }

        val titles =
            shadowOf(context.getSystemService(NotificationManager::class.java))
                .allNotifications
                .map { it.extras.getString("android.title") }
                .toSet()
        assertEquals(
            setOf("몬스테라 고온 주의", "몬스테라 저온 주의", "몬스테라 건조 주의", "몬스테라 과습 주의"),
            titles,
        )
    }

    @Test
    fun `known hash collision allocates distinct stable ids and pending intents across restart`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context
            .getSharedPreferences(
                "weather-notification-identities",
                android.content.Context.MODE_PRIVATE,
            )
            .edit()
            .clear()
            .commit()
        assertEquals("Aa".hashCode(), "BB".hashCode())

        val registry = WeatherNotificationIdentityRegistry(context)
        val first = registry.platformId("Aa")
        val second = registry.platformId("BB")
        val recreated = WeatherNotificationIdentityRegistry(context)

        assertNotEquals(first, second)
        assertEquals(first, recreated.platformId("Aa"))
        assertEquals(second, recreated.platformId("BB"))

        shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WeatherNotificationRenderer.createChannel(context)
        WeatherNotificationRenderer.post(
            context,
            "planterior://weather/plant/plant-a",
            "몬스테라 고온 주의",
            "첫 번째",
            "Aa",
        )
        WeatherNotificationRenderer.post(
            context,
            "planterior://weather/plant/plant-a",
            "몬스테라 건조 주의",
            "두 번째",
            "BB",
        )
        val notifications =
            shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals(2, notifications.size)
        assertEquals(
            setOf(Intent.ACTION_VIEW),
            notifications.map { shadowOf(it.contentIntent).savedIntent.action }.toSet(),
        )
        assertEquals(
            setOf("planterior://weather/plant/plant-a"),
            notifications.map { shadowOf(it.contentIntent).savedIntent.dataString }.toSet(),
        )
    }

    @Test
    fun `weather alert uses its own channel and exact deep link`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WeatherNotificationRenderer.createChannel(context)
        assertNotNull(
            context
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(WeatherNotificationRenderer.CHANNEL_ID)
        )

        val posted =
            WeatherNotificationRenderer.post(
                context,
                "planterior://weather/plant/plant-a",
                "몬스테라 날씨 주의",
                "직사광선을 피해 옮겨 주세요.",
                "alert-deep-link",
            )
        assertEquals(true, posted)
        val notification =
            shadowOf(context.getSystemService(NotificationManager::class.java))
                .allNotifications
                .single()
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("planterior://weather/plant/plant-a", intent.dataString)
        assertEquals("planterior-notification:weather:alert-deep-link", intent.identifier)
    }
}
