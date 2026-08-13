package com.planterior.helper.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.planterior.helper.core.designsystem.component.PlanteriorScreenScaffold
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme

@Composable
fun AuthScreen(
    state: AuthUiState,
    onGoogle: () -> Unit,
    onApple: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlanteriorScreenScaffold(title = "로그인", modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "계정으로 식물과 관리 기록을 안전하게 동기화하세요.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            when (state) {
                AuthUiState.Restoring -> AuthProgress("세션을 확인하고 있어요")
                is AuthUiState.SigningIn -> {
                    AuthProgress("로그인을 완료하고 있어요")
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth().testTag("auth-cancel"),
                    ) {
                        Text("취소")
                    }
                }
                is AuthUiState.SignedOut -> {
                    state.failure?.let { AuthErrorMessage(it, onRetry) }
                    Button(
                        onClick = onGoogle,
                        modifier = Modifier.fillMaxWidth().testTag("auth-google"),
                    ) {
                        Text("Google로 계속하기")
                    }
                    OutlinedButton(
                        onClick = onApple,
                        modifier = Modifier.fillMaxWidth().testTag("auth-apple"),
                    ) {
                        Text("Apple로 계속하기")
                    }
                }
                is AuthUiState.LinkConsentRequired -> AuthNotice("계정을 연결하려면 다시 확인해 주세요.")
                is AuthUiState.ReauthenticationRequired ->
                    AuthNotice("계정 연결 전에 기존 계정으로 다시 인증해 주세요.")
                is AuthUiState.LinkConflict -> AuthNotice("이미 다른 계정에 연결된 로그인 수단이에요.")
                is AuthUiState.LinkFailure -> AuthErrorMessage(state.failure, onRetry)
                is AuthUiState.Authenticated -> {
                    if (state.sync.failures.isNotEmpty())
                        AuthNotice("일부 데이터는 불러오지 못했어요. 연결 후 다시 시도할 수 있어요.")
                }
            }
        }
    }
}

@Composable
private fun AuthProgress(label: String) {
    CircularProgressIndicator(modifier = Modifier.testTag("auth-progress"))
    Text(label, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun AuthNotice(message: String) {
    Text(
        message,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("auth-notice"),
    )
}

@Composable
private fun AuthErrorMessage(failure: AuthFailure, onRetry: () -> Unit) {
    val message =
        when (failure) {
            AuthFailure.Cancelled -> "로그인이 취소됐어요."
            AuthFailure.ProviderUnavailable -> "로그인 서비스를 사용할 수 없어요."
            AuthFailure.NetworkUnavailable -> "네트워크 연결을 확인해 주세요."
            AuthFailure.ConfigurationMissing -> "로그인 설정을 확인할 수 없어요."
            is AuthFailure.AccountCollision -> "이미 다른 계정에 연결된 로그인 수단이에요."
            is AuthFailure.AlreadyLinked -> "이미 연결된 로그인 수단이에요."
            else -> "로그인 정보를 확인하지 못했어요."
        }
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().testTag("auth-error"),
    )
    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("auth-retry")) {
        Text("다시 시도")
    }
}
