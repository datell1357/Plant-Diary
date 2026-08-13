package com.planterior.helper.feature.auth

import android.app.Activity
import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.edit
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import java.security.MessageDigest
import org.json.JSONObject

@Composable
fun DebugAuthControls(onGoogle: () -> Unit, onApple: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().testTag("debug-auth-controls"),
        verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.small),
    ) {
        Text("QA 인증 시나리오")
        QaButton("Google 신규·기존", "qa-google") {
            scenario(context, GOOGLE_A)
            onGoogle()
        }
        QaButton("Apple 신규·기존", "qa-apple") {
            scenario(context, APPLE_B)
            onApple()
        }
        QaButton("로그인 취소", "qa-cancel") {
            scenario(context, CANCEL)
            onGoogle()
        }
        QaButton("Apple 토큰 실패", "qa-failure") {
            scenario(context, FAILURE)
            onApple()
        }
        QaButton("부분 동기화", "qa-partial") {
            scenario(context, PARTIAL)
            onGoogle()
        }
    }
}

@Composable
private fun QaButton(label: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(label)
    }
}

fun prepareDebugAuth(activity: Activity) {
    if (android.os.Build.VERSION.SDK_INT >= 37) {
        activity.requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), 4904)
    }
}

fun debugAuthProvider(context: Context, delegate: AuthProviderAdapter): AuthProviderAdapter =
    object : AuthProviderAdapter {
        override val provider = delegate.provider

        override suspend fun acquire(requestId: Long): ProviderOutcome =
            when (scenario(context)) {
                GOOGLE_A,
                PARTIAL ->
                    if (provider == AuthProvider.GOOGLE) proof(provider, "qa-google-account-a")
                    else proof(provider, "qa-apple-account-b")
                APPLE_B ->
                    if (provider == AuthProvider.APPLE) proof(provider, "qa-apple-account-b")
                    else proof(provider, "qa-google-account-a")
                CANCEL -> ProviderOutcome.Cancelled
                FAILURE ->
                    if (provider == AuthProvider.APPLE)
                        ProviderOutcome.Failed(AuthFailure.InvalidOrExpiredToken)
                    else delegate.acquire(requestId)
                else -> delegate.acquire(requestId)
            }

        override fun cancel(requestId: Long) = delegate.cancel(requestId)
    }

fun debugAccountSyncRemote(context: Context, delegate: AccountSyncRemote): AccountSyncRemote =
    object : AccountSyncRemote by delegate {
        override suspend fun miniHome(accountUid: String): RemoteMiniHome? {
            // 미니홈피 도메인만 실패시켜 부분 동기화를 만든다. 식물 관리는 그대로 유지되어야 한다.
            if (scenario(context) == PARTIAL) error("QA partial synchronization")
            return delegate.miniHome(accountUid)
        }

        override suspend fun verifyDomain(accountUid: String, domain: SyncDomain) {
            if (scenario(context) == PARTIAL && domain == SyncDomain.MINI_HOME) {
                error("QA partial synchronization")
            }
            delegate.verifyDomain(accountUid, domain)
        }
    }

private fun proof(provider: AuthProvider, subject: String): ProviderOutcome.Proof {
    val rawNonce = if (provider == AuthProvider.APPLE) "qa-nonce-$subject" else null
    val issuer =
        if (provider == AuthProvider.APPLE) "https://appleid.apple.com"
        else "https://accounts.google.com"
    val payload =
        JSONObject()
            .put("iss", issuer)
            .put("aud", "demo-planterior")
            .put("sub", subject)
            .put("email", "$subject@example.invalid")
            .put("email_verified", true)
            .put("iat", System.currentTimeMillis() / 1000)
            .put("exp", System.currentTimeMillis() / 1000 + 3600)
    rawNonce?.let { payload.put("nonce", sha256(it)) }
    val token =
        "${encode(JSONObject().put("alg", "none").toString())}.${encode(payload.toString())}."
    return ProviderOutcome.Proof(token, rawNonce)
}

private fun encode(value: String): String =
    Base64.encodeToString(
        value.toByteArray(),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

private fun scenario(context: Context): String =
    context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(SCENARIO, "")
        .orEmpty()

private fun scenario(context: Context, value: String) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
        putString(SCENARIO, value)
    }
}

private const val PREFERENCES = "auth-qa"
private const val SCENARIO = "scenario"
private const val GOOGLE_A = "google-a"
private const val APPLE_B = "apple-b"
private const val CANCEL = "cancel"
private const val FAILURE = "failure"
private const val PARTIAL = "partial"
