package com.planterior.helper.feature.minihome

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MiniHomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val legacySession = MiniHomeControllerSessionToken(0L, 0L, AccountId.LEGACY)

    @Test
    fun `accessible controls add move remove and surface unsaved confirmation`() {
        val plant = MiniHomePlantChoice(PersonalPlantId("plant-a"), "몬스테라", null)
        var current by
            mutableStateOf<MiniHomeUiState>(
                MiniHomeUiState.Editing(
                    layout(),
                    layout(),
                    listOf(plant),
                    emptyList(),
                    null,
                    OperationId("operation-ui-1"),
                    MiniHomeSaveState.Idle,
                )
            )
        var back = false
        compose.setContent {
            PlanteriorTheme {
                MiniHomeScreen(
                    state = current,
                    session = legacySession,
                    onBack = { back = true },
                    onRetryLoad = {},
                    onBeginEditing = {},
                    onRename = {},
                    onAddPlant = {
                        val editing = current as MiniHomeUiState.Editing
                        val placement =
                            MiniHomePlacement(
                                PlacementId("placement-a"),
                                MiniHomePlacementTarget.Plant(it),
                                GridPosition(0, 0),
                                MiniHomeZIndex(0),
                            )
                        current =
                            editing.copy(
                                draft = editing.draft.copy(placements = listOf(placement)),
                                selectedPlacementId = placement.id,
                            )
                    },
                    onAddDecoration = {},
                    onSelect = {},
                    onMove = {},
                    onMoveBy = { _, _ -> },
                    onRemove = {},
                    onSave = {},
                    onDiscard = { MiniHomeDiscardResult.Consumed },
                    onAdoptConflict = {},
                    onOpenCollection = {},
                )
            }
        }

        compose.onNodeWithText("몬스테라 추가").performScrollTo().performClick()
        compose
            .onNodeWithTag(MiniHomeTestTags.MOVE_RIGHT)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        compose
            .onNodeWithTag(MiniHomeTestTags.REMOVE)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithTag(MiniHomeTestTags.UNSAVED_DIALOG).assertIsDisplayed()
        compose.onNodeWithText("계속 편집").performClick()

        assertFalse(back)
    }

    @Test
    fun `stale discard feedback is visible and exposed as an accessibility error`() {
        compose.setContent {
            PlanteriorTheme {
                MiniHomeScreen(
                    state =
                        MiniHomeUiState.Editing(
                            layout(),
                            layout().copy(name = "교체된 편집"),
                            emptyList(),
                            emptyList(),
                            null,
                            OperationId("replacement-operation"),
                            MiniHomeSaveState.ReconciliationRequired(
                                MiniHomeSaveFailure.OUTBOX_MISMATCH
                            ),
                            discardFeedback = MiniHomeDiscardFeedback.STALE_HANDLE,
                        ),
                    session = legacySession,
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
                )
            }
        }

        compose
            .onNodeWithTag(MiniHomeTestTags.DISCARD_FEEDBACK)
            .performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test
    fun `durable transient failure requires typed discard even when draft matches committed`() {
        val handle =
            MiniHomeDiscardHandle(
                com.planterior.helper.core.model.AccountId("account-a"),
                "miniHomeLayouts",
                "transient-operation",
                "transient-operation",
                "transient-generation",
            )
        var backCalls = 0
        compose.setContent {
            PlanteriorTheme {
                MiniHomeScreen(
                    state =
                        MiniHomeUiState.Editing(
                            layout(),
                            layout(),
                            emptyList(),
                            emptyList(),
                            null,
                            OperationId("transient-operation"),
                            MiniHomeSaveState.Failed(MiniHomeSaveFailure.NETWORK),
                            discardHandle = handle,
                        ),
                    session = legacySession,
                    onBack = { backCalls += 1 },
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
                )
            }
        }

        compose.onNodeWithText("홈으로 돌아가기").performClick()

        compose.onNodeWithTag(MiniHomeTestTags.UNSAVED_DIALOG).assertIsDisplayed()
        assertEquals(0, backCalls)
    }

    @Test
    fun `unsaved dialog awaits discard disables double tap and navigates exactly once`() {
        val gate = CompletableDeferred<Unit>()
        var discardCalls = 0
        var backCalls = 0
        var durableRows = 0
        compose.setContent {
            PlanteriorTheme {
                MiniHomeScreen(
                    state =
                        MiniHomeUiState.Editing(
                            layout(),
                            layout().copy(name = "버릴 편집"),
                            emptyList(),
                            emptyList(),
                            null,
                            OperationId("discard-await-operation"),
                            MiniHomeSaveState.Failed(MiniHomeSaveFailure.DATABASE),
                        ),
                    session = legacySession,
                    onBack = {
                        assertEquals(0, durableRows)
                        backCalls += 1
                    },
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
                    onDiscard = {
                        discardCalls += 1
                        gate.await()
                        MiniHomeDiscardResult.Missing
                    },
                    onAdoptConflict = {},
                    onOpenCollection = {},
                )
            }
        }

        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장 안 함").performClick().assertIsNotEnabled()
        compose.onNodeWithText("저장 안 함").performClick()
        compose.runOnIdle {
            assertEquals(1, discardCalls)
            assertEquals(0, backCalls)
            gate.complete(Unit)
        }
        compose.waitUntil(timeoutMillis = 5_000) { backCalls == 1 }

        assertEquals(1, discardCalls)
        assertEquals(1, backCalls)
    }

    @Test
    fun `activity state recreation during discard allows one authoritative retry and one navigation`() {
        val restoration = StateRestorationTester(compose)
        val firstEntered = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        var discardCalls = 0
        var backCalls = 0
        var durableRows = 0
        restoration.setContent {
            PlanteriorTheme {
                MiniHomeScreen(
                    state =
                        MiniHomeUiState.Editing(
                            layout(),
                            layout().copy(name = "재생성 중 편집"),
                            emptyList(),
                            emptyList(),
                            null,
                            OperationId("recreated-discard-operation"),
                            MiniHomeSaveState.Failed(MiniHomeSaveFailure.DATABASE),
                        ),
                    session = legacySession,
                    onBack = {
                        assertEquals(0, durableRows)
                        backCalls += 1
                    },
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
                    onDiscard = {
                        discardCalls += 1
                        if (discardCalls == 1) {
                            firstEntered.complete(Unit)
                            neverComplete.await()
                        }
                        MiniHomeDiscardResult.Missing
                    },
                    onAdoptConflict = {},
                    onOpenCollection = {},
                )
            }
        }

        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장 안 함").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { firstEntered.isCompleted }

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag(MiniHomeTestTags.UNSAVED_DIALOG).assertIsDisplayed()
        compose.onNodeWithText("저장 안 함").assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { backCalls == 1 }
        assertEquals(2, discardCalls)
        assertEquals(1, backCalls)
    }

    @Test
    fun `committed save winning discard race stays on screen and surfaces authoritative result`() {
        val committed = layout().copy(name = "경합 중 저장된 방", revision = Revision(2))
        var current by
            mutableStateOf<MiniHomeUiState>(
                MiniHomeUiState.Editing(
                    layout(),
                    layout().copy(name = "경합 중 저장된 방"),
                    emptyList(),
                    emptyList(),
                    null,
                    OperationId("committed-race-operation"),
                    MiniHomeSaveState.Saving,
                )
            )
        var backCalls = 0
        compose.setContent {
            PlanteriorTheme {
                MiniHomeScreen(
                    state = current,
                    session = legacySession,
                    onBack = { backCalls += 1 },
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
                    onDiscard = {
                        current =
                            MiniHomeUiState.Viewing(
                                committed,
                                emptyList(),
                                emptyList(),
                                stale = false,
                                saved = true,
                            )
                        MiniHomeDiscardResult.Committed(committed)
                    },
                    onAdoptConflict = {},
                    onOpenCollection = {},
                )
            }
        }

        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장 안 함").performClick()

        compose.onNodeWithText("저장했어요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("경합 중 저장된 방").performScrollTo().assertIsDisplayed()
        assertEquals(0, backCalls)
    }

    @Test
    fun `stale dialog discard keeps screen open and announces replacement feedback`() {
        val handle =
            MiniHomeDiscardHandle(
                com.planterior.helper.core.model.AccountId("account-a"),
                "miniHomeLayouts",
                "replacement-operation",
                "replacement-operation",
                "replacement-generation",
            )
        var current by
            mutableStateOf<MiniHomeUiState>(
                MiniHomeUiState.Editing(
                    layout(),
                    layout().copy(name = "이전 편집"),
                    emptyList(),
                    emptyList(),
                    null,
                    OperationId("old-operation"),
                    MiniHomeSaveState.Idle,
                )
            )
        var backCalls = 0
        compose.setContent {
            PlanteriorTheme {
                MiniHomeScreen(
                    state = current,
                    session = legacySession,
                    onBack = { backCalls += 1 },
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
                    onDiscard = {
                        current =
                            (current as MiniHomeUiState.Editing).copy(
                                draft = layout().copy(name = "교체된 편집"),
                                discardHandle = handle,
                                discardFeedback = MiniHomeDiscardFeedback.STALE_HANDLE,
                            )
                        MiniHomeDiscardResult.StaleHandle(null)
                    },
                    onAdoptConflict = {},
                    onOpenCollection = {},
                )
            }
        }

        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장 안 함").performClick()

        compose.onNodeWithText("교체된 편집").assertIsDisplayed()
        compose
            .onNodeWithTag(MiniHomeTestTags.DISCARD_FEEDBACK)
            .performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        assertEquals(0, backCalls)
    }

    @Test
    fun `placement bottom center is the canonical cell center at large text and custom density`() {
        val position = GridPosition(3, 2)
        val placement =
            MiniHomePlacement(
                PlacementId("placement-coordinate"),
                MiniHomePlacementTarget.Plant(PersonalPlantId("plant-coordinate")),
                position,
                MiniHomeZIndex(0),
            )
        val layout = layout().copy(placements = listOf(placement))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2.25f, 2f)) {
                PlanteriorTheme {
                    MiniHomeScreen(
                        state =
                            MiniHomeUiState.Viewing(
                                layout,
                                listOf(
                                    MiniHomePlantChoice(
                                        PersonalPlantId("plant-coordinate"),
                                        "큰 글자 몬스테라",
                                        null,
                                    )
                                ),
                                emptyList(),
                                stale = false,
                            ),
                        session = legacySession,
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
                    )
                }
            }
        }

        compose.onNodeWithTag(MiniHomeTestTags.CANVAS).performScrollTo()
        val canvas =
            compose.onNodeWithTag(MiniHomeTestTags.CANVAS).fetchSemanticsNode().boundsInRoot
        val miniature =
            compose
                .onNodeWithTag(MiniHomeTestTags.placement(placement.id))
                .fetchSemanticsNode()
                .boundsInRoot
        val expected = MiniHomeIsometricProjection(canvas.width, canvas.height).cellCenter(position)

        assertTrue(
            "center=${miniature.center.x}, expected=${canvas.left + expected.x}",
            abs(miniature.center.x - (canvas.left + expected.x)) <= 2f,
        )
        assertTrue(
            "bottom=${miniature.bottom}, expected=${canvas.top + expected.y}, canvas=$canvas, miniature=$miniature",
            abs(miniature.bottom - (canvas.top + expected.y)) <= 2f,
        )
        assertTrue(miniature.left >= canvas.left)
        assertTrue(miniature.right <= canvas.right)
        assertTrue(miniature.top >= canvas.top)
        assertTrue(miniature.bottom <= canvas.bottom)
    }

    @Test
    fun `drag delta is inverted by the same projection used for target cell`() {
        val start = GridPosition(0, 0)
        val target = GridPosition(3, 2)
        val placement =
            MiniHomePlacement(
                PlacementId("placement-drag"),
                MiniHomePlacementTarget.Plant(PersonalPlantId("plant-drag")),
                start,
                MiniHomeZIndex(0),
            )
        val layout = layout().copy(placements = listOf(placement))
        var moved: GridPosition? = null
        compose.setContent {
            PlanteriorTheme {
                MiniHomeScreen(
                    state =
                        MiniHomeUiState.Editing(
                            layout,
                            layout,
                            listOf(
                                MiniHomePlantChoice(
                                    PersonalPlantId("plant-drag"),
                                    "드래그 식물",
                                    null,
                                )
                            ),
                            emptyList(),
                            placement.id,
                            OperationId("operation-drag"),
                            MiniHomeSaveState.Idle,
                        ),
                    session = legacySession,
                    onBack = {},
                    onRetryLoad = {},
                    onBeginEditing = {},
                    onRename = {},
                    onAddPlant = {},
                    onAddDecoration = {},
                    onSelect = {},
                    onMove = { moved = it },
                    onMoveBy = { _, _ -> },
                    onRemove = {},
                    onSave = {},
                    onDiscard = { MiniHomeDiscardResult.Consumed },
                    onAdoptConflict = {},
                    onOpenCollection = {},
                )
            }
        }
        compose.onNodeWithTag(MiniHomeTestTags.CANVAS).performScrollTo()
        val canvas =
            compose.onNodeWithTag(MiniHomeTestTags.CANVAS).fetchSemanticsNode().boundsInRoot
        val projection = MiniHomeIsometricProjection(canvas.width, canvas.height)
        val from = projection.cellCenter(start)
        val to = projection.cellCenter(target)

        compose.onNodeWithTag(MiniHomeTestTags.placement(placement.id)).performTouchInput {
            swipe(
                start = center,
                end =
                    center +
                        Offset(
                            (to.x - from.x) * 1.03f,
                            (to.y - from.y) * 1.03f,
                        ),
                durationMillis = 300,
            )
        }

        assertEquals(target, moved)
    }

    @Test
    fun `representative photo is requested for room and picker identity`() {
        val plant =
            MiniHomePlantChoice(
                PersonalPlantId("plant-photo"),
                "사진 몬스테라",
                "users/account-a/plants/plant-photo/thumb.jpg",
            )
        val placement =
            MiniHomePlacement(
                PlacementId("placement-photo"),
                MiniHomePlacementTarget.Plant(plant.id),
                GridPosition(1, 1),
                MiniHomeZIndex(0),
            )
        val layout = layout().copy(placements = listOf(placement))
        var loads = 0
        val loader = MiniHomePhotoLoader {
            loads += 1
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        }
        compose.setContent {
            PlanteriorTheme {
                MiniHomeScreen(
                    state =
                        MiniHomeUiState.Editing(
                            layout,
                            layout,
                            listOf(plant),
                            emptyList(),
                            placement.id,
                            OperationId("operation-photo"),
                            MiniHomeSaveState.Idle,
                        ),
                    session = legacySession,
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
                    photoLoader = loader,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) { loads == 2 }
        compose.onNodeWithText("사진 몬스테라 배치됨").performScrollTo().assertIsDisplayed()
        assertEquals(2, loads)
    }

    @Test
    fun `owner scoped loading and unavailable UI never retain account A draft`() {
        var current by mutableStateOf<MiniHomeUiState>(editing("privacy-a", "A 비밀 UI 편집"))
        var session by mutableStateOf(session("account-a", generation = 1L))
        setExitContent(
            state = { current },
            session = { session },
            onBack = {},
        )
        compose.onNodeWithText("A 비밀 UI 편집").assertIsDisplayed()

        current = MiniHomeUiState.Loading(AccountId("account-b"))
        session = session("account-b", generation = 2L)
        compose.onNodeWithTag(MiniHomeTestTags.LOADING).assertIsDisplayed()
        compose.onAllNodesWithText("A 비밀 UI 편집").assertCountEquals(0)

        current = MiniHomeUiState.Unavailable(AccountId("account-b"))
        compose.onNodeWithTag(MiniHomeTestTags.ERROR).assertIsDisplayed()
        compose.onAllNodesWithText("A 비밀 UI 편집").assertCountEquals(0)
    }

    @Test
    fun `late discard result from account A cannot navigate account B`() {
        val discardEntered = CompletableDeferred<Unit>()
        val discardRelease = CompletableDeferred<Unit>()
        var current by mutableStateOf<MiniHomeUiState>(editing("discard-a", "A 편집"))
        var session by mutableStateOf(session("account-a", generation = 1L))
        var backCalls = 0
        setExitContent(
            state = { current },
            session = { session },
            onBack = { backCalls += 1 },
            onDiscard = {
                discardEntered.complete(Unit)
                discardRelease.await()
                MiniHomeDiscardResult.Missing
            },
        )

        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장 안 함").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { discardEntered.isCompleted }

        session = session("account-b", generation = 2L)
        current = editing("discard-b", "B 편집")
        compose.onAllNodesWithTag(MiniHomeTestTags.UNSAVED_DIALOG).assertCountEquals(0)
        discardRelease.complete(Unit)
        compose.waitForIdle()

        assertEquals(0, backCalls)
    }

    @Test
    fun `discard state outcome and callback race navigate once for the same intent token`() {
        var current by mutableStateOf<MiniHomeUiState>(editing("discard-race", "버릴 편집"))
        val session = session("account-a")
        var backCalls = 0
        setExitContent(
            state = { current },
            session = { session },
            onBack = { backCalls += 1 },
            onDiscard = {
                current = discarded("account-a", "discard-race")
                MiniHomeDiscardResult.Missing
            },
            intentIdFactory = { "discard-race-intent" },
        )

        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장 안 함").performClick()
        compose.runOnIdle { assertEquals(1, backCalls) }
    }

    @Test
    fun `account A save intent is removed across A to B to A transition`() {
        val saveEntered = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        var current by mutableStateOf<MiniHomeUiState>(editing("aba-save-a", "A 편집"))
        var session by mutableStateOf(session("account-a", generation = 1L))
        var backCalls = 0
        setExitContent(
            state = { current },
            session = { session },
            onBack = { backCalls += 1 },
            onSave = {
                saveEntered.complete(Unit)
                neverComplete.await()
            },
        )

        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장하고 나가기").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { saveEntered.isCompleted }
        session = session("account-b", generation = 2L)
        current = editing("aba-save-b", "B 편집")
        compose.onAllNodesWithTag(MiniHomeTestTags.UNSAVED_DIALOG).assertCountEquals(0)

        session = session("account-a", generation = 3L)
        current = saved("account-a", "aba-save-a")
        compose.waitForIdle()

        assertEquals(0, backCalls)
    }

    @Test
    fun `logout removes pending save exit intent before a later account outcome`() {
        val saveEntered = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        var current by mutableStateOf<MiniHomeUiState>(editing("logout-a", "A 편집"))
        var session by mutableStateOf(session("account-a", generation = 1L))
        var backCalls = 0
        setExitContent(
            state = { current },
            session = { session },
            onBack = { backCalls += 1 },
            onSave = {
                saveEntered.complete(Unit)
                neverComplete.await()
            },
        )

        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장하고 나가기").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { saveEntered.isCompleted }
        session = MiniHomeControllerSessionToken(1L, 2L, null)
        current = MiniHomeUiState.Forbidden
        compose.waitForIdle()

        session = session("account-a", generation = 3L)
        current = saved("account-a", "logout-a")
        compose.waitForIdle()

        assertEquals(0, backCalls)
    }

    @Test
    fun `process restored account A navigation intent is purged by B before A returns`() {
        val restoration = StateRestorationTester(compose)
        var current by mutableStateOf<MiniHomeUiState>(editing("restored-owner-a", "A 복원 편집"))
        var session by mutableStateOf(session("account-a", epoch = 4L, generation = 8L))
        var authOwnership by
            mutableStateOf<MiniHomeAuthOwnership>(
                MiniHomeAuthOwnership.Authenticated(AccountId("account-a"))
            )
        var backCalls = 0
        restoration.setContent {
            PlanteriorTheme {
                ExitTestScreen(
                    state = current,
                    session = session,
                    onBack = { backCalls += 1 },
                    onSave = {},
                    intentIdFactory = { "restored-owner-intent" },
                    authOwnership = authOwnership,
                )
            }
        }
        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithTag(MiniHomeTestTags.UNSAVED_DIALOG).assertIsDisplayed()

        current = MiniHomeUiState.Loading(null)
        session = MiniHomeControllerSessionToken(5L, 0L, null)
        authOwnership = MiniHomeAuthOwnership.Restoring
        restoration.emulateSavedInstanceStateRestore()
        authOwnership = MiniHomeAuthOwnership.Authenticated(AccountId("account-b"))
        compose.waitForIdle()
        current = editing("restored-owner-b", "B 편집")
        session = session("account-b", epoch = 5L, generation = 1L)
        compose.onAllNodesWithTag(MiniHomeTestTags.UNSAVED_DIALOG).assertCountEquals(0)

        current = saved("account-a", "restored-owner-a")
        session = session("account-a", epoch = 5L, generation = 2L)
        compose.waitForIdle()

        assertEquals(0, backCalls)
    }

    @Test
    fun `same owner process restoration rebinds exact full identity and navigates once`() {
        val restoration = StateRestorationTester(compose)
        val handle =
            MiniHomeDiscardHandle(
                AccountId("account-a"),
                "miniHomeLayouts",
                "restore-save",
                "restore-lineage",
                "restore-row",
                7L,
            )
        var current by
            mutableStateOf<MiniHomeUiState>(
                editing("restore-save", "복원 편집", "restore-lineage", handle)
            )
        var session by mutableStateOf(session("account-a", epoch = 4L, generation = 8L))
        var backCalls = 0
        restoration.setContent {
            PlanteriorTheme {
                ExitTestScreen(
                    state = current,
                    session = session,
                    onBack = { backCalls += 1 },
                    onSave = {},
                    intentIdFactory = { "stable-restored-intent" },
                )
            }
        }

        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장하고 나가기").performClick()
        restoration.emulateSavedInstanceStateRestore()
        session = session("account-a", epoch = 5L, generation = 1L)
        compose.onNodeWithTag(MiniHomeTestTags.UNSAVED_DIALOG).assertIsDisplayed()

        current = saved("account-a", "restore-save", "restore-lineage", handle)
        compose.runOnIdle { assertEquals(1, backCalls) }
    }

    @Test
    fun `consecutive save exit intents each navigate once`() {
        var current by mutableStateOf<MiniHomeUiState>(editing("first-save", "첫 편집"))
        val session = session("account-a", generation = 1L)
        var backCalls = 0
        var nextIntent = 0
        setExitContent(
            state = { current },
            session = { session },
            onBack = { backCalls += 1 },
            intentIdFactory = { "intent-${++nextIntent}" },
        )

        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장하고 나가기").performClick()
        current = saved("account-a", "first-save")
        compose.waitUntil(timeoutMillis = 5_000) { backCalls == 1 }

        current = editing("second-save", "둘째 편집")
        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장하고 나가기").performClick()
        current = saved("account-a", "second-save")
        compose.waitUntil(timeoutMillis = 5_000) { backCalls == 2 }

        assertEquals(2, nextIntent)
        assertEquals(2, backCalls)
    }

    @Test
    fun `save exit double tap starts one operation and matching outcome navigates once`() {
        val saveEntered = CompletableDeferred<Unit>()
        val saveRelease = CompletableDeferred<Unit>()
        var saveCalls = 0
        var current by mutableStateOf<MiniHomeUiState>(editing("double-save", "중복 저장"))
        val session = session("account-a")
        var backCalls = 0
        setExitContent(
            state = { current },
            session = { session },
            onBack = { backCalls += 1 },
            onSave = {
                saveCalls += 1
                saveEntered.complete(Unit)
                saveRelease.await()
            },
        )

        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장하고 나가기").performClick().assertIsNotEnabled()
        compose.onNodeWithText("저장하고 나가기").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { saveEntered.isCompleted }
        current = saved("account-a", "double-save")
        saveRelease.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) { backCalls == 1 }

        assertEquals(1, saveCalls)
        assertEquals(1, backCalls)
    }

    @Test
    fun `save exit ignores a same session outcome for a replacement operation`() {
        var current by mutableStateOf<MiniHomeUiState>(editing("old-save", "이전 편집"))
        val session = session("account-a")
        var backCalls = 0
        setExitContent(
            state = { current },
            session = { session },
            onBack = { backCalls += 1 },
        )

        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장하고 나가기").performClick()
        current = saved("account-a", "replacement-save")
        compose.waitForIdle()

        assertEquals(0, backCalls)
    }

    @Test
    fun `save exit intent from account A cannot navigate on account B saved outcome`() {
        val saveEntered = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        var current by
            mutableStateOf<MiniHomeUiState>(
                MiniHomeUiState.Editing(
                    layout(),
                    layout().copy(name = "A 편집"),
                    emptyList(),
                    emptyList(),
                    null,
                    OperationId("account-a-operation"),
                    MiniHomeSaveState.Idle,
                )
            )
        var session by
            mutableStateOf(
                MiniHomeControllerSessionToken(
                    1L,
                    1L,
                    com.planterior.helper.core.model.AccountId("account-a"),
                )
            )
        var backCalls = 0
        compose.setContent {
            PlanteriorTheme {
                MiniHomeScreen(
                    state = current,
                    session = session,
                    onBack = { backCalls += 1 },
                    onRetryLoad = {},
                    onBeginEditing = {},
                    onRename = {},
                    onAddPlant = {},
                    onAddDecoration = {},
                    onSelect = {},
                    onMove = {},
                    onMoveBy = { _, _ -> },
                    onRemove = {},
                    onSave = {
                        saveEntered.complete(Unit)
                        neverComplete.await()
                    },
                    onDiscard = { MiniHomeDiscardResult.Consumed },
                    onAdoptConflict = {},
                    onOpenCollection = {},
                )
            }
        }

        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장하고 나가기").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { saveEntered.isCompleted }

        session =
            MiniHomeControllerSessionToken(
                1L,
                2L,
                com.planterior.helper.core.model.AccountId("account-b"),
            )
        current =
            MiniHomeUiState.Viewing(
                layout().copy(name = "B 저장 완료"),
                emptyList(),
                emptyList(),
                stale = false,
                saved = true,
                exitOutcome =
                    MiniHomeExitOutcome(
                        MiniHomeExitOutcomeKind.SAVED,
                        com.planterior.helper.core.model.AccountId("account-b"),
                        OperationId("account-b-operation"),
                        OperationId("account-b-operation"),
                        null,
                    ),
            )
        compose.waitForIdle()

        assertEquals(0, backCalls)
    }

    private fun setExitContent(
        state: () -> MiniHomeUiState,
        session: () -> MiniHomeControllerSessionToken,
        onBack: () -> Unit,
        onSave: suspend () -> Unit = {},
        onDiscard: suspend () -> MiniHomeDiscardResult = {
            MiniHomeDiscardResult.Consumed
        },
        intentIdFactory: () -> String = { "test-navigation-intent" },
    ) {
        compose.setContent {
            PlanteriorTheme {
                ExitTestScreen(
                    state(),
                    session(),
                    onBack,
                    onSave,
                    onDiscard,
                    intentIdFactory,
                )
            }
        }
    }

    @Composable
    private fun ExitTestScreen(
        state: MiniHomeUiState,
        session: MiniHomeControllerSessionToken,
        onBack: () -> Unit,
        onSave: suspend () -> Unit,
        onDiscard: suspend () -> MiniHomeDiscardResult = {
            MiniHomeDiscardResult.Consumed
        },
        intentIdFactory: () -> String,
        authOwnership: MiniHomeAuthOwnership = MiniHomeAuthOwnership.Unmanaged,
    ) {
        MiniHomeScreen(
            state = state,
            session = session,
            onBack = onBack,
            onRetryLoad = {},
            onBeginEditing = {},
            onRename = {},
            onAddPlant = {},
            onAddDecoration = {},
            onSelect = {},
            onMove = {},
            onMoveBy = { _, _ -> },
            onRemove = {},
            onSave = onSave,
            onDiscard = onDiscard,
            onAdoptConflict = {},
            onOpenCollection = {},
            navigationIntentIdFactory = intentIdFactory,
            authOwnership = authOwnership,
        )
    }

    private fun session(
        owner: String,
        epoch: Long = 1L,
        generation: Long = 1L,
    ) = MiniHomeControllerSessionToken(epoch, generation, AccountId(owner))

    private fun editing(
        operationId: String,
        name: String,
        lineageId: String = operationId,
        discardHandle: MiniHomeDiscardHandle? = null,
    ): MiniHomeUiState.Editing =
        MiniHomeUiState.Editing(
            committed = layout(),
            draft = layout().copy(name = name),
            plants = emptyList(),
            decorations = emptyList(),
            selectedPlacementId = null,
            operationId = OperationId(operationId),
            saveState = MiniHomeSaveState.Idle,
            lineageId = OperationId(lineageId),
            discardHandle = discardHandle,
        )

    private fun saved(
        owner: String,
        operationId: String,
        lineageId: String = operationId,
        discardHandle: MiniHomeDiscardHandle? = null,
    ): MiniHomeUiState.Viewing =
        outcome(
            MiniHomeExitOutcomeKind.SAVED,
            owner,
            operationId,
            lineageId,
            discardHandle,
        )

    private fun discarded(
        owner: String,
        operationId: String,
        lineageId: String = operationId,
        discardHandle: MiniHomeDiscardHandle? = null,
    ): MiniHomeUiState.Viewing =
        outcome(
            MiniHomeExitOutcomeKind.DISCARDED,
            owner,
            operationId,
            lineageId,
            discardHandle,
        )

    private fun outcome(
        kind: MiniHomeExitOutcomeKind,
        owner: String,
        operationId: String,
        lineageId: String,
        discardHandle: MiniHomeDiscardHandle?,
    ) =
        MiniHomeUiState.Viewing(
            committed = layout().copy(name = "완료"),
            plants = emptyList(),
            decorations = emptyList(),
            stale = false,
            saved = kind == MiniHomeExitOutcomeKind.SAVED,
            exitOutcome =
                MiniHomeExitOutcome(
                    kind,
                    AccountId(owner),
                    OperationId(operationId),
                    OperationId(lineageId),
                    discardHandle,
                ),
        )

    private fun layout() =
        MiniHomeLayout(
            MiniHomeId("home-a"),
            "나의 방",
            emptyList(),
            Revision(1),
            Instant.EPOCH,
        )
}
