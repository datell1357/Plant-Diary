package com.planterior.helper.feature.collection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.planterior.helper.core.designsystem.component.PlanteriorCard
import com.planterior.helper.core.designsystem.component.PlanteriorScreenScaffold
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import java.time.format.DateTimeFormatter

@Composable
fun PlantDetailScreen(
    state: PlantDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onBeginEditing: () -> Unit,
    onLastWateredDate: (String) -> Unit,
    onLocation: (String) -> Unit,
    onPrivateNote: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier,
    onReconcileEdit: () -> Unit = {},
) {
    val plant = state.plantOrNull()
    PlanteriorScreenScaffold(
        title = plant?.displayName ?: "식물 관리 정보",
        modifier = modifier,
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.sizeIn(minHeight = PlanteriorTheme.spacing.huge * 2),
        ) {
            Text("도감으로 돌아가기")
        }
        when (state) {
            PlantDetailUiState.Loading -> LoadingState(PlantDetailTestTags.LOADING)
            is PlantDetailUiState.Content ->
                DetailBody(
                    plant = state.detail.plant,
                    guidance = state.detail.guidance,
                    missing = emptySet(),
                    editor = state.editor,
                    editingAllowed = true,
                    onBeginEditing = onBeginEditing,
                    onLastWateredDate = onLastWateredDate,
                    onLocation = onLocation,
                    onPrivateNote = onPrivateNote,
                    onSave = onSave,
                    onCancelEdit = onCancelEdit,
                    onReconcileEdit = onReconcileEdit,
                )
            is PlantDetailUiState.Partial ->
                DetailBody(
                    plant = state.detail.plant,
                    guidance = state.detail.guidance,
                    missing = state.missing,
                    editor = state.editor,
                    editingAllowed = true,
                    noticeTag = PlantDetailTestTags.PARTIAL,
                    noticeTitle = "일부 관리 기준을 준비 중이에요",
                    noticeBody = "확인된 정보만 먼저 보여드려요.",
                    onBeginEditing = onBeginEditing,
                    onLastWateredDate = onLastWateredDate,
                    onLocation = onLocation,
                    onPrivateNote = onPrivateNote,
                    onSave = onSave,
                    onCancelEdit = onCancelEdit,
                    onReconcileEdit = onReconcileEdit,
                )
            is PlantDetailUiState.Stale ->
                DetailBody(
                    plant = state.plant,
                    guidance = state.guidance,
                    missing = CareField.entries.toSet(),
                    editor = state.editor,
                    editingAllowed = state.editingAllowed,
                    noticeTag = PlantDetailTestTags.STALE,
                    noticeTitle = "저장된 개인 기록을 보여드려요",
                    noticeBody =
                        if (state.editingAllowed) {
                            "공개 상태를 확인할 수 없는 관리 기준은 연결 후 표시해요."
                        } else {
                            "서버에서 내 기록을 새로 확인하기 전에는 편집할 수 없어요."
                        },
                    onBeginEditing = onBeginEditing,
                    onLastWateredDate = onLastWateredDate,
                    onLocation = onLocation,
                    onPrivateNote = onPrivateNote,
                    onSave = onSave,
                    onCancelEdit = onCancelEdit,
                    onReconcileEdit = onReconcileEdit,
                )
            is PlantDetailUiState.NoStandardContent ->
                DetailBody(
                    plant = state.plant,
                    guidance = null,
                    missing = emptySet(),
                    editor = state.editor,
                    editingAllowed = true,
                    noticeTag = PlantDetailTestTags.NO_STANDARD,
                    noticeTitle = "표준 관리 정보가 아직 없어요",
                    noticeBody = "직접 입력한 식물도 개인 기록은 계속 관리할 수 있어요.",
                    onBeginEditing = onBeginEditing,
                    onLastWateredDate = onLastWateredDate,
                    onLocation = onLocation,
                    onPrivateNote = onPrivateNote,
                    onSave = onSave,
                    onCancelEdit = onCancelEdit,
                    onReconcileEdit = onReconcileEdit,
                )
            PlantDetailUiState.Forbidden ->
                SafeDetailState(
                    PlantDetailTestTags.FORBIDDEN,
                    "이 식물에 접근할 수 없어요",
                    "내 도감에서 식물을 다시 선택해 주세요.",
                )
            PlantDetailUiState.NotFound ->
                SafeDetailState(
                    PlantDetailTestTags.NOT_FOUND,
                    "식물을 찾을 수 없어요",
                    "삭제되었거나 변경된 식물일 수 있어요.",
                )
            PlantDetailUiState.Error ->
                ErrorState(
                    tag = PlantDetailTestTags.ERROR,
                    retryTag = PlantDetailTestTags.RETRY,
                    title = "관리 정보를 불러오지 못했어요",
                    body = "연결 상태를 확인하고 다시 시도해 주세요.",
                    onRetry = onRetry,
                )
        }
    }
}

