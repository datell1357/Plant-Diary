package com.planterior.helper.feature.watering

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.planterior.helper.core.designsystem.component.PlanteriorCard
import com.planterior.helper.core.designsystem.component.PlanteriorScreenScaffold
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import java.time.format.DateTimeFormatter

object WateringTestTags {
    const val SCREEN = "watering-confirmation:screen"
    const val SCHEDULE = "watering:schedule"
    const val UNAVAILABLE = "watering:unavailable"
    const val UPCOMING = "watering:upcoming"
    const val DUE = "watering:due"
    const val OVERDUE = "watering:overdue"
    const val RECORD = "watering:record"
    const val LOADING = "watering-confirmation:loading"
    const val DATE_INPUT = "watering-confirmation:date"
    const val DATE_ERROR = "watering-confirmation:date-error"
    const val CONFIRM = "watering-confirmation:confirm"
    const val FAILURE = "watering-confirmation:failure"
    const val RETRY = "watering-confirmation:retry"
    const val RECONCILE = "watering-confirmation:reconcile"
    const val RESULT = "watering-confirmation:result"
    const val DONE = "watering-confirmation:done"
    const val BACK = "watering-confirmation:back"
    const val UNAVAILABLE_CONFIRMATION = "watering-confirmation:unavailable"
    const val ERROR = "watering-confirmation:error"
    const val ERROR_RETRY = "watering-confirmation:error-retry"
}

@Composable
fun WateringScheduleCard(
    status: WateringScheduleStatus,
    onRecordWatering: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val (tag, title, body) = status.copy()
    PlanteriorCard(modifier = modifier.testTag(WateringTestTags.SCHEDULE)) {
        Text(
            text = "물 주기 일정",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier =
                Modifier.fillMaxWidth().padding(top = PlanteriorTheme.spacing.medium).testTag(tag),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = PlanteriorTheme.spacing.small),
        )
        if (status !is WateringScheduleStatus.Unavailable && onRecordWatering != null) {
            Button(
                onClick = onRecordWatering,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(top = PlanteriorTheme.spacing.large)
                        .sizeIn(minHeight = PlanteriorTheme.spacing.huge * 2)
                        .testTag(WateringTestTags.RECORD),
            ) {
                Text("물 주기 완료 기록")
            }
        }
    }
}

