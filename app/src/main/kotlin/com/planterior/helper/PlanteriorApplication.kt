package com.planterior.helper

import android.app.Application
import androidx.work.Configuration
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.planterior.helper.notification.FirebaseNotificationEndpointGateway
import com.planterior.helper.notification.NotificationTokenStore
import com.planterior.helper.notification.NotificationWorkerFactory
import com.planterior.helper.notification.WateringNotificationRenderer

class PlanteriorApplication : Application(), Configuration.Provider {
    private val firebaseApp by lazy { FirebaseRuntime.initialize(this) }
    private val tokenStore by lazy { NotificationTokenStore(this) }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .apply {
                    firebaseApp?.let { app ->
                        setWorkerFactory(
                            NotificationWorkerFactory(
                                tokenStore,
                                FirebaseNotificationEndpointGateway(
                                    FirebaseFunctions.getInstance(app)
                                ),
                            ) {
                                FirebaseAuth.getInstance(app).currentUser?.uid
                            }
                        )
                    }
                }
                .build()

    override fun onCreate() {
        super.onCreate()
        WateringNotificationRenderer.createChannel(this)
        firebaseApp?.let { app ->
            AppCheckProviderInstaller.install(app)
            FirebaseMessaging.getInstance().register()
        }
    }
}
