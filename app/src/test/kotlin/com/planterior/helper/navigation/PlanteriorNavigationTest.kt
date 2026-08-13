package com.planterior.helper.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.feature.home.HomeMiniHomePreview
import com.planterior.helper.feature.home.HomePlantCare
import com.planterior.helper.feature.home.HomeRepository
import com.planterior.helper.feature.home.HomeSession
import com.planterior.helper.feature.home.HomeSyncStatus
import com.planterior.helper.feature.home.HomeTestTags
import com.planterior.helper.feature.home.HomeUiState
import com.planterior.helper.feature.home.HomeViewModel
import com.planterior.helper.feature.home.HomeWeather
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 다섯 개 탭 순회, 백스택 보존, 딥링크 복귀를 실제 NavHost로 검증한다. */
@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [ROBOLECTRIC_MAX_SDK],
    qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class PlanteriorNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    private fun start(
        target: PlanteriorRoute = PlanteriorRoute.Home,
        homeViewModel: HomeViewModel? = null,
    ) {
        composeRule.setContent {
            PlanteriorTheme {
                navController = rememberNavController()
                val backStack = PlanteriorRouteResolver.backStackFor(target)
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    backStack.drop(1).forEach { navController.navigate(it) }
                }
                PlanteriorNavHost(
                    navController = navController,
                    startRoute = backStack.first(),
                    homeViewModel = homeViewModel,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** 도중에 로그인되는 저장소를 흑낸다. 세션 전환을 고정 sleep 없이 재현한다. */
    private class SwitchableHomeRepository : HomeRepository {
        private var signedIn = false

        fun signIn() {
            signedIn = true
        }

        override suspend fun session(): HomeSession =
            if (signedIn) HomeSession.SignedIn("uid-1", "민지", ZoneId.of("Asia/Seoul"))
            else HomeSession.SignedOut

        override suspend fun plantCare(): Result<List<HomePlantCare>> =
            Result.success(listOf(HomePlantCare("plant-1", "몬몬이", LocalDate.of(2026, 8, 12), 7)))

        override suspend fun weather(): Result<HomeWeather?> = Result.success(null)

        override suspend fun miniHomePreview(): HomeMiniHomePreview? = null

        override suspend fun syncStatus(): HomeSyncStatus =
            HomeSyncStatus.Synced(Instant.parse("2026-08-12T00:00:00Z"))
    }

    /** 로그인 후 홈을 그리는 ViewModel이다. 진입점 전환은 로그인 상태에서만 의미가 있다. */
    private fun signedInHomeViewModel(): HomeViewModel =
        HomeViewModel(
            repository =
                object : HomeRepository {
                    override suspend fun session(): HomeSession =
                        HomeSession.SignedIn("uid-1", "민지", ZoneId.of("Asia/Seoul"))

                    override suspend fun plantCare(): Result<List<HomePlantCare>> =
                        Result.success(
                            listOf(
                                HomePlantCare(
                                    "plant-1",
                                    "몬몬이",
                                    LocalDate.of(2026, 8, 12),
                                    7,
                                )
                            )
                        )

                    override suspend fun weather(): Result<HomeWeather?> = Result.success(null)

                    override suspend fun miniHomePreview(): HomeMiniHomePreview? =
                        HomeMiniHomePreview("민지의 미니 식물원", 1)

                    override suspend fun syncStatus(): HomeSyncStatus =
                        HomeSyncStatus.Synced(Instant.parse("2026-08-12T00:00:00Z"))
                },
            clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneId.of("Asia/Seoul")),
        )

    private fun currentRoute(): PlanteriorRoute? =
        navController.currentBackStackEntry.toPlanteriorRoute()

    @Test
    fun `visiting every bottom tab lands on its own destination`() {
        start()
        assertEquals(PlanteriorRoute.Home, currentRoute())

        composeRule.onNodeWithContentDescription("도감").performClick()
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.Collection, currentRoute())

        composeRule.onNodeWithContentDescription("창고").performClick()
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.Storage, currentRoute())

        composeRule.onNodeWithContentDescription("설정").performClick()
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.Settings, currentRoute())

        composeRule.onNodeWithContentDescription("홈").performClick()
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.Home, currentRoute())
    }

    @Test
    fun `center camera action opens the camera destination instead of switching tabs`() {
        start()

        composeRule.onNodeWithContentDescription("식물 촬영").performClick()
        composeRule.waitForIdle()

        assertEquals(PlanteriorRoute.Camera, currentRoute())
    }

    @Test
    fun `back from camera returns to the tab the user came from`() {
        start()
        composeRule.onNodeWithContentDescription("창고").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("식물 촬영").performClick()
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.Camera, currentRoute())

        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()

        assertEquals(PlanteriorRoute.Storage, currentRoute())
    }

    @Test
    fun `switching tabs does not grow the back stack without bound`() {
        start()
        repeat(3) {
            composeRule.onNodeWithContentDescription("도감").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("설정").performClick()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()

        assertEquals(PlanteriorRoute.Home, currentRoute())
    }

    @Test
    fun `cold start deep link to a plant detail keeps a parent back stack`() {
        start(PlanteriorRoute.PlantDetail("plant-1"))
        assertEquals(PlanteriorRoute.PlantDetail("plant-1"), currentRoute())

        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.Collection, currentRoute())

        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.Home, currentRoute())
    }

    @Test
    fun `invalid external deep link starts safely on home`() {
        start(PlanteriorRouteResolver.resolve("planterior://collection/plant/../../secret"))

        assertEquals(PlanteriorRoute.Home, currentRoute())
        // 문구 대신 홈 화면이 실제로 그려졌는지를 식별자로 확인한다.
        composeRule.onNodeWithTag(HomeTestTags.GREETING).assertIsDisplayed()
    }

    @Test
    fun `returning to home reloads it so a new session is not shown as logged out`() {
        val repository = SwitchableHomeRepository()
        val model =
            HomeViewModel(
                repository = repository,
                clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneId.of("Asia/Seoul")),
            )
        start(homeViewModel = model)
        assertEquals(HomeUiState.LoggedOut, model.state.value)

        // 로그인 후 홈으로 돌아오는 경로를 흑낸다. 홈은 살아 있는 목적지라 재구성되지 않을 수 있다.
        composeRule.onNodeWithContentDescription("도감").performClick()
        composeRule.waitForIdle()
        repository.signIn()
        composeRule.onNodeWithContentDescription("홈").performClick()
        composeRule.waitForIdle()

        assertTrue(
            "홈으로 돌아오면 새 세션을 반영해야 한다: ${model.state.value}",
            model.state.value is HomeUiState.Content,
        )
    }

    @Test
    fun `home entry points reach their own destinations`() {
        start(homeViewModel = signedInHomeViewModel())

        composeRule.onNodeWithTag(HomeTestTags.MINI_HOME).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.MiniHome, currentRoute())

        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HomeTestTags.IDENTIFY_CTA).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.Camera, currentRoute())
    }

    @Test
    fun `login returns to the requested authenticated destination`() {
        composeRule.setContent {
            PlanteriorTheme {
                navController = rememberNavController()
                PlanteriorNavHost(
                    navController = navController,
                    startRoute = PlanteriorRoute.Login("planterior://storage"),
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.Login("planterior://storage"), currentRoute())

        composeRule.onNodeWithText("계속하기").performClick()
        composeRule.waitForIdle()

        assertEquals(PlanteriorRoute.Storage, currentRoute())
    }

    @Test
    fun `login with a hostile return route falls back to home`() {
        composeRule.setContent {
            PlanteriorTheme {
                navController = rememberNavController()
                PlanteriorNavHost(
                    navController = navController,
                    startRoute = PlanteriorRoute.Login("https://evil.example.com/steal"),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("계속하기").performClick()
        composeRule.waitForIdle()

        assertEquals(PlanteriorRoute.Home, currentRoute())
    }
}
