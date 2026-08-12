package com.planterior.helper.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import org.junit.Assert.assertEquals
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

    private fun start(target: PlanteriorRoute = PlanteriorRoute.Home) {
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
                )
            }
        }
        composeRule.waitForIdle()
    }

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
        composeRule.onNodeWithText("홈").assertIsDisplayed()
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
