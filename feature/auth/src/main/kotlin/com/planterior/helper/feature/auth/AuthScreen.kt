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
                    DebugAuthControls(onGoogle, onApple)
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
fun AuthAccountScreen(
    state: AuthUiState,
    onLink: (AuthProvider, Boolean) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    onNotificationSettings: () -> Unit = {},
    logoutLabel: String = "로그아웃",
    bottomBar: @Composable () -> Unit = {},
) {
    PlanteriorScreenScaffold(title = "설정", modifier = modifier, bottomBar = bottomBar) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                is AuthUiState.Authenticated -> {
                    Text(
                        "계정 ${state.account.uid}",
                        modifier = Modifier.fillMaxWidth().testTag("account-uid"),
                    )
                    Text(
                        "동기화 성공 ${state.sync.completed.size} · 실패 ${state.sync.failures.size}",
                        modifier = Modifier.fillMaxWidth().testTag("account-sync-summary"),
                    )
                    AuthProvider.entries.filterNot(state.account.providers::contains).forEach {
                        provider ->
                        OutlinedButton(
                            onClick = { onLink(provider, false) },
                            modifier =
                                Modifier.fillMaxWidth()
                                    .testTag("link-${provider.name.lowercase()}"),
                        ) {
                            Text("${provider.displayName()} 계정 연결")
                        }
                    }
                }
                is AuthUiState.LinkConsentRequired -> {
                    AuthNotice("현재 계정의 데이터는 유지됩니다. 다른 계정을 가져오거나 덮어쓰지 않고 로그인 수단만 연결합니다.")
                    Button(
                        onClick = { onLink(state.provider, true) },
                        modifier = Modifier.fillMaxWidth().testTag("link-consent-confirm"),
                    ) {
                        Text("동의하고 다시 인증")
                    }
                }
                is AuthUiState.ReauthenticationRequired -> AuthProgress("현재 계정으로 다시 인증하고 있어요")
                is AuthUiState.LinkConflict -> {
                    Text(
                        "이 로그인 수단은 다른 계정에서 사용 중이라 연결할 수 없어요. 현재 계정과 데이터는 변경되지 않았습니다.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().testTag("link-conflict"),
                    )
                }
                is AuthUiState.LinkFailure -> AuthErrorMessage(state.failure) {}
                is AuthUiState.SigningIn -> AuthProgress("로그인 수단을 연결하고 있어요")
                AuthUiState.Restoring -> AuthProgress("계정을 확인하고 있어요")
                is AuthUiState.SignedOut -> AuthNotice("로그인된 계정이 없어요.")
            }
            OutlinedButton(
                onClick = onNotificationSettings,
                modifier = Modifier.fillMaxWidth().testTag("account-notification-settings"),
            ) {
                Text("물 주기 알림 설정")
            }
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().testTag("account-logout"),
            ) {
                Text(logoutLabel)
            }
        }
    }
}

private fun AuthProvider.displayName(): String =
    when (this) {
        AuthProvider.GOOGLE -> "Google"
        AuthProvider.APPLE -> "Apple"
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
