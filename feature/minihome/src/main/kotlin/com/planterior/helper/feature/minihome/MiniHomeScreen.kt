package com.planterior.helper.feature.minihome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.component.PlanteriorCard
import com.planterior.helper.core.designsystem.component.PlanteriorScreenScaffold
import com.planterior.helper.core.designsystem.component.PlanteriorStatusCard
import com.planterior.helper.core.designsystem.theme.PlanteriorRadius
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

object MiniHomeTestTags {
    const val SCREEN = "mini-home:screen"
    const val LOADING = "mini-home:loading"
    const val CANVAS = "mini-home:canvas"
    const val EDIT = "mini-home:edit"
    const val SHARE = "mini-home:share"
    const val SAVE = "mini-home:save"
    const val SAVE_FAILURE = "mini-home:save-failure"
    const val CONFLICT = "mini-home:conflict"
    const val RETRY = "mini-home:retry"
    const val RECONCILE = "mini-home:reconcile"
    const val DISCARD = "mini-home:discard"
    const val DISCARD_FEEDBACK = "mini-home:discard-feedback"
    const val UNSAVED_DIALOG = "mini-home:unsaved-dialog"
    const val ERROR = "mini-home:error"
    const val ERROR_RETRY = "mini-home:error-retry"
    const val PICKER = "mini-home:picker"
    const val REMOVE = "mini-home:remove"
    const val MOVE_LEFT = "mini-home:move-left"
    const val MOVE_RIGHT = "mini-home:move-right"
    const val MOVE_UP = "mini-home:move-up"
    const val MOVE_DOWN = "mini-home:move-down"
    const val BACKGROUND = "mini-home:background"

    fun placement(id: PlacementId) = "mini-home:placement:${id.value}"

    fun plant(id: PersonalPlantId) = "mini-home:plant:${id.value}"

    fun decoration(id: ItemId) = "mini-home:decoration:${id.value}"
}

