package com.planterior.helper.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class TerminalNotificationOwnerStateCleanerTest {
    @Test
    fun `terminal cleanup clears endpoint open confirmation and notification identity state locally`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(
                NotificationTokenStore.PREFERENCES,
                NotificationOpenConfirmationStore.PREFERENCES,
                "weather-notification-identities",
            )
            .forEach { preferences ->
                context
                    .getSharedPreferences(preferences, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            }
        val tokenStore = NotificationTokenStore(context)
        tokenStore.updateToken("pending-fcm-token")
        val confirmations = NotificationOpenConfirmationStore(context)
        confirmations.recordTap(
            "planterior://collection/plant/plant-one?deliveryId=123e4567-e89b-12d3-a456-426614174000"
        )
        WeatherNotificationIdentityRegistry(context).platformId("weather-alert")

        LocalNotificationOwnerStateCleaner(context).clear()

        assertNull(tokenStore.pendingToken())
        assertTrue(confirmations.pending().isEmpty())
        assertTrue(
            context
                .getSharedPreferences("weather-notification-identities", Context.MODE_PRIVATE)
                .all
                .isEmpty()
        )
    }
}
