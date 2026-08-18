package com.planterior.helper.auth

import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.room.Room
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.planterior.helper.BuildConfig
import com.planterior.helper.core.data.FirebaseRemoteMutationGateway
import com.planterior.helper.core.data.OfflineFirstSyncRepository
import com.planterior.helper.core.database.MIGRATION_1_2
import com.planterior.helper.core.database.MIGRATION_2_3
import com.planterior.helper.core.database.MIGRATION_3_4
import com.planterior.helper.core.database.MIGRATION_4_5
import com.planterior.helper.core.database.MIGRATION_5_6
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.feature.auth.AccountProfileStore
import com.planterior.helper.feature.auth.AccountSessionCache
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
import com.planterior.helper.feature.collection.CollectionWateringPreparationSource
import com.planterior.helper.feature.collection.FirebaseCollectionRemoteDataSource
import com.planterior.helper.feature.collection.FirebaseCollectionRepository
import com.planterior.helper.feature.collection.FirebasePlantThumbnailLoader
import com.planterior.helper.feature.collection.PlaceholderPlantThumbnailLoader
import com.planterior.helper.feature.collection.PlantThumbnailLoader
import com.planterior.helper.feature.home.HomeMiniHomePreview
import com.planterior.helper.feature.home.HomePlantCare
import com.planterior.helper.feature.home.HomeRepository
import com.planterior.helper.feature.home.HomeSession
import com.planterior.helper.feature.home.HomeSyncStatus
import com.planterior.helper.feature.home.HomeWeather
import com.planterior.helper.feature.registration.FirebaseRegistrationRemoteDataSource
import com.planterior.helper.feature.registration.FirebaseRegistrationRepository
import com.planterior.helper.feature.registration.RegistrationRepository
import com.planterior.helper.feature.watering.FirebaseWateringRemoteDataSource
import com.planterior.helper.feature.watering.OutboxWateringRepository
import com.planterior.helper.feature.watering.WateringRepository
import com.planterior.helper.home.CachedHomeRepository
import com.planterior.helper.home.debugHomeSessions
import com.planterior.helper.home.debugHomeWeatherSource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

