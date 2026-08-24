package com.planterior.helper.core.designsystem.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme

/** 되돌릴 수 없는 행동을 경고 상태와 구분해 표시하는 공용 버튼이다. */
@Composable
fun PlanteriorDestructiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = PlanteriorTheme.destructive,
                disabledContainerColor = PlanteriorTheme.destructiveContainer,
                disabledContentColor = PlanteriorTheme.onDestructiveContainer,
            ),
        content = content,
    )
}
