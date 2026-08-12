package com.planterior.helper.core.designsystem.component

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