class AuthRuntime
private constructor(
    val coordinator: AuthCoordinator,
    private val apple: AppleWebAuthProvider?,
    val hasSession: Boolean,
    /** 홈 대시보드가 읽는 저장소. 인증 상태와 같은 수명을 가진다. */
    val homeRepository: HomeRepository,
    val registrationRepository: RegistrationRepository?,
    val collectionRepository: CollectionRepository?,
    val wateringRepository: WateringRepository?,
    val collectionThumbnailLoader: PlantThumbnailLoader,
) {
    suspend fun handleAppleCallback(uri: URI): Boolean = apple?.handleCallback(uri) ?: false

    companion object {
        /** 에뮬레이터 연결을 이미 지정했는지. Firebase SDK가 재설정을 허용하지 않아 직접 추적한다. */
        private val emulatorsConnected = java.util.concurrent.atomic.AtomicBoolean(false)

        fun create(activity: ComponentActivity): AuthRuntime {
            prepareDebugAuth(activity)
            if (
                BuildConfig.FIREBASE_PROJECT_ID.isBlank() ||
                    BuildConfig.FIREBASE_APP_ID.isBlank() ||
                    BuildConfig.FIREBASE_API_KEY.isBlank()
            ) {
                return unavailable()
            }
            val app =
                FirebaseApp.getApps(activity).firstOrNull()
                    ?: FirebaseApp.initializeApp(
                        activity,
                        FirebaseOptions.Builder()
                            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                            .setApiKey(BuildConfig.FIREBASE_API_KEY)
                            .build(),
                    )
            val auth = FirebaseAuth.getInstance(app)
            val firestore = FirebaseFirestore.getInstance(app)
            val functions = FirebaseFunctions.getInstance(app)
            val storage = FirebaseStorage.getInstance(app)
            // 에뮬레이터 연결은 프로세스당 한 번만 지정할 수 있다. Activity가 다시 만들어질 때마다 호출하면 화면 회전이나
            // 프로세스 복원에서 그대로 크래시난다.
            if (BuildConfig.DEBUG && emulatorsConnected.compareAndSet(false, true)) {
                auth.useEmulator("10.0.2.2", 9099)
                firestore.useEmulator("10.0.2.2", 8080)
                functions.useEmulator("10.0.2.2", 5001)
                storage.useEmulator("10.0.2.2", 9199)
            }
            val database =
                Room.databaseBuilder(activity, PlanteriorDatabase::class.java, "planterior.db")
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                    )
                    .build()
            val mutationGateway = FirebaseRemoteMutationGateway(functions)
            val repository = OfflineFirstSyncRepository(database, mutationGateway)
            val registrationRepository =
                FirebaseRegistrationRepository(
                    database,
                    FirebaseRegistrationRemoteDataSource(
                        auth,
                        firestore,
                        storage,
                    ) { photo ->
                        val maximum = 20 * 1024 * 1024
                        activity.contentResolver.openInputStream(photo.privateUri.toUri()).use {
                            input ->
                            val bytes = requireNotNull(input).readBounded(maximum)
                            require(bytes.isNotEmpty() && bytes.size <= maximum)
                            bytes
                        }
                    },
                    mutationGateway,
                )
            val collectionRepository =
                FirebaseCollectionRepository(
                    database,
                    FirebaseCollectionRemoteDataSource(auth, firestore),
                    mutationGateway,
                )
            val wateringRepository =
                OutboxWateringRepository(
                    database,
                    CollectionWateringPreparationSource(collectionRepository),
                    FirebaseWateringRemoteDataSource(auth, firestore),
                    mutationGateway,
                )
            val apple =
                AppleWebAuthProvider(
                    FirebaseAppleCallable(functions),
                    ActivityWebAuthorizationLauncher(activity),
                )
            val identity = FirebaseIdentityAdapter(auth)
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
                    FirestoreAccountProfileStore(firestore),
                    RoomAccountSessionCache(repository),
                    FirestoreAccountSynchronizer(
                        debugAccountSyncRemote(
                            activity,
                            FirestoreAccountSyncRemote(firestore),
                        ),
                        database,
                        outbox = repository,
                    ),
                )
            return AuthRuntime(
                coordinator,
                apple,
                identity.current() != null,
                // 데이터는 항상 실제 캐시에서 읽는다. 디버그 QA는 세션과 날씨만 고정할 수 있다.
                debugHomeSessions(activity).let { forcedSessions ->
                    var forcedUid: String? = null
                    val repository =
                        CachedHomeRepository(
                            database,
                            coordinator.state,
                            debugHomeWeatherSource(activity),
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
                registrationRepository,
                collectionRepository,
                wateringRepository,
                FirebasePlantThumbnailLoader(storage),
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
                // 구성이 없으면 로그인할 수 없으므로 홈은 항상 로그아웃 상태로 머무른다.
                UnavailableHomeRepository,
                null,
                null,
                null,
                PlaceholderPlantThumbnailLoader,
            )
        }
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

private fun InputStream.readBounded(maximum: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maximum) { "Representative photo is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private object UnavailableHomeRepository : HomeRepository {
    override fun sessions(): kotlinx.coroutines.flow.Flow<HomeSession> =
        kotlinx.coroutines.flow.flowOf(HomeSession.SignedOut)

    override suspend fun plantCare(): Result<List<HomePlantCare>> = Result.success(emptyList())

    override suspend fun weather(): Result<HomeWeather?> = Result.success(null)

    override suspend fun miniHomePreview(): HomeMiniHomePreview? = null

    override suspend fun syncStatus(): HomeSyncStatus = HomeSyncStatus.Stale(null)
}
