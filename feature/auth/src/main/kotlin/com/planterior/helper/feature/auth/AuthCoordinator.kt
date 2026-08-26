package com.planterior.helper.feature.auth

import com.planterior.helper.core.model.ClientProductEvent
import com.planterior.helper.core.model.ProductEventRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AuthCoordinator(
    private val providers: Map<AuthProvider, AuthProviderAdapter>,
    private val identity: FirebaseIdentityGateway,
    private val profiles: AccountProfileStore,
    private val cache: AccountSessionCache,
    private val synchronizer: AccountSynchronizer,
    private val beforeSignOut: suspend (String) -> Unit = {},
    private val beforeAuthRemoval: suspend (String) -> Unit = {},
    private val authTransition: suspend (String?, suspend () -> Unit) -> Unit = { _, action ->
        action()
    },
    private val productEventRecorder: ProductEventRecorder = ProductEventRecorder {},
    private val accountSyncWriteGate: AccountSyncWriteGate = AccountSyncWriteGate(),
) {
    private val mutableState = MutableStateFlow<AuthUiState>(AuthUiState.Restoring)
    val state: StateFlow<AuthUiState> = mutableState.asStateFlow()
    private var generation = 0L
    private var activeRequest: Pair<Long, AuthProviderAdapter>? = null
    private val authTransitionMutex = Mutex()

    suspend fun restore() {
        val account = identity.current()
        if (account == null) {
            mutableState.value = AuthUiState.SignedOut()
            return
        }
        establishSession(account, returnRoute = null, previousUid = null)
    }

    suspend fun signIn(provider: AuthProvider, returnRoute: String?) {
        val requestId = ++generation
        val requestJob = currentCoroutineContext()[Job]
        val adapter = providers.getValue(provider)
        activeRequest = requestId to adapter
        mutableState.value = AuthUiState.SigningIn(provider)
        val outcome = acquire(adapter, requestId)
        if (requestId != generation) return
        activeRequest = null
        when (outcome) {
            ProviderOutcome.Cancelled ->
                mutableState.value = AuthUiState.SignedOut(AuthFailure.Cancelled)
            is ProviderOutcome.Failed -> mutableState.value = AuthUiState.SignedOut(outcome.failure)
            is ProviderOutcome.Proof -> {
                val proof = ProviderProof(provider, outcome.token, outcome.rawNonce)
                var previousAccount: AuthAccount? = null
                var signedInAccount: AuthAccount? = null
                try {
                    authTransitionMutex.withLock {
                        if (requestId != generation) return@withLock
                        previousAccount = identity.current()
                        val ownerToRemove = previousAccount?.uid
                        if (ownerToRemove != null) {
                            beforeSignOut(ownerToRemove)
                            if (requestId != generation) return@withLock
                        }
                        authTransition(ownerToRemove) transition@{
                            if (requestId != generation) return@transition
                            if (ownerToRemove != null) {
                                beforeAuthRemoval(ownerToRemove)
                                if (requestId != generation) return@transition
                                cache.clearVisible(ownerToRemove)
                                if (requestId != generation) return@transition
                                cache.activate(null)
                                if (requestId != generation) return@transition
                                identity.signOut()
                                if (requestId != generation) return@transition
                            }
                            if (requestId != generation) return@transition
                            signedInAccount =
                                completeOwnedIdentitySignIn(proof, requestId, requestJob)
                        }
                    }
                    if (requestId != generation) return
                    establishSession(
                        requireNotNull(signedInAccount),
                        returnRoute,
                        previousUid = null,
                    )
                } catch (error: AuthGatewayException) {
                    if (requestId == generation)
                        mutableState.value = AuthUiState.SignedOut(error.failure)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    // 로그인 성공 직후 화면이 전환되면 호출측 scope가 취소된다. 이미 세운 세션을 되돌리지 않고
                    // 취소만 그대로 전파해 구조적 동시성을 지킨다.
                    throw cancellation
                } catch (_: Exception) {
                    if (requestId == generation) {
                        mutableState.value =
                            previousAccount?.let {
                                AuthUiState.Authenticated(it, lastKnownOrEmpty(it.uid))
                            } ?: AuthUiState.SignedOut(AuthFailure.Unknown)
                    }
                }
            }
        }
    }

    private suspend fun completeOwnedIdentitySignIn(
        proof: ProviderProof,
        requestId: Long,
        requestJob: Job?,
    ): AuthAccount? {
        var exchangedAccount: AuthAccount? = null
        return try {
            withContext(NonCancellable) {
                val account = identity.signIn(proof)
                exchangedAccount = account
                if (requestId == generation && requestJob?.isActive != false) {
                    account
                } else {
                    rollbackIdentityIfCurrent(account)
                    null
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                exchangedAccount?.let { rollbackIdentityIfCurrent(it) }
            }
            throw cancellation
        }
    }

    private suspend fun rollbackIdentityIfCurrent(account: AuthAccount) {
        if (identity.current()?.uid == account.uid) identity.signOut()
    }

    fun cancelSignIn() {
        val request = activeRequest
        generation += 1
        activeRequest = null
        request?.second?.cancel(request.first)
        mutableState.value = AuthUiState.SignedOut(AuthFailure.Cancelled)
    }

    suspend fun link(provider: AuthProvider, consentConfirmed: Boolean) {
        val current = identity.current()
        if (current == null) {
            mutableState.value = AuthUiState.SignedOut(AuthFailure.InvalidCredential)
            return
        }
        if (provider in current.providers) {
            mutableState.value =
                AuthUiState.LinkFailure(provider, AuthFailure.AlreadyLinked(provider))
            return
        }
        if (!consentConfirmed) {
            mutableState.value = AuthUiState.LinkConsentRequired(provider)
            return
        }
        val currentProvider = current.providers.firstOrNull()
        if (currentProvider == null) {
            mutableState.value = AuthUiState.LinkFailure(provider, AuthFailure.InvalidCredential)
            return
        }
        val requestId = ++generation
        val reauthAdapter = providers.getValue(currentProvider)
        activeRequest = requestId to reauthAdapter
        mutableState.value = AuthUiState.ReauthenticationRequired(provider)
        val reauthOutcome = acquire(reauthAdapter, requestId)
        if (requestId != generation) return
        if (reauthOutcome !is ProviderOutcome.Proof) {
            activeRequest = null
            mutableState.value =
                when (reauthOutcome) {
                    ProviderOutcome.Cancelled ->
                        AuthUiState.Authenticated(current, lastSync(current))
                    is ProviderOutcome.Failed ->
                        AuthUiState.LinkFailure(provider, reauthOutcome.failure)
                    is ProviderOutcome.Proof -> error("unreachable")
                }
            return
        }
        try {
            identity.reauthenticate(
                ProviderProof(currentProvider, reauthOutcome.token, reauthOutcome.rawNonce)
            )
        } catch (error: AuthGatewayException) {
            activeRequest = null
            mutableState.value = AuthUiState.LinkFailure(provider, error.failure)
            return
        }

        val adapter = providers.getValue(provider)
        activeRequest = requestId to adapter
        mutableState.value = AuthUiState.SigningIn(provider)
        when (val outcome = acquire(adapter, requestId)) {
            ProviderOutcome.Cancelled ->
                mutableState.value = AuthUiState.Authenticated(current, lastSync(current))
            is ProviderOutcome.Failed ->
                mutableState.value = AuthUiState.LinkFailure(provider, outcome.failure)
            is ProviderOutcome.Proof ->
                try {
                    val linked =
                        identity.link(ProviderProof(provider, outcome.token, outcome.rawNonce))
                    profiles.upsert(linked)
                    mutableState.value = AuthUiState.Authenticated(linked, lastSync(linked))
                } catch (error: AuthGatewayException) {
                    mutableState.value =
                        if (error.failure is AuthFailure.AccountCollision) {
                            AuthUiState.LinkConflict(provider)
                        } else {
                            AuthUiState.LinkFailure(provider, error.failure)
                        }
                }
        }
        activeRequest = null
    }

    private suspend fun acquire(adapter: AuthProviderAdapter, requestId: Long): ProviderOutcome =
        try {
            adapter.acquire(requestId)
        } catch (error: AuthGatewayException) {
            ProviderOutcome.Failed(error.failure)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ProviderOutcome.Failed(AuthFailure.Unknown)
        }

    private suspend fun lastSync(account: AuthAccount) = synchronizer.lastKnown(account.uid)

    suspend fun reauthenticateCurrent(): AuthReauthenticationResult {
        val account = identity.current() ?: return AuthReauthenticationResult.FAILED
        val provider = account.providers.firstOrNull() ?: return AuthReauthenticationResult.FAILED
        val requestId = ++generation
        val adapter = providers.getValue(provider)
        activeRequest = requestId to adapter
        val outcome = acquire(adapter, requestId)
        if (requestId != generation) return AuthReauthenticationResult.CANCELLED
        activeRequest = null
        return when (outcome) {
            ProviderOutcome.Cancelled -> AuthReauthenticationResult.CANCELLED
            is ProviderOutcome.Failed -> AuthReauthenticationResult.FAILED
            is ProviderOutcome.Proof ->
                try {
                    val refreshed =
                        identity.reauthenticate(
                            ProviderProof(provider, outcome.token, outcome.rawNonce)
                        )
                    if (refreshed.uid == account.uid) {
                        AuthReauthenticationResult.SUCCEEDED
                    } else {
                        AuthReauthenticationResult.FAILED
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: AuthGatewayException) {
                    AuthReauthenticationResult.FAILED
                }
        }
    }

    suspend fun completeTerminalAccountDeletion(accountUid: String): Boolean {
        generation += 1
        activeRequest?.let { it.second.cancel(it.first) }
        activeRequest = null
        return authTransitionMutex.withLock {
            accountSyncWriteGate.removeOwner(accountUid) removeOwner@{
                val currentUid = identity.current()?.uid
                if (currentUid != null && currentUid != accountUid) return@removeOwner false
                try {
                    if (currentUid != null) identity.signOut()
                    true
                } finally {
                    cache.activate(null)
                    mutableState.value = AuthUiState.SignedOut()
                }
            }
        }
    }

    suspend fun logout(): Boolean {
        generation += 1
        activeRequest?.let { it.second.cancel(it.first) }
        activeRequest = null
        return authTransitionMutex.withLock {
            val uid = identity.current()?.uid
            try {
                if (uid != null) beforeSignOut(uid)
                authTransition(uid) {
                    if (uid != null) beforeAuthRemoval(uid)
                    cache.clearVisible(uid)
                    cache.activate(null)
                    identity.signOut()
                    mutableState.value = AuthUiState.SignedOut()
                }
                true
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun establishSession(
        account: AuthAccount,
        returnRoute: String?,
        previousUid: String?,
    ) {
        // Firebase 인증이 끝난 직후, 어떤 중단점도 거치지 않고 세션을 먼저 공개한다.
        // 캐시 정리·프로필 쓰기·동기화는 모두 그 뒤에 오므로, 그중 어느 한 곳에서 취소되거나 실패해도
        // 방금 생긴 세션을 잃지 않는다.
        val generationAtStart = generation
        mutableState.value = AuthUiState.Authenticated(account, SyncSummary.EMPTY, returnRoute)
        // 이전 계정의 보이는 범위를 먼저 닫고 새 계정을 열어야 계정 간 데이터가 섞이지 않는다.
        ignoringServerFailure { cache.clearVisible(previousUid) }
        cache.activate(account.uid)
        publishSync(account, returnRoute, generationAtStart, lastKnownOrEmpty(account.uid))
        // 프로필 쓰기와 동기화는 서버 왕복이라 따로 실패할 수 있다. 실패해도 이미 공개한 세션을 되돌리지 않고
        // 마지막으로 알고 있는 동기화 상태를 그대로 유지한다.
        ignoringServerFailure { profiles.upsert(account) }
        var terminalAttemptFailed = false
        val sync =
            try {
                synchronizer.sync(account.uid)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: SyncNotAttemptedException) {
                null
            } catch (_: Exception) {
                terminalAttemptFailed = true
                null
            }
        if (sync == null) {
            val published =
                publishSync(
                    account,
                    returnRoute,
                    generationAtStart,
                    lastKnownOrEmpty(account.uid),
                )
            if (published && terminalAttemptFailed) {
                recordSyncEvent(ClientProductEvent.SYNC_FAILED)
            }
        } else if (publishSync(account, returnRoute, generationAtStart, sync)) {
            recordTerminalSyncAttempt(sync)
        }
    }

    /**
     * 동기화 요약만 갱신한다.
     *
     * 로그아웃이나 다른 계정 로그인이 먼저 일어난 뒤 늦게 도착한 결과가 현재 상태를 덮어쓰지 않도록, 세대와 계정이 모두 그대로일 때만 반영한다.
     */
    private fun publishSync(
        account: AuthAccount,
        returnRoute: String?,
        generationAtStart: Long,
        sync: SyncSummary,
    ): Boolean {
        if (generation != generationAtStart) return false
        val current = mutableState.value
        if (current !is AuthUiState.Authenticated || current.account.uid != account.uid)
            return false
        mutableState.value = AuthUiState.Authenticated(account, sync, returnRoute)
        return true
    }

    private fun recordTerminalSyncAttempt(sync: SyncSummary) {
        val event =
            when {
                sync.failures.isNotEmpty() -> ClientProductEvent.SYNC_FAILED
                sync.completed == SyncDomain.entries.toSet() -> ClientProductEvent.SYNC_COMPLETED
                else -> return
            }
        recordSyncEvent(event)
    }

    private fun recordSyncEvent(event: ClientProductEvent) {
        try {
            productEventRecorder.record(event)
        } catch (_: Exception) {
            // Telemetry cannot alter the established session or synchronization result.
        }
    }

    /** 마지막으로 알고 있는 동기화 요약을 읽는다. 이것까지 실패하면 빈 요약으로 두고 세션은 지킨다. */
    private suspend fun lastKnownOrEmpty(accountUid: String): SyncSummary =
        ignoringServerFailure { synchronizer.lastKnown(accountUid) } ?: SyncSummary.EMPTY

    /**
     * 서버 왕복 실패를 삼키되 취소는 그대로 전파한다.
     *
     * 취소까지 삼키면 화면을 떠난 뒤에도 작업이 계속 돌아 구조적 동시성이 깨진다.
     */
    private suspend fun <T> ignoringServerFailure(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
}
