package com.planterior.helper.feature.collection

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [36],
    qualifiers = "w320dp-h320dp-normal-notlong-notround-any-420dpi-keyshidden-nonav",
)
class CollectionCompactLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `empty and error actions remain reachable at compact height with large font`() {
        val surface = mutableStateOf(0)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                    when (surface.value) {
                        0 ->
                            CollectionScreen(
                                state = CollectionUiState.Empty,
                                listPosition = CollectionListPosition.ZERO,
                                onListPositionChanged = { _, _ -> },
                                onOpenPlant = {},
                                onIdentify = {},
                                onRegisterDirectly = {},
                                onRetry = {},
                            )
                        1 ->
                            CollectionScreen(
                                state = CollectionUiState.Error,
                                listPosition = CollectionListPosition.ZERO,
                                onListPositionChanged = { _, _ -> },
                                onOpenPlant = {},
                                onIdentify = {},
                                onRegisterDirectly = {},
                                onRetry = {},
                            )
                        else ->
                            PlantDetailScreen(
                                state = PlantDetailUiState.Error,
                                onBack = {},
                                onRetry = {},
                                onBeginEditing = {},
                                onLastWateredDate = {},
                                onLocation = {},
                                onPrivateNote = {},
                                onSave = {},
                                onCancelEdit = {},
                                onReconcileEdit = {},
                            )
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(CollectionTestTags.REGISTER_DIRECT)
            .performScrollTo()
            .assertIsDisplayed()

        surface.value = 1
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CollectionTestTags.RETRY).performScrollTo().assertIsDisplayed()

        surface.value = 2
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PlantDetailTestTags.RETRY).performScrollTo().assertIsDisplayed()
    }
}
