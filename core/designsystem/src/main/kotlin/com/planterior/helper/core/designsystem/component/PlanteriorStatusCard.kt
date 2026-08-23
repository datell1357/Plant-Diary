package com.planterior.helper.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme

/**
 * 제목과 본문 한 쌍으로 상태를 알리는 카드이다.
 *
 * 도감, 물 주기, 미니 식물원, 미니홈 공유가 모두 같은 형태를 쓰고 있어 표면·색·간격을 여기서 한 번만 정한다. 오류 상태는 경고 표면을 쓰고 스크린 리더가 오류로 읽도록
 * semantics를 붙인다.
 *
 * @param error 오류 상태 여부. 경고 표면과 오류 semantics를 함께 적용한다.
 * @param tag 테스트가 이 카드를 지목할 때 쓰는 태그. 필요 없으면 비워 둔다.
 */
@Composable
fun PlanteriorStatusCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    tag: String? = null,
) {
    PlanteriorCard(
        modifier =
            modifier
                .fillMaxWidth()
                .then(tag?.let(Modifier::testTag) ?: Modifier)
                .then(if (error) Modifier.semantics { this.error(body) } else Modifier),
        containerColor =
            if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
            color =
                if (error) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
