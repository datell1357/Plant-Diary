package com.planterior.helper.feature.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.planterior.helper.core.designsystem.component.PlanteriorCard
import com.planterior.helper.core.designsystem.component.PlanteriorScreenScaffold
import com.planterior.helper.core.designsystem.theme.PlanteriorBorderWidth
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.ClientProductEvent
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.ProductEventRecorder
import kotlinx.coroutines.launch

object InventoryTestTags {
    const val SCREEN = "inventory:screen"
    const val WAREHOUSE = "inventory:warehouse"
    const val SHOP = "inventory:shop"
    const val FEEDBACK = "inventory:feedback"
    const val STALE = "inventory:stale"
    const val RETRY = "inventory:retry"
    const val SEARCH = "inventory:search"
    const val UNAVAILABLE_SECTION = "inventory:unavailable-section"
    const val GRID = "inventory:grid"
    const val DETAIL = "inventory:detail"
    const val DETAIL_BACK = "inventory:detail:back"
    const val DETAIL_ACTION = "inventory:detail:action"

    fun detailLink(id: ItemId) = "inventory:item-detail:${id.value}"

    fun item(id: ItemId) = "inventory:item:${id.value}"

    fun acquire(id: ItemId) = "inventory:acquire:${id.value}"

    fun apply(id: ItemId) = "inventory:apply:${id.value}"

    fun category(category: ItemCategory?) = "inventory:category:${category?.name ?: "all"}"

    fun media(identity: String) = "inventory:media:$identity"

    fun mediaLoading(identity: String) = "inventory:media-loading:$identity"

    fun mediaFallback(identity: String) = "inventory:media-fallback:$identity"
}

internal val InventorySectionContainerColor =
    SemanticsPropertyKey<Long>("InventorySectionContainerColor")
internal val InventorySectionLabelColor = SemanticsPropertyKey<Long>("InventorySectionLabelColor")
internal val InventorySectionBorderColor = SemanticsPropertyKey<Long>("InventorySectionBorderColor")
internal val InventoryCategoryContainerColor =
    SemanticsPropertyKey<Long>("InventoryCategoryContainerColor")
internal val InventoryCategoryLabelColor = SemanticsPropertyKey<Long>("InventoryCategoryLabelColor")
internal val InventoryCategoryBorderColor =
    SemanticsPropertyKey<Long>("InventoryCategoryBorderColor")

private class InventoryViewModel(val controller: InventoryController) : ViewModel() {
    override fun onCleared() {
        controller.clear()
    }
}

@Composable
fun InventoryRoute(
    repository: InventoryRepository,
    authOwnership: InventoryAuthOwnership,
    onOpenMiniHome: () -> Unit,
    onOpenItem: (ItemId) -> Unit,
    bottomBar: @Composable () -> Unit = {},
    onStateObserved: (InventoryUiState) -> Unit = {},
    mediaLoader: CatalogMediaLoader = PlaceholderCatalogMediaLoader,
) {
    val model =
        viewModel<InventoryViewModel>(
            factory =
                viewModelFactory {
                    initializer {
                        InventoryViewModel(
                            InventoryController(repository, createSavedStateHandle())
                        )
                    }
                }
        )
    val controller = model.controller
    val state by controller.state.collectAsState()
    val displayed = state.displayedFor(authOwnership)
    val scope = rememberCoroutineScope()
    SideEffect { onStateObserved(displayed) }
    LifecycleResumeEffect(controller, authOwnership) {
        val load = scope.launch { controller.start(authOwnership) }
        onPauseOrDispose { load.cancel() }
    }
    InventoryScreen(
        displayed,
        onSelectSection = controller::selectSection,
        onSelectCategory = controller::selectCategory,
        onSearch = controller::search,
        onAcquire = { scope.launch { controller.acquire(it) } },
        onRetry = { scope.launch { controller.retry() } },
        onOpenMiniHome = {
            controller.openingMiniHome()
            onOpenMiniHome()
        },
        onOpenItem = onOpenItem,
        onFeedbackConsumed = { token -> scope.launch { controller.feedbackConsumed(token) } },
        bottomBar = bottomBar,
        mediaLoader = mediaLoader,
    )
}

internal fun InventoryUiState.displayedFor(ownership: InventoryAuthOwnership): InventoryUiState =
    when (ownership) {
        InventoryAuthOwnership.Unmanaged -> this
        InventoryAuthOwnership.Restoring,
        InventoryAuthOwnership.Unknown -> InventoryUiState.Loading(null)
        InventoryAuthOwnership.SignedOut -> InventoryUiState.Forbidden
        is InventoryAuthOwnership.Authenticated ->
            if (owner == ownership.accountId) this
            else InventoryUiState.Loading(ownership.accountId)
    }

