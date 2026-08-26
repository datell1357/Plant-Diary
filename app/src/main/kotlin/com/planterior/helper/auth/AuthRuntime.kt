package com.planterior.helper.auth

import androidx.activity.ComponentActivity
import com.planterior.helper.BuildConfig
import com.planterior.helper.PlanteriorApplication
import com.planterior.helper.accountdeletion.AppAccountDeletionRuntime
import com.planterior.helper.analytics.AnalyticsRuntime
import com.planterior.helper.feature.auth.AccountProfileStore
import com.planterior.helper.feature.auth.AccountSessionCache
import com.planterior.helper.feature.auth.AccountSyncWriteGate
import com.planterior.helper.feature.auth.AccountSynchronizer
import com.planterior.helper.feature.auth.ActivityWebAuthorizationLauncher
import com.planterior.helper.feature.auth.AppleWebAuthProvider
import com.planterior.helper.feature.auth.AuthAccount
import com.planterior.helper.feature.auth.AuthCoordinator
import com.planterior.helper.feature.auth.AuthFailure
import com.planterior.helper.feature.auth.AuthGatewayException
import com.planterior.helper.feature.auth.AuthProvider
import com.planterior.helper.feature.auth.AuthProviderAdapter
import com.planterior.helper.feature.auth.AuthUiState
import com.planterior.helper.feature.auth.FirebaseAppleCallable
import com.planterior.helper.feature.auth.FirebaseIdentityAdapter
import com.planterior.helper.feature.auth.FirestoreAccountProfileStore
import com.planterior.helper.feature.auth.FirestoreAccountSyncRemote
import com.planterior.helper.feature.auth.FirestoreAccountSynchronizer
import com.planterior.helper.feature.auth.GoogleCredentialProvider
import com.planterior.helper.feature.auth.ProviderOutcome
import com.planterior.helper.feature.auth.RoomAccountSessionCache
import com.planterior.helper.feature.auth.SyncSummary
import com.planterior.helper.feature.auth.debugAccountSyncRemote
import com.planterior.helper.feature.auth.debugAuthProvider
import com.planterior.helper.feature.auth.prepareDebugAuth
import com.planterior.helper.feature.collection.CollectionRepository
import com.planterior.helper.feature.collection.PlaceholderPlantThumbnailLoader
import com.planterior.helper.feature.collection.PlantThumbnailLoader
import com.planterior.helper.feature.home.HomeMiniHomePreview
import com.planterior.helper.feature.home.HomePlantCare
import com.planterior.helper.feature.home.HomeRepository
import com.planterior.helper.feature.home.HomeSession
import com.planterior.helper.feature.home.HomeSyncStatus
import com.planterior.helper.feature.home.HomeWeather
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.feature.registration.RegistrationRepository
import com.planterior.helper.feature.share.MiniHomeShareRepository
import com.planterior.helper.feature.shop.CatalogMediaLoader
import com.planterior.helper.feature.shop.InventoryRepository
import com.planterior.helper.feature.shop.PlaceholderCatalogMediaLoader
import com.planterior.helper.feature.watering.WateringNotificationSettingsRepository
import com.planterior.helper.feature.watering.WateringRepository
import com.planterior.helper.feature.weather.WeatherPermissionCapabilityStore
import com.planterior.helper.feature.weather.WeatherRepository
import com.planterior.helper.home.CachedHomeRepository
import com.planterior.helper.home.debugHomeAccountUid
import com.planterior.helper.home.debugHomeSessions
import com.planterior.helper.home.debugHomeWeatherSource
import com.planterior.helper.minihome.debugMiniHomeRepository
import com.planterior.helper.notification.FirebaseNotificationEndpointGateway
import com.planterior.helper.notification.NotificationAccountTransitionGate
import com.planterior.helper.notification.NotificationEndpointGateway
import com.planterior.helper.notification.NotificationTokenStore
import com.planterior.helper.notification.NotificationWorkScheduler
import com.planterior.helper.notification.SystemNotificationOwnerDataCanceller
import com.planterior.helper.registration.debugRegistrationRepository
import com.planterior.helper.weather.WeatherHomeSource
import java.net.URI

