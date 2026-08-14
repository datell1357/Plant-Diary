package com.planterior.helper.navigation

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.feature.identify.IdentificationTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [ROBOLECTRIC_MAX_SDK],
    qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class IdentificationLifecycleRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `confirmed identification handoff survives nav host saved state recreation`() {
        // Given
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PlanteriorTheme {
                PlanteriorNavHost(
                    navController = rememberNavController(),
                    startRoute = PlanteriorRoute.Identification("fixture-success"),
                )
            }
        }
        compose
            .onNodeWithTag(IdentificationTestTags.candidate("species-pothos"))
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(IdentificationTestTags.CONFIRM).performScrollTo().performClick()
        compose.onNodeWithText("스킨답서스", substring = true).assertExists()

        // When
        restoration.emulateSavedInstanceStateRestore()

        // Then
        compose.onNodeWithText("스킨답서스", substring = true).assertExists()
    }
}