@Composable
fun InventoryScreen(
    state: InventoryUiState,
    onSelectSection: (InventorySection) -> Unit,
    onSelectCategory: (ItemCategory?) -> Unit,
    onSearch: (String) -> Unit = {},
    onAcquire: (ItemId) -> Unit,
    onRetry: () -> Unit,
    onOpenMiniHome: () -> Unit,
    onOpenItem: (ItemId) -> Unit = {},
    onFeedbackConsumed: (InventoryFeedbackPresentationToken) -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    mediaLoader: CatalogMediaLoader = PlaceholderCatalogMediaLoader,
) {
    PlanteriorScreenScaffold(
        title =
            if ((state as? InventoryUiState.Content)?.section == InventorySection.SHOP) {
                "아이템 상점"
            } else {
                "나의 창고"
            },
        bottomBar = bottomBar,
        modifier = Modifier.testTag(InventoryTestTags.SCREEN),
    ) {
        when (state) {
            is InventoryUiState.Loading -> StateMessage("창고를 불러오고 있어요.")
            InventoryUiState.Forbidden -> StateMessage("로그인한 계정의 창고만 볼 수 있어요.")
            is InventoryUiState.Error -> {
                StateMessage("창고를 불러오지 못했어요. 다른 식물 관리 기능은 계속 이용할 수 있어요.")
                OutlinedButton(
                    onClick = onRetry,
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag(InventoryTestTags.RETRY),
                ) {
                    Text("다시 시도")
                }
            }
            is InventoryUiState.Content ->
                InventoryContent(
                    state,
                    onSelectSection,
                    onSelectCategory,
                    onSearch,
                    onAcquire,
                    onOpenMiniHome,
                    onOpenItem,
                    onRetry,
                    onFeedbackConsumed,
                    mediaLoader,
                )
        }
    }
}