class AuthRuntime
private constructor(
    val coordinator: AuthCoordinator,
    private val apple: AppleWebAuthProvider?,
    val hasSession: Boolean,
    val forcedHomeSession: Boolean,
    val forcedHomeAccountUid: String?,
    private val closeAction: () -> Unit,
    /** 홈 대시보드가 읽는 저장소. 인증 상태와 같은 수명을 가진다. */
    val homeRepository: HomeRepository,
    val registrationRepository: RegistrationRepository?,
    val collectionRepository: CollectionRepository?,
    val miniHomeRepository: MiniHomeRepository?,
    val miniHomeShareRepository: MiniHomeShareRepository?,
    val inventoryRepository: InventoryRepository?,
    val wateringRepository: WateringRepository?,
    val wateringNotificationSettingsRepository: WateringNotificationSettingsRepository?,
    val weatherRepository: WeatherRepository?,
    val weatherPermissionCapabilities: WeatherPermissionCapabilityStore?,
    val collectionThumbnailLoader: PlantThumbnailLoader,
    val catalogMediaLoader: CatalogMediaLoader,
    val analyticsRuntime: AnalyticsRuntime?,
    val accountDeletionRuntime: AppAccountDeletionRuntime?,
) {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun handleAppleCallback(uri: URI): Boolean = apple?.handleCallback(uri) ?: false

    fun close() {
        if (closed.compareAndSet(false, true)) closeAction()
    }

    companion object {
        fun create(activity: ComponentActivity): AuthRuntime {
            prepareDebugAuth(activity)
            val application = activity.application
            val shared =
                if (application is PlanteriorApplication) application.repositoryRuntimeOrNull()
                else AuthRepositoryRuntime.create(activity.applicationContext)
            if (shared == null) return unavailable()
            val closesSharedRuntime = application !is PlanteriorApplication
            val apple =
                AppleWebAuthProvider(
                    FirebaseAppleCallable(shared.functions),
                    ActivityWebAuthorizationLauncher(activity),
                )
            val identity = FirebaseIdentityAdapter(shared.auth)
            val accountSyncWriteGate = AccountSyncWriteGate()
            val coordinator =
                AuthCoordinator(
                    mapOf(
                        AuthProvider.GOOGLE to
                            debugAuthProvider(
                                activity,
                                GoogleCredentialProvider(
                                    activity,
                                    BuildConfig.GOOGLE_WEB_CLIENT_ID,
                                ),
                            ),
                        AuthProvider.APPLE to debugAuthProvider(activity, apple),
                    ),
                    identity,
                    FirestoreAccountProfileStore(shared.functions),
                    RoomAccountSessionCache(shared.syncRepository),
                    FirestoreAccountSynchronizer(
                        debugAccountSyncRemote(
                            activity,
                            FirestoreAccountSyncRemote(shared.firestore, shared.functions),
                        ),
                        shared.database,
                        outbox = shared.syncRepository,
                        isCurrentOwner = { shared.auth.currentUser?.uid == it },
                        writeGate = accountSyncWriteGate,
                    ),
                    beforeSignOut =
                        notificationEndpointRevocationAction(
                            NotificationTokenStore(activity.applicationContext),
                            FirebaseNotificationEndpointGateway(shared.functions),
                        ) {
                            NotificationWorkScheduler(
                                    androidx.work.WorkManager.getInstance(
                                        activity.applicationContext
                                    )
                                )
                                .cancelTokenRegistration()
                        },
                    beforeAuthRemoval = shared.analyticsRuntime::clearLocalOwner,
                    authTransition = { ownerUid, action ->
                        NotificationAccountTransitionGate.transition(
                            cancelFormerOwnerNotifications = {
                                if (ownerUid != null) {
                                    SystemNotificationOwnerDataCanceller(activity)
                                        .cancelFormerOwnerNotifications()
                                }
                            },
                            action = action,
                        )
                    },
                    productEventRecorder = shared.analyticsRuntime.recorder,
                    accountSyncWriteGate = accountSyncWriteGate,
                )
            val forcedSessions = debugHomeSessions(activity)
            return AuthRuntime(
                coordinator,
                apple,
                identity.current() != null,
                forcedSessions != null,
                debugHomeAccountUid(activity),
                if (closesSharedRuntime) shared::close else ({}),
                // 데이터는 항상 실제 캐시에서 읽는다. 디버그 QA는 세션과 날씨만 고정할 수 있다.
                run {
                    var forcedUid: String? = null
                    val repository =
                        CachedHomeRepository(
                            shared.database,
                            coordinator.state,
                            debugHomeWeatherSource(
                                activity.applicationContext,
                                WeatherHomeSource(shared.auth, shared.weatherRepository),
                            ),
                            activeAccountUid = {
                                forcedUid
                                    ?: (coordinator.state.value as? AuthUiState.Authenticated)
                                        ?.account
                                        ?.uid
                            },
                        )
                    if (forcedSessions == null) repository
                    else
                        ForcedSessionHomeRepository(repository, forcedSessions) { uid ->
                            forcedUid = uid
                        }
                },
                debugRegistrationRepository(
                    activity.applicationContext,
                    shared.registrationRepository,
                ),
                shared.collectionRepository,
                debugMiniHomeRepository(
                    activity.applicationContext,
                    shared.database,
                    shared.miniHomeRepository,
                ),
                shared.miniHomeShareRepository,
                shared.inventoryRepository,
                shared.wateringRepository,
                shared.wateringNotificationSettingsRepository,
                shared.weatherRepository,
                shared.weatherPermissionCapabilities,
                shared.collectionThumbnailLoader,
                shared.catalogMediaLoader,
                shared.analyticsRuntime,
                AppAccountDeletionRuntime(
                    activity.applicationContext,
                    shared.functions,
                    shared.database,
                    coordinator,
                    shared.analyticsRuntime,
                ),
            )
        }

        private fun unavailable(): AuthRuntime {
            val unavailable =
                object : AuthProviderAdapter {
                    override val provider = AuthProvider.GOOGLE

                    override suspend fun acquire(requestId: Long) =
                        ProviderOutcome.Failed(AuthFailure.ConfigurationMissing)

                    override fun cancel(requestId: Long) = Unit
                }
            val appleUnavailable =
                object : AuthProviderAdapter {
                    override val provider = AuthProvider.APPLE

                    override suspend fun acquire(requestId: Long) =
                        ProviderOutcome.Failed(AuthFailure.ConfigurationMissing)

                    override fun cancel(requestId: Long) = Unit
                }
            val identity =
                object : com.planterior.helper.feature.auth.FirebaseIdentityGateway {
                    override fun current(): AuthAccount? = null

                    override suspend fun signIn(
                        proof: com.planterior.helper.feature.auth.ProviderProof
                    ): AuthAccount = throw AuthGatewayException(AuthFailure.ConfigurationMissing)

                    override suspend fun reauthenticate(
                        proof: com.planterior.helper.feature.auth.ProviderProof
                    ): AuthAccount = throw AuthGatewayException(AuthFailure.ConfigurationMissing)

                    override suspend fun link(
                        proof: com.planterior.helper.feature.auth.ProviderProof
                    ): AuthAccount = throw AuthGatewayException(AuthFailure.ConfigurationMissing)

                    override suspend fun signOut() = Unit
                }
            val coordinator =
                AuthCoordinator(
                    mapOf(
                        AuthProvider.GOOGLE to unavailable,
                        AuthProvider.APPLE to appleUnavailable,
                    ),
                    identity,
                    AccountProfileStore {},
                    object : AccountSessionCache {
                        override suspend fun clearVisible(accountUid: String?) = Unit

                        override fun activate(accountUid: String?) = Unit
                    },
                    AccountSynchronizer { SyncSummary.EMPTY },
                )
            return AuthRuntime(
                coordinator,
                null,
                false,
                false,
                null,
                {},
                // 구성이 없으면 로그인할 수 없으므로 홈은 항상 로그아웃 상태로 머무른다.
                UnavailableHomeRepository,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                PlaceholderPlantThumbnailLoader,
                PlaceholderCatalogMediaLoader,
                null,
                null,
            )
        }
    }
}

