package com.planterior.helper.core.designsystem.component

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEqualTo
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.core.designsystem.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.core.designsystem.icon.PlanteriorIcons
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
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
class PlanteriorBottomBarTest {
    @get:Rule val composeRule = createComposeRule()

    private val tabs =
        listOf(
            PlanteriorTab(label = "홈", icon = PlanteriorIcons.Home),
            PlanteriorTab(label = "도감", icon = PlanteriorIcons.Collection),
            PlanteriorTab(label = "창고", icon = PlanteriorIcons.Storage),
            PlanteriorTab(label = "설정", icon = PlanteriorIcons.Settings),
        )

    @Test
    fun `all four tabs and the camera action are displayed`() {
        composeRule.setContent {
            PlanteriorTheme {
                PlanteriorBottomBar(
                    tabs = tabs,
                    selectedIndex = 0,
                    onTabSelected = {},
                    cameraContentDescription = "식물 촬영",
                    onCameraClick = {},
                )
            }
        }

        listOf("홈", "도감", "창고", "설정").forEach { label ->
            composeRule.onNodeWithContentDescription(label).assertIsDisplayed()
        }
        composeRule.onNodeWithContentDescription("식물 촬영").assertIsDisplayed()
    }

    @Test
    fun `selected tab exposes its selection state to accessibility services`() {
        composeRule.setContent {
            PlanteriorTheme {
                PlanteriorBottomBar(
                    tabs = tabs,
                    selectedIndex = 2,
                    onTabSelected = {},
                    cameraContentDescription = "식물 촬영",
                    onCameraClick = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("창고").assertIsSelected()
    }

    @Test
    fun `tapping a tab reports its index once`() {
        val clicks = mutableListOf<Int>()
        composeRule.setContent {
            PlanteriorTheme {
                PlanteriorBottomBar(
                    tabs = tabs,
                    selectedIndex = 0,
                    onTabSelected = { clicks += it },
                    cameraContentDescription = "식물 촬영",
                    onCameraClick = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("설정").performClick()

        assertEquals(listOf(3), clicks)
    }

    @Test
    fun `camera action reports taps separately from tab selection`() {
        var cameraClicks = 0
        val tabClicks = mutableListOf<Int>()
        composeRule.setContent {
            PlanteriorTheme {
                PlanteriorBottomBar(
                    tabs = tabs,
                    selectedIndex = 0,
                    onTabSelected = { tabClicks += it },
                    cameraContentDescription = "식물 촬영",
                    onCameraClick = { cameraClicks++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription("식물 촬영").performClick()

        assertEquals(1, cameraClicks)
        assertEquals(emptyList<Int>(), tabClicks)
    }

    @Test
    fun `camera circle is a true 52dp circle and is not clipped into an ellipse`() {
        composeRule.setContent {
            PlanteriorTheme {
                PlanteriorBottomBar(
                    tabs = tabs,
                    selectedIndex = 0,
                    onTabSelected = {},
                    cameraContentDescription = "식물 촬영",
                    onCameraClick = {},
                )
            }
        }

        // Figma `camera-circle`: width 52px, height 52px, border-radius 26px.
        // 56dp로 되돌리거나 탭 바 높이에 가로막혀 세로로 잘리면 두 단언 중 하나가 실패한다.
        val bounds = composeRule.onNodeWithContentDescription("식물 촬영").getUnclippedBoundsInRoot()
        (bounds.right - bounds.left).assertIsEqualTo(52.dp, "카메라 원 지름(가로)")
        (bounds.bottom - bounds.top).assertIsEqualTo(52.dp, "카메라 원 지름(세로)")
    }

    @Test
    fun `camera circle overhangs the tab bar top edge by the Figma offset`() {
        composeRule.setContent {
            PlanteriorTheme {
                PlanteriorBottomBar(
                    tabs = tabs,
                    selectedIndex = 0,
                    onTabSelected = {},
                    cameraContentDescription = "식물 촬영",
                    onCameraClick = {},
                )
            }
        }

        val barTop = composeRule.onNodeWithTag(TabBarTestTag).getUnclippedBoundsInRoot().top
        val cameraTop =
            composeRule.onNodeWithContentDescription("식물 촬영").getUnclippedBoundsInRoot().top

        // Figma: wrapper margin-top 8 + circle top -14 = 경계선 위로 6dp 돌출.
        // 돌출이 없으면(cameraTop >= barTop) 이 단언이 실패한다.
        (barTop - cameraTop).assertIsEqualTo(6.dp, "카메라 원 돌출 높이")
    }

    @Test
    fun `camera circle stays horizontally centered on the bar`() {
        composeRule.setContent {
            PlanteriorTheme {
                PlanteriorBottomBar(
                    tabs = tabs,
                    selectedIndex = 0,
                    onTabSelected = {},
                    cameraContentDescription = "식물 촬영",
                    onCameraClick = {},
                )
            }
        }

        val bar = composeRule.onNodeWithTag(TabBarTestTag).getUnclippedBoundsInRoot()
        val camera = composeRule.onNodeWithContentDescription("식물 촬영").getUnclippedBoundsInRoot()

        val barCenterX = bar.left + (bar.right - bar.left) / 2
        val cameraCenterX = camera.left + (camera.right - camera.left) / 2
        cameraCenterX.assertIsEqualTo(barCenterX, "카메라 원 가로 중심")
    }

    @Test
    fun `every tap target meets the minimum accessible touch size`() {
        composeRule.setContent {
            PlanteriorTheme {
                PlanteriorBottomBar(
                    tabs = tabs,
                    selectedIndex = 0,
                    onTabSelected = {},
                    cameraContentDescription = "식물 촬영",
                    onCameraClick = {},
                )
            }
        }

        listOf("홈", "도감", "창고", "설정", "식물 촬영").forEach { description ->
            composeRule
                .onNodeWithContentDescription(description)
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
    }
}