@Composable
private fun ColumnScope.InventoryContent(
    state: InventoryUiState.Content,
    onSelectSection: (InventorySection) -> Unit,
    onSelectCategory: (ItemCategory?) -> Unit,
    onSearch: (String) -> Unit,
    onAcquire: (ItemId) -> Unit,
    onOpenMiniHome: () -> Unit,
    onOpenItem: (ItemId) -> Unit,
    onRetry: () -> Unit,
    onFeedbackConsumed: (InventoryFeedbackPresentationToken) -> Unit,
    mediaLoader: CatalogMediaLoader,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
    ) {
        SectionButton(
            "창고",
            state.section == InventorySection.WAREHOUSE,
            InventoryTestTags.WAREHOUSE,
        ) {
            onSelectSection(InventorySection.WAREHOUSE)
        }
        SectionButton("상점", state.section == InventorySection.SHOP, InventoryTestTags.SHOP) {
            onSelectSection(InventorySection.SHOP)
        }
    }
    if (state.stale) {
        PlanteriorCard(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.testTag(InventoryTestTags.STALE),
        ) {
            Text("저장된 창고를 표시하고 있어요. 최신 정보를 다시 불러오세요.")
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("다시 시도")
            }
        }
    }
    if (state.snapshot.partial) {
        PlanteriorCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Text("일부 보유 아이템은 현재 상점에서 제공하지 않아요. 창고에서 확인하거나 배치에서 제거할 수 있어요.")
        }
    }
    state.feedback?.let {
        InventoryFeedbackMessage(
            it,
            state.feedbackCondition,
            state.feedbackPresentationToken,
            onFeedbackConsumed,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    OutlinedTextField(
        value = state.searchQuery,
        onValueChange = onSearch,
        label = { Text("아이템 검색") },
        singleLine = true,
        modifier =
            Modifier.fillMaxWidth().testTag(InventoryTestTags.SEARCH).semantics {
                contentDescription = "아이템 이름과 설명 검색"
            },
    )
    CategoryFilters(state.category, onSelectCategory)
    val entries =
        if (state.section == InventorySection.WAREHOUSE) {
            InventoryPolicy.warehouseEntries(state.snapshot, state.category, state.searchQuery)
        } else {
            InventoryPolicy.shopEntries(state.snapshot, state.category, state.searchQuery)
        }
    if (entries.isEmpty()) {
        StateMessage(
            if (state.section == InventorySection.WAREHOUSE) {
                "이 종류의 보유 아이템이 아직 없어요. 상점에서 무료 아이템을 확인해 보세요."
            } else {
                "현재 공개된 아이템이 없어요."
            }
        )
    } else {
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val columns =
                when (state.section) {
                    InventorySection.SHOP -> if (maxWidth >= 320.dp && fontScale < 1.5f) 2 else 1
                    InventorySection.WAREHOUSE ->
                        when {
                            maxWidth >= 330.dp && fontScale < 1.5f -> 3
                            maxWidth >= 220.dp -> 2
                            else -> 1
                        }
                }
            val available = entries.filterNot { it.unavailable }
            val unavailable = entries.filter { it.unavailable }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxWidth().testTag(InventoryTestTags.GRID),
                horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
            ) {
                gridItems(available, key = { it.id.value }) { entry ->
                    InventoryItemCard(
                        entry,
                        state.section,
                        state.acquiringItemId == entry.id,
                        onAcquire,
                        onOpenMiniHome,
                        onOpenItem,
                        mediaLoader,
                    )
                }
                if (state.section == InventorySection.WAREHOUSE && unavailable.isNotEmpty()) {
                    item(key = "unavailable-heading", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "현재 이용 불가",
                            style = MaterialTheme.typography.titleMedium,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .testTag(InventoryTestTags.UNAVAILABLE_SECTION)
                                    .semantics { heading() },
                        )
                    }
                    gridItems(unavailable, key = { it.id.value }) { entry ->
                        InventoryItemCard(
                            entry,
                            state.section,
                            state.acquiringItemId == entry.id,
                            onAcquire,
                            onOpenMiniHome,
                            onOpenItem,
                            mediaLoader,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.SectionButton(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val selectedContainerColor = MaterialTheme.colorScheme.primary
    val selectedContentColor = MaterialTheme.colorScheme.onPrimary
    val unselectedContainerColor = Color.Transparent
    val unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val unselectedBorderColor = MaterialTheme.colorScheme.outline
    val containerColor = if (selected) selectedContainerColor else unselectedContainerColor
    val contentColor = if (selected) selectedContentColor else unselectedContentColor
    val borderColor = if (selected) selectedContainerColor else unselectedBorderColor
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = unselectedContainerColor,
                labelColor = unselectedContentColor,
                iconColor = unselectedContentColor,
                selectedContainerColor = selectedContainerColor,
                selectedLabelColor = selectedContentColor,
                selectedLeadingIconColor = selectedContentColor,
                selectedTrailingIconColor = selectedContentColor,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = unselectedBorderColor,
                selectedBorderColor = selectedContainerColor,
                disabledBorderColor = unselectedBorderColor,
                disabledSelectedBorderColor = selectedContainerColor,
                borderWidth = PlanteriorBorderWidth,
                selectedBorderWidth = PlanteriorBorderWidth,
            ),
        modifier =
            Modifier.weight(1f).heightIn(min = 48.dp).testTag(tag).semantics {
                this.selected = selected
                role = Role.Tab
                stateDescription = if (selected) "선택됨" else "선택 안 됨"
                this[InventorySectionContainerColor] = containerColor.toArgb().toLong()
                this[InventorySectionLabelColor] = contentColor.toArgb().toLong()
                this[InventorySectionBorderColor] = borderColor.toArgb().toLong()
            },
    )
}

@Composable
private fun CategoryFilters(
    selected: ItemCategory?,
    onSelected: (ItemCategory?) -> Unit,
) {
    val filters =
        listOf(
            null to "전체",
            ItemCategory.BACKGROUND to "배경",
            ItemCategory.FURNITURE to "가구",
            ItemCategory.DECORATION to "장식",
        )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.small),
    ) {
        filters.forEach { (category, label) ->
            CategoryFilterChip(
                category = category,
                label = label,
                selected = selected == category,
                onSelected = onSelected,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun CategoryFilterChip(
    category: ItemCategory?,
    label: String,
    selected: Boolean,
    onSelected: (ItemCategory?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val selectedContainerColor = MaterialTheme.colorScheme.primary
    val selectedContentColor = MaterialTheme.colorScheme.onPrimary
    val unselectedContainerColor = Color.Transparent
    val unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val unselectedBorderColor = MaterialTheme.colorScheme.outline
    val containerColor = if (selected) selectedContainerColor else unselectedContainerColor
    val contentColor = if (selected) selectedContentColor else unselectedContentColor
    val borderColor = if (selected) selectedContainerColor else unselectedBorderColor
    FilterChip(
        selected = selected,
        onClick = { onSelected(category) },
        enabled = enabled,
        label = { Text(label, maxLines = 1) },
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = unselectedContainerColor,
                labelColor = unselectedContentColor,
                iconColor = unselectedContentColor,
                disabledContainerColor = containerColor,
                disabledLabelColor = contentColor,
                disabledLeadingIconColor = contentColor,
                disabledTrailingIconColor = contentColor,
                selectedContainerColor = selectedContainerColor,
                disabledSelectedContainerColor = selectedContainerColor,
                selectedLabelColor = selectedContentColor,
                selectedLeadingIconColor = selectedContentColor,
                selectedTrailingIconColor = selectedContentColor,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = enabled,
                selected = selected,
                borderColor = unselectedBorderColor,
                selectedBorderColor = selectedContainerColor,
                disabledBorderColor = unselectedBorderColor,
                disabledSelectedBorderColor = selectedContainerColor,
                borderWidth = PlanteriorBorderWidth,
                selectedBorderWidth = PlanteriorBorderWidth,
            ),
        modifier =
            modifier.heightIn(min = 48.dp).testTag(InventoryTestTags.category(category)).semantics {
                this.selected = selected
                role = Role.RadioButton
                stateDescription = if (selected) "선택됨" else "선택 안 됨"
                this[InventoryCategoryContainerColor] = containerColor.toArgb().toLong()
                this[InventoryCategoryLabelColor] = contentColor.toArgb().toLong()
                this[InventoryCategoryBorderColor] = borderColor.toArgb().toLong()
            },
    )
}

@Composable
private fun InventoryItemCard(
    entry: InventoryEntry,
    section: InventorySection,
    acquiring: Boolean,
    onAcquire: (ItemId) -> Unit,
    onOpenMiniHome: () -> Unit,
    onOpenItem: (ItemId) -> Unit,
    mediaLoader: CatalogMediaLoader,
) {
    PlanteriorCard(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(InventoryTestTags.item(entry.id))
                .semantics {
                    contentDescription =
                        "${entry.category?.let(::categoryLabel) ?: "종류 미확인"} 아이템 ${entry.name}, ${entryState(entry)}"
                }
    ) {
        CatalogMedia(
            identity = entry.mediaIdentity,
            name = entry.id.value,
            size = if (section == InventorySection.SHOP) 96.dp else 64.dp,
            loader = mediaLoader,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(entry.name, style = MaterialTheme.typography.titleMedium)
        Text(entry.description, style = MaterialTheme.typography.bodySmall)
        Text(
            if (section == InventorySection.SHOP) {
                entry.item?.let(InventoryPolicy::conditionLabel) ?: "현재 상점에서 제공하지 않음"
            } else {
                entryState(entry)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { onOpenItem(entry.id) },
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(InventoryTestTags.detailLink(entry.id)),
        ) {
            Text("상세 보기")
        }
        if (section == InventorySection.SHOP) {
            Button(
                onClick = { onAcquire(entry.id) },
                enabled = entry.eligibility == AcquisitionEligibility.ELIGIBLE && !acquiring,
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(InventoryTestTags.acquire(entry.id)),
            ) {
                Text(
                    when {
                        acquiring -> "획득 처리 중"
                        entry.eligibility == AcquisitionEligibility.ALREADY_OWNED -> "보유 중"
                        entry.eligibility == AcquisitionEligibility.CONDITION_NOT_MET -> "조건 미충족"
                        else -> "무료로 획득"
                    }
                )
            }
        } else {
            OutlinedButton(
                onClick = onOpenMiniHome,
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(InventoryTestTags.apply(entry.id)),
            ) {
                Text(if (entry.applied) "배치 편집에서 해제" else "미니홈피에서 적용")
            }
        }
    }
}

@Composable
fun InventoryItemDetailRoute(
    repository: InventoryRepository,
    authOwnership: InventoryAuthOwnership,
    itemId: ItemId,
    onBack: () -> Unit,
    onOpenMiniHome: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    mediaLoader: CatalogMediaLoader = PlaceholderCatalogMediaLoader,
    productEventRecorder: ProductEventRecorder = ProductEventRecorder {},
) {
    val model =
        viewModel<InventoryViewModel>(
            factory =
                viewModelFactory {
                    initializer {
                        InventoryViewModel(
                            InventoryController(repository, createSavedStateHandle())
                        )
                    }
                }
        )
    val controller = model.controller
    val state by controller.state.collectAsState()
    val displayed = state.displayedFor(authOwnership)
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(controller, authOwnership) {
        val load = scope.launch { controller.start(authOwnership) }
        onPauseOrDispose { load.cancel() }
    }
    InventoryItemDetailScreen(
        state = displayed,
        itemId = itemId,
        onBack = onBack,
        onAcquire = { scope.launch { controller.acquire(it) } },
        onOpenMiniHome = {
            controller.openingMiniHome()
            onOpenMiniHome()
        },
        onRetry = { scope.launch { controller.retry() } },
        onFeedbackConsumed = { token -> scope.launch { controller.feedbackConsumed(token) } },
        bottomBar = bottomBar,
        mediaLoader = mediaLoader,
        onAcquisitionSourceViewed = {
            try {
                productEventRecorder.record(ClientProductEvent.MINI_HOME_ACQUISITION_SOURCE_VIEWED)
            } catch (_: Exception) {
                // Telemetry cannot affect the visible catalog detail.
            }
        },
    )
}

@Composable
fun InventoryItemDetailScreen(
    state: InventoryUiState,
    itemId: ItemId,
    onBack: () -> Unit,
    onAcquire: (ItemId) -> Unit,
    onOpenMiniHome: () -> Unit,
    onRetry: () -> Unit = {},
    onFeedbackConsumed: (InventoryFeedbackPresentationToken) -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    mediaLoader: CatalogMediaLoader = PlaceholderCatalogMediaLoader,
    onAcquisitionSourceViewed: () -> Unit = {},
) {
    val content = state as? InventoryUiState.Content
    val detailEntry = content?.snapshot?.detailEntry(itemId)
    val lockedAcquisitionSource =
        detailEntry != null &&
            detailEntry.ownership == null &&
            detailEntry.item?.acquisitionCondition != null &&
            detailEntry.eligibility == AcquisitionEligibility.CONDITION_NOT_MET
    var acquisitionSourceRecorded by
        rememberSaveable(content?.owner?.value, itemId.value) { mutableStateOf(false) }
    LaunchedEffect(content?.owner, itemId, lockedAcquisitionSource) {
        if (lockedAcquisitionSource && !acquisitionSourceRecorded) {
            acquisitionSourceRecorded = true
            onAcquisitionSourceViewed()
        }
    }
    PlanteriorScreenScaffold(
        title = "아이템 상세",
        bottomBar = bottomBar,
        modifier = Modifier.testTag(InventoryTestTags.DETAIL),
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.heightIn(min = 48.dp).testTag(InventoryTestTags.DETAIL_BACK),
        ) {
            Text("뒤로")
        }
        when (state) {
            is InventoryUiState.Loading -> StateMessage("아이템을 불러오고 있어요.")
            InventoryUiState.Forbidden -> StateMessage("로그인한 계정의 아이템만 볼 수 있어요.")
            is InventoryUiState.Error -> {
                StateMessage("아이템을 불러오지 못했어요.")
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("다시 시도")
                }
            }
            is InventoryUiState.Content -> {
                val entry = detailEntry
                if (entry == null) {
                    StateMessage("아이템을 찾을 수 없어요.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        item(key = "media") {
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                CatalogMedia(
                                    identity = entry.mediaIdentity,
                                    name = entry.name,
                                    size = minOf(maxWidth, 240.dp),
                                    loader = mediaLoader,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        }
                        item(key = "information") {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement =
                                    Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
                            ) {
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text(
                                    entry.category?.let(::categoryLabel) ?: "종류 미확인",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(entry.description, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    detailStatus(entry),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (entry.unavailable && !entry.applied) {
                                    Text("현재 이용할 수 없어 새로 적용할 수 없어요.")
                                }
                            }
                        }
                        state.feedback?.let { feedback ->
                            item(key = "feedback") {
                                InventoryFeedbackMessage(
                                    feedback,
                                    state.feedbackCondition,
                                    state.feedbackPresentationToken,
                                    onFeedbackConsumed,
                                )
                            }
                        }
                        detailActionLabel(entry, state.acquiringItemId == entry.id)?.let { label ->
                            item(key = "action") {
                                Button(
                                    onClick = {
                                        if (entry.ownership == null) onAcquire(entry.id)
                                        else onOpenMiniHome()
                                    },
                                    enabled =
                                        entry.ownership != null ||
                                            (entry.eligibility == AcquisitionEligibility.ELIGIBLE &&
                                                state.acquiringItemId != entry.id),
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag(InventoryTestTags.DETAIL_ACTION),
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun InventorySnapshot.detailEntry(itemId: ItemId): InventoryEntry? =
    InventoryPolicy.warehouseEntries(this, null).firstOrNull { it.id == itemId }
        ?: InventoryPolicy.shopEntries(this, null).firstOrNull { it.id == itemId }

private fun detailStatus(entry: InventoryEntry): String =
    when {
        entry.unavailable && entry.applied -> "보유 중 · 적용 중 · 현재 이용 불가"
        entry.unavailable -> "보유 중 · 현재 이용 불가"
        entry.applied -> "보유 중 · 적용 중"
        entry.ownership != null -> "보유 중 · 미적용"
        entry.eligibility == AcquisitionEligibility.CONDITION_NOT_MET -> "식물 1개 등록 필요"
        else -> "획득 가능 · ${entry.item?.let(InventoryPolicy::conditionLabel) ?: "무료 획득"}"
    }

private fun detailActionLabel(entry: InventoryEntry, acquiring: Boolean): String? =
    when {
        entry.unavailable && !entry.applied -> null
        entry.ownership != null && entry.applied -> "미니홈피에서 해제"
        entry.ownership != null -> "미니홈피에서 적용"
        acquiring -> "획득 처리 중"
        entry.eligibility == AcquisitionEligibility.CONDITION_NOT_MET -> "조건 미충족"
        else -> "무료로 획득"
    }

@Composable
private fun StateMessage(message: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(PlanteriorTheme.spacing.huge)) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InventoryFeedbackMessage(
    feedback: InventoryFeedback,
    condition: AcquisitionCondition?,
    presentationToken: InventoryFeedbackPresentationToken?,
    onConsumed: (InventoryFeedbackPresentationToken) -> Unit,
    color: Color = Color.Unspecified,
) {
    Text(
        feedbackText(feedback, condition),
        color = color,
        modifier =
            Modifier.fillMaxWidth().testTag(InventoryTestTags.FEEDBACK).semantics {
                liveRegion = LiveRegionMode.Polite
            },
    )
    LaunchedEffect(presentationToken) { presentationToken?.let(onConsumed) }
}

fun feedbackText(feedback: InventoryFeedback, condition: AcquisitionCondition?): String =
    when (feedback) {
        InventoryFeedback.ACQUIRED -> "아이템을 획득했어요. 창고에 바로 추가했어요."
        InventoryFeedback.CONDITION_NOT_MET ->
            when (condition) {
                AcquisitionCondition.REGISTERED_PLANT -> "아직 획득 조건을 충족하지 못했어요. 식물을 1개 등록해 주세요."
                null -> "아직 획득 조건을 충족하지 못했어요. 조건을 확인해 주세요."
            }
        InventoryFeedback.ALREADY_OWNED -> "이미 보유한 아이템이에요."
        InventoryFeedback.NETWORK_FAILURE -> "네트워크 연결을 확인하지 못했어요. 같은 획득 요청을 안전하게 다시 확인해요."
        InventoryFeedback.CATALOG_CHANGED -> "아이템 정보가 변경됐어요. 최신 정보를 확인해 주세요."
        InventoryFeedback.ITEM_UNAVAILABLE -> "이 아이템은 현재 상점에서 획득할 수 없어요."
        InventoryFeedback.FAILURE -> "아이템을 획득하지 못했어요. 다시 시도해 주세요."
        InventoryFeedback.OPEN_MINI_HOME_TO_APPLY -> "미니홈피 편집에서 위치를 선택해 적용하거나 해제해 주세요."
    }

private fun entryState(entry: InventoryEntry): String =
    when {
        entry.applied -> "보유 중, 적용 중"
        entry.unavailable -> "보유 중, 현재 상점에서 제공하지 않음"
        entry.eligibility == AcquisitionEligibility.ALREADY_OWNED -> "보유 중, 미적용"
        entry.eligibility == AcquisitionEligibility.CONDITION_NOT_MET -> "미보유, 조건 미충족"
        else -> "미보유, 획득 가능"
    }

private fun categoryLabel(category: ItemCategory): String =
    when (category) {
        ItemCategory.BACKGROUND -> "배경"
        ItemCategory.FURNITURE -> "가구"
        ItemCategory.DECORATION -> "장식"
    }

private fun categoryGlyph(category: ItemCategory): String =
    when (category) {
        ItemCategory.BACKGROUND -> "▧"
        ItemCategory.FURNITURE -> "▱"
        ItemCategory.DECORATION -> "✦"
    }
