package com.planterior.helper.feature.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.planterior.helper.core.designsystem.component.PlanteriorCard
import com.planterior.helper.core.designsystem.component.PlanteriorScreenScaffold
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object WeatherTestTags {
    const val SCREEN = "weather:screen"
    const val LIST = "weather:list"
    const val CURRENT = "weather:current"
    const val STALE = "weather:stale"
    const val REFRESH = "weather:refresh"
    const val REGION_QUERY = "weather:region-query"
    const val GLOBAL_ALERT = "weather:global-alert"
    const val SAVE_ALERTS = "weather:save-alerts"
    const val NOTIFICATION_PERMISSION = "weather:notification-permission"
    const val LOCATION_CONSENT = "weather:location-consent"

    fun plantAlert(plantId: String) = "weather:plant-alert:$plantId"
}

@Composable
fun WeatherRoute(
    repository: WeatherRepository,
    locationGateway: WeatherLocationGateway,
    onBack: () -> Unit,
    onOpenPlant: (String) -> Unit,
    onOpenLocationSettings: () -> Unit,
    permissionCapabilities: WeatherPermissionCapabilityStore? = null,
    notificationPermissionGranted: Boolean = true,
    canRequestNotificationPermission: Boolean = false,
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenCollection: () -> Unit = {},
    focusedPlantId: String? = null,
    clock: Clock = Clock.systemUTC(),
) {
    val controller =
        viewModel<WeatherController>(
            factory =
                viewModelFactory {
                    initializer {
                        WeatherController(
                            repository,
                            locationGateway,
                            createSavedStateHandle(),
                            clock,
                            permissionCapabilities = permissionCapabilities,
                        )
                    }
                }
        )
    DisposableEffect(controller, locationGateway) {
        controller.updateLocationGateway(locationGateway)
        onDispose { controller.removeLocationGateway(locationGateway) }
    }
    val state by controller.state.collectAsState()
    LifecycleResumeEffect(controller) {
        controller.reconcileLocationPermission()
        onPauseOrDispose {}
    }
    WeatherScreen(
        state = state,
        onBack = onBack,
        onRefresh = controller::refresh,
        onUseCurrentLocation = controller::useCurrentLocation,
        onOpenLocationSettings = onOpenLocationSettings,
        onRevokeLocationConsent = controller::revokeLocationConsent,
        notificationPermissionGranted = notificationPermissionGranted,
        canRequestNotificationPermission = canRequestNotificationPermission,
        onRequestNotificationPermission = onRequestNotificationPermission,
        onOpenNotificationSettings = onOpenNotificationSettings,
        onSearchQuery = controller::changeSearchQuery,
        onSearch = controller::searchRegions,
        onChooseRegion = controller::chooseManualRegion,
        onOpenPlant = onOpenPlant,
        onSaveAlerts = controller::saveAlerts,
        onOpenCollection = onOpenCollection,
        focusedPlantId = focusedPlantId,
    )
}

