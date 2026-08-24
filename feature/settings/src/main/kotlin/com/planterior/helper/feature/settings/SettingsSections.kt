package com.planterior.helper.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.component.PlanteriorCard
import com.planterior.helper.core.designsystem.component.PlanteriorDestructiveButton
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.feature.auth.AuthProviderManagement
import com.planterior.helper.feature.auth.AuthUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun SettingsProfile(authState: AuthUiState) {
    val account = (authState as? AuthUiState.Authenticated)?.account
    PlanteriorCard(modifier = Modifier.testTag("settings.profile")) {
        Text(account?.displayName ?: "Planterior 사용자", style = MaterialTheme.typography.titleLarge)
        account?.email?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = PlanteriorTheme.spacing.small),
            )
        }
    }
}

@Composable
internal fun NotificationSettingsSection(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection("알림 관리", "settings.section.notifications") {
        SettingsSwitchRow(
            "물 주기 알림",
            state.wateringNotificationsEnabled,
            actions.onWateringNotificationsChanged,
            "settings.watering-switch",
        )
        SettingsSwitchRow(
            "날씨 알림",
            state.weatherNotificationsEnabled,
            actions.onWeatherNotificationsChanged,
            "settings.weather-switch",
        )
        SettingsValueRow(
            "알림 금지 시간",
            state.quietHoursSummary,
            "settings.quiet-hours",
            actions.onOpenQuietHours,
        )
        SettingsValueRow(
            "알림 시간과 식물별 설정",
            "관리",
            "settings.watering-detail",
            actions.onOpenWateringSettings,
        )
    }
}

@Composable
internal fun EnvironmentSettingsSection(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection("지역 및 환경", "settings.section.environment") {
        SettingsValueRow(
            "관리 지역",
            state.regionName,
            "settings.region",
            actions.onOpenRegion,
        )
        SettingsValueRow(
            "기기 위치 권한",
            state.osLocationPermission.label(),
            "settings.os-location",
            actions.onOpenRegion,
        )
        SettingsSwitchRow(
            "앱의 현재 위치 사용 동의",
            state.appLocationConsentGranted,
            { enabled ->
                if (enabled) actions.onOpenRegion() else actions.onRevokeLocationConsent()
            },
            "settings.location-consent",
        )
        SettingsProse(
            text = SETTINGS_LOCATION_DISCLOSURE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings.location-disclosure"),
        )
    }
}

@Composable
internal fun DataSettingsSection(state: SettingsUiState) {
    SettingsSection("데이터 관리", "settings.section.data") {
        SettingsValueRow(
            "마지막 동기화",
            state.lastSyncAt.displayTime(),
            "settings.last-sync",
            null,
        )
        SettingsProse(
            text = SETTINGS_PHOTO_DISCLOSURE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings.photo-disclosure"),
        )
    }
}

@Composable
internal fun AccountSettingsSection(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection("계정", "settings.section.account") {
        AuthProviderManagement(
            state = state.authState,
            onLink = actions.onLinkProvider,
            showAccountSummary = false,
        )
        SettingsValueRow("개인정보 처리방침", "보기", "settings.privacy", actions.onOpenPrivacy)
        SettingsValueRow("앱 버전", state.appVersion, "settings.version", null)
        Box(
            modifier =
                Modifier.fillMaxWidth().testTag("account-logout").semantics(
                    mergeDescendants = true
                ) {
                    onClick {
                        actions.onLogout()
                        true
                    }
                }
        ) {
            SettingsValueRow("로그아웃", "", "settings.logout", actions.onLogout)
        }
    }
}

@Composable
internal fun OtherSettingsSection(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection("기타 설정", "settings.section.other") {
        SettingsValueRow(
            "기기 알림 상태",
            state.osNotificationPermission.label(),
            "settings.os-notifications",
            actions.onOpenNotificationSettings,
        )
        SettingsProse(
            text = "기기 알림이 꺼져도 앱의 물 주기·날씨 알림 선택은 바뀌지 않아요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PlanteriorDestructiveButton(
            onClick = actions.onOpenAccountDeletion,
            modifier = Modifier.testTag("account-delete"),
        ) {
            Text("계정 삭제")
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        PlanteriorCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .sizeIn(minHeight = 48.dp)
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .semantics(mergeDescendants = true) {
                    stateDescription = if (checked) "켜짐" else "꺼짐"
                }
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun SettingsValueRow(
    label: String,
    value: String,
    tag: String,
    onClick: (() -> Unit)?,
) {
    val stacked = LocalDensity.current.fontScale >= 2f
    PlanteriorCard(
        onClick = onClick,
        modifier = Modifier.sizeIn(minHeight = 48.dp).testTag(tag),
        contentPadding = PlanteriorTheme.spacing.medium,
    ) {
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.small)) {
                Text(label, modifier = Modifier.testTag("$tag.label"))
                if (value.isNotEmpty()) TrailingValue(value, "$tag.value")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
            ) {
                Text(label, modifier = Modifier.weight(1f).testTag("$tag.label"))
                if (value.isNotEmpty()) TrailingValue(value, "$tag.value")
            }
        }
    }
}

@Composable
private fun TrailingValue(value: String, tag: String) {
    Text(
        value,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(tag),
    )
}

private fun DevicePermissionState.label(): String =
    when (this) {
        DevicePermissionState.ALLOWED -> "허용됨"
        DevicePermissionState.DENIED -> "허용 안 됨"
        DevicePermissionState.NOT_REQUESTED -> "확인 필요"
    }

private fun Instant?.displayTime(): String =
    this?.atZone(ZoneId.systemDefault())?.format(LAST_SYNC_FORMAT) ?: "아직 동기화하지 않음"

private val LAST_SYNC_FORMAT = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN)
