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
        val outcome =
            try {
                adapter.acquire(requestId)
            } catch (error: AuthGatewayException) {
                ProviderOutcome.Failed(error.failure)
            } catch (_: Exception) {
                ProviderOutcome.Failed(AuthFailure.Unknown)
            }
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
        cache.clearVisible(previousUid)
        cache.activate(account.uid)
        profiles.upsert(account)
        val sync = synchronizer.sync(account.uid)
        mutableState.value = AuthUiState.Authenticated(account, sync, returnRoute)
    }
}
