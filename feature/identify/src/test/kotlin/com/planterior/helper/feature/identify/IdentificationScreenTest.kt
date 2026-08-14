package com.planterior.helper.feature.identify

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.PlantContentId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IdentificationScreenTest {
    @get:Rule val compose = createComposeRule()

    private val candidate =
        IdentificationCandidate(
            publicContentId = PlantContentId("species-monstera"),
            koreanName = "몬스테라",
            commonName = "Swiss cheese plant",
            scientificName = "Monstera deliciosa",
            confidence = 0.93,
            thumbnailUrl = null,
        )

    @Test
    fun `confirmation remains disabled until candidate selection`() {
        // Given
        var selectedId by mutableStateOf<PlantContentId?>(null)
        compose.setContent {
            PlanteriorTheme {
                IdentificationScreen(
                    state = IdentificationUiState.Candidates(listOf(candidate), selectedId),
                    onSelect = { selectedId = it.publicContentId },
                    onConfirm = {},
                    onFallback = {},
                    onBack = {},
                )
            }
        }

        // When / Then
        compose.onNodeWithTag(IdentificationTestTags.CONFIRM).assertIsNotEnabled()
        compose
            .onNodeWithTag(IdentificationTestTags.candidate(candidate.publicContentId.value))
            .performClick()
        compose.onNodeWithTag(IdentificationTestTags.CONFIRM).assertIsEnabled()
        assertEquals(candidate.publicContentId, selectedId)
    }

    @Test
    fun `rate limit and no candidate states expose every recovery control`() {
        // Given
        var state by
            mutableStateOf<IdentificationUiState>(
                IdentificationUiState.Failed(IdentificationFailureReason.RATE_LIMITED)
            )
        compose.setContent {
            PlanteriorTheme {
                IdentificationScreen(
                    state = state,
                    onSelect = {},
                    onConfirm = {},
                    onFallback = {},
                    onBack = {},
                )
            }
        }

        for (scenario in listOf(state, IdentificationUiState.NoCandidates)) {
            compose.runOnIdle { state = scenario }

            // When / Then
            compose.onNodeWithTag(IdentificationTestTags.RETRY).assertExists().assertIsEnabled()
            compose.onNodeWithTag(IdentificationTestTags.RETAKE).assertExists()
            compose.onNodeWithTag(IdentificationTestTags.CHANGE).assertExists()
            compose.onNodeWithTag(IdentificationTestTags.EDIT).assertExists()
            compose.onNodeWithTag(IdentificationTestTags.REGISTER).assertExists()
        }
    }
}
