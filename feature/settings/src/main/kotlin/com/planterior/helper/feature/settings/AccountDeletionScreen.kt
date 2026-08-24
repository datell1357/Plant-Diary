package com.planterior.helper.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.component.PlanteriorCard
import com.planterior.helper.core.designsystem.component.PlanteriorDestructiveButton
import com.planterior.helper.core.designsystem.component.PlanteriorScreenScaffold
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.DeletionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class AccountDeletionActions(
    val onReauthenticate: () -> Unit = {},
    val onFinalConfirmationChanged: (Boolean) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRefresh: () -> Unit = {},
)

@Composable
fun AccountDeletionScreen(
    state: AccountDeletionUiState,
    actions: AccountDeletionActions,
    onBack: () -> Unit,
) {
    PlanteriorScreenScaffold(
        title = "계정 삭제",
        topAction = {
            OutlinedButton(onClick = onBack, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                Text("닫기")
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().weight(1f).testTag("account-deletion.screen")) {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .testTag("account-deletion.scroll"),
                verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
            ) {
                when (state) {
                    AccountDeletionUiState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        SettingsProse("삭제 범위와 상태를 확인하고 있어요.")
                    }
                    is AccountDeletionUiState.Ready -> ReadyDeletionContent(state, actions)
                }
            }
            if (state is AccountDeletionUiState.Ready) {
                DeletionPrimaryActionFooter(state, actions, onBack)
            }
        }
    }
}

@Composable
private fun ReadyDeletionContent(
    state: AccountDeletionUiState.Ready,
    actions: AccountDeletionActions,
) {
    state.lifecycleAnnouncement?.let {
        SettingsProse(
            text = it,
            modifier =
                Modifier.fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("account-deletion.lifecycle"),
        )
    }
    DeletionScopeCard(state.scope)
    PlanteriorCard(containerColor = PlanteriorTheme.destructiveContainer) {
        SettingsProse(
            text = "삭제가 시작되면 식물 기록, 알림 연결, 공유 링크, 저장 파일과 로그인 계정이 삭제돼요.",
            color = PlanteriorTheme.onDestructiveContainer,
        )
        SettingsProse(
            text = "새 요청은 7일 동안 취소할 수 있고, 처리 시작 뒤에는 취소할 수 없어요.",
            color = PlanteriorTheme.onDestructiveContainer,
        )
    }
    PlanteriorCard {
        Text("사진 처리 안내", style = MaterialTheme.typography.titleMedium)
        SettingsProse(
            text = SETTINGS_PHOTO_DISCLOSURE,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("account-deletion.photo-disclosure"),
        )
    }
    DeletionStatusContent(state, actions)
    state.failure?.let {
        DeletionFailureStatusCard(it)
    }
}

@Composable
private fun DeletionFailureStatusCard(failure: AccountDeletionFailure) {
    val body = failure.userMessage()
    PlanteriorCard(
        modifier =
            Modifier.fillMaxWidth().testTag("account-deletion.error").semantics { error(body) },
        containerColor = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text("요청을 완료하지 못했어요", style = MaterialTheme.typography.titleMedium)
        SettingsProse(
            text = body,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
        )
    }
}

