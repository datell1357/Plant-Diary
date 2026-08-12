package com.planterior.helper.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.vector.ImageVector
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

/** Figma `camera-circle` 지름. */
private val CameraActionSize = 56.dp

/** Figma 탭 아이콘 크기. */
private val TabIconSize = 24.dp

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
    Column(
        modifier =
            modifier
                .fillMaxWidth()
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
            CameraAction(
                contentDescription = cameraContentDescription,
                onClick = onCameraClick,
                modifier = Modifier.weight(1f),
            )
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

@Composable
private fun CameraAction(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.sizeIn(minHeight = MinimumTouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier.size(CameraActionSize)
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
