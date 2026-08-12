package com.planterior.helper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.planterior.helper.core.designsystem.component.PlanteriorScreenScaffold
import com.planterior.helper.core.designsystem.theme.PlanteriorBorderWidth
import com.planterior.helper.core.designsystem.theme.PlanteriorRadius
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme

/**
 * 아직 기능이 붙지 않은 목적지를 위한 화면이다.
 *
 * Figma에 없는 화면도 같은 토큰과 컴포넌트만 쓰도록 이 하나의 화면으로 통일한다. 실제 데이터가 있는 것처럼 꾸민 예시 콘텐츠는 넣지 않는다.
 *
 * @param title 화면 제목.
 * @param description 이 화면이 무엇을 담게 되는지 알려주는 안내 문구.
 * @param bottomBar 하단 내비게이션. 탭 화면이 아니면 비워 둔다.
 * @param primaryActionLabel 주 동작 버튼 라벨. [onPrimaryAction]이 있을 때만 쓰인다.
 * @param onPrimaryAction 주 동작. 필요 없는 화면은 `null`로 둔다.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
) {
    PlanteriorScreenScaffold(title = title, modifier = modifier, bottomBar = bottomBar) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(PlanteriorRadius.Medium),
                    )
                    .border(
                        PlanteriorBorderWidth,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(PlanteriorRadius.Medium),
                    )
                    .padding(PlanteriorTheme.spacing.extraLarge),
        )
        if (onPrimaryAction != null && primaryActionLabel != null) {
            Button(onClick = onPrimaryAction, modifier = Modifier.fillMaxWidth()) {
                Text(text = primaryActionLabel, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
