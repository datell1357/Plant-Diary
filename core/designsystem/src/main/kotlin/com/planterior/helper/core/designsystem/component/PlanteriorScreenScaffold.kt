package com.planterior.helper.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme

/**
 * 모든 화면이 공유하는 기본 골격이다.
 *
 * Figma에 없는 화면도 이 골격과 테마 토큰만으로 구성해 시각 언어를 유지한다. 상단 제목은 상태 표시줄을, 하단 탭은 내비게이션 바를 침범하지 않도록 시스템 여백을
 * 적용한다.
 *
 * @param title 화면 상단에 표시하고 스크린 리더가 heading으로 읽는 제목.
 * @param bottomBar 하단 내비게이션. 탭이 없는 화면은 비워 둔다.
 * @param content 제목 아래에 놓일 화면 내용.
 */
@Composable
fun PlanteriorScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = bottomBar,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(innerPadding)
                    .statusBarsPadding()
                    .padding(
                        horizontal = PlanteriorTheme.spacing.extraLarge,
                        vertical = PlanteriorTheme.spacing.large,
                    ),
            verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            content()
        }
    }
}
