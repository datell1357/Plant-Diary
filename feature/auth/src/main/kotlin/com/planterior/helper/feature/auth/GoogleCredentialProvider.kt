package com.planterior.helper.feature.auth

import android.app.Activity
import android.os.CancellationSignal
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class GoogleCredentialProvider(
    private val activity: Activity,
    private val serverClientId: String,
    private val credentialManager: CredentialManager = CredentialManager.create(activity),
) : AuthProviderAdapter {
    override val provider = AuthProvider.GOOGLE
    private var cancellation: Pair<Long, CancellationSignal>? = null

    override suspend fun acquire(requestId: Long): ProviderOutcome {
        if (serverClientId.isBlank())
            return ProviderOutcome.Failed(AuthFailure.ConfigurationMissing)
        val signal = CancellationSignal()
        cancellation = requestId to signal
        return try {
            val option =
                GetGoogleIdOption.Builder()
                    .setServerClientId(serverClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()
            val response =
                credentialManager.getCredential(activity, GetCredentialRequest(listOf(option)))
            val credential = response.credential
            if (
                credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                ProviderOutcome.Failed(AuthFailure.InvalidCredential)
            } else {
                ProviderOutcome.Proof(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            }
        } catch (_: GetCredentialCancellationException) {
            ProviderOutcome.Cancelled
        } catch (_: NoCredentialException) {
            ProviderOutcome.Failed(AuthFailure.ProviderUnavailable)
        } catch (_: Exception) {
            ProviderOutcome.Failed(AuthFailure.ProviderUnavailable)
        } finally {
            if (cancellation?.first == requestId) cancellation = null
        }
    }

    override fun cancel(requestId: Long) {
        cancellation?.takeIf { it.first == requestId }?.second?.cancel()
    }
}
