package com.planterior.helper.feature.auth

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.planterior.helper.core.database.PlanteriorDatabase
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthDebugHarnessTest {
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var identity: FirebaseIdentityAdapter
    private lateinit var database: PlanteriorDatabase

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                "android.permission.ACCESS_LOCAL_NETWORK",
            )
        }
        val app = firebaseApp(context)
        auth = FirebaseAuth.getInstance(app)
        firestore = FirebaseFirestore.getInstance(app)
        runCatching { auth.useEmulator("10.0.2.2", 9099) }
        runCatching { firestore.useEmulator("10.0.2.2", 8080) }
        auth.signOut()
        identity = FirebaseIdentityAdapter(auth)
        database = Room.inMemoryDatabaseBuilder(context, PlanteriorDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        auth.signOut()
        database.close()
    }

    @Test
    fun googleAndAppleCreateAndRestoreExistingEmulatorUsers() = bounded {
        listOf(AuthProvider.GOOGLE, AuthProvider.APPLE).forEach { provider ->
            val subject = "${provider.name.lowercase()}-${UUID.randomUUID()}"
            val proof = proof(provider, subject)
            val first = identity.signIn(proof)
            assertTrue(provider in first.providers)
            auth.signOut()

            val existing = identity.signIn(proof)
            assertEquals(first.uid, existing.uid)
            val restored = coordinator(provider, proof).also { it.restore() }
            assertEquals(first.uid, (restored.state.value as AuthUiState.Authenticated).account.uid)
            auth.signOut()
        }
    }

    @Test
    fun cancellationAndCredentialFailureDoNotCreateFirebaseSession() = bounded {
        val cancelled =
            coordinator(
                AuthProvider.GOOGLE,
                ProviderOutcome.Cancelled,
            )
        cancelled.signIn(AuthProvider.GOOGLE, null)
        assertEquals(
            AuthFailure.Cancelled,
            (cancelled.state.value as AuthUiState.SignedOut).failure,
        )
        assertNull(auth.currentUser)

        val failed =
            coordinator(
                AuthProvider.APPLE,
                ProviderOutcome.Failed(AuthFailure.InvalidOrExpiredToken),
            )
        failed.signIn(AuthProvider.APPLE, null)
        assertEquals(
            AuthFailure.InvalidOrExpiredToken,
            (failed.state.value as AuthUiState.SignedOut).failure,
        )
        assertNull(auth.currentUser)
    }

    @Test
    fun accountSwitchIsolationAndPartialSyncUseRealAuthenticatedUids() = bounded {
        val googleProof = proof(AuthProvider.GOOGLE, "isolation-a-${UUID.randomUUID()}")
        val appleProof = proof(AuthProvider.APPLE, "isolation-b-${UUID.randomUUID()}")
        val cache = RecordingCache()
        val delegate = FirestoreAccountSyncRemote(firestore)
        val partialRemote =
            object : AccountSyncRemote by delegate {
                // 미니홈피는 이제 전용 snapshot 조회를 쓴다. 부분 동기화를 만들려면 그 경로를 실패시켜야 한다.
                override suspend fun miniHome(accountUid: String): RemoteMiniHome? =
                    error("forced partial sync")

                override suspend fun verifyDomain(accountUid: String, domain: SyncDomain) {
                    if (domain == SyncDomain.MINI_HOME) error("forced partial sync")
                    delegate.verifyDomain(accountUid, domain)
                }
            }
        val coordinator =
            AuthCoordinator(
                mapOf(
                    AuthProvider.GOOGLE to
                        QueueProvider(
                            AuthProvider.GOOGLE,
                            ArrayDeque(listOf(outcome(googleProof), outcome(googleProof))),
                        ),
                    AuthProvider.APPLE to StaticProvider(AuthProvider.APPLE, outcome(appleProof)),
                ),
                identity,
                FirestoreAccountProfileStore(FirebaseFunctions.getInstance()),
                cache,
                FirestoreAccountSynchronizer(partialRemote, database),
            )

        coordinator.signIn(AuthProvider.GOOGLE, null)
        val accountA = (coordinator.state.value as AuthUiState.Authenticated).account.uid
        coordinator.signIn(AuthProvider.APPLE, null)
        val accountB = (coordinator.state.value as AuthUiState.Authenticated).account.uid
        coordinator.signIn(AuthProvider.GOOGLE, null)
        val final = coordinator.state.value as AuthUiState.Authenticated

        assertTrue(accountA != accountB)
        assertEquals(accountA, final.account.uid)
        assertTrue(final.sync.isPartial)
        assertEquals(
            "FAILED",
            database.syncDao().lastSync(accountA, SyncDomain.MINI_HOME.name)?.status,
        )
        assertEquals(
            listOf(
                "clear:null",
                "activate:$accountA",
                "clear:$accountA",
                "activate:$accountB",
                "clear:$accountB",
                "activate:$accountA",
            ),
            cache.events,
        )
        val profile = firestore.document("users/$accountA").get().await()
        assertEquals(accountA, profile.getString("ownerUid"))
    }

    private fun coordinator(provider: AuthProvider, proof: ProviderProof): AuthCoordinator =
        coordinator(provider, outcome(proof))

    private fun coordinator(
        provider: AuthProvider,
        providerOutcome: ProviderOutcome,
    ): AuthCoordinator =
        AuthCoordinator(
            mapOf(
                AuthProvider.GOOGLE to
                    StaticProvider(
                        AuthProvider.GOOGLE,
                        if (provider == AuthProvider.GOOGLE) providerOutcome
                        else ProviderOutcome.Cancelled,
                    ),
                AuthProvider.APPLE to
                    StaticProvider(
                        AuthProvider.APPLE,
                        if (provider == AuthProvider.APPLE) providerOutcome
                        else ProviderOutcome.Cancelled,
                    ),
            ),
            identity,
            FirestoreAccountProfileStore(FirebaseFunctions.getInstance()),
            RecordingCache(),
            AccountSynchronizer { SyncSummary(SyncDomain.entries.toSet(), emptyMap()) },
        )

    private fun proof(provider: AuthProvider, subject: String): ProviderProof {
        val nonce = if (provider == AuthProvider.APPLE) "nonce-$subject" else null
        val issuer =
            if (provider == AuthProvider.APPLE) "https://appleid.apple.com"
            else "https://accounts.google.com"
        val payload =
            JSONObject()
                .put("iss", issuer)
                .put("aud", "demo-planterior")
                .put("sub", subject)
                .put("email", "$subject@example.invalid")
                .put("email_verified", true)
                .put("iat", System.currentTimeMillis() / 1000)
                .put("exp", System.currentTimeMillis() / 1000 + 3600)
        nonce?.let { payload.put("nonce", sha256(it)) }
        val token =
            "${encode(JSONObject().put("alg", "none").toString())}.${encode(payload.toString())}."
        return ProviderProof(provider, token, nonce)
    }

    private fun outcome(proof: ProviderProof) = ProviderOutcome.Proof(proof.token, proof.rawNonce)

    private fun encode(value: String): String =
        Base64.encodeToString(
            value.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    private fun firebaseApp(context: Context): FirebaseApp = runCatching {
        FirebaseApp.getInstance(APP_NAME)
    }
        .getOrElse {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId("demo-planterior")
                    .setApplicationId("1:1234567890:android:connected")
                    .setApiKey("demo-api-key")
                    .build(),
                APP_NAME,
            )
        }

    private fun bounded(block: suspend () -> Unit) = runBlocking {
        withTimeout(30_000) { block() }
    }

    private class StaticProvider(
        override val provider: AuthProvider,
        private val outcome: ProviderOutcome,
    ) : AuthProviderAdapter {
        override suspend fun acquire(requestId: Long) = outcome

        override fun cancel(requestId: Long) = Unit
    }

    private class QueueProvider(
        override val provider: AuthProvider,
        private val outcomes: ArrayDeque<ProviderOutcome>,
    ) : AuthProviderAdapter {
        override suspend fun acquire(requestId: Long) = outcomes.removeFirst()

        override fun cancel(requestId: Long) = Unit
    }

    private class RecordingCache : AccountSessionCache {
        val events = mutableListOf<String>()

        override suspend fun clearVisible(accountUid: String?) {
            events += "clear:$accountUid"
        }

        override fun activate(accountUid: String?) {
            events += "activate:$accountUid"
        }
    }

    private companion object {
        const val APP_NAME = "auth-connected-tests"
    }
}