@Composable
fun WeatherScreen(
    state: WeatherUiState,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onUseCurrentLocation: () -> Unit = {},
    onOpenLocationSettings: () -> Unit = {},
    onRevokeLocationConsent: () -> Unit = {},
    notificationPermissionGranted: Boolean = true,
    canRequestNotificationPermission: Boolean = false,
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onSearchQuery: (String) -> Unit = {},
    onSearch: () -> Unit = {},
    onChooseRegion: (WeatherRegion) -> Unit = {},
    onOpenPlant: (String) -> Unit = {},
    onSaveAlerts: (Boolean, Map<String, Boolean>) -> Unit = { _, _ -> },
    onOpenCollection: () -> Unit = {},
    focusedPlantId: String? = null,
) {
    PlanteriorScreenScaffold(
        title = "날씨 관리",
        modifier = Modifier.testTag(WeatherTestTags.SCREEN),
    ) {
        when (state) {
            WeatherUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                Text("날씨 정보를 불러오고 있어요")
            }
            WeatherUiState.SignedOut -> Text("로그인하면 식물별 날씨 주의를 확인할 수 있어요.")
            is WeatherUiState.Ready ->
                WeatherReadyContent(
                    state,
                    onBack,
                    onRefresh,
                    onUseCurrentLocation,
                    onOpenLocationSettings,
                    onRevokeLocationConsent,
                    notificationPermissionGranted,
                    canRequestNotificationPermission,
                    onRequestNotificationPermission,
                    onOpenNotificationSettings,
                    onSearchQuery,
                    onSearch,
                    onChooseRegion,
                    onOpenPlant,
                    onSaveAlerts,
                    onOpenCollection,
                    focusedPlantId,
                )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.WeatherReadyContent(
    state: WeatherUiState.Ready,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRevokeLocationConsent: () -> Unit,
    notificationPermissionGranted: Boolean,
    canRequestNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSearchQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onChooseRegion: (WeatherRegion) -> Unit,
    onOpenPlant: (String) -> Unit,
    onSaveAlerts: (Boolean, Map<String, Boolean>) -> Unit,
    onOpenCollection: () -> Unit,
    focusedPlantId: String?,
) {
    var globalEnabled by
        rememberSaveable(state.accountId) {
            mutableStateOf(state.dashboard?.globalAlertsEnabled ?: true)
        }
    val plantAlerts =
        rememberSaveable(
            state.accountId,
            saver =
                Saver(
                    save = { values ->
                        ArrayList(
                            values.entries.sortedBy(Map.Entry<String, Boolean>::key).map {
                                (key, enabled) ->
                                "$key=${if (enabled) 1 else 0}"
                            }
                        )
                    },
                    restore = { values ->
                        mutableStateMapOf<String, Boolean>().apply {
                            values.forEach { encoded ->
                                val separator = encoded.lastIndexOf('=')
                                if (separator > 0) {
                                    put(
                                        encoded.substring(0, separator),
                                        encoded.substring(separator + 1) == "1",
                                    )
                                }
                            }
                        }
                    },
                ),
        ) {
            mutableStateMapOf<String, Boolean>().apply {
                putAll(state.dashboard?.plantAlerts.orEmpty())
            }
        }
    LaunchedEffect(
        state.dashboard?.revision,
        state.dashboard?.plantNames,
        state.dashboard?.plantAlerts,
    ) {
        val dashboard = state.dashboard ?: return@LaunchedEffect
        plantAlerts.keys.retainAll(dashboard.plantNames.keys)
        dashboard.plantNames.keys.forEach { plantId ->
            plantAlerts.putIfAbsent(plantId, dashboard.plantAlerts[plantId] != false)
        }
    }
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth().testTag(WeatherTestTags.LIST),
        verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
    ) {
        item {
            state.dashboard?.let { dashboard -> CurrentWeatherCard(dashboard) }
                ?: PlanteriorCard {
                    Text("관리 지역을 설정해 주세요", style = MaterialTheme.typography.titleMedium)
                    Text("현재 위치 또는 직접 선택한 지역으로 날씨 안내를 시작할 수 있어요.")
                }
        }
        if (state.failure != null) {
            item {
                PlanteriorCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        when (state.failure) {
                            WeatherFailure.LocationUnavailable ->
                                "현재 위치를 확인하지 못했어요. 직접 지역을 선택하거나 다시 시도해 주세요."
                            WeatherFailure.ProviderUnavailable ->
                                "날씨 정보를 새로 받지 못했어요. 마지막 정보를 유지하고 있어요."
                            WeatherFailure.SaveFailed -> "설정을 저장하지 못했어요. 다시 시도해 주세요."
                            WeatherFailure.RevisionConflict ->
                                "다른 기기에서 설정이 바뀌었어요. 최신 설정을 다시 확인해 주세요."
                            WeatherFailure.ConsentConflict ->
                                "다른 기기에서 위치 동의가 바뀌었어요. 최신 상태를 확인해 다시 시도해 주세요."
                        },
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
        }
        item {
            RegionControls(
                state,
                onUseCurrentLocation,
                onOpenLocationSettings,
                onRevokeLocationConsent,
                onSearchQuery,
                onSearch,
            )
        }
        items(state.searchResults, key = WeatherRegion::regionCode) { region ->
            PlanteriorCard(onClick = { onChooseRegion(region) }) {
                Text(region.regionName, style = MaterialTheme.typography.bodyLarge)
                Text("이 지역을 날씨 관리 기준으로 사용")
            }
        }
        val dashboard = state.dashboard
        if (dashboard != null) {
            val visibleRisks =
                dashboard.risks.filter { risk ->
                    risk.active && (focusedPlantId == null || risk.plantId == focusedPlantId)
                }
            if (focusedPlantId != null && visibleRisks.isEmpty()) {
                item {
                    PlanteriorCard {
                        Text("이 식물의 날씨 주의를 찾을 수 없어요.", style = MaterialTheme.typography.titleMedium)
                        Text("식물이 삭제되었거나 최신 날씨에서 위험 상태가 끝났을 수 있어요.")
                        Button(onClick = onOpenCollection) { Text("도감으로 이동") }
                    }
                }
            } else if (
                dashboard.risks.isEmpty() &&
                    dashboard.unavailablePlants.isEmpty() &&
                    !dashboard.stale
            ) {
                item { PlanteriorCard { Text("현재 날씨는 등록 식물의 적정 범위 안이에요.") } }
            }
            items(visibleRisks, key = WeatherRisk::riskId) { risk ->
                RiskCard(risk, dashboard.stale, onOpenPlant)
            }
            if (dashboard.unavailablePlants.isNotEmpty()) {
                item {
                    PlanteriorCard {
                        Text("날씨 판단 정보가 없는 식물", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${dashboard.unavailablePlants.size}개 식물은 공개 온·습도 기준이 없어 위험을 임의로 판단하지 않아요."
                        )
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large)) {
                    if (!notificationPermissionGranted) {
                        WeatherNotificationPermissionGuidance(
                            canRequestNotificationPermission,
                            onRequestNotificationPermission,
                            onOpenNotificationSettings,
                        )
                    }
                    AlertSettings(
                        globalEnabled,
                        { globalEnabled = it },
                        plantAlerts,
                        dashboard.plantNames,
                        state.saving,
                        onSaveAlerts,
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f).sizeIn(minHeight = MinimumTouch),
                ) {
                    Text("뒤로")
                }
                Button(
                    onClick = onRefresh,
                    enabled = !state.refreshing,
                    modifier =
                        Modifier.weight(1f)
                            .sizeIn(minHeight = MinimumTouch)
                            .testTag(WeatherTestTags.REFRESH),
                ) {
                    if (state.refreshing)
                        CircularProgressIndicator(modifier = Modifier.heightIn(max = ProgressSize))
                    else Text("날씨 새로고침")
                }
            }
        }
    }
}

@Composable
private fun CurrentWeatherCard(dashboard: WeatherDashboard) {
    PlanteriorCard(modifier = Modifier.testTag(WeatherTestTags.CURRENT)) {
        Text(dashboard.snapshot.regionName, style = MaterialTheme.typography.titleMedium)
        Text(
            "${dashboard.snapshot.regionName} · ${formatNumber(dashboard.snapshot.temperatureCelsius)}°C · " +
                "습도 ${dashboard.snapshot.humidityPercent}% · 강수 ${formatNumber(dashboard.snapshot.precipitationMillimeters)}mm"
        )
        val observed =
            OBSERVED_FORMAT.withZone(ZoneId.of(dashboard.snapshot.zoneId))
                .format(dashboard.snapshot.observedAt)
        Text(
            if (dashboard.stale) "마지막 관측 $observed · 최신 정보가 아니에요" else "마지막 관측 $observed",
            color =
                if (dashboard.stale) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (dashboard.stale) Modifier.testTag(WeatherTestTags.STALE) else Modifier,
        )
    }
}

@Composable
private fun RegionControls(
    state: WeatherUiState.Ready,
    onUseCurrentLocation: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRevokeLocationConsent: () -> Unit,
    onSearchQuery: (String) -> Unit,
    onSearch: () -> Unit,
) {
    PlanteriorCard {
        Text("관리 지역", style = MaterialTheme.typography.titleMedium)
        Text("위치는 날씨 기반 식물 관리에만 사용하며 약 1km 단위로 줄여 저장해요.")
        val consentEnabled = state.appLocationConsentGranted
        Text(
            if (consentEnabled) "앱의 현재 위치 사용 동의가 켜져 있어요." else "앱의 현재 위치 사용 동의가 꺼져 있어요.",
            modifier =
                Modifier.testTag(WeatherTestTags.LOCATION_CONSENT).semantics {
                    stateDescription = if (consentEnabled) "동의 켜짐" else "동의 꺼짐"
                },
        )
        when (val permission = state.locationPermission) {
            LocationPermission.GrantedApproximate -> {
                Text("기기 위치 권한은 허용되어 있어요.")
                OutlinedButton(
                    onClick = onUseCurrentLocation,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MinimumTouch),
                ) {
                    Text(if (consentEnabled) "현재 위치 주변 사용" else "현재 위치 사용 동의하고 사용")
                }
            }
            is LocationPermission.Denied ->
                if (permission.canAskAgain) {
                    Text("기기 위치 권한이 필요해요. 허용 후 앱 위치 동의를 선택할 수 있어요.")
                    OutlinedButton(
                        onClick = onUseCurrentLocation,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MinimumTouch),
                    ) {
                        Text("위치 사용 동의하고 허용")
                    }
                } else {
                    Text("기기 위치 권한이 꺼져 있어요. 기기 설정에서 다시 허용해 주세요.")
                    OutlinedButton(
                        onClick = onOpenLocationSettings,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MinimumTouch),
                    ) {
                        Text("기기 설정에서 위치 허용")
                    }
                }
        }
        if (state.locationPermission is LocationPermission.GrantedApproximate && consentEnabled) {
            OutlinedButton(
                onClick = onRevokeLocationConsent,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MinimumTouch),
            ) {
                Text("현재 위치 사용 동의 철회")
            }
            Text("동의를 철회해도 직접 선택한 지역은 유지돼요.")
        }
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQuery,
            label = { Text("지역 직접 검색") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(WeatherTestTags.REGION_QUERY),
        )
        Button(
            onClick = onSearch,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MinimumTouch),
        ) {
            Text("지역 검색")
        }
    }
}