@Composable
private fun ColumnScope.DetailBody(
    plant: PersonalPlantDetail,
    guidance: PlantCareGuidance?,
    missing: Set<CareField>,
    editor: EditorState,
    editingAllowed: Boolean,
    onBeginEditing: () -> Unit,
    onLastWateredDate: (String) -> Unit,
    onLocation: (String) -> Unit,
    onPrivateNote: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
    onReconcileEdit: () -> Unit,
    noticeTag: String? = null,
    noticeTitle: String = "",
    noticeBody: String = "",
) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement =
            androidx.compose.foundation.layout.Arrangement.spacedBy(PlanteriorTheme.spacing.large),
    ) {
        if (noticeTag != null) StatusCard(noticeTag, noticeTitle, noticeBody)
        if (guidance != null) {
            CareCard(
                tag = PlantDetailTestTags.WATER,
                title = "물",
                value = guidance.wateringIntervalDays?.let { "권장 간격 ${it}일" },
                missing = CareField.WATER in missing,
            )
            CareCard(
                tag = PlantDetailTestTags.LIGHT,
                title = "빛",
                value = guidance.lightGuidance,
                missing = CareField.LIGHT in missing,
            )
            CareCard(
                tag = PlantDetailTestTags.TEMPERATURE,
                title = "온도",
                value =
                    range(
                        guidance.minimumTemperatureCelsius,
                        guidance.maximumTemperatureCelsius,
                        "°C",
                    ),
                missing = CareField.TEMPERATURE in missing,
            )
            CareCard(
                tag = PlantDetailTestTags.HUMIDITY,
                title = "습도",
                value =
                    range(guidance.minimumHumidityPercent, guidance.maximumHumidityPercent, "%"),
                missing = CareField.HUMIDITY in missing,
                missingTag = PlantDetailTestTags.HUMIDITY_MISSING,
            )
        }
        when {
            CareField.SYMPTOMS in missing ->
                StatusCard(
                    PlantDetailTestTags.SYMPTOMS_MISSING,
                    "증상 안내를 불러오지 못했어요",
                    "연결을 확인한 뒤 관리 정보를 다시 불러와 주세요.",
                )
            guidance != null && guidance.symptoms.isEmpty() ->
                StatusCard(
                    PlantDetailTestTags.SYMPTOMS_EMPTY,
                    "등록된 주요 증상 안내가 없어요",
                    "식물 상태가 걱정되면 전문가에게 확인해 주세요.",
                )
            guidance != null -> {
                Text(
                    text = "증상별 확인",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                )
                guidance.symptoms.forEach { symptom -> SymptomCard(symptom) }
                Text(
                    text = "증상 안내는 확정 진단이 아닌 일반적인 초기 대응 정보예요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PersonalRecord(
            plant = plant,
            editor = editor,
            editingAllowed = editingAllowed,
            onBeginEditing = onBeginEditing,
            onLastWateredDate = onLastWateredDate,
            onLocation = onLocation,
            onPrivateNote = onPrivateNote,
            onSave = onSave,
            onCancelEdit = onCancelEdit,
            onReconcileEdit = onReconcileEdit,
        )
    }
}

@Composable
private fun CareCard(
    tag: String,
    title: String,
    value: String?,
    missing: Boolean,
    missingTag: String? = null,
) {
    PlanteriorCard(modifier = Modifier.testTag(tag)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (missing || value == null) "아직 제공되지 않는 정보예요" else value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(top = PlanteriorTheme.spacing.medium)
                    .then(
                        if (missingTag != null && missing) Modifier.testTag(missingTag)
                        else Modifier
                    ),
        )
    }
}

