package com.planterior.helper.feature.watering

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.planterior.helper.core.designsystem.component.PlanteriorCard
import com.planterior.helper.core.designsystem.component.PlanteriorScreenScaffold
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.PersonalPlantId
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

object WateringNotificationSettingsTestTags {
    const val PERMISSION_GUIDANCE = "watering-notifications:permission-guidance"
    const val DEFAULT_TIME = "watering-notifications:default-time"
    const val SAVE = "watering-notifications:save"
    const val RETRY = "watering-notifications:retry"
    const val EMPTY = "watering-notifications:empty"

    fun plantEnabled(id: String) = "watering-notifications:$id:enabled"

    fun plantTime(id: String) = "watering-notifications:$id:time"
}

private class WateringNotificationSettingsViewModel(
    val controller: WateringNotificationSettingsController
) : ViewModel()

@Composable
fun WateringNotificationSettingsRoute(
    repository: WateringNotificationSettingsRepository,
    notificationPermissionGranted: Boolean,
    canRequestNotificationPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val model =
        viewModel<WateringNotificationSettingsViewModel>(
            factory =
                viewModelFactory {
                    initializer {
                        WateringNotificationSettingsViewModel(
                            WateringNotificationSettingsController(
                                repository,
                                createSavedStateHandle(),
                            )
                        )
                    }
                }
        )
    val state by model.controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(model.controller) { model.controller.loadIfNeeded() }
    WateringNotificationSettingsScreen(
        state,
        notificationPermissionGranted,
        canRequestNotificationPermission,
        onRequestPermission,
        onOpenSystemSettings,
        onBack,
        onGlobalEnabled = model.controller::setGlobalEnabled,
        onDefaultTime = model.controller::setDefaultTime,
        onPlantEnabled = model.controller::setPlantEnabled,
        onPlantTime = model.controller::setPlantTime,
        onSave = { scope.launch { model.controller.save() } },
        onRetryLoad = { scope.launch { model.controller.load() } },
    )
}

