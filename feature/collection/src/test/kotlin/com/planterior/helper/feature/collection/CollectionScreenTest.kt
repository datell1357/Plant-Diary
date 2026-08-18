package com.planterior.helper.feature.collection

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.RegistrationMethod
import java.time.Instant
import java.time.LocalDate
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
class CollectionScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `collection loading content empty and error each render a complete semantic state`() {
        val state = mutableStateOf<CollectionUiState>(CollectionUiState.Loading)
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                CollectionScreen(
                    state = state.value,
                    listPosition = CollectionListPosition.ZERO,
                    onListPositionChanged = { _, _ -> },
                    onOpenPlant = {},
                    onIdentify = {},
                    onRegisterDirectly = {},
                    onRetry = {},
                )
            }
        }

        val loading = composeRule.onNodeWithTag(CollectionTestTags.LOADING, useUnmergedTree = true)
        loading.assertIsDisplayed()
        loading.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        )
        loading.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        )

        state.value = CollectionUiState.Content(listOf(item("plant-a")), stale = false)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("${CollectionTestTags.ITEM}:plant-a").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithTag(CollectionTestTags.LOADING).fetchSemanticsNodes().size,
        )

        state.value = CollectionUiState.Empty
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CollectionTestTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithTag(CollectionTestTags.IDENTIFY).assertIsDisplayed()
        composeRule.onNodeWithTag(CollectionTestTags.REGISTER_DIRECT).assertIsDisplayed()

        state.value = CollectionUiState.Error
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CollectionTestTags.ERROR).assertIsDisplayed()
        composeRule.onNodeWithTag(CollectionTestTags.RETRY).assertIsDisplayed()
    }

    @Test
    fun `empty actions are independent reachable and meet minimum touch target`() {
        var identify = 0
        var register = 0
        showCollection(
            CollectionUiState.Empty,
            onIdentify = { identify++ },
            onRegisterDirectly = { register++ },
        )

        composeRule
            .onNodeWithTag(CollectionTestTags.IDENTIFY)
            .performClick()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        composeRule
            .onNodeWithTag(CollectionTestTags.REGISTER_DIRECT)
            .performClick()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)

        assertEquals(1, identify)
        assertEquals(1, register)
    }

    @Test
    fun `collection starts at the restored list position and reports the visible anchor`() {
        val positions = mutableListOf<Pair<Int, Int>>()
        val plants = (0 until 60).map { item("plant-$it") }
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                CollectionScreen(
                    state = CollectionUiState.Content(plants, stale = false),
                    listPosition = CollectionListPosition(30, 0),
                    onListPositionChanged = { index, offset -> positions += index to offset },
                    onOpenPlant = {},
                    onIdentify = {},
                    onRegisterDirectly = {},
                    onRetry = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("${CollectionTestTags.ITEM}:plant-30").assertIsDisplayed()
        assertEquals(30, positions.last().first)
    }

    @Test
    fun `collection row opens exactly its opaque id and stale content remains visible`() {
        val opened = mutableListOf<PersonalPlantId>()
        showCollection(
            CollectionUiState.Content(listOf(item("plant-a")), stale = true),
            onOpenPlant = { opened += it },
        )

        composeRule.onNodeWithTag(CollectionTestTags.STALE).assertIsDisplayed()
        composeRule.onNodeWithTag("${CollectionTestTags.ITEM}:plant-a").performClick()

        assertEquals(listOf(PersonalPlantId("plant-a")), opened)
    }

    @Test
    fun `fresh detail renders water light temperature humidity and public symptom anatomy`() {
        showDetail(PlantDetailUiState.Content(detail(), EditorState.from(detail().plant)))

        listOf(
                PlantDetailTestTags.WATER,
                PlantDetailTestTags.LIGHT,
                PlantDetailTestTags.TEMPERATURE,
                PlantDetailTestTags.HUMIDITY,
                "${PlantDetailTestTags.SYMPTOM}:droop",
                "${PlantDetailTestTags.SYMPTOM_CAUSE}:droop",
                "${PlantDetailTestTags.SYMPTOM_ACTION}:droop",
            )
            .forEach { tag ->
                composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            }
        assertEquals(
            0,
            composeRule
                .onAllNodesWithTag("${PlantDetailTestTags.SYMPTOM}:private")
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    fun `partial stale and no standard content are explicit without fake care cards`() {
        val state =
            mutableStateOf<PlantDetailUiState>(
                PlantDetailUiState.Partial(
                    detail(),
                    setOf(CareField.HUMIDITY),
                    EditorState.from(detail().plant),
                )
            )
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                PlantDetailScreen(
                    state = state.value,
                    onBack = {},
                    onRetry = {},
                    onBeginEditing = {},
                    onLastWateredDate = {},
                    onLocation = {},
                    onPrivateNote = {},
                    onSave = {},
                    onCancelEdit = {},
                )
            }
        }
        composeRule.onNodeWithTag(PlantDetailTestTags.PARTIAL).assertIsDisplayed()
        composeRule
            .onNodeWithTag(PlantDetailTestTags.HUMIDITY_MISSING)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(PlantDetailTestTags.WATER).performScrollTo().assertIsDisplayed()

        state.value =
            PlantDetailUiState.Stale(
                detail().plant,
                guidance = null,
                EditorState.from(detail().plant),
                editingAllowed = false,
                accountZone = null,
            )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PlantDetailTestTags.STALE).assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithTag(PlantDetailTestTags.WATER).fetchSemanticsNodes().size,
        )

        state.value =
            PlantDetailUiState.NoStandardContent(
                detail().plant,
                EditorState.from(detail().plant),
                java.time.ZoneId.of("Asia/Seoul"),
            )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PlantDetailTestTags.NO_STANDARD).assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithTag(PlantDetailTestTags.WATER).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `forbidden not found and error offer safe navigation or retry without content leakage`() {
        val state = mutableStateOf<PlantDetailUiState>(PlantDetailUiState.Forbidden)
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                PlantDetailScreen(
                    state = state.value,
                    onBack = {},
                    onRetry = {},
                    onBeginEditing = {},
                    onLastWateredDate = {},
                    onLocation = {},
                    onPrivateNote = {},
                    onSave = {},
                    onCancelEdit = {},
                )
            }
        }
        composeRule.onNodeWithTag(PlantDetailTestTags.FORBIDDEN).assertIsDisplayed()
        assertEquals(
            0,
            composeRule
                .onAllNodesWithTag(PlantDetailTestTags.PRIVATE_NOTE)
                .fetchSemanticsNodes()
                .size,
        )

        state.value = PlantDetailUiState.NotFound
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PlantDetailTestTags.NOT_FOUND).assertIsDisplayed()

        state.value = PlantDetailUiState.Error
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PlantDetailTestTags.ERROR).assertIsDisplayed()
        composeRule.onNodeWithTag(PlantDetailTestTags.RETRY).assertIsDisplayed()
    }

    @Test
    fun `editor shows restored failed draft read only and retries the exact snapshot`() {
        var retries = 0
        val editor =
            EditorState(
                isEditing = true,
                operationId = OperationId("operation-edit-stable"),
                lastWateredDate = "2026-08-17",
                location = "침실",
                privateNote = "새 잎 확인",
                failure = EditFailure.REMOTE_WRITE_FAILED,
            )
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                PlantDetailScreen(
                    state = PlantDetailUiState.Content(detail(), editor),
                    onBack = {},
                    onRetry = {},
                    onBeginEditing = {},
                    onLastWateredDate = {},
                    onLocation = {},
                    onPrivateNote = {},
                    onSave = { retries++ },
                    onCancelEdit = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(PlantDetailTestTags.EDIT_FAILURE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(PlantDetailTestTags.LAST_WATERED_INPUT)
            .performScrollTo()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("2026-08-17"),
                )
            )
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(PlantDetailTestTags.EDIT_RETRY).performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `validation renders distinct actionable support nodes with error semantics`() {
        val rendered =
            mutableStateOf(
                PlantDetailUiState.Content(
                    detail(),
                    EditorState.from(detail().plant)
                        .copy(
                            isEditing = true,
                            errors = setOf(EditValidationError.INVALID_LAST_WATERED_DATE),
                        ),
                )
            )
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                PlantDetailScreen(
                    state = rendered.value,
                    onBack = {},
                    onRetry = {},
                    onBeginEditing = {},
                    onLastWateredDate = {},
                    onLocation = {},
                    onPrivateNote = {},
                    onSave = {},
                    onCancelEdit = {},
                    onReconcileEdit = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(PlantDetailTestTags.LAST_WATERED_INPUT)
            .performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        composeRule
            .onNodeWithTag(PlantDetailTestTags.DATE_INVALID_ERROR, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(
            0,
            composeRule
                .onAllNodesWithTag(
                    PlantDetailTestTags.DATE_FUTURE_ERROR,
                    useUnmergedTree = true,
                )
                .fetchSemanticsNodes()
                .size,
        )

        rendered.value =
            rendered.value.copy(
                editor =
                    rendered.value.editor.copy(
                        errors =
                            setOf(
                                EditValidationError.FUTURE_LAST_WATERED_DATE,
                                EditValidationError.LOCATION_TOO_LONG,
                                EditValidationError.NOTE_TOO_LONG,
                            )
                    )
            )
        composeRule.waitForIdle()

        listOf(
                PlantDetailTestTags.DATE_FUTURE_ERROR,
                PlantDetailTestTags.LOCATION_ERROR,
                PlantDetailTestTags.NOTE_ERROR,
            )
            .forEach { tag ->
                composeRule
                    .onNodeWithTag(tag, useUnmergedTree = true)
                    .performScrollTo()
                    .assertIsDisplayed()
            }
        listOf(
                PlantDetailTestTags.LAST_WATERED_INPUT,
                PlantDetailTestTags.LOCATION_INPUT,
                PlantDetailTestTags.NOTE_INPUT,
            )
            .forEach { tag ->
                composeRule
                    .onNodeWithTag(tag)
                    .performScrollTo()
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            }
    }

    @Test
    fun `failed edit is read only with exact retry while conflict offers reload only`() {
        var retries = 0
        var reconciliations = 0
        val rendered =
            mutableStateOf(
                PlantDetailUiState.Content(
                    detail(),
                    EditorState.from(detail().plant)
                        .copy(
                            isEditing = true,
                            operationId = OperationId("operation-edit-stable"),
                            failure = EditFailure.REMOTE_WRITE_FAILED,
                        ),
                )
            )
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                PlantDetailScreen(
                    state = rendered.value,
                    onBack = {},
                    onRetry = {},
                    onBeginEditing = {},
                    onLastWateredDate = {},
                    onLocation = {},
                    onPrivateNote = {},
                    onSave = { retries++ },
                    onCancelEdit = {},
                    onReconcileEdit = { reconciliations++ },
                )
            }
        }

        listOf(
                PlantDetailTestTags.LAST_WATERED_INPUT,
                PlantDetailTestTags.LOCATION_INPUT,
                PlantDetailTestTags.NOTE_INPUT,
            )
            .forEach { tag ->
                composeRule.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
            }
        composeRule.onNodeWithTag(PlantDetailTestTags.EDIT_RETRY).performScrollTo().performClick()
        assertEquals(1, retries)
        assertEquals(
            0,
            composeRule
                .onAllNodesWithTag(PlantDetailTestTags.EDIT_RELOAD)
                .fetchSemanticsNodes()
                .size,
        )

        rendered.value =
            rendered.value.copy(
                editor = rendered.value.editor.copy(failure = EditFailure.REVISION_CONFLICT)
            )
        composeRule.waitForIdle()
        assertEquals(
            0,
            composeRule
                .onAllNodesWithTag(PlantDetailTestTags.EDIT_RETRY)
                .fetchSemanticsNodes()
                .size,
        )
        composeRule.onNodeWithTag(PlantDetailTestTags.EDIT_RELOAD).performScrollTo().performClick()
        assertEquals(1, reconciliations)
    }

    @Test
    fun `missing symptoms and successful empty symptoms render distinct states`() {
        val emptyDetail = detail().copy(guidance = detail().guidance.copy(symptoms = emptyList()))
        val rendered =
            mutableStateOf<PlantDetailUiState>(
                PlantDetailUiState.Partial(
                    emptyDetail,
                    setOf(CareField.SYMPTOMS),
                    EditorState.from(emptyDetail.plant),
                )
            )
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                PlantDetailScreen(
                    state = rendered.value,
                    onBack = {},
                    onRetry = {},
                    onBeginEditing = {},
                    onLastWateredDate = {},
                    onLocation = {},
                    onPrivateNote = {},
                    onSave = {},
                    onCancelEdit = {},
                    onReconcileEdit = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(PlantDetailTestTags.SYMPTOMS_MISSING)
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(
            0,
            composeRule
                .onAllNodesWithTag(PlantDetailTestTags.SYMPTOMS_EMPTY)
                .fetchSemanticsNodes()
                .size,
        )

        rendered.value =
            PlantDetailUiState.Content(emptyDetail, EditorState.from(emptyDetail.plant))
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(PlantDetailTestTags.SYMPTOMS_EMPTY)
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(
            0,
            composeRule
                .onAllNodesWithTag(PlantDetailTestTags.SYMPTOMS_MISSING)
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    fun `stale collection shows last refresh and an actionable retry`() {
        var retries = 0
        val lastRefresh = Instant.parse("2026-08-17T00:00:00Z")
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                CollectionScreen(
                    state =
                        CollectionUiState.Content(
                            listOf(item("cached")),
                            stale = true,
                            lastSuccessfulAt = lastRefresh,
                        ),
                    listPosition = CollectionListPosition.ZERO,
                    onListPositionChanged = { _, _ -> },
                    onOpenPlant = {},
                    onIdentify = {},
                    onRegisterDirectly = {},
                    onRetry = { retries++ },
                )
            }
        }

        composeRule
            .onNodeWithTag(CollectionTestTags.LAST_REFRESH)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Text,
                    listOf(AnnotatedString(lastRefresh.toString())),
                )
            )
        composeRule.onNodeWithTag(CollectionTestTags.STALE_RETRY).performClick()
        assertEquals(1, retries)
    }

    private fun showCollection(
        state: CollectionUiState,
        onOpenPlant: (PersonalPlantId) -> Unit = {},
        onIdentify: () -> Unit = {},
        onRegisterDirectly: () -> Unit = {},
    ) {
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                CollectionScreen(
                    state = state,
                    listPosition = CollectionListPosition.ZERO,
                    onListPositionChanged = { _, _ -> },
                    onOpenPlant = onOpenPlant,
                    onIdentify = onIdentify,
                    onRegisterDirectly = onRegisterDirectly,
                    onRetry = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun showDetail(
        state: PlantDetailUiState,
        onLastWateredDate: (String) -> Unit = {},
        onLocation: (String) -> Unit = {},
        onPrivateNote: (String) -> Unit = {},
        onSave: () -> Unit = {},
    ) {
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                PlantDetailScreen(
                    state = state,
                    onBack = {},
                    onRetry = {},
                    onBeginEditing = {},
                    onLastWateredDate = onLastWateredDate,
                    onLocation = onLocation,
                    onPrivateNote = onPrivateNote,
                    onSave = onSave,
                    onCancelEdit = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun item(id: String) = CollectionPlant(PersonalPlantId(id), "몬스테라", null)

    private fun detail() =
        PlantDetail(
            plant =
                PersonalPlantDetail(
                    accountId = AccountId("account-a"),
                    id = PersonalPlantId("plant-a"),
                    displayName = "몬스테라",
                    contentId = PlantContentId("species-a"),
                    registrationMethod = RegistrationMethod.IDENTIFIED,
                    representativePhotoPath = null,
                    location = "거실",
                    privateNote = "잎을 닦음",
                    lastWateredDate = LocalDate.of(2026, 8, 12),
                    revision = 1,
                    updatedAt = Instant.parse("2026-08-18T00:00:00Z"),
                ),
            accountZone = java.time.ZoneId.of("Asia/Seoul"),
            guidance =
                PlantCareGuidance(
                    wateringIntervalDays = 7,
                    lightGuidance = "밝은 간접광",
                    minimumTemperatureCelsius = 18.0,
                    maximumTemperatureCelsius = 28.0,
                    minimumHumidityPercent = 40,
                    maximumHumidityPercent = 70,
                    symptoms =
                        listOf(
                            PublicSymptomGuidance(
                                id = "droop",
                                symptom = "잎 처짐",
                                possibleCause = "흙이 말랐을 수 있어요",
                                action = "흙을 확인하고 물을 주세요",
                            )
                        ),
                ),
        )
}