@Composable
private fun DeletionScopeCard(scope: AccountDeletionScope) {
    PlanteriorCard(modifier = Modifier.testTag("account-deletion.scope")) {
        Text("서버가 계산한 삭제 범위", style = MaterialTheme.typography.titleMedium)
        BoxWithConstraints(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(top = PlanteriorTheme.spacing.medium)
                    .testTag("account-deletion.scope-grid")
        ) {
            val useTwoColumns =
                maxWidth >= DELETION_SCOPE_TWO_COLUMN_MIN_WIDTH &&
                    LocalDensity.current.fontScale < DELETION_SCOPE_TWO_COLUMN_MAX_FONT_SCALE
            if (useTwoColumns) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.small),
                ) {
                    scope.categories.chunked(2).forEach { categories ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
                        ) {
                            categories.forEach { category ->
                                DeletionScopeLabel(category, Modifier.weight(1f))
                            }
                            if (categories.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.small),
                ) {
                    scope.categories.forEach { category ->
                        DeletionScopeLabel(category, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun DeletionScopeLabel(category: AccountDeletionCategory, modifier: Modifier) {
    Text(
        text = "· ${category.label()}",
        modifier = modifier.testTag("account-deletion.scope-row.${category.serverId}"),
    )
}

@Composable
private fun DeletionStatusContent(
    state: AccountDeletionUiState.Ready,
    actions: AccountDeletionActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
    ) {
        val workflow = state.workflow
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.small),
        ) {
            SettingsProse(
                text = workflow.statusLabel(),
                modifier = Modifier.testTag("account-deletion.status"),
            )
            workflow?.let {
                Text(
                    "요청 시각 · ${it.requestedAt.coarseTime()}",
                    modifier = Modifier.testTag("account-deletion.requested-at"),
                )
                Text(
                    "처리 예정 · ${it.scheduledAt.coarseTime()}",
                    modifier = Modifier.testTag("account-deletion.scheduled-at"),
                )
            }
            if (
                workflow?.status == DeletionStatus.FAILED ||
                    workflow?.status == DeletionStatus.PARTIALLY_FAILED
            ) {
                SettingsProse(
                    "계정은 유지돼요",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("account-deletion.account-retained"),
                )
                workflow.remainingCategories.forEach {
                    Text(
                        "삭제되지 않은 범위 · ${it.label()}",
                        modifier = Modifier.testTag("account-deletion.remaining.${it.serverId}"),
                    )
                }
            }
        }
        when (workflow?.status) {
            null,
            DeletionStatus.CANCELLED,
            DeletionStatus.FAILED,
            DeletionStatus.PARTIALLY_FAILED -> ConfirmationControls(state, actions)
            DeletionStatus.PROCESSING -> SettingsProse("처리가 끝나면 서버 상태를 다시 확인해 주세요.")
            DeletionStatus.RECEIVED,
            DeletionStatus.COMPLETED -> Unit
        }
    }
}

@Composable
private fun ConfirmationControls(
    state: AccountDeletionUiState.Ready,
    actions: AccountDeletionActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
    ) {
        OutlinedButton(
            onClick = actions.onReauthenticate,
            enabled = !state.reauthenticating && !state.submitting,
            modifier =
                Modifier.fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .testTag("account-deletion.reauthenticate"),
        ) {
            Text(if (state.reauthenticating) "본인 확인 중" else "로그인 수단으로 본인 확인")
        }
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .toggleable(
                        value = state.finalConfirmed,
                        enabled = state.reauthenticated && !state.submitting,
                        role = Role.Checkbox,
                        onValueChange = actions.onFinalConfirmationChanged,
                    )
                    .testTag("account-deletion.final-confirmation"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = state.finalConfirmed,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Text("삭제 결과와 되돌릴 수 없는 영향을 확인했어요.")
        }
    }
}

@Composable
private fun DeletionPrimaryActionFooter(
    state: AccountDeletionUiState.Ready,
    actions: AccountDeletionActions,
    onBack: () -> Unit,
) {
    val status = state.workflow?.status
    if (status == DeletionStatus.PROCESSING) return
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .testTag("account-deletion.footer")
                .padding(
                    top = PlanteriorTheme.spacing.large,
                    bottom = PlanteriorTheme.spacing.large,
                )
    ) {
        when (status) {
            null,
            DeletionStatus.CANCELLED ->
                PlanteriorDestructiveButton(
                    onClick = actions.onSubmit,
                    enabled = state.reauthenticated && state.finalConfirmed && !state.submitting,
                    modifier = Modifier.testTag("account-deletion.submit"),
                ) {
                    Text("계정 삭제 요청")
                }
            DeletionStatus.RECEIVED ->
                OutlinedButton(
                    onClick = actions.onCancel,
                    enabled = !state.submitting,
                    modifier =
                        Modifier.fillMaxWidth()
                            .sizeIn(minHeight = 48.dp)
                            .testTag("account-deletion.cancel"),
                ) {
                    Text("삭제 요청 취소")
                }
            DeletionStatus.FAILED,
            DeletionStatus.PARTIALLY_FAILED ->
                PlanteriorDestructiveButton(
                    onClick = actions.onSubmit,
                    enabled = state.reauthenticated && state.finalConfirmed && !state.submitting,
                    modifier = Modifier.testTag("account-deletion.retry"),
                ) {
                    Text("삭제되지 않은 범위 다시 삭제")
                }
            DeletionStatus.COMPLETED ->
                OutlinedButton(
                    onClick = onBack,
                    modifier =
                        Modifier.fillMaxWidth()
                            .sizeIn(minHeight = 48.dp)
                            .testTag("account-deletion.done"),
                ) {
                    Text("완료")
                }
            DeletionStatus.PROCESSING -> Unit
        }
    }
}