@Composable
fun MiniHomeScreen(
    state: MiniHomeUiState,
    session: MiniHomeControllerSessionToken,
    onBack: () -> Unit,
    onRetryLoad: suspend () -> Unit,
    onBeginEditing: () -> Unit,
    onRename: (String) -> Unit,
    onAddPlant: (PersonalPlantId) -> Unit,
    onAddDecoration: (ItemId) -> Unit,
    onSelect: (PlacementId) -> Unit,
    onMove: (GridPosition) -> Unit,
    onMoveBy: (Int, Int) -> Unit,
    onRemove: () -> Unit,
    onSave: suspend () -> Unit,
    onDiscard: suspend () -> MiniHomeDiscardResult,
    onAdoptConflict: suspend () -> Unit,
    onOpenCollection: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenShare: (() -> Unit)? = null,
    onReconcileSaveFailure: suspend () -> Unit = {},
    photoLoader: MiniHomePhotoLoader = PlaceholderMiniHomePhotoLoader,
    navigationIntentIdFactory: () -> String = { UUID.randomUUID().toString() },
    authOwnership: MiniHomeAuthOwnership = MiniHomeAuthOwnership.Unmanaged,
) {
    val scope = rememberCoroutineScope()
    var navigationIntent by
        rememberSaveable(stateSaver = NavigationIntentTokenSaver) {
            mutableStateOf<NavigationIntentToken?>(null)
        }
    var activeIntentId by remember { mutableStateOf<String?>(null) }
    val navigatedIntentIds = remember { mutableSetOf<String>() }
    val currentSession by rememberUpdatedState(session)
    val currentNavigationIntent by rememberUpdatedState(navigationIntent)
    val editing = state as? MiniHomeUiState.Editing
    val dialogVisible = navigationIntent != null && editing != null

    fun requestBack() {
        val requiresDiscard =
            editing?.let {
                it.hasUnsavedChanges ||
                    it.discardHandle != null ||
                    it.saveState !is MiniHomeSaveState.Idle
            } == true
        if (!requiresDiscard) {
            onBack()
            return
        }
        if (navigationIntent != null) return
        val activeOwner = session.owner ?: return
        navigationIntent =
            editing.navigationIntent(
                activeOwner,
                session,
                NavigationIntentAction.CONFIRM_EXIT,
                navigationIntentIdFactory(),
            )
    }

    fun navigateOnce(intent: NavigationIntentToken) {
        if (!navigatedIntentIds.add(intent.intentId)) return
        if (navigationIntent?.intentId == intent.intentId) navigationIntent = null
        if (activeIntentId == intent.intentId) activeIntentId = null
        onBack()
    }

    fun discardAndExit(intent: NavigationIntentToken? = navigationIntent) {
        val pending = intent ?: return
        if (activeIntentId == pending.intentId) return
        val operationToken = pending.copy(action = NavigationIntentAction.DISCARD_AND_EXIT)
        navigationIntent = operationToken
        activeIntentId = operationToken.intentId
        scope.launch {
            val result =
                try {
                    onDiscard()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    MiniHomeDiscardResult.Rejected
                }
            val stillCurrent =
                currentNavigationIntent == operationToken && operationToken.matches(currentSession)
            if (activeIntentId == operationToken.intentId) activeIntentId = null
            if (!stillCurrent) return@launch
            if (
                result is MiniHomeDiscardResult.Consumed || result is MiniHomeDiscardResult.Missing
            ) {
                navigateOnce(operationToken)
            } else {
                navigationIntent = null
            }
        }
    }

    BackHandler { requestBack() }
    LaunchedEffect(state, session, navigationIntent, authOwnership) {
        val pending = navigationIntent ?: return@LaunchedEffect
        when (authOwnership) {
            MiniHomeAuthOwnership.Restoring,
            MiniHomeAuthOwnership.Unknown -> return@LaunchedEffect
            MiniHomeAuthOwnership.SignedOut -> {
                navigationIntent = null
                activeIntentId = null
                return@LaunchedEffect
            }
            MiniHomeAuthOwnership.Unmanaged -> Unit
            is MiniHomeAuthOwnership.Authenticated ->
                if (pending.owner != authOwnership.accountId) {
                    navigationIntent = null
                    activeIntentId = null
                    return@LaunchedEffect
                }
        }
        if (state is MiniHomeUiState.Loading) return@LaunchedEffect
        val activeOwner = session.owner
        if (activeOwner == null) {
            navigationIntent = null
            activeIntentId = null
            return@LaunchedEffect
        }
        if (!pending.matches(session)) {
            if (pending.owner == activeOwner && pending.matchesIdentity(state)) {
                navigationIntent = pending.rebind(session)
            } else {
                navigationIntent = null
                activeIntentId = null
            }
            return@LaunchedEffect
        }
        if (!pending.matchesIdentity(state)) {
            navigationIntent = null
            activeIntentId = null
            return@LaunchedEffect
        }
        val outcome = (state as? MiniHomeUiState.Viewing)?.exitOutcome
        if (pending.matches(outcome)) {
            navigateOnce(pending)
        }
    }

    PlanteriorScreenScaffold(
        title = "미니 식물원",
        modifier = modifier.testTag(MiniHomeTestTags.SCREEN),
    ) {
        TextButton(
            onClick = ::requestBack,
            modifier = Modifier.sizeIn(minHeight = PlanteriorTheme.spacing.huge * 2),
        ) {
            Text("홈으로 돌아가기")
        }
        when (state) {
            is MiniHomeUiState.Loading ->
                CircularProgressIndicator(
                    modifier =
                        Modifier.align(Alignment.CenterHorizontally)
                            .testTag(MiniHomeTestTags.LOADING)
                )
            is MiniHomeUiState.Viewing ->
                MiniHomeBody(
                    layout = state.committed,
                    plants = state.plants,
                    decorations = state.decorations,
                    editing = null,
                    onRename = onRename,
                    onSelect = onSelect,
                    onMove = onMove,
                    onMoveBy = onMoveBy,
                    onRemove = onRemove,
                    onAddPlant = onAddPlant,
                    onAddDecoration = onAddDecoration,
                    onOpenCollection = onOpenCollection,
                    photoLoader = photoLoader,
                    footer = {
                        if (state.stale) StatusCard("저장된 구성을 보여드려요", "연결되면 최신 구성을 다시 확인해요.")
                        if (state.saved) StatusCard("저장했어요", "이 구성이 다른 화면과 다음 접속에 표시돼요.")
                        Button(
                            onClick = onBeginEditing,
                            modifier = Modifier.action(MiniHomeTestTags.EDIT),
                        ) {
                            Text("배치 편집")
                        }
                        // 공유 진입은 저장본만 다루므로 편집 중에는 노출하지 않는다.
                        if (onOpenShare != null) {
                            OutlinedButton(
                                onClick = onOpenShare,
                                modifier = Modifier.action(MiniHomeTestTags.SHARE),
                            ) {
                                Text("미니홈 공유")
                            }
                        }
                    },
                )
            is MiniHomeUiState.Editing ->
                MiniHomeBody(
                    layout = state.draft,
                    plants = state.plants,
                    decorations = state.decorations,
                    editing = state,
                    onRename = onRename,
                    onSelect = onSelect,
                    onMove = onMove,
                    onMoveBy = onMoveBy,
                    onRemove = onRemove,
                    onAddPlant = onAddPlant,
                    onAddDecoration = onAddDecoration,
                    onOpenCollection = onOpenCollection,
                    photoLoader = photoLoader,
                    footer = {
                        state.discardFeedback?.let { feedback ->
                            val (title, message) =
                                when (feedback) {
                                    MiniHomeDiscardFeedback.STALE_HANDLE ->
                                        "편집 항목이 바뀌었어요" to "최신 편집을 다시 불러왔어요. 내용을 확인한 뒤 다시 시도해 주세요."
                                    MiniHomeDiscardFeedback.RETRY_REQUIRED ->
                                        "편집을 종료하지 못했어요" to
                                            "최신 편집을 유지했어요. 계정과 연결 상태를 확인하고 다시 시도해 주세요."
                                }
                            StatusCard(
                                title,
                                message,
                                error = true,
                                tag = MiniHomeTestTags.DISCARD_FEEDBACK,
                            )
                        }
                        state.issue?.let { issue ->
                            StatusCard("이 위치에 배치할 수 없어요", issueMessage(issue), error = true)
                        }
                        when (val saveState = state.saveState) {
                            MiniHomeSaveState.Idle -> Unit
                            MiniHomeSaveState.Saving -> StatusCard("저장 중이에요", "배치를 안전하게 확인하고 있어요.")
                            is MiniHomeSaveState.Failed -> {
                                StatusCard(
                                    "저장하지 못했어요",
                                    saveFailureMessage(saveState.failure),
                                    error = true,
                                    tag = MiniHomeTestTags.SAVE_FAILURE,
                                )
                                Button(
                                    onClick = { scope.launch { onSave() } },
                                    modifier = Modifier.action(MiniHomeTestTags.RETRY),
                                ) {
                                    Text("같은 배치 다시 저장")
                                }
                            }
                            is MiniHomeSaveState.ValidationFailed ->
                                StatusCard(
                                    "저장 요청을 수정해야 해요",
                                    saveFailureMessage(saveState.failure),
                                    error = true,
                                    tag = MiniHomeTestTags.SAVE_FAILURE,
                                )
                            is MiniHomeSaveState.ReconciliationRequired -> {
                                StatusCard(
                                    "최신 구성을 확인해야 해요",
                                    saveFailureMessage(saveState.failure),
                                    error = true,
                                    tag = MiniHomeTestTags.SAVE_FAILURE,
                                )
                                Button(
                                    onClick = {
                                        scope.launch { onReconcileSaveFailure() }
                                    },
                                    modifier = Modifier.action(MiniHomeTestTags.RECONCILE),
                                ) {
                                    Text("최신 구성과 배치 대상 확인")
                                }
                            }
                            is MiniHomeSaveState.Corrected -> {
                                StatusCard(
                                    "배치 대상을 확인했어요",
                                    correctedMessage(saveState),
                                )
                                StatusCard("최신 확정 구성", state.committed.name)
                            }
                            MiniHomeSaveState.Conflict -> {
                                StatusCard(
                                    "다른 곳에서 구성이 변경됐어요",
                                    "편집본은 보존했어요. 서버의 최신 구성을 불러온 뒤 다시 편집해 주세요.",
                                    error = true,
                                    tag = MiniHomeTestTags.CONFLICT,
                                )
                                Button(
                                    onClick = { scope.launch { onAdoptConflict() } },
                                    modifier = Modifier.action(MiniHomeTestTags.RETRY),
                                ) {
                                    Text("최신 구성 불러오기")
                                }
                            }
                        }
                        if (
                            state.saveState is MiniHomeSaveState.Idle ||
                                state.saveState is MiniHomeSaveState.Corrected
                        ) {
                            Button(
                                onClick = { scope.launch { onSave() } },
                                enabled = state.hasUnsavedChanges,
                                modifier = Modifier.action(MiniHomeTestTags.SAVE),
                            ) {
                                Text(
                                    if (state.saveState is MiniHomeSaveState.Corrected) {
                                        "수정한 배치 저장"
                                    } else {
                                        "배치 저장"
                                    }
                                )
                            }
                            TextButton(
                                onClick = {
                                    if (state.hasUnsavedChanges) {
                                        requestBack()
                                    } else {
                                        val activeOwner = session.owner
                                        val intent = activeOwner?.let {
                                            state.navigationIntent(
                                                it,
                                                session,
                                                NavigationIntentAction.DISCARD_AND_EXIT,
                                                navigationIntentIdFactory(),
                                            )
                                        }
                                        navigationIntent = intent
                                        discardAndExit(intent)
                                    }
                                },
                                modifier = Modifier.action(MiniHomeTestTags.DISCARD),
                            ) {
                                Text("편집 종료")
                            }
                        }
                    },
                )
            MiniHomeUiState.Forbidden ->
                ErrorBody("이 미니 식물원에 접근할 수 없어요", "로그인한 계정의 홈에서 다시 열어 주세요.", null)
            is MiniHomeUiState.Unavailable,
            MiniHomeUiState.Error ->
                ErrorBody(
                    "미니 식물원을 불러오지 못했어요",
                    "연결 상태를 확인하고 다시 시도해 주세요.",
                    { scope.launch { onRetryLoad() } },
                )
        }
    }

    if (dialogVisible) {
        val intentInProgress = activeIntentId == navigationIntent?.intentId
        AlertDialog(
            modifier = Modifier.testTag(MiniHomeTestTags.UNSAVED_DIALOG),
            onDismissRequest = {
                if (!intentInProgress) navigationIntent = null
            },
            title = { Text("편집한 배치를 저장할까요?") },
            text = { Text("저장하지 않고 나가면 이번 변경 사항은 사라져요.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pending = navigationIntent ?: return@TextButton
                        if (activeIntentId == pending.intentId) return@TextButton
                        val operationToken =
                            pending.copy(action = NavigationIntentAction.SAVE_AND_EXIT)
                        navigationIntent = operationToken
                        activeIntentId = operationToken.intentId
                        scope.launch {
                            try {
                                onSave()
                            } finally {
                                if (activeIntentId == operationToken.intentId) {
                                    activeIntentId = null
                                }
                            }
                        }
                    },
                    enabled = !intentInProgress,
                ) {
                    Text("저장하고 나가기")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { discardAndExit() },
                        enabled = !intentInProgress,
                    ) {
                        Text("저장 안 함")
                    }
                    TextButton(
                        onClick = { navigationIntent = null },
                        enabled = !intentInProgress,
                    ) {
                        Text("계속 편집")
                    }
                }
            },
        )
    }
}

