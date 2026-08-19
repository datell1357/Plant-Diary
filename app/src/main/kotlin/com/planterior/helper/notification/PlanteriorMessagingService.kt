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
                WateringNotificationRenderer.post(
                    context = this@PlanteriorMessagingService,
                    route = route,
                    title = title,
                    body = body,
                    notificationId =
                        message.data["dueDate"].orEmpty().hashCode() xor route.hashCode(),
                )
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
                action = Intent.ACTION_VIEW
                data = route.toUri()
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
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_watering)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        return true
    }
}
