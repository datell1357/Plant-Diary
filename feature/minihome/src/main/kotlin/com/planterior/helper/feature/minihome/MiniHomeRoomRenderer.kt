package com.planterior.helper.feature.minihome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.planterior.helper.core.designsystem.theme.PlanteriorBorderWidth
import com.planterior.helper.core.designsystem.theme.PlanteriorRadius
import com.planterior.helper.core.model.ItemCategory

/**
 * 미니 식물원 방의 정규 렌더링 규격이다.
 *
 * 편집 화면과 공유 이미지 내보내기가 같은 투영과 같은 그리기 순서를 쓰도록 한 곳에 모았다. 두 번째 좌표계를 만들지 않아야 미리보기와 내보낸 이미지의 배치가 어긋나지
 * 않는다.
 */
object MiniHomeRoomRenderer {
    /** 방의 정규 종횡비이다. 미리보기와 내보내기 모두 이 비율을 지킨다. */
    const val ASPECT_RATIO = 1.2f

    /** 배경으로 적용된 배치를 찾는다. 배경은 바닥 타일 대신 표면 전체를 채운다. */
    fun backgroundPlacement(
        layout: MiniHomeLayout,
        decorations: List<MiniHomeDecorationChoice>,
    ): MiniHomePlacement? =
        layout.placements.firstOrNull { placement ->
            val itemId =
                (placement.target as? MiniHomePlacementTarget.Decoration)?.itemId
                    ?: return@firstOrNull false
            decorations.firstOrNull { it.id == itemId }?.category == ItemCategory.BACKGROUND
        }

    /** 바닥 위에 그릴 배치를 정규 깊이 순서로 돌려준다. 배경 배치는 제외한다. */
    fun stagePlacements(
        layout: MiniHomeLayout,
        decorations: List<MiniHomeDecorationChoice>,
    ): List<MiniHomePlacement> {
        val background = backgroundPlacement(layout, decorations)
        return layout.placements.filterNot { it.id == background?.id }.sortedBy { it.zIndex.value }
    }
}

/**
 * 확정 구성만 그리는 방 표면이다.
 *
 * 상호작용, 선택 테두리, 드래그가 없어 저장된 결과를 그대로 보여준다. 공유 미리보기와 공유 이미지 캡처가 이 composable을 공유한다.
 *
 * @param placementTagPrefix 배치 노드에 붙일 테스트 태그 접두사. 캡처 화면과 편집 화면이 서로 다른 태그를 쓴다.
 */
@Composable
fun MiniHomeCommittedRoom(
    layout: MiniHomeLayout,
    plants: List<MiniHomePlantChoice>,
    decorations: List<MiniHomeDecorationChoice>,
    photoLoader: MiniHomePhotoLoader,
    modifier: Modifier = Modifier,
    placementTagPrefix: String? = null,
) {
    val backgroundPlacement = MiniHomeRoomRenderer.backgroundPlacement(layout, decorations)
    val backgroundChoice = backgroundPlacement?.let { placement ->
        val itemId = (placement.target as MiniHomePlacementTarget.Decoration).itemId
        decorations.firstOrNull { it.id == itemId }
    }
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier.clip(RoundedCornerShape(PlanteriorRadius.Card)),
        contentAlignment = Alignment.TopStart,
    ) {
        val projection =
            with(density) { MiniHomeIsometricProjection(maxWidth.toPx(), maxHeight.toPx()) }
        val miniatureWidth = with(density) { projection.miniatureWidth.toDp() }
        val miniatureHeight = with(density) { projection.miniatureHeight.toDp() }
        MiniHomeBackground(backgroundChoice, photoLoader, Modifier.fillMaxSize())
        MiniHomeFloor(projection, backgroundChoice != null)
        MiniHomeRoomRenderer.stagePlacements(layout, decorations).forEach { placement ->
            val anchor = projection.cellCenter(placement.position)
            Box(
                modifier =
                    Modifier.offset(
                            x =
                                with(density) {
                                    (anchor.x - projection.miniatureWidth / 2f).toDp()
                                },
                            y = with(density) { (anchor.y - projection.miniatureHeight).toDp() },
                        )
                        .size(miniatureWidth, miniatureHeight)
                        .then(
                            placementTagPrefix?.let {
                                Modifier.testTag("$it${placement.id.value}")
                            } ?: Modifier
                        )
                        .semantics { hideFromAccessibility() },
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
    }
}

/** 정규 5x4 다이아몬드 바닥과 뒷벽을 그린다. */
@Composable
internal fun MiniHomeFloor(
    projection: MiniHomeIsometricProjection,
    hasBackground: Boolean,
) {
    val floor =
        if (hasBackground) MaterialTheme.colorScheme.surface.copy(alpha = 0.30f)
        else MaterialTheme.colorScheme.primaryContainer
    val wall =
        if (hasBackground) MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)
        else MaterialTheme.colorScheme.surface
    val grid = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxSize()) {
        drawRect(wall, size = Size(size.width, projection.floorTop + size.height * 0.10f))
        for (row in 0 until MiniHomeGrid.ROWS) {
            for (column in 0 until MiniHomeGrid.COLUMNS) {
                val tile = projection.cell(GridPosition(column, row))
                val path =
                    Path().apply {
                        moveTo(tile.back.x, tile.back.y)
                        lineTo(tile.right.x, tile.right.y)
                        lineTo(tile.front.x, tile.front.y)
                        lineTo(tile.left.x, tile.left.y)
                        close()
                    }
                drawPath(path, if ((column + row) % 2 == 0) floor else floor.copy(alpha = 0.82f))
                drawPath(path, grid, style = Stroke(PlanteriorBorderWidth.toPx()))
            }
        }
    }
}

/** 배치 하나를 정규 미니어처로 그린다. */
@Composable
internal fun MiniHomePlacementMiniature(
    placement: MiniHomePlacement,
    plants: List<MiniHomePlantChoice>,
    decorations: List<MiniHomeDecorationChoice>,
    photoLoader: MiniHomePhotoLoader,
    width: Dp,
    height: Dp,
) {
    when (val target = placement.target) {
        is MiniHomePlacementTarget.Plant -> {
            val plant = plants.firstOrNull { it.id == target.plantId }
            PlantMiniature(
                identity = target.plantId.value,
                name = plant?.displayName ?: target.plantId.value,
                representativePhotoPath = plant?.representativePhotoPath,
                photoLoader = photoLoader,
                width = width,
                height = height,
            )
        }
        is MiniHomePlacementTarget.Decoration -> {
            val decoration = decorations.firstOrNull { it.id == target.itemId }
            DecorationMiniature(
                identity = target.itemId.value,
                name = decoration?.displayName ?: target.itemId.value,
                mediaIdentity = decoration?.mediaIdentity,
                photoLoader = photoLoader,
                width = width,
                height = height,
            )
        }
    }
}
