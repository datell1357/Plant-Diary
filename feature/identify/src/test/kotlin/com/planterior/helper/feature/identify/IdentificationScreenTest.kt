package com.planterior.helper.feature.identify

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.PlantContentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
    fun `candidate cards render on shared white surface`() {
        var expectedSurface = Color.Unspecified
        compose.setContent {
            PlanteriorTheme {
                expectedSurface = MaterialTheme.colorScheme.surface
                IdentificationScreen(
                    state =
                        IdentificationUiState.Candidates(
                            listOf(candidate),
                            candidate.publicContentId,
                        ),
                    onSelect = {},
                    onConfirm = {},
                    onFallback = {},
                    onBack = {},
                )
            }
        }

        val candidateBounds =
            compose
                .onNodeWithTag(
                    IdentificationTestTags.candidate(candidate.publicContentId.value),
                    useUnmergedTree = true,
                )
                .fetchSemanticsNode()
                .boundsInRoot
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val renderedSurface =
            pixels[(candidateBounds.right - 8f).toInt(), candidateBounds.center.y.toInt()]

        assertEquals(
            "candidate card did not render the shared surface token; root=${pixels.width}x${pixels.height}, bounds=$candidateBounds",
            expectedSurface,
            renderedSurface,
        )
    }

    @Test
    fun `title is heading and each candidate is one selectable radio control`() {
        val second =
            candidate.copy(
                publicContentId = PlantContentId("species-pothos"),
                koreanName = "스킨답서스",
                commonName = "Golden pothos",
                scientificName = "Epipremnum aureum",
                confidence = 0.87,
            )
        var selectedId by mutableStateOf<PlantContentId?>(candidate.publicContentId)
        compose.setContent {
            PlanteriorTheme {
                IdentificationScreen(
                    state =
                        IdentificationUiState.Candidates(
                            listOf(candidate, second),
                            selectedId,
                        ),
                    onSelect = { selectedId = it.publicContentId },
                    onConfirm = {},
                    onFallback = {},
                    onBack = {},
                )
            }
        }

        compose
            .onNodeWithText("식물 후보 확인")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val selectableRadio =
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)
        compose.onAllNodes(selectableRadio, useUnmergedTree = true).assertCountEquals(2)

        val firstTag = IdentificationTestTags.candidate(candidate.publicContentId.value)
        val secondTag = IdentificationTestTags.candidate(second.publicContentId.value)
        compose
            .onNodeWithTag(firstTag, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(hasClickAction())
        compose
            .onNodeWithTag(secondTag, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
            .performClick()
        compose
            .onNodeWithTag(firstTag, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        compose
            .onNodeWithTag(secondTag, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))

        listOf(firstTag, secondTag).forEach { tag ->
            compose
                .onAllNodes(
                    hasAnyAncestor(hasTestTag(tag)) and hasClickAction(),
                    useUnmergedTree = true,
                )
                .assertCountEquals(0)
            val bounds =
                compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val density =
                ApplicationProvider.getApplicationContext<Context>()
                    .resources
                    .displayMetrics
                    .density
            assertTrue("$tag was less than 48dp high", bounds.height / density >= 48f)
        }
    }

    @Test
    fun `candidate CJK remains inside the viewport at 200 percent font scale`() {
        val longCandidate =
            candidate.copy(
                koreanName = "무늬가 선명한 몬스테라 델리시오사",
                commonName = "Variegated Swiss cheese plant",
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                PlanteriorTheme {
                    IdentificationScreen(
                        state =
                            IdentificationUiState.Candidates(
                                listOf(longCandidate),
                                longCandidate.publicContentId,
                            ),
                        onSelect = {},
                        onConfirm = {},
                        onFallback = {},
                        onBack = {},
                    )
                }
            }
        }

        compose.onNodeWithText("무늬가 선명한 몬스테라 델리시오사").performScrollTo().assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val candidateBounds =
            compose
                .onNodeWithTag(
                    IdentificationTestTags.candidate(longCandidate.publicContentId.value),
                    useUnmergedTree = true,
                )
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(candidateBounds.left >= root.left)
        assertTrue(candidateBounds.right <= root.right)
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