private fun AccountDeletionWorkflow?.statusLabel(): String =
    when (this?.status) {
        null -> "삭제를 요청하기 전에 범위와 결과를 확인해 주세요."
        DeletionStatus.RECEIVED -> "삭제 요청 접수됨 · 7일 유예"
        DeletionStatus.PROCESSING -> "계정 데이터를 삭제하고 있어요."
        DeletionStatus.COMPLETED -> "계정 삭제가 완료됐어요."
        DeletionStatus.FAILED -> "계정 삭제에 실패했어요."
        DeletionStatus.PARTIALLY_FAILED -> "일부 삭제에 실패했어요."
        DeletionStatus.CANCELLED -> "삭제 요청이 취소됐어요."
    }

private fun AccountDeletionCategory.label(): String =
    when (this) {
        AccountDeletionCategory.FIRESTORE_ACCOUNT_DATA -> "식물과 관리 기록"
        AccountDeletionCategory.NOTIFICATION_LINKS -> "알림 연결"
        AccountDeletionCategory.PUBLIC_SHARES -> "공유 링크"
        AccountDeletionCategory.IDENTIFICATION_MEDIA -> "사진 분석 원본"
        AccountDeletionCategory.ACCOUNT_MEDIA -> "대표 사진과 공유 이미지"
        AccountDeletionCategory.PRIVATE_MEDIA_RESERVATIONS -> "비공개 미디어 업로드 예약"
        AccountDeletionCategory.AUTH_ACCOUNT -> "로그인 계정"
    }

private fun AccountDeletionFailure.userMessage(): String =
    when (this) {
        AccountDeletionFailure.PREVIEW_UNAVAILABLE,
        AccountDeletionFailure.STATUS_UNAVAILABLE -> "연결 상태를 확인하고 삭제 상태를 다시 불러와 주세요."
        AccountDeletionFailure.REAUTHENTICATION_FAILED -> "로그인 수단으로 본인 확인을 다시 진행해 주세요."
        AccountDeletionFailure.REQUEST_FAILED -> "확인한 범위는 유지돼요. 같은 화면에서 다시 시도해 주세요."
        AccountDeletionFailure.CANCEL_FAILED -> "서버 상태를 다시 확인한 뒤 취소 가능 여부를 확인해 주세요."
        AccountDeletionFailure.TERMINAL_CALLBACK_FAILED -> "서버 삭제는 완료됐지만 기기 정리를 이어가지 못했어요."
    }

private fun Instant.coarseTime(): String =
    atZone(ZoneId.systemDefault()).format(DELETION_TIME_FORMAT)

private val DELETION_SCOPE_TWO_COLUMN_MIN_WIDTH = 320.dp
private const val DELETION_SCOPE_TWO_COLUMN_MAX_FONT_SCALE = 1.5f
private val DELETION_TIME_FORMAT = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN)
