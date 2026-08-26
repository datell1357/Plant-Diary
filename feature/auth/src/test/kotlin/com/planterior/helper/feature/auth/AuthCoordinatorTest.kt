package com.planterior.helper.feature.auth

import com.planterior.helper.core.model.ClientProductEvent
import com.planterior.helper.core.model.ProductEventRecorder
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
    fun `account deletion reauthentication refreshes current credential without changing session`() =
        runTest {
            val identity = FakeIdentity(emptyMap(), account("account-a", AuthProvider.GOOGLE))
            val coordinator =
                coordinator(
                    google =
                        FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("fresh-token")),
                    identity = identity,
                )
            coordinator.restore()
            val before = coordinator.state.value

            val result = coordinator.reauthenticateCurrent()

            assertEquals(AuthReauthenticationResult.SUCCEEDED, result)
            assertEquals(1, identity.reauthenticateCalls)
            assertEquals(before, coordinator.state.value)
        }

    @Test
    fun `terminal deletion signs out locally without ordinary logout hooks or visible-only purge`() =
        runTest {
            val identity = FakeIdentity(emptyMap(), account("account-a", AuthProvider.GOOGLE))
            val cache = RecordingAccountCache()
            val ordinaryHooks = mutableListOf<String>()
            val coordinator =
                coordinator(
                    identity = identity,
                    cache = cache,
                    beforeSignOut = { ordinaryHooks += "remote-unregister:$it" },
                    beforeAuthRemoval = { ordinaryHooks += "ordinary-cleanup:$it" },
                )
            coordinator.restore()
            cache.events.clear()

            coordinator.completeTerminalAccountDeletion("account-a")

            assertEquals(1, identity.signOutCalls)
            assertEquals(listOf("activate:null"), cache.events)
            assertTrue(ordinaryHooks.isEmpty())
            assertTrue(coordinator.state.value is AuthUiState.SignedOut)
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
    fun `full sync convergence records completed once after the terminal attempt`() = runTest {
        val summary = SyncSummary(SyncDomain.entries.toSet(), emptyMap())
        val events = mutableListOf<ClientProductEvent>()
        val synchronizer =
            object : AccountSynchronizer {
                override suspend fun sync(accountUid: String) = summary

                override suspend fun lastKnown(accountUid: String) = summary
            }
        val coordinator =
            coordinator(
                identity = FakeIdentity(emptyMap(), account("account-a", AuthProvider.GOOGLE)),
                synchronizer = synchronizer,
                productEventRecorder = ProductEventRecorder(events::add),
            )

        coordinator.restore()

        assertEquals(listOf(ClientProductEvent.SYNC_COMPLETED), events)
    }

    @Test
    fun `terminal failed and partial sync attempts each record failed once`() = runTest {
        listOf(
                SyncSummary(emptySet(), mapOf(SyncDomain.PLANTS to "failed")),
                SyncSummary(
                    setOf(SyncDomain.PLANTS),
                    mapOf(SyncDomain.MINI_HOME to "partial"),
                ),
            )
            .forEach { summary ->
                val events = mutableListOf<ClientProductEvent>()
                val coordinator =
                    coordinator(
                        identity =
                            FakeIdentity(
                                emptyMap(),
                                account("account-a", AuthProvider.GOOGLE),
                            ),
                        synchronizer = FakeSynchronizer(summary),
                        productEventRecorder = ProductEventRecorder(events::add),
                    )

                coordinator.restore()

                assertEquals(listOf(ClientProductEvent.SYNC_FAILED), events)
            }
    }

    @Test
    fun `thrown terminal sync attempt publishes last known and records failed once`() = runTest {
        val lastKnown =
            SyncSummary(setOf(SyncDomain.PLANTS), mapOf(SyncDomain.MINI_HOME to "offline"))
        val events = mutableListOf<ClientProductEvent>()
        val coordinator =
            coordinator(
                identity = FakeIdentity(emptyMap(), account("account-a", AuthProvider.GOOGLE)),
                synchronizer = FailingSynchronizer(lastKnown),
                productEventRecorder = ProductEventRecorder(events::add),
            )

        coordinator.restore()

        assertEquals(lastKnown, (coordinator.state.value as AuthUiState.Authenticated).sync)
        assertEquals(listOf(ClientProductEvent.SYNC_FAILED), events)
    }

    @Test
    fun `retrying thrown terminal sync records once per attempt without publish duplicates`() =
        runTest {
            val lastKnown =
                SyncSummary(setOf(SyncDomain.PLANTS), mapOf(SyncDomain.MINI_HOME to "offline"))
            val events = mutableListOf<ClientProductEvent>()
            val coordinator =
                coordinator(
                    identity = FakeIdentity(emptyMap(), account("account-a", AuthProvider.GOOGLE)),
                    synchronizer = FailingSynchronizer(lastKnown),
                    productEventRecorder = ProductEventRecorder(events::add),
                )

            coordinator.restore()
            coordinator.restore()

            assertEquals(
                listOf(ClientProductEvent.SYNC_FAILED, ClientProductEvent.SYNC_FAILED),
                events,
            )
        }

    @Test
    fun `superseded owner switch receives no event from a late thrown sync attempt`() = runTest {
        val google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("token-b"))
        val identity =
            FakeIdentity(
                mapOf("token-b" to account("account-b", AuthProvider.GOOGLE)),
                account("account-a", AuthProvider.GOOGLE),
            )
        val synchronizer =
            DeferredFailingSynchronizer(
                failingOwner = "account-a",
                lastKnown =
                    SyncSummary(
                        setOf(SyncDomain.PLANTS),
                        mapOf(SyncDomain.MINI_HOME to "offline"),
                    ),
            )
        val events = mutableListOf<ClientProductEvent>()
        val coordinator =
            coordinator(
                google = google,
                identity = identity,
                synchronizer = synchronizer,
                productEventRecorder = ProductEventRecorder(events::add),
            )
        val restoring = async { coordinator.restore() }
        synchronizer.started.await()

        coordinator.signIn(AuthProvider.GOOGLE, null)
        synchronizer.fail.complete(Unit)
        restoring.await()

        assertEquals(
            "account-b",
            (coordinator.state.value as AuthUiState.Authenticated).account.uid,
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `telemetry failure cannot mask a thrown sync result or authenticated state`() = runTest {
        val lastKnown =
            SyncSummary(setOf(SyncDomain.PLANTS), mapOf(SyncDomain.MINI_HOME to "offline"))
        val coordinator =
            coordinator(
                identity = FakeIdentity(emptyMap(), account("account-a", AuthProvider.GOOGLE)),
                synchronizer = FailingSynchronizer(lastKnown),
                productEventRecorder = ProductEventRecorder { error("telemetry unavailable") },
            )

        coordinator.restore()

        assertEquals(lastKnown, (coordinator.state.value as AuthUiState.Authenticated).sync)
    }

    @Test
    fun `offline failure before a sync attempt records no terminal event`() = runTest {
        val events = mutableListOf<ClientProductEvent>()
        val coordinator =
            coordinator(
                identity = FakeIdentity(emptyMap(), account("account-a", AuthProvider.GOOGLE)),
                synchronizer = OfflineNoAttemptSynchronizer(SyncSummary.EMPTY),
                productEventRecorder = ProductEventRecorder(events::add),
            )

        coordinator.restore()

        assertTrue(events.isEmpty())
    }

    @Test
    fun `a stalled synchronization does not hold back the restored session`() = runTest {
        val identity = FakeIdentity(emptyMap(), account("account-a", AuthProvider.GOOGLE))
        val gate = CompletableDeferred<Unit>()
        val lastKnown =
            SyncSummary(setOf(SyncDomain.PLANTS), mapOf(SyncDomain.MINI_HOME to "offline"))
        val coordinator =
            coordinator(
                identity = identity,
                synchronizer = StallingSynchronizer(gate, lastKnown),
            )

        val restoring = async { coordinator.restore() }
        // 동기화가 끝나기 전이지만 이미 로그인 상태여야 홈이 캐시된 내용을 보여줄 수 있다.
        testScheduler.advanceUntilIdle()

        val duringSync = coordinator.state.value
        assertTrue("동기화 중에도 로그인 상태여야 한다: $duringSync", duringSync is AuthUiState.Authenticated)
        assertEquals(lastKnown, (duringSync as AuthUiState.Authenticated).sync)

        gate.complete(Unit)
        restoring.await()
        assertTrue(coordinator.state.value is AuthUiState.Authenticated)
    }

    @Test
    fun `a stalled profile write does not hold back the restored session`() = runTest {
        val identity = FakeIdentity(emptyMap(), account("account-a", AuthProvider.GOOGLE))
        val gate = CompletableDeferred<Unit>()
        val coordinator = coordinator(identity = identity, profile = StallingProfileStore(gate))

        val restoring = async { coordinator.restore() }
        testScheduler.advanceUntilIdle()

        // 서버 프로필 쓰기가 멈춰도 로컬 캐시를 보여줄 수 있어야 한다.
        assertTrue(
            "프로필 쓰기 지연이 세션을 막으면 안 된다: ${coordinator.state.value}",
            coordinator.state.value is AuthUiState.Authenticated,
        )

        gate.complete(Unit)
        restoring.await()
    }

    @Test
    fun `a failing profile write or synchronization keeps the session and reports last sync`() =
        runTest {
            val identity = FakeIdentity(emptyMap(), account("account-a", AuthProvider.GOOGLE))
            val lastKnown =
                SyncSummary(setOf(SyncDomain.PLANTS), mapOf(SyncDomain.MINI_HOME to "offline"))
            val coordinator =
                coordinator(
                    identity = identity,
                    profile = { error("server unavailable") },
                    synchronizer = FailingSynchronizer(lastKnown),
                )

            coordinator.restore()

            val state = coordinator.state.value
            assertTrue("서버 실패가 세션을 지우면 안 된다: $state", state is AuthUiState.Authenticated)
            assertEquals(lastKnown, (state as AuthUiState.Authenticated).sync)
        }

    @Test
    fun `cancelling the sign in scope after success keeps the established session`() = runTest {
        val google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("google-token"))
        val identity =
            FakeIdentity(mapOf("google-token" to account("account-a", AuthProvider.GOOGLE)))
        val gate = CompletableDeferred<Unit>()
        val coordinator =
            coordinator(
                google,
                identity = identity,
                synchronizer = StallingSynchronizer(gate, SyncSummary.EMPTY),
            )

        // 로그인 성공 직후 화면이 전환되면 호출측 scope가 취소된다. 이때 세션을 잃으면 안 된다.
        val signingIn = async { coordinator.signIn(AuthProvider.GOOGLE, null) }
        testScheduler.advanceUntilIdle()
        assertTrue(coordinator.state.value is AuthUiState.Authenticated)

        signingIn.cancel()
        testScheduler.advanceUntilIdle()

        assertTrue(
            "취소된 후에도 로그인 상태여야 한다: ${coordinator.state.value}",
            coordinator.state.value is AuthUiState.Authenticated,
        )
    }

    @Test
    fun `cancelling inside lastKnown still leaves an authenticated session`() = runTest {
        val google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("google-token"))
        val identity =
            FakeIdentity(mapOf("google-token" to account("account-a", AuthProvider.GOOGLE)))
        val gate = CompletableDeferred<Unit>()
        val profile = RecordingProfileStore()
        val coordinator =
            coordinator(
                google,
                identity = identity,
                profile = profile,
                synchronizer = StallingLastKnownSynchronizer(gate),
            )

        val signingIn = async { coordinator.signIn(AuthProvider.GOOGLE, null) }
        testScheduler.advanceUntilIdle()

        // lastKnown 안에서 멈춰 있는 상태다. 그래도 Firebase 성공 직후이므로 이미 로그인 상태여야 한다.
        val duringLastKnown = coordinator.state.value
        assertTrue(
            "lastKnown 중단 중에도 Authenticated여야 한다: $duringLastKnown",
            duringLastKnown is AuthUiState.Authenticated,
        )
        assertEquals("account-a", (duringLastKnown as AuthUiState.Authenticated).account.uid)

        signingIn.cancel()
        testScheduler.advanceUntilIdle()

        val afterCancel = coordinator.state.value
        assertTrue(
            "취소 후에도 세션을 잃으면 안 된다: $afterCancel",
            afterCancel is AuthUiState.Authenticated,
        )
        // 취소되었으므로 이후 서버 부수 효과는 일어나지 않아야 한다.
        assertTrue("취소 후 프로필 쓰기가 이어지면 안 된다", profile.upserts.isEmpty())
    }

    @Test
    fun `a late sync result never overwrites a session that already moved on`() = runTest {
        val google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("google-token"))
        val identity =
            FakeIdentity(mapOf("google-token" to account("account-a", AuthProvider.GOOGLE)))
        val gate = CompletableDeferred<Unit>()
        val coordinator =
            coordinator(
                google,
                identity = identity,
                synchronizer = StallingSynchronizer(gate, SyncSummary.EMPTY),
            )

        val signingIn = async { coordinator.signIn(AuthProvider.GOOGLE, null) }
        testScheduler.advanceUntilIdle()
        assertTrue(coordinator.state.value is AuthUiState.Authenticated)

        // 동기화가 끝나기 전에 로그아웃하고, 늦게 도착한 결과가 로그아웃을 되돌리는지 본다.
        coordinator.logout()
        gate.complete(Unit)
        signingIn.join()
        testScheduler.advanceUntilIdle()

        assertTrue(
            "늦게 끝난 동기화가 로그아웃을 덮어쓰면 안 된다: ${coordinator.state.value}",
            coordinator.state.value is AuthUiState.SignedOut,
        )
    }

    @Test
    fun `failed endpoint cleanup keeps authentication so logout can recover before account switch`() =
        runTest {
            val identity = FakeIdentity(emptyMap(), account("account-a", AuthProvider.GOOGLE))
            var attempts = 0
            var cancellations = 0
            val coordinator =
                coordinator(
                    identity = identity,
                    beforeSignOut = {
                        attempts += 1
                        if (attempts == 1) error("unregister unavailable")
                    },
                    beforeAuthRemoval = { cancellations += 1 },
                )
            coordinator.restore()

            assertEquals(false, coordinator.logout())
            assertTrue(coordinator.state.value is AuthUiState.Authenticated)
            assertEquals("account-a", identity.current()?.uid)
            assertEquals(0, identity.signOutCalls)
            assertEquals(0, cancellations)

            assertEquals(true, coordinator.logout())
            assertTrue(coordinator.state.value is AuthUiState.SignedOut)
            assertNull(identity.current())
            assertEquals(2, attempts)
            assertEquals(1, identity.signOutCalls)
            assertEquals(1, cancellations)
        }

    @Test
    fun `account switch cancels former owner notifications after revocation and before auth removal`() =
        runTest {
            val google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("token-b"))
            val identity =
                FakeIdentity(
                    mapOf("token-b" to account("account-b", AuthProvider.GOOGLE)),
                    account("account-a", AuthProvider.GOOGLE),
                )
            val events = mutableListOf<String>()
            val coordinator =
                coordinator(
                    google = google,
                    identity = identity,
                    beforeSignOut = { events += "revoke:$it" },
                    beforeAuthRemoval = { events += "cancel:$it" },
                )

            coordinator.signIn(AuthProvider.GOOGLE, null)

            assertEquals(listOf("revoke:account-a", "cancel:account-a"), events)
            assertEquals(
                "account-b",
                (coordinator.state.value as AuthUiState.Authenticated).account.uid,
            )
            assertEquals(1, identity.signOutCalls)
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
                "activate:null",
                "clear:null",
                "activate:account-b",
                "clear:account-b",
                "activate:null",
                "clear:null",
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
    fun `sign-in superseded during revocation cannot mutate auth or cache after the winner starts`() =
        runTest {
            val revocationStarted = CompletableDeferred<Unit>()
            val releaseRevocation = CompletableDeferred<Unit>()
            var revocations = 0
            val identity =
                FakeIdentity(
                    mapOf(
                        "a" to account("account-a", AuthProvider.GOOGLE),
                        "b" to account("account-b", AuthProvider.APPLE),
                    ),
                    account("account-old", AuthProvider.GOOGLE),
                )
            val cache = RecordingAccountCache()
            val coordinator =
                coordinator(
                    google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("a")),
                    apple = FakeProvider(AuthProvider.APPLE, ProviderOutcome.Proof("b", "nonce")),
                    identity = identity,
                    cache = cache,
                    beforeSignOut = {
                        revocations += 1
                        if (revocations == 1) {
                            revocationStarted.complete(Unit)
                            releaseRevocation.await()
                        }
                    },
                )

            val losing = async { coordinator.signIn(AuthProvider.GOOGLE, null) }
            revocationStarted.await()
            val winning = async { coordinator.signIn(AuthProvider.APPLE, null) }
            testScheduler.advanceUntilIdle()
            releaseRevocation.complete(Unit)
            losing.await()
            winning.await()

            assertEquals("account-b", identity.current()?.uid)
            assertEquals(listOf("b"), identity.signInProofs.map { it.token })
            assertEquals(1, identity.signOutCalls)
            assertEquals(
                listOf("clear:account-old", "activate:null", "clear:null", "activate:account-b"),
                cache.events,
            )
        }

    @Test
    fun `sign-in superseded during former-owner cleanup leaves cache owned by the winner`() =
        runTest {
            val cleanupStarted = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            var cleanups = 0
            val identity =
                FakeIdentity(
                    mapOf(
                        "a" to account("account-a", AuthProvider.GOOGLE),
                        "b" to account("account-b", AuthProvider.APPLE),
                    ),
                    account("account-old", AuthProvider.GOOGLE),
                )
            val cache = RecordingAccountCache()
            val coordinator =
                coordinator(
                    google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("a")),
                    apple = FakeProvider(AuthProvider.APPLE, ProviderOutcome.Proof("b", "nonce")),
                    identity = identity,
                    cache = cache,
                    beforeAuthRemoval = {
                        cleanups += 1
                        if (cleanups == 1) {
                            cleanupStarted.complete(Unit)
                            releaseCleanup.await()
                        }
                    },
                )

            val losing = async { coordinator.signIn(AuthProvider.GOOGLE, null) }
            cleanupStarted.await()
            val winning = async { coordinator.signIn(AuthProvider.APPLE, null) }
            testScheduler.advanceUntilIdle()
            releaseCleanup.complete(Unit)
            losing.await()
            winning.await()

            assertEquals("account-b", identity.current()?.uid)
            assertEquals(listOf("b"), identity.signInProofs.map { it.token })
            assertEquals(1, identity.signOutCalls)
            assertEquals(
                listOf("clear:account-old", "activate:null", "clear:null", "activate:account-b"),
                cache.events,
            )
        }

    @Test
    fun `cancel during identity exchange rolls back a late Firebase success`() = runTest {
        val identity =
            ControlledSignInIdentity(
                mapOf("a" to account("account-a", AuthProvider.GOOGLE)),
                controlledToken = "a",
            )
        val coordinator =
            coordinator(
                google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("a")),
                identity = identity,
            )
        val signingIn = async { coordinator.signIn(AuthProvider.GOOGLE, null) }
        identity.started.await()

        coordinator.cancelSignIn()
        identity.allowMutation.complete(Unit)
        identity.mutated.await()
        identity.allowReturn.complete(Unit)
        signingIn.await()

        assertNull(identity.current())
        assertEquals(listOf("account-a"), identity.signedOutUids)
        assertEquals(
            AuthFailure.Cancelled,
            (coordinator.state.value as AuthUiState.SignedOut).failure,
        )
    }

    @Test
    fun `coroutine cancellation during identity exchange rolls back after Firebase completes`() =
        runTest {
            val identity =
                ControlledSignInIdentity(
                    mapOf("a" to account("account-a", AuthProvider.GOOGLE)),
                    controlledToken = "a",
                )
            val coordinator =
                coordinator(
                    google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("a")),
                    identity = identity,
                )
            val signingIn = async { coordinator.signIn(AuthProvider.GOOGLE, null) }
            identity.started.await()

            signingIn.cancel()
            identity.allowMutation.complete(Unit)
            identity.mutated.await()
            identity.allowReturn.complete(Unit)
            signingIn.join()

            assertNull(identity.current())
            assertEquals(listOf("account-a"), identity.signedOutUids)
        }

    @Test
    fun `cancel after Firebase mutation but before response removes the hidden identity`() =
        runTest {
            val identity =
                ControlledSignInIdentity(
                    mapOf("a" to account("account-a", AuthProvider.GOOGLE)),
                    controlledToken = "a",
                )
            val coordinator =
                coordinator(
                    google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("a")),
                    identity = identity,
                )
            val signingIn = async { coordinator.signIn(AuthProvider.GOOGLE, null) }
            identity.started.await()
            identity.allowMutation.complete(Unit)
            identity.mutated.await()
            assertEquals("account-a", identity.current()?.uid)

            coordinator.cancelSignIn()
            identity.allowReturn.complete(Unit)
            signingIn.await()

            assertNull(identity.current())
            assertEquals(listOf("account-a"), identity.signedOutUids)
        }

    @Test
    fun `superseded identity exchange rolls back before the winning sign-in mutates auth`() =
        runTest {
            val identity =
                ControlledSignInIdentity(
                    mapOf(
                        "a" to account("account-a", AuthProvider.GOOGLE),
                        "b" to account("account-b", AuthProvider.APPLE),
                    ),
                    controlledToken = "a",
                )
            val coordinator =
                coordinator(
                    google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("a")),
                    apple = FakeProvider(AuthProvider.APPLE, ProviderOutcome.Proof("b", "nonce")),
                    identity = identity,
                )
            val stale = async { coordinator.signIn(AuthProvider.GOOGLE, null) }
            identity.started.await()
            val winner = async { coordinator.signIn(AuthProvider.APPLE, null) }

            identity.allowMutation.complete(Unit)
            identity.mutated.await()
            identity.allowReturn.complete(Unit)
            stale.await()
            winner.await()

            assertEquals("account-b", identity.current()?.uid)
            assertEquals(listOf("account-a"), identity.signedOutUids)
            assertEquals(
                "account-b",
                (coordinator.state.value as AuthUiState.Authenticated).account.uid,
            )
        }

    @Test
    fun `cancelled identity exchange rolls back before a newer sign-in wins`() = runTest {
        val identity =
            ControlledSignInIdentity(
                mapOf(
                    "a" to account("account-a", AuthProvider.GOOGLE),
                    "b" to account("account-b", AuthProvider.APPLE),
                ),
                controlledToken = "a",
            )
        val coordinator =
            coordinator(
                google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("a")),
                apple = FakeProvider(AuthProvider.APPLE, ProviderOutcome.Proof("b", "nonce")),
                identity = identity,
            )
        val cancelled = async { coordinator.signIn(AuthProvider.GOOGLE, null) }
        identity.started.await()
        coordinator.cancelSignIn()
        val winner = async { coordinator.signIn(AuthProvider.APPLE, null) }

        identity.allowMutation.complete(Unit)
        identity.mutated.await()
        identity.allowReturn.complete(Unit)
        cancelled.await()
        winner.await()

        assertEquals("account-b", identity.current()?.uid)
        assertEquals(listOf("a", "b"), identity.signInProofs.map(ProviderProof::token))
        assertEquals(listOf("account-a"), identity.signedOutUids)
        assertEquals(
            "account-b",
            (coordinator.state.value as AuthUiState.Authenticated).account.uid,
        )
    }

    @Test
    fun `stale rollback never signs out an identity it does not own`() = runTest {
        val accountB = account("account-b", AuthProvider.APPLE)
        val identity =
            ControlledSignInIdentity(
                mapOf("a" to account("account-a", AuthProvider.GOOGLE)),
                controlledToken = "a",
            )
        val coordinator =
            coordinator(
                google = FakeProvider(AuthProvider.GOOGLE, ProviderOutcome.Proof("a")),
                identity = identity,
            )
        val cancelled = async { coordinator.signIn(AuthProvider.GOOGLE, null) }
        identity.started.await()
        identity.allowMutation.complete(Unit)
        identity.mutated.await()
        coordinator.cancelSignIn()
        identity.replaceCurrent(accountB)

        identity.allowReturn.complete(Unit)
        cancelled.await()

        assertEquals("account-b", identity.current()?.uid)
        assertTrue(identity.signedOutUids.isEmpty())
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
        identity: FirebaseIdentityGateway = FakeIdentity(emptyMap()),
        profile: AccountProfileStore = RecordingProfileStore(),
        cache: RecordingAccountCache = RecordingAccountCache(),
        synchronizer: AccountSynchronizer = FakeSynchronizer(SyncSummary.EMPTY),
        beforeSignOut: suspend (String) -> Unit = {},
        beforeAuthRemoval: suspend (String) -> Unit = {},
        productEventRecorder: ProductEventRecorder = ProductEventRecorder {},
    ) =
        AuthCoordinator(
            mapOf(AuthProvider.GOOGLE to google, AuthProvider.APPLE to apple),
            identity,
            profile,
            cache,
            synchronizer,
            beforeSignOut,
            beforeAuthRemoval,
            productEventRecorder = productEventRecorder,
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

    private class ControlledSignInIdentity(
        private val accounts: Map<String, AuthAccount>,
        private val controlledToken: String,
    ) : FirebaseIdentityGateway {
        val started = CompletableDeferred<Unit>()
        val allowMutation = CompletableDeferred<Unit>()
        val mutated = CompletableDeferred<Unit>()
        val allowReturn = CompletableDeferred<Unit>()
        val signInProofs = mutableListOf<ProviderProof>()
        val signedOutUids = mutableListOf<String>()
        private var currentAccount: AuthAccount? = null

        override fun current() = currentAccount

        override suspend fun signIn(proof: ProviderProof): AuthAccount {
            signInProofs += proof
            val account = accounts.getValue(proof.token)
            if (proof.token != controlledToken) {
                currentAccount = account
                return account
            }
            started.complete(Unit)
            allowMutation.await()
            currentAccount = account
            mutated.complete(Unit)
            allowReturn.await()
            return account
        }

        fun replaceCurrent(account: AuthAccount) {
            currentAccount = account
        }

        override suspend fun reauthenticate(proof: ProviderProof) = error("not used")

        override suspend fun link(proof: ProviderProof) = error("not used")

        override suspend fun signOut() {
            currentAccount?.uid?.let(signedOutUids::add)
            currentAccount = null
        }
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
        val signInProofs = mutableListOf<ProviderProof>()
        var linkFailure: AuthFailure? = null
        var signOutCalls = 0

        override fun current() = currentAccount

        override suspend fun signIn(proof: ProviderProof): AuthAccount {
            signInCalls += 1
            lastProof = proof
            signInProofs += proof
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
            signOutCalls += 1
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

    /** `lastKnown` 안에서 멈추는 저장소를 흑낸다. 세션 공개가 이 중단점 뒤에 있으면 테스트가 실패한다. */
    private class StallingLastKnownSynchronizer(private val gate: CompletableDeferred<Unit>) :
        AccountSynchronizer {
        override suspend fun sync(accountUid: String): SyncSummary {
            gate.await()
            return SyncSummary.EMPTY
        }

        override suspend fun lastKnown(accountUid: String): SyncSummary {
            gate.await()
            return SyncSummary.EMPTY
        }
    }

    /** 동기화 자체가 실패하는 서버를 흑낸다. */
    private class FailingSynchronizer(private val lastKnown: SyncSummary) : AccountSynchronizer {
        override suspend fun sync(accountUid: String): SyncSummary = error("sync unavailable")

        override suspend fun lastKnown(accountUid: String): SyncSummary = lastKnown
    }

    private class OfflineNoAttemptSynchronizer(private val lastKnown: SyncSummary) :
        AccountSynchronizer {
        override suspend fun sync(accountUid: String): SyncSummary =
            throw SyncNotAttemptedException()

        override suspend fun lastKnown(accountUid: String): SyncSummary = lastKnown
    }

    private class DeferredFailingSynchronizer(
        private val failingOwner: String,
        private val lastKnown: SyncSummary,
    ) : AccountSynchronizer {
        val started = CompletableDeferred<Unit>()
        val fail = CompletableDeferred<Unit>()

        override suspend fun sync(accountUid: String): SyncSummary {
            if (accountUid != failingOwner) return SyncSummary.EMPTY
            started.complete(Unit)
            fail.await()
            error("sync unavailable")
        }

        override suspend fun lastKnown(accountUid: String): SyncSummary =
            if (accountUid == failingOwner) lastKnown else SyncSummary.EMPTY
    }

    /** 응답하지 않는 서버 프로필 쓰기를 흑낸다. */
    private class StallingProfileStore(private val gate: CompletableDeferred<Unit>) :
        AccountProfileStore {
        override suspend fun upsert(account: AuthAccount) {
            gate.await()
        }
    }

    /** 서버가 응답하지 않는 동기화를 흑낸다. 고정 sleep 없이 gate로만 진행을 제어한다. */
    private class StallingSynchronizer(
        private val gate: CompletableDeferred<Unit>,
        private val lastKnown: SyncSummary,
    ) : AccountSynchronizer {
        override suspend fun sync(accountUid: String): SyncSummary {
            gate.await()
            return lastKnown
        }

        override suspend fun lastKnown(accountUid: String): SyncSummary = lastKnown
    }
}
