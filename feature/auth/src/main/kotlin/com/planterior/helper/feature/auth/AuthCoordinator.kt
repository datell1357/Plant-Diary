package com.planterior.helper.feature.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuthCoordinator(
    private val providers: Map<AuthProvider, AuthProviderAdapter>,
    private val identity: FirebaseIdentityGateway,
    private val profiles: AccountProfileStore,
    private val cache: AccountSessionCache,
    private val synchronizer: AccountSynchronizer,
) {
    private val mutableState = MutableStateFlow<AuthUiState>(AuthUiState.Restoring)
    val state: StateFlow<AuthUiState> = mutableState.asStateFlow()
    private var generation = 0L
    private var activeRequest: Pair<Long, AuthProviderAdapter>? = null

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
                val previous = identity.current()?.uid
                val proof = ProviderProof(provider, outcome.token, outcome.rawNonce)
                try {
                    val account = identity.signIn(proof)
                    if (requestId != generation) return
                    establishSession(account, returnRoute, previous)
                } catch (error: AuthGatewayException) {
                    if (requestId == generation)
                        mutableState.value = AuthUiState.SignedOut(error.failure)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    // 로그인 성공 직후 화면이 전환되면 호출측 scope가 취소된다. 이미 세운 세션을 되돌리지 않고
                    // 취소만 그대로 전파해 구조적 동시성을 지킨다.
                    throw cancellation
                } catch (_: Exception) {
                    if (requestId == generation)
                        mutableState.value = AuthUiState.SignedOut(AuthFailure.Unknown)
                }
            }
        }
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

    suspend fun logout() {
        generation += 1
        activeRequest?.let { it.second.cancel(it.first) }
        activeRequest = null
        val uid = identity.current()?.uid
        cache.clearVisible(uid)
        cache.activate(null)
        identity.signOut()
        mutableState.value = AuthUiState.SignedOut()
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
        val sync = ignoringServerFailure { synchronizer.sync(account.uid) }
        publishSync(
            account,
            returnRoute,
            generationAtStart,
            sync ?: lastKnownOrEmpty(account.uid),
        )
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
    ) {
        if (generation != generationAtStart) return
        val current = mutableState.value
        if (current !is AuthUiState.Authenticated || current.account.uid != account.uid) return
        mutableState.value = AuthUiState.Authenticated(account, sync, returnRoute)
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
