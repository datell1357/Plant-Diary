package com.planterior.helper.feature.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.planterior.helper.core.designsystem.component.PlanteriorCard
import com.planterior.helper.core.designsystem.component.PlanteriorScreenScaffold
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.PersonalPlantId
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun CollectionScreen(
    state: CollectionUiState,
    listPosition: CollectionListPosition,
    onListPositionChanged: (Int, Int) -> Unit,
    onOpenPlant: (PersonalPlantId) -> Unit,
    onIdentify: () -> Unit,
    onRegisterDirectly: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    thumbnailLoader: PlantThumbnailLoader = PlaceholderPlantThumbnailLoader,
) {
    PlanteriorScreenScaffold(
        title = "나의 식물 도감",
        modifier = modifier.testTag(CollectionTestTags.SCREEN),
        bottomBar = bottomBar,
    ) {
        when (state) {
            CollectionUiState.Loading -> LoadingState(CollectionTestTags.LOADING)
            is CollectionUiState.Content -> {
                if (state.stale) {
                    StaleCollectionStatus(state.lastSuccessfulAt?.toString(), onRetry)
                }
                CollectionList(
                    items = state.items,
                    initialPosition = listPosition,
                    onPositionChanged = onListPositionChanged,
                    onOpenPlant = onOpenPlant,
                    thumbnailLoader = thumbnailLoader,
                )
            }
            CollectionUiState.Empty ->
                EmptyCollection(onIdentify = onIdentify, onRegisterDirectly = onRegisterDirectly)
            CollectionUiState.Error ->
                ErrorState(
                    tag = CollectionTestTags.ERROR,
                    retryTag = CollectionTestTags.RETRY,
                    title = "도감을 불러오지 못했어요",
                    body = "연결 상태를 확인하고 다시 시도해 주세요.",
                    onRetry = onRetry,
                )
        }
    }
}

@Composable
private fun StaleCollectionStatus(lastSuccessfulAt: String?, onRetry: () -> Unit) {
    PlanteriorCard(
        modifier = Modifier.testTag(CollectionTestTags.STALE),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text("저장된 도감을 보여드려요", style = MaterialTheme.typography.titleMedium)
        Text(
            "현재 목록은 최신 정보가 아닐 수 있어요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
        )
        if (lastSuccessfulAt != null) {
            Text(
                "마지막으로 새로고친 시각",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
            )
            Text(
                lastSuccessfulAt,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(CollectionTestTags.LAST_REFRESH),
            )
        }
        TextButton(
            onClick = onRetry,
            modifier =
                Modifier.fillMaxWidth()
                    .sizeIn(minHeight = PlanteriorTheme.spacing.huge * 2)
                    .testTag(CollectionTestTags.STALE_RETRY),
        ) {
            Text("최신 목록 다시 불러오기")
        }
    }
}

@Composable
private fun ColumnScope.CollectionList(
    items: List<CollectionPlant>,
    initialPosition: CollectionListPosition,
    onPositionChanged: (Int, Int) -> Unit,
    onOpenPlant: (PersonalPlantId) -> Unit,
    thumbnailLoader: PlantThumbnailLoader,
) {
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex =
                initialPosition.index.coerceAtMost(items.lastIndex.coerceAtLeast(0)),
            initialFirstVisibleItemScrollOffset = initialPosition.offset,
        )
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) -> onPositionChanged(index, offset) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().weight(1f).testTag(CollectionTestTags.CONTENT),
        verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
    ) {
        items(items, key = { it.id.value }) { plant ->
            PlanteriorCard(
                modifier = Modifier.testTag("${CollectionTestTags.ITEM}:${plant.id.value}"),
                onClick = { onOpenPlant(plant.id) },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlantThumbnail(
                        path = plant.representativePhotoPath,
                        name = plant.displayName,
                        loader = thumbnailLoader,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.small),
                    ) {
                        Text(
                            text = plant.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "관리 정보 보기",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.EmptyCollection(
    onIdentify: () -> Unit,
    onRegisterDirectly: () -> Unit,
) {
    CollectionStateBody(tag = CollectionTestTags.EMPTY) {
        PlanteriorCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                text = "아직 등록한 식물이 없어요",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            Text(
                text = "사진으로 알아보거나 이름을 직접 입력해 첫 식물을 등록해 보세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = PlanteriorTheme.spacing.medium),
            )
        }
        Button(
            onClick = onIdentify,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(top = PlanteriorTheme.spacing.huge)
                    .sizeIn(minHeight = PlanteriorTheme.spacing.huge * 2)
                    .testTag(CollectionTestTags.IDENTIFY),
        ) {
            Text("사진으로 식별")
        }
        TextButton(
            onClick = onRegisterDirectly,
            modifier =
                Modifier.fillMaxWidth()
                    .sizeIn(minHeight = PlanteriorTheme.spacing.huge * 2)
                    .testTag(CollectionTestTags.REGISTER_DIRECT),
        ) {
            Text("직접 등록")
        }
    }
}