@Composable
fun WateringNotificationSettingsScreen(
    state: WateringNotificationSettingsState,
    notificationPermissionGranted: Boolean,
    canRequestNotificationPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onBack: () -> Unit,
    onGlobalEnabled: (Boolean) -> Unit,
    onDefaultTime: (LocalTime) -> Unit,
    onPlantEnabled: (PersonalPlantId, Boolean) -> Unit,
    onPlantTime: (PersonalPlantId, LocalTime?) -> Unit,
    onSave: () -> Unit,
    onRetryLoad: () -> Unit,
) {
    PlanteriorScreenScaffold(title = "물 주기 알림") {
        TextButton(onClick = onBack, modifier = Modifier.minimumAction()) { Text("홈으로 돌아가기") }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
        ) {
            if (!notificationPermissionGranted) {
                PermissionGuidance(
                    canRequestNotificationPermission,
                    onRequestPermission,
                    onOpenSystemSettings,
                )
            }
            when (state) {
                WateringNotificationSettingsState.Loading ->
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                WateringNotificationSettingsState.Empty ->
                    PlanteriorCard(
                        modifier =
                            Modifier.fillMaxWidth()
                                .testTag(WateringNotificationSettingsTestTags.EMPTY)
                    ) {
                        Text("등록한 식물이 없어요", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "도감에 식물을 등록하면 식물별 알림을 설정할 수 있어요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
                        )
                    }
                WateringNotificationSettingsState.Error -> {
                    StatusCard("알림 설정을 불러오지 못했어요", "연결 상태를 확인하고 다시 시도해 주세요.")
                    Button(onClick = onRetryLoad, modifier = Modifier.minimumAction()) {
                        Text("다시 시도")
                    }
                }
                is WateringNotificationSettingsState.Ready -> {
                    state.errorMessage?.let { message ->
                        StatusCard("최신 설정을 불러왔어요", message)
                    }
                    SettingsContent(
                        state.settings,
                        saving = false,
                        dirty = false,
                        onGlobalEnabled,
                        onDefaultTime,
                        onPlantEnabled,
                        onPlantTime,
                        onSave,
                    )
                }
                is WateringNotificationSettingsState.Editing ->
                    SettingsContent(
                        state.draft,
                        saving = false,
                        dirty = true,
                        onGlobalEnabled,
                        onDefaultTime,
                        onPlantEnabled,
                        onPlantTime,
                        onSave,
                    )
                is WateringNotificationSettingsState.Saving ->
                    SettingsContent(
                        state.draft,
                        saving = true,
                        dirty = true,
                        onGlobalEnabled,
                        onDefaultTime,
                        onPlantEnabled,
                        onPlantTime,
                        onSave,
                    )
                is WateringNotificationSettingsState.SaveFailed -> {
                    StatusCard("알림 설정을 저장하지 못했어요", "편집한 값은 그대로 두었어요. 같은 설정으로 다시 시도해 주세요.")
                    SettingsContent(
                        state.draft,
                        saving = false,
                        dirty = true,
                        onGlobalEnabled,
                        onDefaultTime,
                        onPlantEnabled,
                        onPlantTime,
                        onSave,
                        saveTag = WateringNotificationSettingsTestTags.RETRY,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionGuidance(
    canRequestPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    PlanteriorCard(
        modifier =
            Modifier.fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(WateringNotificationSettingsTestTags.PERMISSION_GUIDANCE),
        containerColor = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text("기기 알림이 꺼져 있어요", style = MaterialTheme.typography.titleMedium)
        Text(
            "권한이 없어도 앱에서 예정일을 확인하고 물 주기를 완료할 수 있어요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = PlanteriorTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
        ) {
            if (canRequestPermission) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth().minimumAction(),
                ) {
                    Text("알림 허용")
                }
            } else {
                TextButton(
                    onClick = onOpenSystemSettings,
                    modifier = Modifier.fillMaxWidth().minimumAction(),
                ) {
                    Text("기기 설정")
                }
            }
        }
    }
}

@Composable
private fun SettingsContent(
    settings: WateringNotificationSettings,
    saving: Boolean,
    dirty: Boolean,
    onGlobalEnabled: (Boolean) -> Unit,
    onDefaultTime: (LocalTime) -> Unit,
    onPlantEnabled: (PersonalPlantId, Boolean) -> Unit,
    onPlantTime: (PersonalPlantId, LocalTime?) -> Unit,
    onSave: () -> Unit,
    saveTag: String = WateringNotificationSettingsTestTags.SAVE,
) {
    val context = LocalContext.current
    PlanteriorCard(modifier = Modifier.fillMaxWidth()) {
        SettingSwitchRow("물 주기 알림", settings.global.enabled, !saving, onGlobalEnabled)
        TimeButton(
            label = "기본 알림 시간",
            time = settings.global.defaultTime,
            enabled = !saving,
            tag = WateringNotificationSettingsTestTags.DEFAULT_TIME,
            onClick = {
                TimePickerDialog(
                        context,
                        { _, hour, minute -> onDefaultTime(LocalTime.of(hour, minute)) },
                        settings.global.defaultTime.hour,
                        settings.global.defaultTime.minute,
                        true,
                    )
                    .show()
            },
        )
    }
    if (settings.plants.isEmpty()) {
        PlanteriorCard(
            modifier = Modifier.fillMaxWidth().testTag(WateringNotificationSettingsTestTags.EMPTY)
        ) {
            Text("등록한 식물이 없어요", style = MaterialTheme.typography.titleMedium)
            Text(
                "기본 시간은 저장할 수 있어요. 도감에 식물을 등록하면 식물별 알림도 설정할 수 있어요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
            )
        }
    }
    settings.plants.forEach { plant ->
        PlanteriorCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                plant.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            SettingSwitchRow(
                "이 식물 알림",
                plant.enabled,
                !saving,
                { onPlantEnabled(plant.plantId, it) },
                Modifier.testTag(
                    WateringNotificationSettingsTestTags.plantEnabled(plant.plantId.value)
                ),
            )
            TimeButton(
                label = "알림 시간",
                time = plant.timeOverride,
                enabled = !saving,
                tag = WateringNotificationSettingsTestTags.plantTime(plant.plantId.value),
                onClick = {
                    val initial = plant.timeOverride ?: settings.global.defaultTime
                    TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                onPlantTime(plant.plantId, LocalTime.of(hour, minute))
                            },
                            initial.hour,
                            initial.minute,
                            true,
                        )
                        .show()
                },
            )
            if (plant.timeOverride != null) {
                TextButton(
                    onClick = { onPlantTime(plant.plantId, null) },
                    enabled = !saving,
                    modifier = Modifier.minimumAction(),
                ) {
                    Text("기본 시간 사용")
                }
            }
        }
    }
    if (dirty) {
        Button(
            onClick = onSave,
            enabled = !saving,
            modifier =
                Modifier.fillMaxWidth()
                    .minimumAction()
                    .semantics {
                        if (saving) stateDescription = "저장 중, 편집 잠김"
                    }
                    .testTag(saveTag),
        ) {
            if (saving) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(PlanteriorTheme.spacing.extraLarge),
                        strokeWidth = PlanteriorTheme.spacing.extraSmall,
                    )
                    Text("저장 중")
                }
            } else {
                Text("알림 설정 저장")
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .sizeIn(minHeight = PlanteriorTheme.spacing.huge * 2)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun TimeButton(
    label: String,
    time: LocalTime?,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().minimumAction().testTag(tag),
    ) {
        Text("$label: ${time?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "기본 시간 사용"}")
    }
}

@Composable
private fun StatusCard(title: String, body: String) {
    PlanteriorCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier =
                Modifier.semantics {
                    error(body)
                    liveRegion = LiveRegionMode.Assertive
                },
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
        )
    }
}

@Composable
private fun Modifier.minimumAction(): Modifier =
    sizeIn(
        minWidth = PlanteriorTheme.spacing.huge * 2,
        minHeight = PlanteriorTheme.spacing.huge * 2,
    )
