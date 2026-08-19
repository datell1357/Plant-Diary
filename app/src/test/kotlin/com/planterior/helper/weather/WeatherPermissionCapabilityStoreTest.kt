package com.planterior.helper.weather

import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.feature.weather.WeatherPermissionCapabilityState
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeatherPermissionCapabilityStoreTest {
    @Test
    fun `capability survives store recreation and remains partitioned by account`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pending =
            WeatherPermissionCapabilityState(
                desiredGranted = false,
                acknowledgedGranted = true,
                revocationPending = true,
                commandGeneration = 7,
                osPermissionGranted = false,
            )
        SharedPreferencesWeatherPermissionCapabilityStore(context).write("account-a", pending)

        val recreated = SharedPreferencesWeatherPermissionCapabilityStore(context)

        assertEquals(pending, recreated.read("account-a"))
        assertNull(recreated.read("account-b"))
        val acknowledged = pending.copy(acknowledgedGranted = false, revocationPending = false)
        recreated.write("account-a", acknowledged)
        assertEquals(
            acknowledged,
            SharedPreferencesWeatherPermissionCapabilityStore(context).read("account-a"),
        )
    }

    @Test
    fun `legacy combined capability migrates without inventing os permission state`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val accountId = "legacy-account"
        val digest = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray())
        val key = "capability." + digest.joinToString("") { byte -> "%02x".format(byte) }
        context
            .getSharedPreferences(
                "weather-permission-capabilities",
                android.content.Context.MODE_PRIVATE,
            )
            .edit()
            .putBoolean(key, false)
            .commit()

        assertEquals(
            WeatherPermissionCapabilityState(
                desiredGranted = false,
                acknowledgedGranted = false,
                revocationPending = false,
                osPermissionGranted = null,
            ),
            SharedPreferencesWeatherPermissionCapabilityStore(context).read(accountId),
        )
    }
}