private val NavigationIntentTokenSaver =
    listSaver<NavigationIntentToken?, Any>(
        save = { token ->
            if (token == null) {
                emptyList()
            } else {
                val handle = token.discardHandle
                listOf(
                    token.owner.value,
                    token.controllerEpoch,
                    token.controllerGeneration,
                    token.action.name,
                    token.operationId.value,
                    token.lineageId.value,
                    handle != null,
                    handle?.accountId?.value.orEmpty(),
                    handle?.aggregateType.orEmpty(),
                    handle?.rowOperationId.orEmpty(),
                    handle?.rowLineageId.orEmpty(),
                    handle?.rowHandleId.orEmpty(),
                    handle?.rowVersion ?: 0L,
                    token.intentId,
                )
            }
        },
        restore = { saved ->
            if (saved.isEmpty()) {
                null
            } else {
                runCatching {
                    val handle =
                        if (saved[6] as Boolean) {
                            MiniHomeDiscardHandle(
                                AccountId(saved[7] as String),
                                saved[8] as String,
                                saved[9] as String,
                                (saved[10] as String).ifEmpty { null },
                                saved[11] as String,
                                saved[12] as Long,
                            )
                        } else {
                            null
                        }
                    NavigationIntentToken(
                        AccountId(saved[0] as String),
                        saved[1] as Long,
                        saved[2] as Long,
                        NavigationIntentAction.valueOf(saved[3] as String),
                        com.planterior.helper.core.model.OperationId(saved[4] as String),
                        com.planterior.helper.core.model.OperationId(saved[5] as String),
                        handle,
                        saved[13] as String,
                    )
                }
                    .getOrNull()
            }
        },
    )

