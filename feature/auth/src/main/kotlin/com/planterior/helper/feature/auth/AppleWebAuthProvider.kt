package com.planterior.helper.feature.auth

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import java.net.URI
import kotlinx.coroutines.CompletableDeferred

interface AppleAuthCallable {
    suspend fun begin(
        nonceHash: String,
        codeChallenge: String,
        state: String,
    ): AppleAuthorizationStart

    suspend fun complete(sessionId: String, state: String, codeVerifier: String): String
}

data class AppleAuthorizationStart(val sessionId: String, val authorizationUrl: String)

fun interface WebAuthorizationLauncher {
    fun open(url: Uri)
}

class AppleWebAuthProvider(
    private val callable: AppleAuthCallable,
    private val launcher: WebAuthorizationLauncher,
    private val sessionFactory: () -> AppleWebSession = AppleWebSession::create,
) : AuthProviderAdapter {
    override val provider = AuthProvider.APPLE
    private var pending: Pending? = null

    override suspend fun acquire(requestId: Long): ProviderOutcome {
        val session = sessionFactory()
        val result = CompletableDeferred<ProviderOutcome>()
        return try {
            val start = callable.begin(session.nonceHash, session.codeChallenge, session.state)
            val uri = start.authorizationUrl.toUri()
            if (uri.scheme != "https" || uri.host != "appleid.apple.com") {
                return ProviderOutcome.Failed(AuthFailure.InvalidCredential)
            }
            pending = Pending(requestId, session, start.sessionId, result)
            launcher.open(uri)
            result.await()
        } catch (error: AuthGatewayException) {
            ProviderOutcome.Failed(error.failure)
        } catch (_: Exception) {
            ProviderOutcome.Failed(AuthFailure.ProviderUnavailable)
        } finally {
            if (pending?.requestId == requestId) pending = null
        }
    }

    suspend fun handleCallback(uri: URI): Boolean {
        val active = pending ?: return false
        return try {
            val callback = active.session.validateCallback(uri, java.time.Instant.now())
            if (callback.sessionId != active.serverSessionId)
                throw AuthGatewayException(AuthFailure.InvalidCredential)
            val idToken =
                callable.complete(callback.sessionId, active.session.state, callback.codeVerifier)
            active.result.complete(ProviderOutcome.Proof(idToken, callback.rawNonce))
            true
        } catch (error: AuthGatewayException) {
            active.result.complete(ProviderOutcome.Failed(error.failure))
            true
        } catch (_: Exception) {
            active.result.complete(ProviderOutcome.Failed(AuthFailure.ProviderUnavailable))
            true
        }
    }

    override fun cancel(requestId: Long) {
        pending?.takeIf { it.requestId == requestId }?.result?.complete(ProviderOutcome.Cancelled)
    }

    private data class Pending(
        val requestId: Long,
        val session: AppleWebSession,
        val serverSessionId: String,
        val result: CompletableDeferred<ProviderOutcome>,
    )
}

fun ActivityWebAuthorizationLauncher(activity: android.app.Activity) =
    WebAuthorizationLauncher { url ->
        CustomTabsIntent.Builder().build().launchUrl(activity, url)
    }
