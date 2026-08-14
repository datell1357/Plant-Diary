package com.planterior.helper.feature.identify

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.PlantContentId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class IdentificationRouteRestorationTest {
    @get:Rule val compose = createComposeRule()

    private val monstera =
        IdentificationCandidate(
            publicContentId = PlantContentId("species-monstera"),
            koreanName = "몬스테라",
            commonName = "Swiss cheese plant",
            scientificName = "Monstera deliciosa",
            confidence = 0.93,
            thumbnailUrl = null,
        )
    private val pothos =
        IdentificationCandidate(
            publicContentId = PlantContentId("species-pothos"),
            koreanName = "스킨답서스",
            commonName = "Golden pothos",
            scientificName = "Epipremnum aureum",
            confidence = 0.67,
            thumbnailUrl = null,
        )

    @Test
    fun `second candidate survives saved state recreation and remains confirmable`() {
        // Given
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PlanteriorTheme {
                IdentificationRoute(
                    requestIdValue = "request_12345678",
                    onExit = {},
                    onRetakePhoto = {},
                    onChangePhoto = {},
                    onEditManually = {},
                    onRegisterManually = {},
                    onConfirmed = {},
                    gateway =
                        IdentificationGateway { _, _ ->
                            IdentificationResult.Candidates(listOf(monstera, pothos))
                        },
                )
            }
        }
        compose
            .onNodeWithTag(IdentificationTestTags.candidate(pothos.publicContentId.value))
            .performClick()
        compose.onNodeWithTag(IdentificationTestTags.CONFIRM).assertIsEnabled()

        // When
        restoration.emulateSavedInstanceStateRestore()

        // Then
        compose.onNodeWithTag(IdentificationTestTags.CONFIRM).assertIsEnabled()
        compose
            .onNode(
                hasTestTag(IdentificationTestTags.candidate(pothos.publicContentId.value)) and
                    hasAnyDescendant(
                        SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
                    )
            )
            .assertExists()
    }
}