@Composable
private fun SymptomCard(symptom: PublicSymptomGuidance) {
    PlanteriorCard(modifier = Modifier.testTag("${PlantDetailTestTags.SYMPTOM}:${symptom.id}")) {
        Text(text = symptom.symptom, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "가능한 원인",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = PlanteriorTheme.spacing.large),
        )
        Text(
            text = symptom.possibleCause,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("${PlantDetailTestTags.SYMPTOM_CAUSE}:${symptom.id}"),
        )
        Text(
            text = "먼저 해볼 일",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = PlanteriorTheme.spacing.large),
        )
        Text(
            text = symptom.action,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("${PlantDetailTestTags.SYMPTOM_ACTION}:${symptom.id}"),
        )
    }
}

@Composable
private fun PersonalRecord(
    plant: PersonalPlantDetail,
    editor: EditorState,
    editingAllowed: Boolean,
    onBeginEditing: () -> Unit,
    onLastWateredDate: (String) -> Unit,
    onLocation: (String) -> Unit,
    onPrivateNote: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
    onReconcileEdit: () -> Unit,
) {
    Text(
        text = "나의 관리 기록",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
    if (!editor.isEditing) {
        PlanteriorCard(modifier = Modifier.testTag(PlantDetailTestTags.PRIVATE_NOTE)) {
            RecordLine(
                "마지막 물 준 날",
                plant.lastWateredDate?.format(DateTimeFormatter.ISO_LOCAL_DATE),
            )
            RecordLine("놓인 곳", plant.location)
            RecordLine("비공개 메모", plant.privateNote)
        }
        if (editingAllowed) {
            Button(
                onClick = onBeginEditing,
                modifier =
                    Modifier.fillMaxWidth()
                        .sizeIn(minHeight = PlanteriorTheme.spacing.huge * 2)
                        .testTag(PlantDetailTestTags.EDIT),
            ) {
                Text("관리 기록 편집")
            }
        }
        return
    }

    if (editor.failure != null) EditFailureCard(editor.failure)
    val dateError = dateError(editor.errors)
    val locationError =
        if (EditValidationError.LOCATION_TOO_LONG in editor.errors) {
            "위치는 50자 이하로 줄여 주세요."
        } else null
    val noteError =
        if (EditValidationError.NOTE_TOO_LONG in editor.errors) {
            "비공개 메모는 1000자 이하로 줄여 주세요."
        } else null
    val fieldsEnabled = !editor.saving && !editor.isFrozen
    OutlinedTextField(
        value = editor.lastWateredDate,
        onValueChange = onLastWateredDate,
        enabled = fieldsEnabled,
        label = { Text("마지막 물 준 날짜") },
        supportingText = {
            FieldSupport(
                text = dateError?.first ?: "YYYY-MM-DD",
                tag = dateError?.second,
            )
        },
        isError = dateError != null,
        singleLine = true,
        modifier =
            Modifier.fillMaxWidth()
                .then(
                    dateError?.first?.let { message -> Modifier.semantics { error(message) } }
                        ?: Modifier
                )
                .testTag(PlantDetailTestTags.LAST_WATERED_INPUT),
    )
    OutlinedTextField(
        value = editor.location,
        onValueChange = onLocation,
        enabled = fieldsEnabled,
        label = { Text("식물이 놓인 곳") },
        supportingText = {
            FieldSupport(
                locationError ?: "최대 50자",
                locationError?.let { PlantDetailTestTags.LOCATION_ERROR },
            )
        },
        isError = locationError != null,
        singleLine = true,
        modifier =
            Modifier.fillMaxWidth()
                .then(
                    locationError?.let { message -> Modifier.semantics { error(message) } }
                        ?: Modifier
                )
                .testTag(PlantDetailTestTags.LOCATION_INPUT),
    )
    OutlinedTextField(
        value = editor.privateNote,
        onValueChange = onPrivateNote,
        enabled = fieldsEnabled,
        label = { Text("비공개 관리 메모") },
        supportingText = {
            FieldSupport(
                noteError ?: "나만 볼 수 있어요 · 최대 1000자",
                noteError?.let { PlantDetailTestTags.NOTE_ERROR },
            )
        },
        isError = noteError != null,
        minLines = 3,
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = PlanteriorTheme.spacing.huge * 5)
                .then(
                    noteError?.let { message -> Modifier.semantics { error(message) } } ?: Modifier
                )
                .testTag(PlantDetailTestTags.NOTE_INPUT),
    )
    when {
        editor.requiresReconciliation ->
            Button(
                onClick = onReconcileEdit,
                enabled = !editor.saving,
                modifier = Modifier.actionModifier(PlantDetailTestTags.EDIT_RELOAD),
            ) {
                Text(if (editor.saving) "불러오는 중" else "서버 기록 다시 불러오기")
            }
        editor.canRetryExactSnapshot -> {
            Button(
                onClick = onSave,
                enabled = !editor.saving,
                modifier = Modifier.actionModifier(PlantDetailTestTags.EDIT_RETRY),
            ) {
                Text(if (editor.saving) "저장 중" else "같은 내용 다시 저장")
            }
        }
        else -> {
            Button(
                onClick = onSave,
                enabled = !editor.saving,
                modifier = Modifier.actionModifier(PlantDetailTestTags.SAVE),
            ) {
                Text(if (editor.saving) "저장 중" else "변경 내용 저장")
            }
            TextButton(
                onClick = onCancelEdit,
                enabled = !editor.saving,
                modifier =
                    Modifier.fillMaxWidth().sizeIn(minHeight = PlanteriorTheme.spacing.huge * 2),
            ) {
                Text("편집 취소")
            }
        }
    }
}