private fun MiniHomeUiState.Editing.navigationIntent(
    owner: AccountId,
    session: MiniHomeControllerSessionToken,
    action: NavigationIntentAction,
    intentId: String,
) =
    NavigationIntentToken(
        owner,
        session.controllerEpoch,
        session.generation,
        action,
        operationId,
        lineageId,
        discardHandle,
        intentId,
    )

private fun NavigationIntentToken.matches(session: MiniHomeControllerSessionToken): Boolean =
    owner == session.owner &&
        controllerEpoch == session.controllerEpoch &&
        controllerGeneration == session.generation

private fun NavigationIntentToken.rebind(
    session: MiniHomeControllerSessionToken
): NavigationIntentToken =
    copy(
        controllerEpoch = session.controllerEpoch,
        controllerGeneration = session.generation,
    )

private fun NavigationIntentToken.matchesIdentity(state: MiniHomeUiState): Boolean =
    when (state) {
        is MiniHomeUiState.Editing ->
            operationId == state.operationId &&
                lineageId == state.lineageId &&
                discardHandle == state.discardHandle
        is MiniHomeUiState.Viewing -> matches(state.exitOutcome)
        is MiniHomeUiState.Loading,
        is MiniHomeUiState.Unavailable,
        MiniHomeUiState.Forbidden,
        MiniHomeUiState.Error -> false
    }

