package com.planterior.helper.feature.auth

import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.time.ZoneId
import java.util.UUID
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

class FirestoreAccountProfileStore(private val firestore: FirebaseFirestore) : AccountProfileStore {
    override suspend fun upsert(account: AuthAccount) {
        firestore
            .runTransaction { transaction ->
                val reference = firestore.document("users/${account.uid}")
                val existing = transaction.get(reference)
                val previousRevision =
                    if (existing.exists()) existing.getLong("revision") ?: 0L else 0L
                transaction.set(
                    reference,
                    mapOf(
                        "ownerUid" to account.uid,
                        "displayName" to account.displayName,
                        "zoneId" to ZoneId.systemDefault().id,
                        "providers" to account.providers.map { it.name }.sorted(),
                        "revision" to previousRevision + 1,
                        "expectedRevision" to previousRevision,
                        "idempotencyKey" to UUID.randomUUID().toString().replace("-", ""),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }
            .await()
    }
}

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