@Composable
private fun RiskCard(risk: WeatherRisk, stale: Boolean, onOpenPlant: (String) -> Unit) {
    PlanteriorCard(
        onClick = { onOpenPlant(risk.plantId) },
        containerColor = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(risk.plantName, style = MaterialTheme.typography.titleMedium)
        Text(risk.type.title(), style = MaterialTheme.typography.bodyLarge)
        if (stale) Text("마지막 관측 기준 · 최신 위험 정보가 아니에요")
        if (risk.reason.isNotBlank()) Text(risk.reason)
        Text(risk.action)
        Text("날씨를 기준으로 한 관리 주의이며 식물 상태를 확정 진단하지 않아요.")
    }
}

@Composable
private fun WeatherNotificationPermissionGuidance(
    canRequestPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    PlanteriorCard(
        modifier =
            Modifier.fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(WeatherTestTags.NOTIFICATION_PERMISSION),
        containerColor = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text("날씨 알림을 받을 수 없어요", style = MaterialTheme.typography.titleMedium)
        Text(
            "권한이 없어도 날씨와 식물 위험은 앱에서 계속 확인할 수 있어요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        if (canRequestPermission) {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MinimumTouch),
            ) {
                Text("알림 허용")
            }
        } else {
            OutlinedButton(
                onClick = onOpenSystemSettings,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MinimumTouch),
            ) {
                Text("기기 알림 설정")
            }
        }
    }
}

