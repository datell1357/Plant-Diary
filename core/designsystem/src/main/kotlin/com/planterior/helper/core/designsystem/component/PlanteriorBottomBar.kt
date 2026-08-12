package com.planterior.helper.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.icon.PlanteriorIcons
import com.planterior.helper.core.designsystem.theme.PlanteriorBorderWidth
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme

/** Figma `tab-bar` 높이. */
private val TabBarHeight = 62.dp

/** 접근성 최소 터치 대상. Figma 탭 폭보다 크지 않도록 높이에만 적용한다. */
private val MinimumTouchTarget = 48.dp

/** Figma `camera-circle` 지름. 검사 패널 원문은 `width: 52px; height: 52px; border-radius: 26px`. */
private val CameraActionSize = 52.dp

/**
 * 카메라 원이 탭 바 위로 돌출되는 높이이다.
 *
 * Figma에서 `tab-camera-wrapper`의 `margin-top`은 8px이고 그 안의 `camera-circle`은 `position: absolute; top:
 * -14px`이다. 따라서 원의 윗변은 탭 바 상단 경계선 기준 `8 - 14 = -6px` 위치가 되어 6dp 만큼 위로 솔아오른다.
 */
private val CameraOverhang = 6.dp

/** Figma `box-shadow: 0 4px 12px` 에 대응하는 그림자 높이. */
private val CameraElevation = 6.dp

/** Figma 탭 아이콘 크기. */
private val TabIconSize = 24.dp

/**
 * 탭 바 영역을 가리키는 테스트 태그이다.
 *
 * 카메라 원이 경계선 위로 얼마나 솔아오르는지를 측정하려면 탭 바의 윗변(= `border-top` 경계선) 좌표가 필요하다.
 */
const val TabBarTestTag: String = "planterior:tab-bar"

/** Figma `camera` 아이콘 크기. */
private val CameraIconSize = 26.dp

/**
 * 하단 탭 하나를 나타낸다.
 *
 * @param label 탭 라벨. Figma `tab-label` 텍스트.
 * @param icon 탭 아이콘.
 * @param contentDescription 스크린 리더가 읽을 설명.
 */
data class PlanteriorTab(
    val label: String,
    val icon: ImageVector,
    val contentDescription: String = label,
)

/**
 * Figma `bottom-navigation-wrapper`를 재현한 하단 내비게이션이다.
 *
 * 좌측 2개 탭, 가운데 원형 카메라 액션, 우측 2개 탭 순서로 배치한다. 카메라 액션은 탭이 아니라 별도 동작이므로 선택 상태를 갖지 않는다.
 *
 * @param tabs 좌우에 나눠 배치할 4개 탭. 앞 2개가 좌측, 뒤 2개가 우측이다.
 * @param selectedIndex 현재 선택된 탭의 [tabs] 인덱스. 선택된 탭이 없으면 `-1`.
 * @param onTabSelected 탭을 눌렀을 때 인덱스를 전달한다.
 * @param cameraContentDescription 가운데 카메라 액션의 접근성 설명.
 * @param onCameraClick 가운데 카메라 액션을 눌렀을 때 호출한다.
 */
@Composable
fun PlanteriorBottomBar(
    tabs: List<PlanteriorTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    cameraContentDescription: String,
    onCameraClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    require(tabs.size == 4) { "하단 탭은 카메라 액션을 제외하고 정확히 4개여야 한다." }
    // 카메라 원이 탭 바 위로 솔아올라야 하므로 바깥 Box는 돌출 높이만큼 위쪽 여백을 두고
    // 잘라내지 않는다. 배경과 시스템 여백은 탭 바 영역에만 적용한다.
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(top = CameraOverhang)
                    .testTag(TabBarTestTag)
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
        ) {
            PlanteriorDivider()
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(TabBarHeight)
                        .padding(
                            start = PlanteriorTheme.spacing.extraLarge,
                            top = PlanteriorTheme.spacing.medium,
                            end = PlanteriorTheme.spacing.extraLarge,
                            bottom = PlanteriorTheme.spacing.extraSmall,
                        ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.take(2).forEachIndexed { index, tab ->
                    TabItem(
                        tab = tab,
                        selected = selectedIndex == index,
                        onClick = { onTabSelected(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 카메라 원은 오버레이로 그리고 여기서는 가운데 자리만 비워 둔다.
                Spacer(modifier = Modifier.weight(1f))
                tabs.drop(2).forEachIndexed { offset, tab ->
                    val index = offset + 2
                    TabItem(
                        tab = tab,
                        selected = selectedIndex == index,
                        onClick = { onTabSelected(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        // 원의 윗변을 바깥 Box 상단에 정확히 맞춰 탭 바 경계선 기준 6dp 돌출을 만든다.
        // 터치 대상 보정은 원 아래쪽으로만 확장해 돌출 높이가 흔들리지 않게 한다.
        CameraAction(
            contentDescription = cameraContentDescription,
            onClick = onCameraClick,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun TabItem(
    tab: PlanteriorTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else PlanteriorTheme.tertiaryText
    Column(
        modifier =
            modifier
                .sizeIn(minHeight = MinimumTouchTarget)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) {
                    role = Role.Tab
                    this.selected = selected
                    contentDescription = tab.contentDescription
                },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TabIconSize),
        )
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier =
                Modifier.padding(top = PlanteriorTheme.spacing.small).clearAndSetSemantics {},
        )
    }
}

/**
 * Figma `camera-circle`을 그린다.
 *
 * 탭 바 위로 돌출되도록 부모 Box의 [Alignment.TopCenter]에 놓이며, 부모가 이미 [CameraOverhang] 만큼 위쪽 여백을 가지므로 원은 그 여백을
 * 채우며 경계선 위로 솔아오른다. 크기를 제한하는 상위 Row 밖에 있어 세로로 잘리지 않고 정원을 유지한다.
 */
@Composable
private fun CameraAction(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier =
                Modifier.size(CameraActionSize)
                    .shadow(elevation = CameraElevation, shape = CircleShape, clip = false)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                    .semantics {
                        role = Role.Button
                        this.contentDescription = contentDescription
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PlanteriorIcons.Camera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(CameraIconSize),
            )
        }
    }
}

/** Figma 1dp `#E5E7EB` 경계선. */
@Composable
internal fun PlanteriorDivider(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(PlanteriorBorderWidth)
                .background(MaterialTheme.colorScheme.outline)
    )
}
