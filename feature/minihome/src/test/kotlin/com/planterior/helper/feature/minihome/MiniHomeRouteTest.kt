package com.planterior.helper.feature.minihome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
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
class MiniHomeRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `A to B composition is synchronously redacted before success or failure effects`() {
        var state by mutableStateOf<MiniHomeUiState>(viewing("account-a", "A private room"))
        var auth by mutableStateOf<MiniHomeAuthOwnership>(authenticated("account-a"))
        val observed = mutableListOf<MiniHomeUiState>()
        setGateContent({ state }, { auth }, observed::add)
        compose.onNodeWithText("A private room").assertIsDisplayed()

        auth = authenticated("account-b")
        compose.onNodeWithTag(MiniHomeTestTags.LOADING).assertIsDisplayed()
        assertPrivateStateAbsent("A private room")
        assertEquals(AccountId("account-b"), observed.last().owner)

        state = viewing("account-b", "B room")
        compose.onNodeWithText("B room").assertIsDisplayed()
        compose.onAllNodesWithText("A private room").assertCountEquals(0)

        state = viewing("account-a", "A private room")
        compose.onNodeWithTag(MiniHomeTestTags.LOADING).assertIsDisplayed()
        assertPrivateStateAbsent("A private room")
        state = MiniHomeUiState.Unavailable(AccountId("account-b"))
        compose.onNodeWithTag(MiniHomeTestTags.ERROR).assertIsDisplayed()
        assertPrivateStateAbsent("A private room")
    }

    @Test
    fun `B to A logout restoring and unknown compositions fail closed`() {
        var state by mutableStateOf<MiniHomeUiState>(viewing("account-b", "B private room"))
        var auth by mutableStateOf<MiniHomeAuthOwnership>(authenticated("account-b"))
        setGateContent({ state }, { auth })
        compose.onNodeWithText("B private room").assertIsDisplayed()

        auth = authenticated("account-a")
        compose.onNodeWithTag(MiniHomeTestTags.LOADING).assertIsDisplayed()
        assertPrivateStateAbsent("B private room")
        state = viewing("account-a", "A current room")
        compose.onNodeWithText("A current room").assertIsDisplayed()
        compose.onAllNodesWithText("B private room").assertCountEquals(0)

        auth = MiniHomeAuthOwnership.SignedOut
        compose.onAllNodesWithText("A current room").assertCountEquals(0)
        compose.onAllNodesWithText("B private room").assertCountEquals(0)
        compose.onAllNodesWithTag(MiniHomeTestTags.EDIT).assertCountEquals(0)

        auth = MiniHomeAuthOwnership.Restoring
        compose.onNodeWithTag(MiniHomeTestTags.LOADING).assertIsDisplayed()
        assertPrivateStateAbsent("A current room")

        auth = MiniHomeAuthOwnership.Unknown
        compose.onNodeWithTag(MiniHomeTestTags.LOADING).assertIsDisplayed()
        assertPrivateStateAbsent("A current room")
    }

    @Test
    fun `same owner state renders normally with its actions`() {
        val state = viewing("account-a", "A current room")
        setGateContent({ state }, { authenticated("account-a") })

        compose.onNodeWithText("A current room").assertIsDisplayed()
        compose.onAllNodesWithTag(MiniHomeTestTags.EDIT).assertCountEquals(1)
    }

    @Test
    fun `restored stale owner state is redacted on the first recreated composition`() {
        val restoration = StateRestorationTester(compose)
        var state by
            mutableStateOf<MiniHomeUiState>(viewing("account-a", "A restored private room"))
        var auth by mutableStateOf<MiniHomeAuthOwnership>(authenticated("account-a"))
        restoration.setContent {
            PlanteriorTheme { GatedScreen(state, auth) }
        }
        compose.onNodeWithText("A restored private room").assertIsDisplayed()

        auth = MiniHomeAuthOwnership.Restoring
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(MiniHomeTestTags.LOADING).assertIsDisplayed()
        assertPrivateStateAbsent("A restored private room")

        auth = authenticated("account-b")
        compose.onNodeWithTag(MiniHomeTestTags.LOADING).assertIsDisplayed()
        assertPrivateStateAbsent("A restored private room")

        state = viewing("account-b", "B restored room")
        compose.onNodeWithText("B restored room").assertIsDisplayed()
        compose.onAllNodesWithText("A restored private room").assertCountEquals(0)
    }

    private fun setGateContent(
        state: () -> MiniHomeUiState,
        auth: () -> MiniHomeAuthOwnership,
        onObserved: (MiniHomeUiState) -> Unit = {},
    ) {
        compose.setContent {
            PlanteriorTheme {
                MiniHomeOwnershipGate(state(), auth(), onObserved) { displayed ->
                    Screen(displayed)
                }
            }
        }
    }

    private fun assertPrivateStateAbsent(text: String) {
        compose.onAllNodesWithText(text).assertCountEquals(0)
        compose.onAllNodesWithTag(MiniHomeTestTags.CANVAS).assertCountEquals(0)
        compose.onAllNodesWithTag(MiniHomeTestTags.EDIT).assertCountEquals(0)
        compose.onAllNodesWithTag(MiniHomeTestTags.SAVE).assertCountEquals(0)
    }

    private fun viewing(owner: String, name: String) =
        MiniHomeUiState.Viewing(
            committed =
                MiniHomeLayout(
                    MiniHomeId("home-$owner"),
                    name,
                    emptyList(),
                    Revision(1),
                    Instant.EPOCH,
                ),
            plants = emptyList(),
            decorations = emptyList(),
            stale = false,
            owner = AccountId(owner),
        )

    private fun authenticated(owner: String): MiniHomeAuthOwnership =
        MiniHomeAuthOwnership.Authenticated(AccountId(owner))

    @Composable
    private fun GatedScreen(state: MiniHomeUiState, auth: MiniHomeAuthOwnership) {
        MiniHomeOwnershipGate(state, auth) { displayed -> Screen(displayed) }
    }

    @Composable
    private fun Screen(state: MiniHomeUiState) {
        MiniHomeScreen(
            state = state,
            session = MiniHomeControllerSessionToken(1L, 1L, state.owner),
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
            onDiscard = { MiniHomeDiscardResult.Missing },
            onAdoptConflict = {},
            onOpenCollection = {},
        )
    }
}
