package com.planterior.helper.feature.watering

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
class WateringScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `detail schedule card distinguishes unavailable upcoming due and overdue with actions`() {
        val state =
            mutableStateOf<WateringScheduleStatus>(
                WateringScheduleStatus.Unavailable(
                    WateringUnavailableReason.MISSING_LAST_WATERED_DATE
                )
            )
        var records = 0
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                WateringScheduleCard(state.value, onRecordWatering = { records++ })
            }
        }

        composeRule.onNodeWithTag(WateringTestTags.UNAVAILABLE).assertIsDisplayed()
        composeRule
            .onNodeWithText("물 주기 일정")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertEquals(
            0,
            composeRule.onAllNodesWithTag(WateringTestTags.RECORD).fetchSemanticsNodes().size,
        )

        state.value = WateringScheduleStatus.Upcoming(LocalDate.of(2026, 8, 13), daysUntil = 1)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WateringTestTags.UPCOMING).assertIsDisplayed()
        composeRule
            .onNodeWithTag(WateringTestTags.RECORD)
            .performClick()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)

        state.value = WateringScheduleStatus.Due(LocalDate.of(2026, 8, 12))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WateringTestTags.DUE).assertIsDisplayed()

        state.value = WateringScheduleStatus.Overdue(LocalDate.of(2026, 8, 11), daysLate = 1)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WateringTestTags.OVERDUE).assertIsDisplayed()
        assertEquals(1, records)
    }

    @Test
    fun `confirmation failure is frozen with safe retry and completed result is explicit`() {
        var retries = 0
        var done = 0
        val rendered =
            mutableStateOf<WateringConfirmationUiState>(
                WateringConfirmationUiState.Failure(
                    snapshot = snapshot(),
                    schedule = WateringScheduleStatus.Due(LocalDate.of(2026, 8, 11)),
                    draft =
                        WateringCompletionDraft(
                            OperationId("watering-operation-stable"),
                            "2026-08-11",
                            frozen = true,
                        ),
                    nextDueDate = LocalDate.of(2026, 8, 21),
                    failure = WateringCompletionFailure.REMOTE_WRITE_FAILED,
                )
            )
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                WateringConfirmationScreen(
                    state = rendered.value,
                    onBack = {},
                    onWateredDate = {},
                    onConfirm = {},
                    onRetry = { retries++ },
                    onDone = { done++ },
                )
            }
        }

        composeRule
            .onNodeWithTag(WateringTestTags.FAILURE)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        composeRule
            .onNodeWithText("완료 기록을 저장하지 못했어요")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithTag(WateringTestTags.DATE_INPUT).assertIsNotEnabled()
        composeRule.onNodeWithTag(WateringTestTags.RETRY).performClick()
        assertEquals(1, retries)

        rendered.value = WateringConfirmationUiState.Completed(receipt())
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(WateringTestTags.RESULT)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        composeRule
            .onNodeWithText("물 주기를 기록했어요")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithTag(WateringTestTags.DONE).performClick()
        assertEquals(1, done)
    }

    @Test
    fun `reconciliation failure is announced and offers one non retry-loop action`() {
        var reconciliations = 0
        val failure =
            WateringConfirmationUiState.Failure(
                snapshot = snapshot(),
                schedule = WateringScheduleStatus.Due(LocalDate.of(2026, 8, 11)),
                draft =
                    WateringCompletionDraft(
                        OperationId("watering-operation-stable"),
                        "2026-08-11",
                        frozen = true,
                    ),
                nextDueDate = LocalDate.of(2026, 8, 21),
                failure = WateringCompletionFailure.RECONCILIATION_REQUIRED,
            )
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                WateringConfirmationScreen(
                    state = failure,
                    onBack = {},
                    onWateredDate = {},
                    onConfirm = {},
                    onRetry = {},
                    onDone = {},
                    onReconcile = { reconciliations++ },
                )
            }
        }

        assertEquals(
            0,
            composeRule.onAllNodesWithTag(WateringTestTags.RETRY).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithTag(WateringTestTags.RECONCILE).performClick()
        assertEquals(1, reconciliations)
    }

    @Test
    fun `invalid public interval has terminal actionable copy instead of loading copy`() {
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                WateringScheduleCard(
                    WateringScheduleStatus.Unavailable(
                        WateringUnavailableReason.INVALID_PUBLIC_INTERVAL
                    ),
                    onRecordWatering = null,
                )
            }
        }

        composeRule.onNodeWithText("공개 관리 정보를 다시 불러와 주세요.").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithTag(WateringTestTags.RECORD).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `future date error has semantics and never fires confirmation`() {
        var confirms = 0
        val ready =
            WateringConfirmationUiState.Ready(
                snapshot = snapshot(),
                schedule = WateringScheduleStatus.Due(LocalDate.of(2026, 8, 11)),
                draft =
                    WateringCompletionDraft(
                        OperationId("watering-operation-stable"),
                        "2026-08-12",
                    ),
                nextDueDate = LocalDate.of(2026, 8, 22),
                validationError = WateringCompletionValidationError.FUTURE_DATE,
            )
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                WateringConfirmationScreen(
                    state = ready,
                    onBack = {},
                    onWateredDate = {},
                    onConfirm = { confirms++ },
                    onRetry = {},
                    onDone = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(WateringTestTags.DATE_INPUT)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            .performTextReplacement("2026-08-13")
        composeRule.onNodeWithTag(WateringTestTags.CONFIRM).assertIsNotEnabled()
        assertEquals(0, confirms)
    }

    @Test
    fun `deleted notification target renders safe not found with collection return action`() {
        var backs = 0
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                WateringConfirmationScreen(
                    state = WateringConfirmationUiState.NotFound,
                    onBack = { backs++ },
                    onWateredDate = {},
                    onConfirm = {},
                    onRetry = {},
                    onDone = {},
                )
            }
        }

        composeRule.onNodeWithText("식물을 찾을 수 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("식물 관리로 돌아가기").performClick()
        assertEquals(1, backs)
    }

    private fun snapshot() =
        WateringPlantSnapshot(
            AccountId("account-a"),
            PersonalPlantId("plant-a"),
            "몬스테라",
            LocalDate.of(2026, 8, 1),
            10,
            ZoneId.of("Asia/Seoul"),
            4,
        )

    private fun receipt() =
        WateringCompletionReceipt(
            AccountId("account-a"),
            PersonalPlantId("plant-a"),
            OperationId("watering-operation-stable"),
            "watering-operation-stable",
            LocalDate.of(2026, 8, 11),
            LocalDate.of(2026, 8, 21),
            5,
            3,
            Instant.parse("2026-08-11T00:00:00Z"),
        )
}
