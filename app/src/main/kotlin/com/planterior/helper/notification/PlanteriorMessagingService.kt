package com.planterior.helper.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.planterior.helper.MainActivity
import com.planterior.helper.R
import kotlinx.coroutines.runBlocking

class PlanteriorMessagingService : FirebaseMessagingService() {
    @Deprecated("FCM compatibility callback")
    override fun onNewToken(token: String) = registerToken(token)

    override fun onRegistered(token: String) = registerToken(token)

    private fun registerToken(token: String) {
        NotificationTokenStore(this).updateToken(token)
        NotificationWorkScheduler(WorkManager.getInstance(this)).enqueueTokenRegistration()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val ownerUid = message.data["ownerUid"] ?: return
        val route = message.data["route"] ?: return
        val title = message.data["title"] ?: return
        val body = message.data["body"] ?: return
        runBlocking {
            NotificationAccountTransitionGate.postIfCurrent(
                ownerUid = ownerUid,
                currentOwnerUid = { FirebaseAuth.getInstance().currentUser?.uid },
            ) {
                val weatherRiskType =
                    if (message.data["type"] == "WEATHER_RISK") {
                        message.data["riskType"] ?: return@postIfCurrent false
                    } else {
                        null
                    }
                if (weatherRiskType != null) {
                    val alertId = message.data["alertId"] ?: return@postIfCurrent false
                    val riskId = message.data["riskId"] ?: return@postIfCurrent false
                    val transition =
                        message.data["transition"]?.toIntOrNull() ?: return@postIfCurrent false
                    if (riskId.isBlank() || transition < 1) return@postIfCurrent false
                    val plantName = message.data["plantName"] ?: return@postIfCurrent false
                    if (!isKnownWeatherRiskType(weatherRiskType)) return@postIfCurrent false
                    WeatherNotificationRenderer.post(
                        context = this@PlanteriorMessagingService,
                        route = route,
                        title = weatherNotificationTitle(plantName, weatherRiskType),
                        body = body,
                        alertId = alertId,
                    )
                } else {
                    val notificationId =
                        message.data["dueDate"].orEmpty().hashCode() xor route.hashCode()
                    WateringNotificationRenderer.post(
                        context = this@PlanteriorMessagingService,
                        route = route,
                        title = title,
                        body = body,
                        notificationId = notificationId,
                    )
                }
            }
        }
    }
}

object WateringNotificationRenderer {
    const val CHANNEL_ID = "watering-reminders"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                    CHANNEL_ID,
                    "물 주기 알림",
                    NotificationManager.IMPORTANCE_HIGH,
                )
                .apply { description = "예정일과 다음 날 미완료 물 주기를 알려 드려요." }
        )
    }

    fun post(
        context: Context,
        route: String,
        title: String,
        body: String,
        notificationId: Int,
    ): Boolean {
        val deliveryId = route.toUri().getQueryParameter("deliveryId")
        val deepLinkIdentity = "watering:${deliveryId ?: "$notificationId:$route"}"
        return postNotification(
            context,
            CHANNEL_ID,
            route,
            title,
            body,
            notificationId,
            deepLinkIdentity = deepLinkIdentity,
        )
    }
}

internal fun weatherNotificationTitle(plantName: String, riskType: String): String =
    "$plantName ${weatherRiskTypeLabel(riskType)}"

private fun isKnownWeatherRiskType(riskType: String): Boolean =
    riskType in setOf("HIGH_TEMPERATURE", "LOW_TEMPERATURE", "DRY", "OVERHUMID")

private fun weatherRiskTypeLabel(riskType: String): String =
    when (riskType) {
        "HIGH_TEMPERATURE" -> "고온 주의"
        "LOW_TEMPERATURE" -> "저온 주의"
        "DRY" -> "건조 주의"
        "OVERHUMID" -> "과습 주의"
        else -> "날씨 주의"
    }

object WeatherNotificationRenderer {
    const val CHANNEL_ID = "plant-weather-alerts"

    fun createChannel(context: Context) {
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                        CHANNEL_ID,
                        "날씨 주의 알림",
                        NotificationManager.IMPORTANCE_HIGH,
                    )
                    .apply { description = "등록 식물의 고온·저온·건조·과습 위험을 알려 드려요." }
            )
    }

    fun post(
        context: Context,
        route: String,
        title: String,
        body: String,
        alertId: String,
    ): Boolean {
        val notificationId = WeatherNotificationIdentityRegistry(context).platformId(alertId)
        return postNotification(
            context,
            CHANNEL_ID,
            route,
            title,
            body,
            notificationId,
            deepLinkIdentity = "weather:$alertId",
            notificationTag = "weather:$alertId",
        )
    }
}

internal class WeatherNotificationIdentityRegistry(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun platformId(immutableAlertId: String): Int {
        require(immutableAlertId.matches(VALID_IDENTITY))
        synchronized(lock) {
            val identityKey = "identity.$immutableAlertId"
            if (preferences.contains(identityKey)) {
                return preferences.getInt(identityKey, 0)
            }
            var candidate = immutableAlertId.hashCode()
            while (true) {
                val platformKey = "platform.$candidate"
                val occupant = preferences.getString(platformKey, null)
                if (occupant == null || occupant == immutableAlertId) {
                    preferences.edit(commit = true) {
                        putInt(identityKey, candidate)
                        putString(platformKey, immutableAlertId)
                    }
                    return candidate
                }
                candidate += 1
            }
        }
    }

    private companion object {
        const val PREFERENCES = "weather-notification-identities"
        val VALID_IDENTITY = Regex("^[A-Za-z0-9_-]{1,128}$")
        val lock = Any()
    }
}

private fun postNotification(
    context: Context,
    channelId: String,
    route: String,
    title: String,
    body: String,
    notificationId: Int,
    intentAction: String = Intent.ACTION_VIEW,
    deepLinkIdentity: String,
    notificationTag: String? = null,
): Boolean {
    if (
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    val intent =
        Intent(context, MainActivity::class.java).apply {
            action = intentAction
            data = route.toUri()
            identifier = "planterior-notification:$deepLinkIdentity"
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    val pendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val notification =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_watering)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
    val manager = NotificationManagerCompat.from(context)
    if (notificationTag == null) {
        manager.notify(notificationId, notification)
    } else {
        manager.notify(notificationTag, notificationId, notification)
    }
    return true
}
