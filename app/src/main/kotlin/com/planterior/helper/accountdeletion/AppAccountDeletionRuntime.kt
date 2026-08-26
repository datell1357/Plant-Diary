package com.planterior.helper.accountdeletion

import android.content.Context
import androidx.work.WorkManager
import com.google.firebase.functions.FirebaseFunctions
import com.planterior.helper.analytics.AnalyticsRuntime
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.auth.AuthCoordinator
import com.planterior.helper.feature.auth.AuthReauthenticationResult
import com.planterior.helper.feature.settings.AccountDeletionDependencies
import com.planterior.helper.feature.settings.AccountDeletionReauthenticationResult
import com.planterior.helper.feature.settings.AccountDeletionReauthenticator
import com.planterior.helper.feature.settings.AccountDeletionTerminalCallback
import com.planterior.helper.feature.settings.AnalyticsDeletionGuard
import com.planterior.helper.feature.share.MiniHomeShareImageStore
import com.planterior.helper.feature.weather.WeatherLocationGateway
import com.planterior.helper.notification.LocalNotificationOwnerStateCleaner
import com.planterior.helper.notification.NotificationWorkScheduler
import com.planterior.helper.weather.SharedPreferencesWeatherPermissionCapabilityStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class AppAccountDeletionRuntime(
    context: Context,
    functions: FirebaseFunctions,
    database: PlanteriorDatabase,
    private val coordinator: AuthCoordinator,
    private val analyticsRuntime: AnalyticsRuntime,
) {
    private val applicationContext = context.applicationContext
    private val exitChannel = Channel<TerminalAccountDeletionCleanupCommand>(Channel.BUFFERED)
    private var locationGateway: WeatherLocationGateway? = null
    private val cleanup =
        TerminalAccountDeletionCleanupRuntime(
            SharedPreferencesTerminalAccountDeletionCleanupJournal(applicationContext),
            TerminalAccountDeletionCleanupActions { phase, command ->
                when (phase) {
                    TerminalCleanupPhase.CANCEL_LOCATION -> locationGateway?.cancel()
                    TerminalCleanupPhase.CANCEL_NOTIFICATION_WORK ->
                        NotificationWorkScheduler(WorkManager.getInstance(applicationContext))
                            .cancelTokenRegistration()
                    TerminalCleanupPhase.PURGE_ROOM ->
                        database.terminalAccountDeletionDao().purgeOwner(command.owner.value)
                    TerminalCleanupPhase.CLEAR_ANALYTICS ->
                        analyticsRuntime.clearLocalOwner(command.owner.value)
                    TerminalCleanupPhase.CLEAR_NOTIFICATIONS ->
                        LocalNotificationOwnerStateCleaner(applicationContext).clear()
                    TerminalCleanupPhase.CLEAR_WEATHER ->
                        SharedPreferencesWeatherPermissionCapabilityStore(applicationContext)
                            .clear(command.owner.value)
                    TerminalCleanupPhase.CLEAR_SHARE_CACHE ->
                        MiniHomeShareImageStore(applicationContext).clear()
                    TerminalCleanupPhase.SIGN_OUT_LOCAL ->
                        check(coordinator.completeTerminalAccountDeletion(command.owner.value))
                    TerminalCleanupPhase.EMIT_EXIT -> exitChannel.send(command)
                }
            },
        )
    private val callable = FirebaseAccountDeletionCallable(functions)

    val exits: Flow<TerminalAccountDeletionCleanupCommand> = exitChannel.receiveAsFlow()

    fun attachLocationGateway(gateway: WeatherLocationGateway) {
        locationGateway = gateway
    }

    fun dependencies(ownerUid: String): AccountDeletionDependencies {
        val owner = AccountId(ownerUid)
        return AccountDeletionDependencies(
            repository = FirebaseAccountDeletionRepository(owner, callable),
            reauthenticator =
                AccountDeletionReauthenticator {
                    when (coordinator.reauthenticateCurrent()) {
                        AuthReauthenticationResult.SUCCEEDED ->
                            AccountDeletionReauthenticationResult.SUCCEEDED
                        AuthReauthenticationResult.CANCELLED ->
                            AccountDeletionReauthenticationResult.CANCELLED
                        AuthReauthenticationResult.FAILED ->
                            AccountDeletionReauthenticationResult.FAILED
                    }
                },
            terminalCallback =
                AccountDeletionTerminalCallback { completion ->
                    cleanup.execute(
                        TerminalAccountDeletionCleanupCommand(owner, completion.requestId)
                    )
                },
            analyticsDeletionGuard =
                AnalyticsDeletionGuard { analyticsRuntime.deletionReceived(owner.value) },
        )
    }

    suspend fun retryIncompleteAfterSignedOutStartup() {
        cleanup.retryIncompleteAfterSignedOutStartup()
    }
}
