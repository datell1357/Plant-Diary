package com.planterior.helper.feature.registration

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.PersonalPlantId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RegistrationScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `last watered field forwards fixed valid date`() {
        // Given
        val draft =
            RegistrationDraft(
                PersonalPlantId("plant-date"),
                null,
                "몬스테라",
                null,
                null,
                null,
            )
        var enteredDate: String? = null
        compose.setContent {
            PlanteriorTheme {
                RegistrationScreen(
                    state = RegistrationUiState.Editing(draft),
                    identifiedRequestId = null,
                    onName = {},
                    onDate = { enteredDate = it },
                    onSearch = {},
                    onSelectContent = {},
                    onUseIdentificationPhoto = {},
                    onPickPhoto = {},
                    onSubmit = {},
                    onOpenExisting = {},
                    onAddAnother = {},
                    onCancelDuplicate = {},
                    onRetry = {},
                    onCancel = {},
                )
            }
        }

        // When
        compose
            .onNodeWithTag(RegistrationTestTags.LAST_WATERED)
            .performTextReplacement("2026-08-20")

        // Then
        compose.runOnIdle { assertEquals("2026-08-20", enteredDate) }
    }

    @Test
    fun `direct entry and duplicate state expose the required actions`() {
        val draft =
            RegistrationDraft(
                PersonalPlantId("plant-a"),
                null,
                "몬스테라",
                null,
                null,
                null,
            )
        var ui by mutableStateOf<RegistrationUiState>(RegistrationUiState.Editing(draft))
        compose.setContent {
            PlanteriorTheme {
                RegistrationScreen(
                    state = ui,
                    identifiedRequestId = null,
                    onName = {},
                    onDate = {},
                    onSearch = {},
                    onSelectContent = {},
                    onUseIdentificationPhoto = {},
                    onPickPhoto = {},
                    onSubmit = {},
                    onOpenExisting = {},
                    onAddAnother = {},
                    onCancelDuplicate = {},
                    onRetry = {},
                    onCancel = {},
                )
            }
        }
        compose.onNodeWithTag(RegistrationTestTags.NAME).assertIsDisplayed()
        compose.onNodeWithTag(RegistrationTestTags.SUBMIT).assertIsEnabled()

        compose.runOnIdle {
            ui =
                RegistrationUiState.DuplicateFound(
                    draft,
                    listOf(
                        ExistingPersonalPlant(
                            PersonalPlantId("existing-a"),
                            "기존 몬스테라",
                        )
                    ),
                )
        }
        compose.onNodeWithText("기존 식물 열기").assertIsDisplayed()
        compose.onNodeWithText("한 그루 더 등록").assertIsDisplayed()
        compose.onNodeWithText("등록 취소").assertIsDisplayed()
    }
}