private fun NavigationIntentToken.matches(outcome: MiniHomeExitOutcome?): Boolean {
    outcome ?: return false
    val expectedKind =
        when (action) {
            NavigationIntentAction.SAVE_AND_EXIT -> MiniHomeExitOutcomeKind.SAVED
            NavigationIntentAction.DISCARD_AND_EXIT -> MiniHomeExitOutcomeKind.DISCARDED
            NavigationIntentAction.CONFIRM_EXIT -> return false
        }
    return outcome.kind == expectedKind &&
        owner == outcome.owner &&
        operationId == outcome.operationId &&
        lineageId == outcome.lineageId &&
        discardHandle == outcome.discardHandle
}

@Composable
private fun ColumnScope.MiniHomeBody(
    layout: MiniHomeLayout,
    plants: List<MiniHomePlantChoice>,
    decorations: List<MiniHomeDecorationChoice>,
    editing: MiniHomeUiState.Editing?,
    onRename: (String) -> Unit,
    onSelect: (PlacementId) -> Unit,
    onMove: (GridPosition) -> Unit,
    onMoveBy: (Int, Int) -> Unit,
    onRemove: () -> Unit,
    onAddPlant: (PersonalPlantId) -> Unit,
    onAddDecoration: (ItemId) -> Unit,
    onOpenCollection: () -> Unit,
    photoLoader: MiniHomePhotoLoader,
    footer: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
    ) {
        if (editing == null) {
            Text(layout.name, style = MaterialTheme.typography.titleLarge)
        } else {
            OutlinedTextField(
                value = layout.name,
                onValueChange = onRename,
                enabled = !editing.frozen,
                label = { Text("미니 식물원 이름") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IsometricRoom(
            layout = layout,
            selected = editing?.selectedPlacementId,
            enabled = editing != null && !editing.frozen,
            onSelect = onSelect,
            onMove = onMove,
            plants = plants,
            decorations = decorations,
            photoLoader = photoLoader,
        )
        if (editing != null) {
            val selectedIsBackground =
                editing.selectedPlacementId?.let { selectedId ->
                    val itemId =
                        (editing.draft.placements.firstOrNull { it.id == selectedId }?.target
                                as? MiniHomePlacementTarget.Decoration)
                            ?.itemId
                    decorations.firstOrNull { it.id == itemId }?.category == ItemCategory.BACKGROUND
                } == true
            SelectedPlacementControls(
                editing,
                movable = !selectedIsBackground,
                onMoveBy = onMoveBy,
                onRemove = onRemove,
            )
            Picker(
                editing,
                plants,
                decorations,
                onAddPlant,
                onAddDecoration,
                onOpenCollection,
                photoLoader,
            )
        } else if (layout.placements.isEmpty()) {
            PlanteriorCard {
                Text("아직 배치한 식물이 없어요", style = MaterialTheme.typography.titleMedium)
                Text(
                    "편집을 열어 도감의 식물을 추가해 보세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
                )
            }
        }
        footer()
    }
}

@Composable
private fun IsometricRoom(
    layout: MiniHomeLayout,
    selected: PlacementId?,
    enabled: Boolean,
    onSelect: (PlacementId) -> Unit,
    onMove: (GridPosition) -> Unit,
    plants: List<MiniHomePlantChoice>,
    decorations: List<MiniHomeDecorationChoice>,
    photoLoader: MiniHomePhotoLoader,
) {
    val backgroundPlacement = MiniHomeRoomRenderer.backgroundPlacement(layout, decorations)
    val backgroundChoice = backgroundPlacement?.let { placement ->
        val itemId = (placement.target as MiniHomePlacementTarget.Decoration).itemId
        decorations.firstOrNull { it.id == itemId }
    }
    val selectedBorder = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier =
            Modifier.fillMaxWidth()
                .aspectRatio(MiniHomeRoomRenderer.ASPECT_RATIO)
                .clip(RoundedCornerShape(PlanteriorRadius.Card))
                .testTag(MiniHomeTestTags.CANVAS)
                .semantics {
                    contentDescription =
                        backgroundChoice?.let { "미니 식물원 배치 공간, 배경 ${it.displayName}" }
                            ?: "미니 식물원 배치 공간, 기본 배경"
                },
        contentAlignment = Alignment.TopStart,
    ) {
        val roomWidth = maxWidth
        val roomHeight = maxHeight
        val projection =
            with(density) {
                MiniHomeIsometricProjection(roomWidth.toPx(), roomHeight.toPx())
            }
        val miniatureWidth = with(density) { projection.miniatureWidth.toDp() }
        val miniatureHeight = with(density) { projection.miniatureHeight.toDp() }
        val touchWidth = maxOf(miniatureWidth, PlanteriorTheme.spacing.huge * 2)
        val touchHeight = maxOf(miniatureHeight, PlanteriorTheme.spacing.huge * 2)
        MiniHomeBackground(backgroundChoice, photoLoader, Modifier.fillMaxSize())
        MiniHomeFloor(projection, backgroundChoice != null)
        MiniHomeRoomRenderer.stagePlacements(layout, decorations).forEach { placement ->
            val plant =
                (placement.target as? MiniHomePlacementTarget.Plant)?.let { target ->
                    plants.firstOrNull { it.id == target.plantId }
                }
            val decoration =
                (placement.target as? MiniHomePlacementTarget.Decoration)?.let { target ->
                    decorations.firstOrNull { it.id == target.itemId }
                }
            val label =
                when (val target = placement.target) {
                    is MiniHomePlacementTarget.Plant ->
                        "식물 ${plant?.displayName ?: target.plantId.value}"
                    is MiniHomePlacementTarget.Decoration -> {
                        val kind =
                            when (decoration?.category) {
                                ItemCategory.BACKGROUND -> "배경"
                                ItemCategory.FURNITURE -> "가구"
                                ItemCategory.DECORATION -> "장식"
                                null -> "종류 미확인 아이템"
                            }
                        "$kind ${decoration?.displayName ?: target.itemId.value}"
                    }
                }
            val selectedNow = placement.id == selected
            val anchor = projection.cellCenter(placement.position)
            val touchWidthPx = with(density) { touchWidth.toPx() }
            val touchHeightPx = with(density) { touchHeight.toPx() }
            Box(
                modifier =
                    Modifier.offset(
                            x = with(density) { (anchor.x - touchWidthPx / 2f).toDp() },
                            y = with(density) { (anchor.y - touchHeightPx).toDp() },
                        )
                        .size(touchWidth, touchHeight)
                        .then(
                            if (selectedNow) {
                                Modifier.border(
                                    PlanteriorTheme.spacing.extraSmall,
                                    selectedBorder,
                                    RoundedCornerShape(PlanteriorRadius.Card),
                                )
                            } else {
                                Modifier
                            }
                        )
                        .clickable(
                            enabled = enabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) {
                            onSelect(placement.id)
                        }
                        .testTag(MiniHomeTestTags.placement(placement.id))
                        .semantics {
                            this.selected = selectedNow
                            contentDescription =
                                "$label, ${placement.position.column + 1}열 ${placement.position.row + 1}행"
                        }
                        .then(
                            if (!enabled) {
                                Modifier
                            } else {
                                Modifier.pointerInput(
                                    placement.id,
                                    projection.width,
                                    projection.height,
                                ) {
                                    var dragged = Offset.Zero
                                    detectDragGestures(
                                        onDragStart = {
                                            dragged = Offset.Zero
                                            onSelect(placement.id)
                                        },
                                        onDragEnd = {
                                            onMove(
                                                projection.positionAt(
                                                    MiniHomePoint(
                                                        anchor.x + dragged.x,
                                                        anchor.y + dragged.y,
                                                    )
                                                )
                                            )
                                        },
                                    ) { change, amount ->
                                        change.consume()
                                        dragged += amount
                                    }
                                }
                            }
                        ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                MiniHomePlacementMiniature(
                    placement = placement,
                    plants = plants,
                    decorations = decorations,
                    photoLoader = photoLoader,
                    width = miniatureWidth,
                    height = miniatureHeight,
                )
            }
        }
        if (backgroundPlacement != null && backgroundChoice != null) {
            OutlinedButton(
                onClick = { onSelect(backgroundPlacement.id) },
                enabled = enabled,
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .padding(PlanteriorTheme.spacing.medium)
                        .sizeIn(minHeight = 48.dp)
                        .testTag(MiniHomeTestTags.BACKGROUND)
                        .semantics {
                            this.selected = backgroundPlacement.id == selected
                            contentDescription = "배경 ${backgroundChoice.displayName} 선택"
                        },
            ) {
                Text("배경 선택")
            }
        }
    }
}

@Composable
private fun SelectedPlacementControls(
    state: MiniHomeUiState.Editing,
    movable: Boolean,
    onMoveBy: (Int, Int) -> Unit,
    onRemove: () -> Unit,
) {
    if (state.selectedPlacementId == null) return
    PlanteriorCard {
        Text(
            if (movable) "선택한 미니어처 이동" else "선택한 배경",
            style = MaterialTheme.typography.titleMedium,
        )
        if (movable) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = PlanteriorTheme.spacing.medium),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MoveButton("왼쪽", MiniHomeTestTags.MOVE_LEFT) { onMoveBy(-1, 0) }
                MoveButton("위", MiniHomeTestTags.MOVE_UP) { onMoveBy(0, -1) }
                MoveButton("아래", MiniHomeTestTags.MOVE_DOWN) { onMoveBy(0, 1) }
                MoveButton("오른쪽", MiniHomeTestTags.MOVE_RIGHT) { onMoveBy(1, 0) }
            }
        }
        OutlinedButton(
            onClick = onRemove,
            enabled = !state.frozen,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(top = PlanteriorTheme.spacing.medium)
                    .sizeIn(minHeight = 48.dp)
                    .testTag(MiniHomeTestTags.REMOVE),
        ) {
            Text(if (movable) "배치에서 제거" else "배경 제거")
        }
    }
}

