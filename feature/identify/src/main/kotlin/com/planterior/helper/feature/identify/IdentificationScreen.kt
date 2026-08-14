package com.planterior.helper.feature.identify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import java.text.NumberFormat

object IdentificationTestTags {
    const val PENDING = "identify:pending"
    const val CANDIDATES = "identify:candidates"
    const val CONFIRM = "identify:confirm"
    const val RETRY = "identify:retry"
    const val RETAKE = "identify:retake"
    const val CHANGE = "identify:change"
    const val EDIT = "identify:edit"
    const val REGISTER = "identify:register"

    fun candidate(id: String) = "identify:candidate:$id"
}

@Composable
fun IdentificationScreen(
    state: IdentificationUiState,
    onSelect: (IdentificationCandidate) -> Unit,
    onConfirm: () -> Unit,
    onFallback: (IdentificationFallback) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(PlanteriorTheme.spacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                Text("이전")
            }
            Text("식물 후보 확인", style = MaterialTheme.typography.headlineSmall)
            when (state) {
                IdentificationUiState.Pending -> PendingContent()
                is IdentificationUiState.Candidates ->
                    CandidateContent(state, onSelect, onConfirm, onFallback)
                IdentificationUiState.NoCandidates ->
                    FallbackContent(
                        title = "사진에서 식물 후보를 찾지 못했어요",
                        message = "다른 사진으로 다시 시도하거나 식물 이름을 직접 등록할 수 있어요.",
                        onFallback = onFallback,
                    )
                is IdentificationUiState.Failed ->
                    FallbackContent(
                        title = failureTitle(state.reason),
                        message = "잠시 뒤 다시 시도하거나 다른 방법으로 계속할 수 있어요.",
                        onFallback = onFallback,
                    )
            }
        }
    }
}

@Composable
private fun PendingContent() {
    Column(
        modifier =
            Modifier.fillMaxWidth().testTag(IdentificationTestTags.PENDING).semantics {
                liveRegion = LiveRegionMode.Polite
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text("사진을 분석하고 있어요")
    }
}

@Composable
private fun CandidateContent(
    state: IdentificationUiState.Candidates,
    onSelect: (IdentificationCandidate) -> Unit,
    onConfirm: () -> Unit,
    onFallback: (IdentificationFallback) -> Unit,
) {
    Text(
        "신뢰도는 식물 종류를 확정하는 진단이 아니라 후보 선택을 돕는 참고 정보예요.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(
        modifier = Modifier.fillMaxWidth().testTag(IdentificationTestTags.CANDIDATES),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.candidates.forEach { candidate ->
            Card(
                onClick = { onSelect(candidate) },
                modifier =
                    Modifier.fillMaxWidth()
                        .testTag(IdentificationTestTags.candidate(candidate.publicContentId.value)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.selectedId == candidate.publicContentId,
                        onClick = { onSelect(candidate) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            candidate.koreanName
                                ?: candidate.commonName
                                ?: candidate.scientificName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        candidate.commonName
                            ?.takeIf { it != candidate.koreanName }
                            ?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Text(
                            candidate.scientificName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "참고 신뢰도 ${NumberFormat.getPercentInstance().format(candidate.confidence)}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
    Button(
        onClick = onConfirm,
        enabled = state.selectedId != null,
        modifier =
            Modifier.fillMaxWidth()
                .sizeIn(minHeight = 48.dp)
                .testTag(IdentificationTestTags.CONFIRM),
    ) {
        Text("선택한 후보 확정")
    }
    AlternativeActions(onFallback)
}

@Composable
private fun FallbackContent(
    title: String,
    message: String,
    onFallback: (IdentificationFallback) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(message, style = MaterialTheme.typography.bodyMedium)
    Button(
        onClick = { onFallback(IdentificationFallback.RETRY) },
        modifier = Modifier.fillMaxWidth().testTag(IdentificationTestTags.RETRY),
    ) {
        Text("다시 시도")
    }
    AlternativeActions(onFallback)
}

@Composable
private fun AlternativeActions(onFallback: (IdentificationFallback) -> Unit) {
    OutlinedButton(
        onClick = { onFallback(IdentificationFallback.RETAKE_PHOTO) },
        modifier = Modifier.fillMaxWidth().testTag(IdentificationTestTags.RETAKE),
    ) {
        Text("다시 촬영")
    }
    OutlinedButton(
        onClick = { onFallback(IdentificationFallback.CHANGE_PHOTO) },
        modifier = Modifier.fillMaxWidth().testTag(IdentificationTestTags.CHANGE),
    ) {
        Text("사진 변경")
    }
    TextButton(
        onClick = { onFallback(IdentificationFallback.EDIT_MANUALLY) },
        modifier = Modifier.fillMaxWidth().testTag(IdentificationTestTags.EDIT),
    ) {
        Text("식물 이름 직접 수정")
    }
    TextButton(
        onClick = { onFallback(IdentificationFallback.REGISTER_MANUALLY) },
        modifier = Modifier.fillMaxWidth().testTag(IdentificationTestTags.REGISTER),
    ) {
        Text("식물 이름 직접 등록")
    }
}

private fun failureTitle(reason: IdentificationFailureReason): String =
    when (reason) {
        IdentificationFailureReason.TIMEOUT -> "분석 시간이 오래 걸리고 있어요"
        IdentificationFailureReason.RATE_LIMITED -> "지금은 요청이 많아요"
        IdentificationFailureReason.PROVIDER_UNAVAILABLE -> "식물 분석을 사용할 수 없어요"
        IdentificationFailureReason.MALFORMED_RESPONSE -> "식물 분석 결과를 확인할 수 없어요"
    }
