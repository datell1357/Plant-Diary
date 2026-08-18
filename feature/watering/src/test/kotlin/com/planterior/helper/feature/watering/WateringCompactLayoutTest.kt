package com.planterior.helper.feature.watering

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import java.time.Instant
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [36],
    qualifiers = "w402dp-h360dp-normal-notlong-notround-any-420dpi-keyshidden-nonav",
)
class WateringCompactLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `compact confirmation keeps the fixed return action and scrolls to the result action`() {
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                WateringConfirmationScreen(
                    state = WateringConfirmationUiState.Completed(receipt()),
                    onBack = {},
                    onWateredDate = {},
                    onConfirm = {},
                    onRetry = {},
                    onDone = {},
                )
            }
        }

        composeRule.onNodeWithTag(WateringTestTags.RESULT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(WateringTestTags.DONE).performScrollTo().assertIsDisplayed()
    }

    private fun receipt() =
        WateringCompletionReceipt(
            AccountId("account-a"),
            PersonalPlantId("plant-a"),
            OperationId("watering-operation-stable"),
            "watering-operation-stable",
            LocalDate.of(2026, 8, 12),
            LocalDate.of(2026, 8, 22),
            5,
            3,
            Instant.parse("2026-08-12T00:30:00Z"),
        )
}
