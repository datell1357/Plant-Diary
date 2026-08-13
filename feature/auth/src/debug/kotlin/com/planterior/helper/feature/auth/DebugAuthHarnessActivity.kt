package com.planterior.helper.feature.auth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.lifecycleScope
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import kotlinx.coroutines.launch

/**
 * Deterministic QA surface. This source set and its provider fixtures do not exist in release
 * artifacts.
 */
class DebugAuthHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra(EXTRA_SCENARIO) ?: GOOGLE_SUCCESS
        val identity = DebugIdentity()
        val coordinator =
            AuthCoordinator(
                mapOf(
                    AuthProvider.GOOGLE to DebugProvider(AuthProvider.GOOGLE, scenario),
                    AuthProvider.APPLE to DebugProvider(AuthProvider.APPLE, scenario),
                ),
                identity,
                AccountProfileStore {},
                object : AccountSessionCache {
                    override suspend fun clearVisible(accountUid: String?) = Unit

                    override fun activate(accountUid: String?) = Unit
                },
                AccountSynchronizer { SyncSummary(setOf(SyncDomain.PLANTS), emptyMap()) },
            )
        setContent {
            PlanteriorTheme {
                val state by coordinator.state.collectAsState()
                val scope = rememberCoroutineScope()
                AuthScreen(
                    state = state,
                    onGoogle = {
                        scope.launch {
                            coordinator.signIn(AuthProvider.GOOGLE, "planterior://home")
                        }
                    },
                    onApple = {
                        scope.launch { coordinator.signIn(AuthProvider.APPLE, "planterior://home") }
                    },
                    onCancel = coordinator::cancelSignIn,
                    onRetry = { scope.launch { coordinator.restore() } },
                )
            }
        }
        lifecycleScope.launch { coordinator.restore() }
    }

    private class DebugProvider(override val provider: AuthProvider, private val scenario: String) :
        AuthProviderAdapter {
        override suspend fun acquire(requestId: Long): ProviderOutcome =
            when (scenario) {
                CANCEL -> ProviderOutcome.Cancelled
                INVALID_APPLE ->
                    if (provider == AuthProvider.APPLE)
                        ProviderOutcome.Failed(AuthFailure.InvalidOrExpiredToken)
                    else ProviderOutcome.Proof("google-a")
                APPLE_SUCCESS ->
                    if (provider == AuthProvider.APPLE)
                        ProviderOutcome.Proof("apple-b", "debug-nonce")
                    else ProviderOutcome.Proof("google-a")
                else -> ProviderOutcome.Proof("google-a")
            }

        override fun cancel(requestId: Long) = Unit
    }

    private class DebugIdentity : FirebaseIdentityGateway {
        private var account: AuthAccount? = null

        override fun current() = account

        override suspend fun signIn(proof: ProviderProof): AuthAccount =
            AuthAccount(
                    uid =
                        if (proof.provider == AuthProvider.GOOGLE) "debug-account-a"
                        else "debug-account-b",
                    email = null,
                    displayName = "디버그 식집사",
                    providers = setOf(proof.provider),
                )
                .also { account = it }

        override suspend fun link(proof: ProviderProof) =
            requireNotNull(account)
                .copy(providers = requireNotNull(account).providers + proof.provider)

        override suspend fun signOut() {
            account = null
        }
    }

    companion object {
        const val EXTRA_SCENARIO = "authQaScenario"
        const val GOOGLE_SUCCESS = "google-success"
        const val APPLE_SUCCESS = "apple-success"
        const val CANCEL = "cancel"
        const val INVALID_APPLE = "invalid-apple"
    }
}
