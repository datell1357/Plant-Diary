package com.planterior.helper.feature.minihome

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MiniHomeShareEntryTest {
    @get:Rule val compose = createComposeRule()

    private val session = MiniHomeControllerSessionToken(0L, 0L, AccountId.LEGACY)

    private fun layout() =
        MiniHomeLayout(
            MiniHomeId("mini-home-a"),
            "우리 집 식물원",
            emptyList(),
            Revision(3),
            Instant.ofEpochMilli(1_700_000_000_000L),
        )

    private fun setScreen(state: MiniHomeUiState, onOpenShare: (() -> Unit)? = {}) {
        compose.setContent {
            PlanteriorTheme {
                MiniHomeScreen(
                    state = state,
                    session = session,
                    onBack = {},
                    onRetryLoad = {},
                    onBeginEditing = {},
                    onRename = {},
                    onAddPlant = {},
                    onAddDecoration = {},
                    onSelect = {},
                    onMove = {},
                    onMoveBy = { _, _ -> },
                    onRemove = {},
                    onSave = {},
                    onDiscard = { MiniHomeDiscardResult.Consumed },
                    onAdoptConflict = {},
                    onOpenCollection = {},
                    onOpenShare = onOpenShare,
                )
            }
        }
    }

    @Test
    fun `viewing footer shows a text labeled share action beside edit`() {
        var opened = 0
        setScreen(MiniHomeUiState.Viewing(layout(), emptyList(), emptyList(), stale = false)) {
            opened += 1
        }

        compose
            .onNodeWithTag(MiniHomeTestTags.SHARE)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("미니홈 공유").assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeTestTags.SHARE).performScrollTo().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `share entry is absent while loading`() {
        setScreen(MiniHomeUiState.Loading(AccountId.LEGACY))

        compose.onNodeWithTag(MiniHomeTestTags.SHARE).assertDoesNotExist()
    }

    @Test
    fun `share entry is absent while editing`() {
        setScreen(
            MiniHomeUiState.Editing(
                layout(),
                layout(),
                emptyList(),
                emptyList(),
                null,
                OperationId("operation-share-entry"),
                MiniHomeSaveState.Idle,
            )
        )

        compose.onNodeWithTag(MiniHomeTestTags.SHARE).assertDoesNotExist()
    }

    @Test
    fun `share entry is absent in the forbidden state`() {
        setScreen(MiniHomeUiState.Forbidden)

        compose.onNodeWithTag(MiniHomeTestTags.SHARE).assertDoesNotExist()
    }

    @Test
    fun `share entry is absent in the unavailable state`() {
        setScreen(MiniHomeUiState.Unavailable(AccountId.LEGACY))

        compose.onNodeWithTag(MiniHomeTestTags.SHARE).assertDoesNotExist()
    }

    @Test
    fun `share entry is absent in the error state`() {
        setScreen(MiniHomeUiState.Error)

        compose.onNodeWithTag(MiniHomeTestTags.SHARE).assertDoesNotExist()
    }

    @Test
    fun `share entry is absent when no share destination is wired`() {
        setScreen(
            MiniHomeUiState.Viewing(layout(), emptyList(), emptyList(), stale = false),
            onOpenShare = null,
        )

        compose.onNodeWithTag(MiniHomeTestTags.SHARE).assertDoesNotExist()
    }
}
