package com.planterior.helper.auth

import androidx.activity.ComponentActivity
import androidx.room.Room
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.planterior.helper.BuildConfig
import com.planterior.helper.core.data.OfflineFirstSyncRepository
import com.planterior.helper.core.data.RemoteMutationCommand
import com.planterior.helper.core.data.RemoteMutationGateway
import com.planterior.helper.core.data.RemoteMutationResult
import com.planterior.helper.core.database.MIGRATION_1_2
import com.planterior.helper.core.database.MIGRATION_2_3
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
import java.net.URI

class AuthRuntime
private constructor(
    val coordinator: AuthCoordinator,
    private val apple: AppleWebAuthProvider?,
    val hasSession: Boolean,
) {
    suspend fun handleAppleCallback(uri: URI): Boolean = apple?.handleCallback(uri) ?: false

    companion object {
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
            if (BuildConfig.DEBUG) {
                auth.useEmulator("10.0.2.2", 9099)
                firestore.useEmulator("10.0.2.2", 8080)
                functions.useEmulator("10.0.2.2", 5001)
            }
            val database =
                Room.databaseBuilder(activity, PlanteriorDatabase::class.java, "planterior.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
            val repository = OfflineFirstSyncRepository(database, OfflineGateway)
            val apple =
                AppleWebAuthProvider(
                    FirebaseAppleCallable(functions),
                    ActivityWebAuthorizationLauncher(activity),
                )
            val identity = FirebaseIdentityAdapter(auth)
            return AuthRuntime(
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
                    ),
                ),
                apple,
                identity.current() != null,
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
            return AuthRuntime(coordinator, null, false)
        }
    }
}

private object OfflineGateway : RemoteMutationGateway {
    override suspend fun apply(command: RemoteMutationCommand): RemoteMutationResult =
        RemoteMutationResult.Failed("OFFLINE")
}