@Composable
private fun AlertSettings(
    globalEnabled: Boolean,
    onGlobalEnabled: (Boolean) -> Unit,
    plantAlerts: MutableMap<String, Boolean>,
    plantNames: Map<String, String>,
    saving: Boolean,
    onSave: (Boolean, Map<String, Boolean>) -> Unit,
) {
    PlanteriorCard {
        Text("날씨 주의 알림", style = MaterialTheme.typography.titleMedium)
        SettingRow("전체 날씨 알림", globalEnabled, onGlobalEnabled, WeatherTestTags.GLOBAL_ALERT)
        if (!globalEnabled) Text("전체 알림이 꺼져 있으면 식물별 설정과 관계없이 푸시를 보내지 않아요.")
        plantNames.forEach { (plantId, plantName) ->
            SettingRow(
                plantName,
                plantAlerts[plantId] != false,
                { plantAlerts[plantId] = it },
                WeatherTestTags.plantAlert(plantId),
            )
        }
        Button(
            onClick = { onSave(globalEnabled, plantAlerts.toMap()) },
            enabled = !saving,
            modifier =
                Modifier.fillMaxWidth()
                    .sizeIn(minHeight = MinimumTouch)
                    .testTag(WeatherTestTags.SAVE_ALERTS),
        ) {
            Text(if (saving) "저장 중" else "알림 설정 저장")
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked, modifier = Modifier.testTag(tag))
    }
}

private fun WeatherRiskType.title(): String =
    when (this) {
        WeatherRiskType.HIGH_TEMPERATURE -> "고온 주의"
        WeatherRiskType.LOW_TEMPERATURE -> "저온 주의"
        WeatherRiskType.DRY -> "건조 주의"
        WeatherRiskType.OVERHUMID -> "과습 주의"
    }

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(Locale.US, value)

private val OBSERVED_FORMAT = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN)
private val MinimumTouch = 48.dp
private val ProgressSize = 24.dp
