package com.planterior.helper.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.feature.weather.ApproximateLocation
import com.planterior.helper.feature.weather.LocationPermission
import com.planterior.helper.feature.weather.WeatherConsentMutationResult
import com.planterior.helper.feature.weather.WeatherDashboard
import com.planterior.helper.feature.weather.WeatherLoad
import com.planterior.helper.feature.weather.WeatherLocationGateway
import com.planterior.helper.feature.weather.WeatherRegion
import com.planterior.helper.feature.weather.WeatherRepository
import com.planterior.helper.feature.weather.WeatherRisk
import com.planterior.helper.feature.weather.WeatherRiskType
import com.planterior.helper.feature.weather.WeatherSnapshot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [ROBOLECTRIC_MAX_SDK],
    qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class WeatherNavigationContractTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var navController: NavHostController

    @Test
    fun `authenticated cold weather notification builds canonical risk stack`() {
        assertEquals(
            listOf(
                PlanteriorRoute.Home,
                PlanteriorRoute.Weather,
                PlanteriorRoute.WeatherRisk("plant-a"),
            ),
            NotificationTapRouter.coldStartStack(
                "planterior://weather/plant/plant-a",
                authenticated = true,
            ),
        )
    }

    @Test
    fun `authenticated warm weather notification replaces unrelated history`() {
        var stack = listOf<PlanteriorRoute>(PlanteriorRoute.Settings)

        NotificationTapRouter.openWarm(
            "planterior://weather/plant/plant-a",
            authenticated = true,
            navigator = NotificationStackNavigator { stack = it },
        )

        assertEquals(
            listOf(
                PlanteriorRoute.Home,
                PlanteriorRoute.Weather,
                PlanteriorRoute.WeatherRisk("plant-a"),
            ),
            stack,
        )
    }

    @Test
    fun `logged-out weather notification resumes exact risk after login`() {
        val uri = "planterior://weather/plant/plant-a"

        assertEquals(
            listOf(PlanteriorRoute.Home, PlanteriorRoute.Login(uri)),
            NotificationTapRouter.coldStartStack(uri, authenticated = false),
        )
        assertEquals(
            listOf(
                PlanteriorRoute.Home,
                PlanteriorRoute.Weather,
                PlanteriorRoute.WeatherRisk("plant-a"),
            ),
            NotificationTapRouter.resumeAfterLogin(uri),
        )
    }

    @Test
    fun `weather alert deep link renders focused production risk surface`() {
        start(PlanteriorRoute.WeatherRisk("plant-a"), dashboard())
        composeRule.onNodeWithText("고온 주의").assertIsDisplayed()
        composeRule.onNodeWithText("직사광선을 피해 옮겨 주세요.").assertIsDisplayed()
        assertEquals(PlanteriorRoute.WeatherRisk("plant-a"), currentRoute())
    }

    @Test
    fun `granted notification capability hides weather recovery guidance`() {
        start(PlanteriorRoute.Weather, dashboard(), notificationPermissionGranted = true)

        composeRule.onNodeWithText("날씨 알림을 받을 수 없어요").assertDoesNotExist()
    }

    @Test
    fun `api 33 first denial requests permission from production weather route`() {
        var requests = 0
        start(
            PlanteriorRoute.Weather,
            dashboard(),
            notificationPermissionGranted = false,
            canRequestNotificationPermission = true,
            onRequestNotificationPermission = { requests += 1 },
        )

        composeRule.onNodeWithText("알림 허용").performClick()
        composeRule.onNodeWithText("기기 알림 설정").assertDoesNotExist()
        assertEquals(1, requests)
    }

    @Test
    fun `permanently denied notification opens app settings from production weather route`() {
        var settings = 0
        start(
            PlanteriorRoute.Weather,
            dashboard(),
            notificationPermissionGranted = false,
            canRequestNotificationPermission = false,
            onOpenNotificationSettings = { settings += 1 },
        )

        composeRule.onNodeWithText("기기 알림 설정").performClick()
        composeRule.onNodeWithText("알림 허용").assertDoesNotExist()
        assertEquals(1, settings)
    }

    @Test
    @Config(sdk = [32])
    fun `api 29 to 32 app disabled notifications expose settings fallback`() {
        var settings = 0
        start(
            PlanteriorRoute.Weather,
            dashboard(),
            notificationPermissionGranted = false,
            canRequestNotificationPermission = false,
            onOpenNotificationSettings = { settings += 1 },
        )

        composeRule.onNodeWithText("기기 알림 설정").performClick()
        assertEquals(1, settings)
    }

    @Test
    fun `weather route reflects notification capability refreshed on resume`() {
        var granted by mutableStateOf(false)
        composeRule.setContent {
            PlanteriorTheme {
                navController = rememberNavController()
                PlanteriorNavHost(
                    navController = navController,
                    startRoute = PlanteriorRoute.Weather,
                    weatherRepository = FakeWeatherRepository(dashboard()),
                    weatherLocationGateway = FakeLocationGateway,
                    notificationPermissionGranted = granted,
                    canRequestNotificationPermission = false,
                    clock =
                        Clock.fixed(
                            Instant.parse("2026-08-12T03:00:00Z"),
                            ZoneOffset.UTC,
                        ),
                )
            }
        }
        composeRule.onNodeWithText("기기 알림 설정").assertIsDisplayed()

        granted = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText("날씨 알림을 받을 수 없어요").assertDoesNotExist()
    }

    @Test
    fun `deleted weather alert target renders safe unavailable state and collection escape`() {
        start(PlanteriorRoute.WeatherRisk("deleted-plant"), dashboard())
        composeRule.onNodeWithText("이 식물의 날씨 주의를 찾을 수 없어요.").assertIsDisplayed()
        composeRule.onNodeWithText("도감으로 이동").performClick()
        assertEquals(PlanteriorRoute.Collection, currentRoute())
    }

    private fun start(
        route: PlanteriorRoute,
        dashboard: WeatherDashboard,
        notificationPermissionGranted: Boolean = true,
        canRequestNotificationPermission: Boolean = false,
        onRequestNotificationPermission: () -> Unit = {},
        onOpenNotificationSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            PlanteriorTheme {
                navController = rememberNavController()
                PlanteriorNavHost(
                    navController = navController,
                    startRoute = route,
                    weatherRepository = FakeWeatherRepository(dashboard),
                    weatherLocationGateway = FakeLocationGateway,
                    notificationPermissionGranted = notificationPermissionGranted,
                    canRequestNotificationPermission = canRequestNotificationPermission,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    clock = Clock.fixed(Instant.parse("2026-08-12T03:00:00Z"), ZoneOffset.UTC),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun currentRoute() = navController.currentBackStackEntry.toPlanteriorRoute()

    private fun dashboard() =
        WeatherDashboard(
            WeatherSnapshot(
                "region",
                "서울",
                35.0,
                55,
                0.0,
                Instant.parse("2026-08-12T02:00:00Z"),
                "Asia/Seoul",
            ),
            listOf(
                WeatherRisk(
                    "risk-a",
                    "plant-a",
                    "몬스테라",
                    WeatherRiskType.HIGH_TEMPERATURE,
                    "직사광선을 피해 옮겨 주세요.",
                    Instant.parse("2026-08-12T03:00:00Z"),
                    true,
                )
            ),
            emptyList(),
            false,
            true,
            mapOf("plant-a" to true),
            1,
        )

    private class FakeWeatherRepository(private val dashboard: WeatherDashboard) :
        WeatherRepository {
        override fun accounts() = flowOf("account-a")

        override suspend fun load(accountId: String) = WeatherLoad.Fresh(dashboard)

        override suspend fun recordLocationConsent(
            accountId: String,
            granted: Boolean,
            commandGeneration: Long,
        ) = WeatherConsentMutationResult(commandGeneration, granted)

        override suspend fun refresh(accountId: String, location: ApproximateLocation?) =
            WeatherLoad.Fresh(dashboard)

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

    private object FakeLocationGateway : WeatherLocationGateway {
        override fun permission() = LocationPermission.Denied(true)

        override suspend fun approximateLocation(): ApproximateLocation? = null
    }
}
