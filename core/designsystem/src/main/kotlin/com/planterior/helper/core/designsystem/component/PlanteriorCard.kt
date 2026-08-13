package com.planterior.helper.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.planterior.helper.core.designsystem.theme.PlanteriorRadius
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme

/**
 * Figma `home-screen`의 카드 표면이다.
 *
 * 홈의 관리 카드와 미니홈피 미리보기가 모두 이 표면을 쓴다. 화면 코드가 배경색·모서리·안쪽 여백을 각자 정하면 카드마다 값이 어긋나므로 여기서 한 번만 정한다.
 *
 * @param onClick 카드 전체를 누를 수 있게 한다. 누를 수 없는 카드는 `null`로 둔다.
 * @param containerColor 표면 색. 기본은 Figma 카드의 흰색이다.
 * @param contentPadding 카드 안쪽 여백. 이미지를 가장자리까지 채우는 카드는 0으로 둔다.
 */
@Composable
fun PlanteriorCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: androidx.compose.ui.unit.Dp = PlanteriorTheme.spacing.extraLarge,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(PlanteriorRadius.Card)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(containerColor, shape)
                .then(
                    if (onClick == null) {
                        Modifier
                    } else {
                        Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onClick,
                            )
                            .semantics { role = Role.Button }
                    }
                )
                .padding(contentPadding),
        content = content,
    )
}
