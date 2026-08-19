package com.planterior.helper.feature.auth

import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.functions.FirebaseFunctions
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class FirebaseIdentityAdapter(private val auth: FirebaseAuth) : FirebaseIdentityGateway {
    override fun current(): AuthAccount? = auth.currentUser?.toAccount()

    override suspend fun signIn(proof: ProviderProof): AuthAccount =
        try {
            val credential = proof.credential()
            requireNotNull(auth.signInWithCredential(credential).await().user).toAccount()
        } catch (error: Exception) {
            throw error.toGatewayFailure(proof.provider)
        }

    override suspend fun reauthenticate(proof: ProviderProof): AuthAccount =
        try {
            val user = auth.currentUser ?: throw AuthGatewayException(AuthFailure.InvalidCredential)
            user.reauthenticate(proof.credential()).await()
            requireNotNull(auth.currentUser).toAccount()
        } catch (error: Exception) {
            throw error.toGatewayFailure(proof.provider)
        }

    override suspend fun link(proof: ProviderProof): AuthAccount =
        try {
            val user = auth.currentUser ?: throw AuthGatewayException(AuthFailure.InvalidCredential)
            requireNotNull(user.linkWithCredential(proof.credential()).await().user).toAccount()
        } catch (error: Exception) {
            throw error.toGatewayFailure(proof.provider)
        }

    override suspend fun signOut() = auth.signOut()

    private fun ProviderProof.credential() =
        when (provider) {
            AuthProvider.GOOGLE -> GoogleAuthProvider.getCredential(token, null)
            AuthProvider.APPLE -> {
                val nonce = rawNonce ?: throw AuthGatewayException(AuthFailure.InvalidCredential)
                OAuthProvider.newCredentialBuilder("apple.com")
                    .setIdTokenWithRawNonce(token, nonce)
                    .build()
            }
        }

    private fun FirebaseUser.toAccount(): AuthAccount {
        val providers =
            providerData
                .mapNotNull {
                    when (it.providerId) {
                        GoogleAuthProvider.PROVIDER_ID -> AuthProvider.GOOGLE
                        "apple.com" -> AuthProvider.APPLE
                        else -> null
                    }
                }
                .toSet()
        return AuthAccount(uid, email, displayName, providers)
    }

    private fun Exception.toGatewayFailure(provider: AuthProvider): AuthGatewayException =
        when (this) {
            is AuthGatewayException -> this
            is FirebaseAuthUserCollisionException ->
                AuthGatewayException(AuthFailure.AccountCollision(provider), this)
            is FirebaseAuthInvalidCredentialsException ->
                AuthGatewayException(AuthFailure.InvalidCredential, this)
            is FirebaseNetworkException ->
                AuthGatewayException(AuthFailure.NetworkUnavailable, this)
            else -> AuthGatewayException(AuthFailure.Unknown, this)
        }
}

fun interface AccountProfileCallable {
    suspend fun call(data: Map<String, Any?>)
}

class FirestoreAccountProfileStore
internal constructor(
    private val callable: AccountProfileCallable,
    private val accountZone: () -> ZoneId,
) : AccountProfileStore {
    constructor(
        functions: FirebaseFunctions
    ) : this(
        AccountProfileCallable { data ->
            functions.getHttpsCallable("updateAccountProfile").call(data).await()
        },
        ZoneId::systemDefault,
    )

    override suspend fun upsert(account: AuthAccount) {
        callable.call(
            mapOf(
                "expectedOwnerUid" to account.uid,
                "displayName" to account.displayName,
                "providers" to account.providers.map { it.name }.sorted(),
                "zoneId" to accountZone().id,
            )
        )
    }
}

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
