package com.planterior.helper.feature.auth

enum class AuthProvider {
    GOOGLE,
    APPLE,
}

data class AuthAccount(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val providers: Set<AuthProvider>,
)

data class ProviderProof(
    val provider: AuthProvider,
    val token: String,
    val rawNonce: String? = null,
)

sealed interface ProviderOutcome {
    data class Proof(val token: String, val rawNonce: String? = null) : ProviderOutcome

    data object Cancelled : ProviderOutcome

    data class Failed(val failure: AuthFailure) : ProviderOutcome
}

sealed interface AuthFailure {
    data object Cancelled : AuthFailure

    data object ProviderUnavailable : AuthFailure

    data object InvalidCredential : AuthFailure

    data object InvalidOrExpiredToken : AuthFailure

    data object ReplayedCallback : AuthFailure

    data object WrongAudienceOrIssuer : AuthFailure

    data object NetworkUnavailable : AuthFailure

    data object StaleCallback : AuthFailure

    data object ConfigurationMissing : AuthFailure

    data class AccountCollision(val provider: AuthProvider) : AuthFailure

    data class AlreadyLinked(val provider: AuthProvider) : AuthFailure

    data object Unknown : AuthFailure
}

class AuthGatewayException(val failure: AuthFailure, cause: Throwable? = null) : Exception(cause)

interface AuthProviderAdapter {
    val provider: AuthProvider

    suspend fun acquire(requestId: Long): ProviderOutcome

    fun cancel(requestId: Long)
}

interface FirebaseIdentityGateway {
    fun current(): AuthAccount?

    suspend fun signIn(proof: ProviderProof): AuthAccount

    suspend fun reauthenticate(proof: ProviderProof): AuthAccount

    suspend fun link(proof: ProviderProof): AuthAccount

    suspend fun signOut()
}

fun interface AccountProfileStore {
    suspend fun upsert(account: AuthAccount)
}

interface AccountSessionCache {
    suspend fun clearVisible(accountUid: String?)

    fun activate(accountUid: String?)
}

enum class SyncDomain {
    PLANTS,
    WATERING,
    NOTIFICATIONS,
    MINI_HOME,
}

enum class SyncStatus {
    SUCCESS,
    FAILED,
}

data class SyncRecord(
    val attemptedAt: java.time.Instant,
    val status: SyncStatus,
    val errorCode: String?,
)

data class SyncSummary(
    val completed: Set<SyncDomain>,
    val failures: Map<SyncDomain, String>,
    val records: Map<SyncDomain, SyncRecord> = emptyMap(),
) {
    val isPartial: Boolean
        get() = completed.isNotEmpty() && failures.isNotEmpty()

    companion object {
        val EMPTY = SyncSummary(emptySet(), emptyMap())
    }
}

fun interface AccountSynchronizer {
    suspend fun sync(accountUid: String): SyncSummary

    suspend fun lastKnown(accountUid: String): SyncSummary = SyncSummary.EMPTY
}

enum class AuthReauthenticationResult {
    SUCCEEDED,
    CANCELLED,
    FAILED,
}

sealed interface AuthUiState {
    data object Restoring : AuthUiState

    data class SignedOut(val failure: AuthFailure? = null) : AuthUiState

    data class SigningIn(val provider: AuthProvider) : AuthUiState

    data class Authenticated(
        val account: AuthAccount,
        val sync: SyncSummary,
        val returnRoute: String? = null,
    ) : AuthUiState

    data class LinkConsentRequired(val provider: AuthProvider) : AuthUiState

    data class ReauthenticationRequired(val provider: AuthProvider) : AuthUiState

    data class LinkConflict(val provider: AuthProvider) : AuthUiState

    data class LinkFailure(val provider: AuthProvider, val failure: AuthFailure) : AuthUiState
}