@Composable
private fun EditFailureCard(failure: EditFailure) {
    val (title, body) =
        when (failure) {
            EditFailure.REVISION_CONFLICT -> "다른 곳에서 기록이 변경됐어요" to "서버의 최신 기록을 다시 불러온 뒤 새로 편집해 주세요."
            EditFailure.OUTBOX_MISMATCH ->
                "이전 저장 요청과 내용이 달라요" to "이전 요청은 중지했어요. 서버 기록을 다시 불러온 뒤 새로 편집해 주세요."
            EditFailure.REMOTE_WRITE_FAILED ->
                "연결 문제로 저장하지 못했어요" to "입력은 고정해 두었어요. 연결 후 같은 내용을 다시 저장해 주세요."
            EditFailure.DATABASE_UNAVAILABLE ->
                "기기에 저장하지 못했어요" to "입력은 고정해 두었어요. 같은 내용을 다시 저장해 주세요."
            EditFailure.INCONSISTENT_RECEIPT ->
                "저장 결과를 확인하지 못했어요" to "입력은 고정해 두었어요. 같은 요청으로 결과를 다시 확인해 주세요."
        }
    StatusCard(PlantDetailTestTags.EDIT_FAILURE, title, body)
}

@Composable
private fun FieldSupport(text: String, tag: String?) {
    Text(text, modifier = if (tag == null) Modifier else Modifier.testTag(tag))
}

private fun dateError(errors: Set<EditValidationError>): Pair<String, String>? =
    when {
        EditValidationError.INVALID_LAST_WATERED_DATE in errors ->
            "날짜를 YYYY-MM-DD 형식으로 입력해 주세요." to PlantDetailTestTags.DATE_INVALID_ERROR
        EditValidationError.FUTURE_LAST_WATERED_DATE in errors ->
            "계정 시간대의 오늘 또는 이전 날짜를 입력해 주세요." to PlantDetailTestTags.DATE_FUTURE_ERROR
        else -> null
    }

@Composable
private fun Modifier.actionModifier(tag: String): Modifier =
    fillMaxWidth().sizeIn(minHeight = PlanteriorTheme.spacing.huge * 2).testTag(tag)

@Composable
private fun RecordLine(label: String, value: String?) {
    Text(text = label, style = MaterialTheme.typography.bodyLarge)
    Text(
        text = value?.takeIf(String::isNotBlank) ?: "기록 없음",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(bottom = PlanteriorTheme.spacing.medium),
    )
}

private fun PlantDetailUiState.plantOrNull(): PersonalPlantDetail? =
    when (this) {
        is PlantDetailUiState.Content -> detail.plant
        is PlantDetailUiState.Partial -> detail.plant
        is PlantDetailUiState.Stale -> plant
        is PlantDetailUiState.NoStandardContent -> plant
        else -> null
    }

private fun <T : Number> range(minimum: T?, maximum: T?, suffix: String): String? =
    if (minimum == null || maximum == null) null else "$minimum$suffix - $maximum$suffix"