@Composable
private fun MoveButton(label: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag(tag),
    ) {
        Text(label)
    }
}

@Composable
private fun Picker(
    editing: MiniHomeUiState.Editing,
    plants: List<MiniHomePlantChoice>,
    decorations: List<MiniHomeDecorationChoice>,
    onAddPlant: (PersonalPlantId) -> Unit,
    onAddDecoration: (ItemId) -> Unit,
    onOpenCollection: () -> Unit,
    photoLoader: MiniHomePhotoLoader,
) {
    PlanteriorCard(modifier = Modifier.testTag(MiniHomeTestTags.PICKER)) {
        Text("배치할 대상", style = MaterialTheme.typography.titleMedium)
        if (plants.isEmpty()) {
            Text(
                "도감에 식물을 등록하면 미니어처를 추가할 수 있어요.",
                modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
            )
            TextButton(
                onClick = onOpenCollection,
                modifier = Modifier.action(MiniHomeTestTags.PICKER),
            ) {
                Text("도감에서 식물 등록")
            }
        } else {
            plants.forEach { plant ->
                val placed =
                    editing.draft.placements.any {
                        (it.target as? MiniHomePlacementTarget.Plant)?.plantId == plant.id
                    }
                OutlinedButton(
                    onClick = { onAddPlant(plant.id) },
                    enabled = !editing.frozen && !placed,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(top = PlanteriorTheme.spacing.medium)
                            .sizeIn(minHeight = 48.dp)
                            .testTag(MiniHomeTestTags.plant(plant.id)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
                    ) {
                        PickerIdentity(plant = plant, decoration = null, photoLoader = photoLoader)
                        Text(
                            if (placed) "${plant.displayName} 배치됨" else "${plant.displayName} 추가",
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        }
        decorations.forEach { decoration ->
            val placed =
                editing.draft.placements.any {
                    (it.target as? MiniHomePlacementTarget.Decoration)?.itemId == decoration.id
                }
            OutlinedButton(
                onClick = { onAddDecoration(decoration.id) },
                enabled = !editing.frozen && !placed && decoration.availableForApplication,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(top = PlanteriorTheme.spacing.medium)
                        .sizeIn(minHeight = 48.dp)
                        .testTag(MiniHomeTestTags.decoration(decoration.id)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
                ) {
                    PickerIdentity(
                        plant = null,
                        decoration = decoration,
                        photoLoader = photoLoader,
                    )
                    Text(
                        when {
                            placed -> "${decoration.displayName} 배치됨"
                            !decoration.availableForApplication ->
                                "${decoration.displayName} 현재 추가할 수 없음"
                            decoration.category == ItemCategory.BACKGROUND ->
                                "${decoration.displayName} 배경 적용"
                            else -> "${decoration.displayName} 추가"
                        },
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    body: String,
    error: Boolean = false,
    tag: String? = null,
) {
    PlanteriorStatusCard(title = title, body = body, error = error, tag = tag)
}

@Composable
private fun ErrorBody(title: String, body: String, retry: (() -> Unit)?) {
    PlanteriorCard(modifier = Modifier.fillMaxWidth().testTag(MiniHomeTestTags.ERROR)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium))
    }
    if (retry != null) {
        Button(onClick = retry, modifier = Modifier.action(MiniHomeTestTags.ERROR_RETRY)) {
            Text("다시 시도")
        }
    }
}

private fun issueMessage(issue: MiniHomePlacementIssue): String =
    when (issue) {
        MiniHomePlacementIssue.OCCUPIED -> "다른 미니어처가 있는 칸이에요. 빈 칸을 선택해 주세요."
        MiniHomePlacementIssue.ALREADY_PLACED -> "같은 식물이나 장식은 한 번만 배치할 수 있어요."
        MiniHomePlacementIssue.ROOM_FULL -> "배치 공간이 가득 찼어요. 기존 미니어처를 제거해 주세요."
        MiniHomePlacementIssue.CATEGORY_LIMIT -> "배경은 1개, 가구와 장식은 각각 10개까지 적용할 수 있어요."
        MiniHomePlacementIssue.ITEM_UNAVAILABLE ->
            "현재 제공하지 않는 아이템은 새로 적용할 수 없어요. 기존 배치에서는 제거할 수 있어요."
        MiniHomePlacementIssue.INVALID_NAME -> "이름은 앞뒤 공백 없이 1~100자로 입력해 주세요."
        MiniHomePlacementIssue.INVALID_REQUEST -> "배치 정보를 수정한 뒤 다시 저장해 주세요."
    }

private fun saveFailureMessage(failure: MiniHomeSaveFailure): String =
    when (failure) {
        MiniHomeSaveFailure.NETWORK -> "서버 확정 구성은 바뀌지 않았어요. 연결 후 같은 배치로 다시 시도해 주세요."
        MiniHomeSaveFailure.DATABASE -> "기기에 저장 결과를 반영하지 못했어요. 같은 배치로 다시 확인해 주세요."
        MiniHomeSaveFailure.INCONSISTENT_RECEIPT -> "서버 저장 결과를 확인하지 못했어요. 같은 배치를 다시 확인해 주세요."
        MiniHomeSaveFailure.OUTBOX_MISMATCH ->
            "같은 요청 번호의 저장 내용이 달라 반복하지 않았어요. 최신 구성과 이전 저장 결과를 먼저 확인해 주세요."
        MiniHomeSaveFailure.PAYLOAD_MISMATCH ->
            "서버에 확정된 요청 내용이 현재 편집본과 달라요. 최신 구성과 이전 저장 결과를 먼저 확인해 주세요."
        MiniHomeSaveFailure.UNAVAILABLE_ENTITY ->
            "삭제되었거나 더 이상 보유하지 않은 대상이 있어 같은 요청을 반복하지 않았어요. 최신 배치 대상을 확인해 주세요."
        MiniHomeSaveFailure.REVISION_CONFLICT -> "다른 기기에서 구성이 변경됐어요. 최신 구성과 내 편집을 함께 확인해 주세요."
        MiniHomeSaveFailure.INVALID_REQUEST -> "편집본은 보존했어요. 잘못된 값을 수정하면 새 요청으로 저장할 수 있어요."
        MiniHomeSaveFailure.PERMISSION_DENIED -> "이 계정으로 저장할 권한을 확인하지 못했어요. 계정 상태를 다시 확인해 주세요."
        MiniHomeSaveFailure.MALFORMED_RESPONSE -> "서버 응답 계약이 올바르지 않아 같은 요청을 반복하지 않았어요."
    }

private fun correctedMessage(state: MiniHomeSaveState.Corrected): String =
    when {
        state.removedTargets == 0 -> "최신 구성과 이전 저장 결과를 확인했어요. 보존된 편집본을 검토한 뒤 직접 저장해 주세요."
        state.removedTargets == 1 -> "사용할 수 없는 대상 1개를 제외하고 나머지 편집은 보존했어요. 검토한 뒤 직접 저장해 주세요."
        else -> "사용할 수 없는 대상 ${state.removedTargets}개를 제외하고 나머지 편집은 보존했어요. 검토한 뒤 직접 저장해 주세요."
    }

@Composable
private fun Modifier.action(tag: String): Modifier =
    fillMaxWidth().sizeIn(minHeight = 48.dp).testTag(tag)
