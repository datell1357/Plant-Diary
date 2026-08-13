package com.planterior.helper.feature.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthCoordinatorTest {
    @Test
    fun `Google success signs in upserts trusted profile and returns to requested route`() =
        runTest {
            val google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("google-token"))
            val identity =
                FakeIdentity(mapOf("google-token" to account("account-a", AuthProvider.GOOGLE)))
            val profile = RecordingProfileStore()
            val cache = RecordingAccountCache()
            val coordinator =
                coordinator(google, identity = identity, profile = profile, cache = cache)

            coordinator.signIn(AuthProvider.GOOGLE, "planterior://storage")

            val state = coordinator.state.value as AuthUiState.Authenticated
            assertEquals("account-a", state.account.uid)
            assertEquals("planterior://storage", state.returnRoute)
            assertEquals(listOf("account-a"), profile.upserts.map { it.uid })
            assertEquals(listOf("clear:null", "activate:account-a"), cache.events)
        }

    @Test
    fun `Google cancel and provider failure stay retryable without Firebase side effects`() =
        runTest {
            listOf(
                    ProviderOutcome.Cancelled to AuthFailure.Cancelled,
                    ProviderOutcome.Failed(AuthFailure.ProviderUnavailable) to
                        AuthFailure.ProviderUnavailable,
                )
                .forEach { (outcome, expected) ->
                    val identity = FakeIdentity(emptyMap())
                    val profile = RecordingProfileStore()
                    val coordinator =
                        coordinator(
                            FakeProvider(AuthProvider.GOOGLE, outcome),
                            identity = identity,
                            profile = profile,
                        )

                    coordinator.signIn(AuthProvider.GOOGLE, null)

                    assertEquals(
                        expected,
                        (coordinator.state.value as AuthUiState.SignedOut).failure,
                    )
                    assertEquals(0, identity.signInCalls)
                    assertTrue(profile.upserts.isEmpty())
                }
        }

    @Test
    fun `Apple validated token succeeds while invalid expired and replay are explicit`() = runTest {
        val identity =
            FakeIdentity(mapOf("apple-token" to account("apple-user", AuthProvider.APPLE)))
        val success =
            coordinator(
                apple =
                    FakeProvider(
                        AuthProvider.APPLE,
                        ProviderOutcome.Proof("apple-token", "raw-nonce"),
                    ),
                identity = identity,
            )
        success.signIn(AuthProvider.APPLE, null)
        assertEquals("apple-user", (success.state.value as AuthUiState.Authenticated).account.uid)
        assertEquals("raw-nonce", identity.lastProof?.rawNonce)

        listOf(AuthFailure.InvalidOrExpiredToken, AuthFailure.ReplayedCallback).forEach { failure ->
            val coordinator =
                coordinator(
                    apple = FakeProvider(AuthProvider.APPLE, ProviderOutcome.Failed(failure))
                )
            coordinator.signIn(AuthProvider.APPLE, null)
            assertEquals(failure, (coordinator.state.value as AuthUiState.SignedOut).failure)
        }
    }

    @Test
    fun `already linked provider never launches another credential flow`() = runTest {
        val provider = CountingProvider(AuthProvider.GOOGLE)
        val identity = FakeIdentity(emptyMap(), account("account-a", AuthProvider.GOOGLE))
        val coordinator = coordinator(google = provider, identity = identity)
        coordinator.restore()

        coordinator.link(AuthProvider.GOOGLE, consentConfirmed = true)

        assertEquals(
            AuthUiState.LinkFailure(
                AuthProvider.GOOGLE,
                AuthFailure.AlreadyLinked(AuthProvider.GOOGLE),
            ),
            coordinator.state.value,
        )
        assertEquals(0, provider.calls)
    }

    @Test
    fun `link requires consent and authenticated reauth then exposes collision`() = runTest {
        val identity = FakeIdentity(emptyMap(), account("account-a", AuthProvider.GOOGLE))
        identity.linkFailure = AuthFailure.AccountCollision(AuthProvider.APPLE)
        val coordinator =
            coordinator(
                google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("reauth-token")),
                apple =
                    FakeProvider(AuthProvider.APPLE, ProviderOutcome.Proof("link-token", "nonce")),
                identity = identity,
            )
        coordinator.restore()

        coordinator.link(AuthProvider.APPLE, consentConfirmed = false)
        assertEquals(AuthUiState.LinkConsentRequired(AuthProvider.APPLE), coordinator.state.value)
        coordinator.link(AuthProvider.APPLE, consentConfirmed = true)

        assertEquals(AuthUiState.LinkConflict(AuthProvider.APPLE), coordinator.state.value)
        assertEquals(1, identity.reauthenticateCalls)
        assertEquals(AuthProvider.GOOGLE, identity.reauthenticatedProof?.provider)
        assertEquals(1, identity.linkCalls)
        assertEquals("account-a", identity.current()?.uid)
    }

    @Test
    fun `session restore reports partial sync without hiding successful domains`() = runTest {
        val identity = FakeIdentity(emptyMap(), account("account-a", AuthProvider.GOOGLE))
        val summary =
            SyncSummary(setOf(SyncDomain.PLANTS), mapOf(SyncDomain.MINI_HOME to "offline"))
        val coordinator = coordinator(identity = identity, synchronizer = FakeSynchronizer(summary))

        coordinator.restore()

        val state = coordinator.state.value as AuthUiState.Authenticated
        assertTrue(state.sync.isPartial)
        assertEquals(setOf(SyncDomain.PLANTS), state.sync.completed)
        assertEquals("offline", state.sync.failures[SyncDomain.MINI_HOME])
    }

    @Test
    fun `logout and A to B to A clear visible cache while preserving scoped drafts`() = runTest {
        val identity =
            FakeIdentity(
                mapOf(
                    "a" to account("account-a", AuthProvider.GOOGLE),
                    "b" to account("account-b", AuthProvider.APPLE),
                )
            )
        val cache = RecordingAccountCache(mutableMapOf("account-a" to 1, "account-b" to 2))
        val coordinator =
            coordinator(
                google =
                    QueueProvider(
                        AuthProvider.GOOGLE,
                        ArrayDeque(listOf(ProviderOutcome.Proof("a"), ProviderOutcome.Proof("a"))),
                    ),
                apple = FakeProvider(AuthProvider.APPLE, ProviderOutcome.Proof("b", "nonce")),
                identity = identity,
                cache = cache,
            )

        coordinator.signIn(AuthProvider.GOOGLE, null)
        coordinator.signIn(AuthProvider.APPLE, null)
        coordinator.signIn(AuthProvider.GOOGLE, null)
        coordinator.logout()

        assertEquals(
            listOf(
                "clear:null",
                "activate:account-a",
                "clear:account-a",
                "activate:account-b",
                "clear:account-b",
                "activate:account-a",
                "clear:account-a",
                "activate:null",
            ),
            cache.events,
        )
        assertEquals(mapOf("account-a" to 1, "account-b" to 2), cache.drafts)
        assertNull(identity.current())
    }

    @Test
    fun `callback after cancellation is ignored`() = runTest {
        val pending = CompletableDeferred<ProviderOutcome>()
        val identity = FakeIdentity(mapOf("late" to account("late-user", AuthProvider.GOOGLE)))
        val google = DeferredProvider(AuthProvider.GOOGLE, pending)
        val coordinator = coordinator(google, identity = identity)
        val request = async { coordinator.signIn(AuthProvider.GOOGLE, null) }
        google.started.await()

        coordinator.cancelSignIn()
        pending.complete(ProviderOutcome.Proof("late"))
        request.await()

        assertEquals(
            AuthFailure.Cancelled,
            (coordinator.state.value as AuthUiState.SignedOut).failure,
        )
        assertEquals(0, identity.signInCalls)
    }

    @Test
    fun `stale callback after account switch cannot replace active account`() = runTest {
        val pending = CompletableDeferred<ProviderOutcome>()
        val identity =
            FakeIdentity(
                mapOf(
                    "late-a" to account("account-a", AuthProvider.GOOGLE),
                    "b" to account("account-b", AuthProvider.APPLE),
                )
            )
        val google = DeferredProvider(AuthProvider.GOOGLE, pending)
        val coordinator =
            coordinator(
                google = google,
                apple = FakeProvider(AuthProvider.APPLE, ProviderOutcome.Proof("b", "nonce")),
                identity = identity,
            )
        val stale = async { coordinator.signIn(AuthProvider.GOOGLE, null) }
        google.started.await()

        coordinator.signIn(AuthProvider.APPLE, null)
        pending.complete(ProviderOutcome.Proof("late-a"))
        stale.await()

        assertEquals(
            "account-b",
            (coordinator.state.value as AuthUiState.Authenticated).account.uid,
        )
        assertEquals(1, identity.signInCalls)
    }

    private fun coordinator(
        google: AuthProviderAdapter = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Cancelled),
        apple: AuthProviderAdapter = FakeProvider(AuthProvider.APPLE, ProviderOutcome.Cancelled),
        identity: FakeIdentity = FakeIdentity(emptyMap()),
        profile: RecordingProfileStore = RecordingProfileStore(),
        cache: RecordingAccountCache = RecordingAccountCache(),
        synchronizer: AccountSynchronizer = FakeSynchronizer(SyncSummary.EMPTY),
    ) =
        AuthCoordinator(
            mapOf(AuthProvider.GOOGLE to google, AuthProvider.APPLE to apple),
            identity,
            profile,
            cache,
            synchronizer,
        )

    private fun account(uid: String, provider: AuthProvider) =
        AuthAccount(uid, "$uid@example.invalid", uid, setOf(provider))

    private class FakeProvider(
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

    private class CountingProvider(override val provider: AuthProvider) : AuthProviderAdapter {
        var calls = 0

        override suspend fun acquire(requestId: Long): ProviderOutcome {
            calls += 1
            return ProviderOutcome.Cancelled
        }

        override fun cancel(requestId: Long) = Unit
    }

    private class DeferredProvider(
        override val provider: AuthProvider,
        private val result: CompletableDeferred<ProviderOutcome>,
    ) : AuthProviderAdapter {
        val started = CompletableDeferred<Unit>()

        override suspend fun acquire(requestId: Long): ProviderOutcome {
            started.complete(Unit)
            return result.await()
        }

        override fun cancel(requestId: Long) = Unit
    }

    private class FakeIdentity(
        private val accounts: Map<String, AuthAccount>,
        private var currentAccount: AuthAccount? = null,
    ) : FirebaseIdentityGateway {
        var signInCalls = 0
        var linkCalls = 0
        var reauthenticateCalls = 0
        var reauthenticatedProof: ProviderProof? = null
        var lastProof: ProviderProof? = null
        var linkFailure: AuthFailure? = null

        override fun current() = currentAccount

        override suspend fun signIn(proof: ProviderProof): AuthAccount {
            signInCalls += 1
            lastProof = proof
            return accounts.getValue(proof.token).also { currentAccount = it }
        }

        override suspend fun reauthenticate(proof: ProviderProof): AuthAccount {
            reauthenticateCalls += 1
            reauthenticatedProof = proof
            return requireNotNull(currentAccount)
        }

        override suspend fun link(proof: ProviderProof): AuthAccount {
            linkCalls += 1
            linkFailure?.let { throw AuthGatewayException(it) }
            return requireNotNull(currentAccount)
                .copy(providers = requireNotNull(currentAccount).providers + proof.provider)
        }

        override suspend fun signOut() {
            currentAccount = null
        }
    }

    private class RecordingProfileStore : AccountProfileStore {
        val upserts = mutableListOf<AuthAccount>()

        override suspend fun upsert(account: AuthAccount) {
            upserts += account
        }
    }

    private class RecordingAccountCache(val drafts: MutableMap<String, Int> = mutableMapOf()) :
        AccountSessionCache {
        val events = mutableListOf<String>()

        override suspend fun clearVisible(accountUid: String?) {
            events += "clear:$accountUid"
        }

        override fun activate(accountUid: String?) {
            events += "activate:$accountUid"
        }
    }

    private class FakeSynchronizer(private val result: SyncSummary) : AccountSynchronizer {
        override suspend fun sync(accountUid: String) = result
    }
}
