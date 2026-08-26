package com.planterior.helper

import android.app.Application
import androidx.work.Configuration
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.planterior.helper.analytics.PlanteriorWorkerFactory
import com.planterior.helper.auth.AuthRepositoryRuntime
import com.planterior.helper.notification.FirebaseNotificationEndpointGateway
import com.planterior.helper.notification.NotificationTokenStore
import com.planterior.helper.notification.NotificationWorkerFactory
import com.planterior.helper.notification.WateringNotificationRenderer
import com.planterior.helper.notification.WeatherNotificationRenderer

class PlanteriorApplication : Application(), Configuration.Provider {
    private val firebaseApp by lazy { FirebaseRuntime.initialize(this) }
    private val tokenStore by lazy { NotificationTokenStore(this) }
    private val repositoryRuntimes = ApplicationRepositoryRuntimeStore {
        checkNotNull(AuthRepositoryRuntime.create(this)) {
            "Firebase repository runtime is unavailable"
        }
    }

    internal fun repositoryRuntimeOrNull(): AuthRepositoryRuntime? {
        if (firebaseApp == null) return null
        return repositoryRuntimes.acquire()
    }

    internal fun repositoryRuntimeSnapshot(): ApplicationRuntimeSnapshot =
        repositoryRuntimes.snapshot()

    internal fun shutdownRepositoryRuntime(): ApplicationRuntimeShutdownReceipt =
        repositoryRuntimes.shutdown()

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .apply {
                    firebaseApp?.let { app ->
                        setWorkerFactory(
                            PlanteriorWorkerFactory(
                                NotificationWorkerFactory(
                                    tokenStore,
                                    FirebaseNotificationEndpointGateway(
                                        FirebaseFunctions.getInstance(app)
                                    ),
                                ) {
                                    FirebaseAuth.getInstance(app).currentUser?.uid
                                }
                            ) {
                                repositoryRuntimeOrNull()?.analyticsRuntime
                            }
                        )
                    }
                }
                .build()

    override fun onCreate() {
        super.onCreate()
        WateringNotificationRenderer.createChannel(this)
        WeatherNotificationRenderer.createChannel(this)
        firebaseApp?.let { app ->
            AppCheckProviderInstaller.install(app)
            FirebaseMessaging.getInstance().register()
        }
    }

    override fun onTerminate() {
        shutdownRepositoryRuntime()
        super.onTerminate()
    }
}