@Composable
fun WateringConfirmationScreen(
    state: WateringConfirmationUiState,
    onBack: () -> Unit,
    onWateredDate: (String) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryLoad: () -> Unit = {},
    onReconcile: () -> Unit = {},
) {
    val title =
        when (state) {
            is WateringConfirmationUiState.Ready -> state.snapshot.displayName
            is WateringConfirmationUiState.Saving -> state.snapshot.displayName
            is WateringConfirmationUiState.Failure -> state.snapshot.displayName
            is WateringConfirmationUiState.Unavailable -> state.snapshot.displayName
            else -> "물 주기 완료"
        }
    PlanteriorScreenScaffold(
        title = title,
        modifier = modifier.testTag(WateringTestTags.SCREEN),
    ) {
        TextButton(
            onClick = onBack,
            modifier =
                Modifier.sizeIn(minHeight = PlanteriorTheme.spacing.huge * 2)
                    .testTag(WateringTestTags.BACK),
        ) {
            Text("식물 관리로 돌아가기")
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
        ) {
            when (state) {
                WateringConfirmationUiState.Loading ->
                    CircularProgressIndicator(
                        modifier =
                            Modifier.align(Alignment.CenterHorizontally)
                                .testTag(WateringTestTags.LOADING)
                    )
                is WateringConfirmationUiState.Ready ->
                    ConfirmationForm(
                        state.snapshot,
                        state.draft,
                        state.nextDueDate,
                        state.validationError,
                        saving = false,
                        onWateredDate,
                        onConfirm = {
                            WateringConfirmActionDiagnostics.observe(
                                WateringConfirmActionObservation(
                                    WateringConfirmActionStage.SCREEN_CALLBACK,
                                    state.snapshot.plantId,
                                    state.draft.operationId,
                                )
                            )
                            onConfirm()
                        },
                    )
                is WateringConfirmationUiState.Saving ->
                    ConfirmationForm(
                        state.snapshot,
                        state.draft,
                        state.nextDueDate,
                        validationError = null,
                        saving = true,
                        onWateredDate,
                        onConfirm,
                    )
                is WateringConfirmationUiState.Failure -> {
                    ConfirmationForm(
                        state.snapshot,
                        state.draft,
                        state.nextDueDate,
                        validationError = null,
                        saving = false,
                        onWateredDate,
                        onConfirm,
                    )
                    val message = failureMessage(state.failure)
                    PlanteriorCard(
                        modifier =
                            Modifier.fillMaxWidth()
                                .semantics {
                                    error(message)
                                    liveRegion = LiveRegionMode.Assertive
                                }
                                .testTag(WateringTestTags.FAILURE),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            "완료 기록을 저장하지 못했어요",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
                        )
                    }
                    when {
                        state.failure.safeToRetry ->
                            Button(
                                onClick = onRetry,
                                modifier = Modifier.action(WateringTestTags.RETRY),
                            ) {
                                Text("같은 기록 다시 저장")
                            }
                        state.failure.requiresReconciliation ->
                            Button(
                                onClick = onReconcile,
                                modifier = Modifier.action(WateringTestTags.RECONCILE),
                            ) {
                                Text("저장 결과 다시 확인")
                            }
                    }
                }
                is WateringConfirmationUiState.Unavailable ->
                    PlanteriorCard(
                        modifier =
                            Modifier.fillMaxWidth()
                                .testTag(WateringTestTags.UNAVAILABLE_CONFIRMATION)
                    ) {
                        Text(
                            "완료 기록을 만들 수 없어요",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            unavailableMessage(state.schedule.reason),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
                        )
                    }
                is WateringConfirmationUiState.Completed -> {
                    PlanteriorCard(
                        modifier =
                            Modifier.fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag(WateringTestTags.RESULT),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            "물 주기를 기록했어요",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            "마지막 물 준 날 ${state.receipt.wateredDate}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
                        )
                        Text(
                            "다음 예정일 ${state.receipt.nextDueDate}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = PlanteriorTheme.spacing.small),
                        )
                    }
                    Button(onClick = onDone, modifier = Modifier.action(WateringTestTags.DONE)) {
                        Text("식물 관리에서 확인")
                    }
                }
                WateringConfirmationUiState.Forbidden ->
                    MessageCard("이 식물에 접근할 수 없어요", "내 도감에서 식물을 다시 선택해 주세요.")
                WateringConfirmationUiState.NotFound ->
                    MessageCard("식물을 찾을 수 없어요", "삭제되었거나 변경된 식물일 수 있어요.")
                WateringConfirmationUiState.Error -> {
                    MessageCard(
                        "물 주기 정보를 불러오지 못했어요",
                        "연결 상태를 확인하고 다시 시도해 주세요.",
                        WateringTestTags.ERROR,
                        actionableError = true,
                    )
                    Button(
                        onClick = onRetryLoad,
                        modifier = Modifier.action(WateringTestTags.ERROR_RETRY),
                    ) {
                        Text("다시 시도")
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ConfirmationForm(
    snapshot: WateringPlantSnapshot,
    draft: WateringCompletionDraft,
    nextDueDate: java.time.LocalDate?,
    validationError: WateringCompletionValidationError?,
    saving: Boolean,
    onWateredDate: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val errorText =
        when (validationError) {
            WateringCompletionValidationError.INVALID_DATE -> "날짜를 YYYY-MM-DD 형식으로 입력해 주세요."
            WateringCompletionValidationError.FUTURE_DATE -> "계정 시간대의 오늘 또는 이전 날짜를 입력해 주세요."
            null -> null
        }
    Text(
        "기록 날짜를 확인해 주세요. 기본값은 계정 시간대의 오늘이에요.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = draft.wateredDate,
        onValueChange = onWateredDate,
        enabled = !saving && !draft.frozen,
        label = { Text("물 준 날짜") },
        supportingText = {
            Text(
                errorText ?: "YYYY-MM-DD",
                modifier =
                    if (errorText == null) Modifier
                    else Modifier.testTag(WateringTestTags.DATE_ERROR),
            )
        },
        isError = errorText != null,
        singleLine = true,
        modifier =
            Modifier.fillMaxWidth()
                .then(
                    errorText?.let { message ->
                        Modifier.semantics {
                            error(message)
                            liveRegion = LiveRegionMode.Assertive
                        }
                    } ?: Modifier
                )
                .testTag(WateringTestTags.DATE_INPUT),
    )
    PlanteriorCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "기록 후 일정",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "권장 간격 ${snapshot.publicIntervalDays}일",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
        )
        Text(
            nextDueDate?.format(DateTimeFormatter.ISO_LOCAL_DATE) ?: "날짜를 확인해 주세요",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = PlanteriorTheme.spacing.small),
        )
    }
    if (!draft.frozen) {
        Button(
            onClick = onConfirm,
            enabled = !saving && validationError == null,
            modifier = Modifier.action(WateringTestTags.CONFIRM),
        ) {
            Text(if (saving) "저장 중" else "이 날짜로 완료 기록")
        }
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    tag: String? = null,
    actionableError: Boolean = false,
) {
    val semantics =
        if (actionableError) {
            Modifier.semantics {
                error(body)
                liveRegion = LiveRegionMode.Assertive
            }
        } else {
            Modifier
        }
    PlanteriorCard(
        modifier =
            Modifier.fillMaxWidth().then(semantics).then(tag?.let(Modifier::testTag) ?: Modifier)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
        )
    }
}

private data class ScheduleCopy(val tag: String, val title: String, val body: String)

private fun WateringScheduleStatus.copy(): ScheduleCopy =
    when (this) {
        is WateringScheduleStatus.Unavailable ->
            ScheduleCopy(WateringTestTags.UNAVAILABLE, "일정을 계산할 수 없어요", unavailableMessage(reason))
        is WateringScheduleStatus.Upcoming ->
            ScheduleCopy(
                WateringTestTags.UPCOMING,
                "${dueDate} 예정",
                "${daysUntil}일 뒤 물 주기를 확인해 주세요.",
            )
        is WateringScheduleStatus.Due ->
            ScheduleCopy(WateringTestTags.DUE, "오늘 물 줄 날이에요", "예정일 ${dueDate}")
        is WateringScheduleStatus.Overdue ->
            ScheduleCopy(
                WateringTestTags.OVERDUE,
                "물 주기가 ${daysLate}일 지났어요",
                "예정일 ${dueDate} · 흙 상태를 먼저 확인해 주세요.",
            )
    }

private fun unavailableMessage(reason: WateringUnavailableReason): String =
    when (reason) {
        WateringUnavailableReason.MISSING_LAST_WATERED_DATE -> "관리 기록 편집에서 마지막 물 준 날짜를 입력해 주세요."
        WateringUnavailableReason.MISSING_PUBLIC_INTERVAL ->
            "공개된 권장 물 주기 간격이 아직 없어요. 임의 간격은 사용하지 않아요."
        WateringUnavailableReason.INVALID_PUBLIC_INTERVAL -> "공개 관리 정보를 다시 불러와 주세요."
    }

private fun failureMessage(failure: WateringCompletionFailure): String =
    when (failure) {
        WateringCompletionFailure.REMOTE_WRITE_FAILED -> "기존 일정은 그대로예요. 연결 후 같은 기록으로 다시 시도해 주세요."
        WateringCompletionFailure.DATABASE_UNAVAILABLE ->
            "서버 결과를 기기에 반영하지 못했어요. 같은 기록으로 다시 확인해 주세요."
        WateringCompletionFailure.INCONSISTENT_RECEIPT -> "저장 결과를 확인하지 못했어요. 같은 기록으로 다시 확인해 주세요."
        WateringCompletionFailure.REVISION_CONFLICT ->
            "다른 곳에서 기록이 변경됐어요. 식물 관리로 돌아가 최신 기록을 확인해 주세요."
        WateringCompletionFailure.OUTBOX_MISMATCH ->
            "이전 요청과 내용이 달라 중지했어요. 같은 요청의 저장 결과를 다시 확인해 주세요."
        WateringCompletionFailure.RECONCILIATION_REQUIRED -> "요청을 다시 보내지 않고 같은 기록의 저장 결과를 확인해 주세요."
    }

@Composable
private fun Modifier.action(tag: String): Modifier =
    fillMaxWidth().sizeIn(minHeight = PlanteriorTheme.spacing.huge * 2).testTag(tag)