internal fun notificationEndpointRevocationAction(
    tokenStore: NotificationTokenStore,
    gateway: NotificationEndpointGateway,
    cancelTokenRegistration: suspend () -> Unit,
): suspend (String) -> Unit = { uid ->
    cancelTokenRegistration()
    tokenStore.unresolvedRegistrationFor(uid)?.let { registration ->
        gateway.register(registration)
        tokenStore.markRegistered(registration)
    }
    tokenStore.beginLogoutUnregistration(uid)?.let { unregistration ->
        val result = gateway.unregister(unregistration)
        tokenStore.markUnregistered(unregistration, result)
    }
}

/**
 * Firebase 구성이 없을 때 쓰는 홈 저장소이다.
 *
 * 샘플 식물을 지어내지 않고 상태만 정직하게 돌려준다.
 */
/**
 * 세션만 고정하고 나머지는 실제 저장소에 그대로 위임한다.
 *
 * 디버그 계측 테스트가 실제 계정을 만들지 않고도 진짜 캐시 데이터를 검증할 수 있게 한다.
 */
private class ForcedSessionHomeRepository(
    private val delegate: HomeRepository,
    private val forcedSessions: kotlinx.coroutines.flow.Flow<HomeSession>,
    private val onSession: (String?) -> Unit,
) : HomeRepository by delegate {
    override fun sessions(): kotlinx.coroutines.flow.Flow<HomeSession> =
        kotlinx.coroutines.flow.flow {
            forcedSessions.collect { session ->
                // 조회대상 계정을 먼저 바꿀 뒤에 상태를 흘려야 뒤따르는 조회가 같은 계정을 본다.
                onSession((session as? HomeSession.SignedIn)?.accountUid)
                emit(session)
            }
        }
}

private object UnavailableHomeRepository : HomeRepository {
    override fun sessions(): kotlinx.coroutines.flow.Flow<HomeSession> =
        kotlinx.coroutines.flow.flowOf(HomeSession.SignedOut)

    override suspend fun plantCare(): Result<List<HomePlantCare>> = Result.success(emptyList())

    override suspend fun weather(): Result<HomeWeather?> = Result.success(null)

    override suspend fun miniHomePreview(): HomeMiniHomePreview? = null

    override suspend fun syncStatus(): HomeSyncStatus = HomeSyncStatus.Stale(null)
}
