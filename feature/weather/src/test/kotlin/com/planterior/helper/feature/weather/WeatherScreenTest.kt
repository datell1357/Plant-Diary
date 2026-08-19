package com.planterior.helper.feature.weather

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [36],
    qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class WeatherScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `production weather route rotation uses recreated gateway instead of destroyed launcher`() {
        val repository = RouteWeatherRepository(requireNotNull(ready(stale = false).dashboard))
        val oldGateway = RouteLocationGateway(ApproximateLocation(33.45, 126.57))
        val recreatedGateway = RouteLocationGateway(ApproximateLocation(37.56, 126.98))
        var gateway by mutableStateOf<WeatherLocationGateway>(oldGateway)
        composeRule.setContent {
            PlanteriorTheme {
                WeatherRoute(
                    repository = repository,
                    locationGateway = gateway,
                    onBack = {},
                    onOpenPlant = {},
                    onOpenLocationSettings = {},
                )
            }
        }
        composeRule.waitForIdle()

        oldGateway.destroyed = true
        gateway = recreatedGateway
        composeRule.waitForIdle()
        composeRule.onNodeWithText("현재 위치 사용 동의하고 사용").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { repository.refreshLocations.isNotEmpty() }

        assertEquals(listOf(ApproximateLocation(37.56, 126.98)), repository.refreshLocations)
        assertEquals(0, oldGateway.locationRequests)
        assertEquals(1, recreatedGateway.locationRequests)
        composeRule.onNodeWithText("날씨 정보를 새로 받지 못했어요. 마지막 정보를 유지하고 있어요.").assertDoesNotExist()
    }

    @Test
    fun `current weather multiple plant risks and actions are observable`() {
        val state = ready(stale = false)
        composeRule.setContent { PlanteriorTheme { WeatherScreen(state = state) } }

        composeRule.onNodeWithTag(WeatherTestTags.CURRENT).assertIsDisplayed()
        composeRule.onNodeWithText("서울 성동구 · 35°C · 습도 30% · 강수 0mm").assertIsDisplayed()
        composeRule.onNodeWithText("고온 주의").assertIsDisplayed()
        composeRule.onNodeWithText("건조 주의").assertIsDisplayed()
        composeRule.onNodeWithText("직사광선을 피해 옮겨 주세요.").assertIsDisplayed()
    }

    @Test
    fun `stale provider state keeps timestamp and refresh action without hiding risks`() {
        composeRule.setContent { PlanteriorTheme { WeatherScreen(state = ready(stale = true)) } }
        composeRule.onNodeWithTag(WeatherTestTags.STALE).assertIsDisplayed()
        composeRule.onNodeWithText("마지막 관측 8월 12일 08:00 · 최신 정보가 아니에요").assertIsDisplayed()
        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(6)
        composeRule.onNodeWithTag(WeatherTestTags.REFRESH).assertHasClickAction()
        composeRule.onNodeWithText("고온 주의").assertIsDisplayed()
    }

    @Test
    fun `permanent permission denial exposes system settings and manual region path`() {
        var settings = 0
        composeRule.setContent {
            PlanteriorTheme {
                WeatherScreen(
                    state = ready(permission = LocationPermission.Denied(false)),
                    onOpenLocationSettings = { settings += 1 },
                )
            }
        }
        composeRule.onNodeWithText("기기 설정에서 위치 허용").performClick()
        composeRule.onNodeWithTag(WeatherTestTags.REGION_QUERY).assertIsDisplayed()
        assertEquals(1, settings)
    }

    @Test
    fun `safe plants can be preconfigured and stale risks are visibly identified`() {
        composeRule.setContent { PlanteriorTheme { WeatherScreen(state = ready(stale = true)) } }

        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(5)
        composeRule.onNodeWithTag(WeatherTestTags.plantAlert("plant-safe")).assertIsDisplayed()
        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(2)
        composeRule.onAllNodesWithText("마지막 관측 기준 · 최신 위험 정보가 아니에요").assertCountEquals(2)
    }

    @Test
    fun `explicit location consent revocation preserves manual region explanation`() {
        var revoked = 0
        composeRule.setContent {
            PlanteriorTheme {
                WeatherScreen(state = ready(), onRevokeLocationConsent = { revoked += 1 })
            }
        }

        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(1)
        composeRule.onNodeWithText("현재 위치 사용 동의 철회").performClick()
        composeRule.onNodeWithText("동의를 철회해도 직접 선택한 지역은 유지돼요.").assertIsDisplayed()
        assertEquals(1, revoked)
    }

    @Test
    fun `app consent off with os permission granted offers enable and never revoke`() {
        var enabled = 0
        composeRule.setContent {
            PlanteriorTheme {
                WeatherScreen(
                    state = ready(appConsentGranted = false),
                    onUseCurrentLocation = { enabled += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(1)
        composeRule
            .onNodeWithTag(WeatherTestTags.LOCATION_CONSENT)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "동의 꺼짐",
                )
            )
        composeRule.onNodeWithText("기기 위치 권한은 허용되어 있어요.").assertIsDisplayed()
        composeRule.onNodeWithText("현재 위치 사용 동의 철회").assertDoesNotExist()
        composeRule.onNodeWithText("현재 위치 사용 동의하고 사용").performClick()
        assertEquals(1, enabled)
    }

    @Test
    fun `consent presentation follows owner state across restoration and account switch`() {
        var state by mutableStateOf(ready(accountId = "account-a", appConsentGranted = false))
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { PlanteriorTheme { WeatherScreen(state = state) } }
        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(1)
        composeRule.onNodeWithText("현재 위치 사용 동의하고 사용").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(1)
        composeRule.onNodeWithText("현재 위치 사용 동의하고 사용").assertIsDisplayed()

        state = ready(accountId = "account-b", appConsentGranted = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(1)
        composeRule.onNodeWithText("현재 위치 사용 동의 철회").assertIsDisplayed()
        composeRule.onNodeWithText("현재 위치 사용 동의하고 사용").assertDoesNotExist()
    }

    @Test
    fun `notification recovery guidance remains accessible on compact large text`() {
        var settings = 0
        composeRule.setContent {
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides
                    androidx.compose.ui.unit.Density(density.density, fontScale = 2f)
            ) {
                PlanteriorTheme {
                    WeatherScreen(
                        state = ready(),
                        notificationPermissionGranted = false,
                        canRequestNotificationPermission = false,
                        onOpenNotificationSettings = { settings += 1 },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(5)
        composeRule.onNodeWithText("기기 알림 설정").assertIsDisplayed().performClick()
        assertEquals(1, settings)
    }

    @Test
    fun `global weather off leaves per plant choices visible and explains precedence`() {
        var saved: Pair<Boolean, Map<String, Boolean>>? = null
        composeRule.setContent {
            PlanteriorTheme {
                WeatherScreen(
                    state = ready(stale = false),
                    onSaveAlerts = { global, plants -> saved = global to plants },
                )
            }
        }
        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(5)
        composeRule.onNodeWithTag(WeatherTestTags.GLOBAL_ALERT).performClick()
        composeRule.onNodeWithText("전체 알림이 꺼져 있으면 식물별 설정과 관계없이 푸시를 보내지 않아요.").assertIsDisplayed()
        composeRule.onNodeWithTag(WeatherTestTags.SAVE_ALERTS).performClick()
        assertEquals(false, saved?.first)
        assertEquals(true, saved?.second?.get("plant-a"))
    }

    @Test
    fun `authoritative plant sets prune deleted drafts preserve survivors and default new plants across retry and recreation`() {
        var state by mutableStateOf(ready())
        var saved: Map<String, Boolean>? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            PlanteriorTheme {
                WeatherScreen(state = state, onSaveAlerts = { _, plants -> saved = plants })
            }
        }
        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(5)
        composeRule.onNodeWithTag(WeatherTestTags.plantAlert("plant-a")).performClick()

        state =
            ready(
                plantNames = mapOf("plant-a" to "몬스테라", "plant-new" to "고무나무"),
                plantAlerts = mapOf("plant-a" to true, "plant-new" to true),
            )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(5)
        composeRule.onNodeWithTag(WeatherTestTags.SAVE_ALERTS).performClick()
        assertEquals(mapOf("plant-a" to false, "plant-new" to true), saved)

        state =
            ready(
                plantNames = mapOf("plant-new" to "고무나무", "plant-third" to "스킨답서스"),
                plantAlerts = mapOf("plant-new" to true, "plant-third" to true),
            )
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(5)
        composeRule.onNodeWithTag(WeatherTestTags.SAVE_ALERTS).performClick()
        assertEquals(mapOf("plant-new" to true, "plant-third" to true), saved)
    }

    @Test
    fun `unavailable criteria notice survives recreation without a safe risk claim`() {
        val base = ready(stale = false)
        val state =
            base.copy(
                dashboard =
                    base.dashboard?.copy(
                        risks = emptyList(),
                        unavailablePlants = listOf("plant-a"),
                    )
            )
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { PlanteriorTheme { WeatherScreen(state = state) } }
        composeRule.onNodeWithText("1개 식물은 공개 온·습도 기준이 없어 위험을 임의로 판단하지 않아요.").assertIsDisplayed()
        composeRule.onNodeWithText("고온 주의").assertDoesNotExist()
        composeRule.onNodeWithText("현재 날씨는 등록 식물의 적정 범위 안이에요.").assertDoesNotExist()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("1개 식물은 공개 온·습도 기준이 없어 위험을 임의로 판단하지 않아요.").assertIsDisplayed()
        composeRule.onNodeWithText("고온 주의").assertDoesNotExist()
        composeRule.onNodeWithText("현재 날씨는 등록 식물의 적정 범위 안이에요.").assertDoesNotExist()
    }

    @Test
    fun `same revision account switch clears false draft from prior owner`() {
        var state by mutableStateOf(ready(accountId = "account-a"))
        var saved: Map<String, Boolean>? = null
        composeRule.setContent {
            PlanteriorTheme {
                WeatherScreen(state = state, onSaveAlerts = { _, plants -> saved = plants })
            }
        }
        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(5)
        composeRule.onNodeWithTag(WeatherTestTags.plantAlert("plant-a")).performClick()

        state =
            ready(
                accountId = "account-b",
                plantNames = mapOf("plant-a" to "다른 계정 식물"),
                plantAlerts = mapOf("plant-a" to true),
            )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(5)
        composeRule.onNodeWithTag(WeatherTestTags.SAVE_ALERTS).performClick()

        assertEquals(mapOf("plant-a" to true), saved)
    }

    @Test
    fun `unsaved alert choices survive activity state restoration`() {
        var saved: Pair<Boolean, Map<String, Boolean>>? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            PlanteriorTheme {
                WeatherScreen(
                    state = ready(stale = false),
                    onSaveAlerts = { global, plants -> saved = global to plants },
                )
            }
        }
        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(5)
        composeRule.onNodeWithTag(WeatherTestTags.GLOBAL_ALERT).performClick()

        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(WeatherTestTags.LIST).performScrollToIndex(5)

        composeRule.onNodeWithTag(WeatherTestTags.SAVE_ALERTS).performClick()
        assertEquals(false, saved?.first)
        assertEquals(true, saved?.second?.get("plant-a"))
    }

    private class RouteWeatherRepository(private val dashboard: WeatherDashboard) :
        WeatherRepository {
        val refreshLocations = mutableListOf<ApproximateLocation?>()

        override fun accounts() = flowOf("account-a")

        override suspend fun load(accountId: String) = WeatherLoad.Fresh(dashboard)

        override suspend fun recordLocationConsent(
            accountId: String,
            granted: Boolean,
            commandGeneration: Long,
        ) = WeatherConsentMutationResult(commandGeneration, granted)

        override suspend fun refresh(
            accountId: String,
            location: ApproximateLocation?,
        ): WeatherLoad {
            refreshLocations += location
            return WeatherLoad.Fresh(dashboard)
        }

        override suspend fun searchRegions(accountId: String, query: String) =
            emptyList<WeatherRegion>()

        override suspend fun selectManualRegion(
            accountId: String,
            region: WeatherRegion,
            expectedRevision: Long,
        ) = WeatherLoad.Fresh(dashboard)

        override suspend fun saveAlerts(
            accountId: String,
            globalEnabled: Boolean,
            plants: Map<String, Boolean>,
            expectedRevision: Long,
        ) = WeatherLoad.Fresh(dashboard)
    }

    private class RouteLocationGateway(private val location: ApproximateLocation) :
        WeatherLocationGateway {
        var destroyed = false
        var locationRequests = 0

        override fun permission(): LocationPermission {
            check(!destroyed) { "destroyed Activity launcher" }
            return LocationPermission.GrantedApproximate
        }

        override suspend fun approximateLocation(): ApproximateLocation {
            check(!destroyed) { "destroyed Activity location client" }
            locationRequests += 1
            return location
        }
    }

    private fun ready(
        stale: Boolean = false,
        permission: LocationPermission = LocationPermission.GrantedApproximate,
        accountId: String = "account-a",
        appConsentGranted: Boolean = true,
        plantNames: Map<String, String> = mapOf("plant-a" to "몬스테라", "plant-safe" to "선인장"),
        plantAlerts: Map<String, Boolean> = mapOf("plant-a" to true),
    ) =
        WeatherUiState.Ready(
            accountId = accountId,
            locationPermission = permission,
            appLocationConsentGranted = appConsentGranted,
            searchQuery = "",
            dashboard =
                WeatherDashboard(
                    snapshot =
                        WeatherSnapshot(
                            "region-a",
                            "서울 성동구",
                            35.0,
                            30,
                            0.0,
                            Instant.parse("2026-08-11T23:00:00Z"),
                            "Asia/Seoul",
                        ),
                    risks =
                        listOf(
                            WeatherRisk(
                                "risk-high",
                                "plant-a",
                                "몬스테라",
                                WeatherRiskType.HIGH_TEMPERATURE,
                                "직사광선을 피해 옮겨 주세요.",
                                Instant.parse("2026-08-12T00:00:00Z"),
                                true,
                            ),
                            WeatherRisk(
                                "risk-dry",
                                "plant-a",
                                "몬스테라",
                                WeatherRiskType.DRY,
                                "잎 상태를 보고 분무해 주세요.",
                                Instant.parse("2026-08-12T00:00:00Z"),
                                true,
                            ),
                        ),
                    unavailablePlants = listOf("직접 입력 식물"),
                    stale = stale,
                    globalAlertsEnabled = true,
                    plantAlerts = plantAlerts,
                    revision = 2,
                    plantNames = plantNames,
                ),
        )
}
