package com.planterior.helper.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.planterior.helper.core.designsystem.component.PlanteriorScreenScaffold
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    bottomBar: @Composable () -> Unit = {},
) {
    PlanteriorScreenScaffold(title = "설정", bottomBar = bottomBar) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag("settings.screen"),
            verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.huge),
        ) {
            SettingsProfile(state.authState)
            NotificationSettingsSection(state, actions)
            EnvironmentSettingsSection(state, actions)
            DataSettingsSection(state)
            AccountSettingsSection(state, actions)
            OtherSettingsSection(state, actions)
        }
    }
}
