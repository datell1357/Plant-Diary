package com.planterior.helper.weather

import android.content.Context
import androidx.core.content.edit
import com.planterior.helper.feature.weather.WeatherPermissionCapabilityState
import com.planterior.helper.feature.weather.WeatherPermissionCapabilityStore
import java.security.MessageDigest

class SharedPreferencesWeatherPermissionCapabilityStore(context: Context) :
    WeatherPermissionCapabilityStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(accountId: String): WeatherPermissionCapabilityState? {
        val prefix = keyPrefix(accountId)
        val desiredKey = "$prefix.desired"
        if (preferences.contains(desiredKey)) {
            val acknowledgedKey = "$prefix.acknowledged"
            return WeatherPermissionCapabilityState(
                desiredGranted = preferences.getBoolean(desiredKey, false),
                acknowledgedGranted =
                    if (preferences.contains(acknowledgedKey)) {
                        preferences.getBoolean(acknowledgedKey, false)
                    } else {
                        null
                    },
                revocationPending = preferences.getBoolean("$prefix.pending", false),
                commandGeneration = preferences.getLong("$prefix.generation", 0),
                osPermissionGranted =
                    if (preferences.contains("$prefix.os-granted")) {
                        preferences.getBoolean("$prefix.os-granted", false)
                    } else {
                        null
                    },
            )
        }
        val legacyKey = prefix
        if (!preferences.contains(legacyKey)) return null
        val granted = preferences.getBoolean(legacyKey, false)
        return WeatherPermissionCapabilityState(
            desiredGranted = granted,
            acknowledgedGranted = granted,
            revocationPending = false,
        )
    }

    override fun write(accountId: String, state: WeatherPermissionCapabilityState) {
        val prefix = keyPrefix(accountId)
        preferences.edit(commit = true) {
            putBoolean("$prefix.desired", state.desiredGranted)
            val acknowledged = state.acknowledgedGranted
            if (acknowledged == null) {
                remove("$prefix.acknowledged")
            } else {
                putBoolean("$prefix.acknowledged", acknowledged)
            }
            putBoolean("$prefix.pending", state.revocationPending)
            putLong("$prefix.generation", state.commandGeneration)
            val osPermissionGranted = state.osPermissionGranted
            if (osPermissionGranted == null) {
                remove("$prefix.os-granted")
            } else {
                putBoolean("$prefix.os-granted", osPermissionGranted)
            }
            remove(prefix)
        }
    }

    fun clear(accountId: String) {
        val prefix = keyPrefix(accountId)
        preferences.edit(commit = true) {
            remove(prefix)
            remove("$prefix.desired")
            remove("$prefix.acknowledged")
            remove("$prefix.pending")
            remove("$prefix.generation")
            remove("$prefix.os-granted")
        }
    }

    private fun keyPrefix(accountId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray())
        return "capability." + digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val PREFERENCES = "weather-permission-capabilities"
    }
}
