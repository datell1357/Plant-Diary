package com.planterior.helper.feature.watering

import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WateringConfirmationController(
    private val plantId: PersonalPlantId,
    private val repository: WateringRepository,
    private val clock: Clock,
    private val savedStateHandle: SavedStateHandle,
    private val operationIdFactory: () -> OperationId = OperationId::random,
) {
    private val restoredReceipt = restoreReceipt()
    private val _state =
        MutableStateFlow<WateringConfirmationUiState>(
            restoredReceipt?.let(WateringConfirmationUiState::Completed)
                ?: WateringConfirmationUiState.Loading
        )
    val state: StateFlow<WateringConfirmationUiState> = _state.asStateFlow()
    private var generation = 0L

    suspend fun start() {
        if (_state.value is WateringConfirmationUiState.Completed) return
        load()
    }

    suspend fun retryLoad() = load()

    fun changeWateredDate(value: String) {
        val ready = _state.value as? WateringConfirmationUiState.Ready ?: return
        if (ready.draft.frozen) return
        val draft = ready.draft.copy(wateredDate = value)
        val validation = validate(value, ready.snapshot)
        _state.value =
            ready.copy(
                draft = draft,
                nextDueDate = validation.date?.plusDays(requireInterval(ready.snapshot).toLong()),
                validationError = validation.error,
            )
        saveEditableDraft(draft)
    }

    suspend fun confirm() {
        when (val current = _state.value) {
            is WateringConfirmationUiState.Ready -> {
                val validation = validate(current.draft.wateredDate, current.snapshot)
                if (validation.error != null || validation.date == null) {
                    _state.value =
                        current.copy(
                            validationError = validation.error,
                            nextDueDate =
                                validation.date?.plusDays(
                                    requireInterval(current.snapshot).toLong()
                                ),
                        )
                    return
                }
                val request =
                    WateringCompletionRequest(
                        current.snapshot.accountId,
                        current.snapshot.plantId,
                        current.snapshot.revision,
                        current.draft.operationId,
                        validation.date,
                        current.snapshot.accountZone,
                    )
                submit(
                    current.snapshot,
                    current.schedule,
                    current.draft,
                    validation.date.plusDays(requireInterval(current.snapshot).toLong()),
                    request,
                    repository::complete,
                )
            }
            is WateringConfirmationUiState.Failure -> {
                val request = current.request ?: return
                if (!current.failure.safeToRetry) return
                submit(
                    current.snapshot,
                    current.schedule,
                    current.draft,
                    current.nextDueDate,
                    request,
                    repository::complete,
                )
            }
            else -> Unit
        }
    }

    suspend fun reconcile() {
        val current = _state.value as? WateringConfirmationUiState.Failure ?: return
        val request = current.request ?: return
        if (!current.failure.requiresReconciliation) return
        submit(
            current.snapshot,
            current.schedule,
            current.draft,
            current.nextDueDate,
            request,
            repository::reconcile,
        )
    }

    private suspend fun submit(
        snapshot: WateringPlantSnapshot,
        schedule: WateringScheduleStatus,
        draft: WateringCompletionDraft,
        nextDueDate: LocalDate?,
        request: WateringCompletionRequest,
        operation: suspend (WateringCompletionRequest) -> WateringCompletionResult,
    ) {
        val frozen = draft.copy(frozen = true)
        saveFrozenRequest(request, WateringCompletionFailure.INCONSISTENT_RECEIPT)
        _state.value =
            WateringConfirmationUiState.Saving(
                snapshot,
                schedule,
                frozen,
                nextDueDate,
                request,
            )
        val result =
            try {
                operation(request)
            } catch (error: CancellationException) {
                val failure = WateringCompletionFailure.INCONSISTENT_RECEIPT
                saveFrozenRequest(request, failure)
                _state.value =
                    WateringConfirmationUiState.Failure(
                        snapshot,
                        schedule,
                        frozen,
                        nextDueDate,
                        failure,
                        request,
                    )
                throw error
            } catch (_: Exception) {
                WateringCompletionResult.Failed(WateringCompletionFailure.RECONCILIATION_REQUIRED)
            }
        applyResult(snapshot, schedule, frozen, nextDueDate, request, result)
    }

    private fun applyResult(
        snapshot: WateringPlantSnapshot,
        schedule: WateringScheduleStatus,
        draft: WateringCompletionDraft,
        nextDueDate: LocalDate?,
        request: WateringCompletionRequest,
        result: WateringCompletionResult,
    ) {
        when (result) {
            is WateringCompletionResult.Completed -> {
                saveReceipt(result.receipt)
                clearDraft()
                _state.value = WateringConfirmationUiState.Completed(result.receipt)
            }
            is WateringCompletionResult.Failed -> {
                saveFrozenRequest(request, result.failure)
                _state.value =
                    WateringConfirmationUiState.Failure(
                        snapshot,
                        schedule,
                        draft,
                        nextDueDate,
                        result.failure,
                        request,
                    )
            }
            WateringCompletionResult.Forbidden ->
                _state.value = WateringConfirmationUiState.Forbidden
            WateringCompletionResult.NotFound -> _state.value = WateringConfirmationUiState.NotFound
        }
    }

    private suspend fun load() {
        val request = ++generation
        _state.value = WateringConfirmationUiState.Loading
        val result =
            try {
                repository.load(plantId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                WateringLoad.Failed
            }
        if (request != generation) return
        _state.value =
            when (result) {
                is WateringLoad.Found -> ready(result.snapshot)
                WateringLoad.Forbidden -> WateringConfirmationUiState.Forbidden
                WateringLoad.NotFound -> WateringConfirmationUiState.NotFound
                WateringLoad.Failed -> WateringConfirmationUiState.Error
            }
    }

    private fun ready(snapshot: WateringPlantSnapshot): WateringConfirmationUiState {
        val schedule =
            WateringScheduleCalculator.calculate(
                snapshot.lastWateredDate,
                snapshot.publicIntervalDays,
                snapshot.accountZone,
                clock,
            )
        val frozenRequest = restoreFrozenRequest()
        if (savedStateHandle.get<Boolean>(FROZEN) == true) {
            if (frozenRequest == null || frozenRequest.plantId != plantId) {
                return WateringConfirmationUiState.Error
            }
            val failure = restoreFailure() ?: WateringCompletionFailure.INCONSISTENT_RECEIPT
            val draft =
                WateringCompletionDraft(
                    frozenRequest.operationId,
                    frozenRequest.wateredDate.toString(),
                    frozen = true,
                )
            val nextDueDate =
                snapshot.publicIntervalDays
                    ?.takeIf { it in 1..365 }
                    ?.let { frozenRequest.wateredDate.plusDays(it.toLong()) }
            return WateringConfirmationUiState.Failure(
                snapshot,
                schedule,
                draft,
                nextDueDate,
                failure,
                frozenRequest,
            )
        }
        if (schedule is WateringScheduleStatus.Unavailable) {
            return WateringConfirmationUiState.Unavailable(snapshot, schedule)
        }
        val operation =
            savedStateHandle.get<String>(OPERATION)?.let(::OperationId) ?: operationIdFactory()
        val wateredDate =
            savedStateHandle.get<String>(DATE)
                ?: LocalDate.now(clock.withZone(snapshot.accountZone)).toString()
        val draft = WateringCompletionDraft(operation, wateredDate)
        val parsed = validate(wateredDate, snapshot)
        val nextDueDate = parsed.date?.plusDays(requireInterval(snapshot).toLong())
        saveEditableDraft(draft)
        return WateringConfirmationUiState.Ready(
            snapshot,
            schedule,
            draft,
            nextDueDate,
            parsed.error,
        )
    }

    private fun validate(value: String, snapshot: WateringPlantSnapshot): DateValidation {
        val date = runCatching {
            LocalDate.parse(value)
        }
            .getOrElse {
                return DateValidation(null, WateringCompletionValidationError.INVALID_DATE)
            }
        val today = LocalDate.now(clock.withZone(snapshot.accountZone))
        return if (date > today) {
            DateValidation(date, WateringCompletionValidationError.FUTURE_DATE)
        } else {
            DateValidation(date, null)
        }
    }

    private fun requireInterval(snapshot: WateringPlantSnapshot): Int =
        requireNotNull(snapshot.publicIntervalDays).also { require(it in 1..365) }

    private fun saveEditableDraft(draft: WateringCompletionDraft) {
        savedStateHandle[OPERATION] = draft.operationId.value
        savedStateHandle[DATE] = draft.wateredDate
        savedStateHandle[FROZEN] = false
        savedStateHandle[FAILURE] = null
    }

    private fun saveFrozenRequest(
        request: WateringCompletionRequest,
        failure: WateringCompletionFailure,
    ) {
        savedStateHandle[FROZEN] = true
        savedStateHandle[REQUEST_ACCOUNT] = request.accountId.value
        savedStateHandle[REQUEST_PLANT] = request.plantId.value
        savedStateHandle[REQUEST_OPERATION] = request.operationId.value
        savedStateHandle[REQUEST_DATE] = request.wateredDate.toString()
        savedStateHandle[REQUEST_REVISION] = request.expectedPlantRevision
        savedStateHandle[REQUEST_ZONE] = request.accountZone.id
        savedStateHandle[FAILURE] = failure.name
    }

    private fun restoreFrozenRequest(): WateringCompletionRequest? = runCatching {
        WateringCompletionRequest(
            AccountId(savedStateHandle.get<String>(REQUEST_ACCOUNT) ?: return null),
            PersonalPlantId(savedStateHandle.get<String>(REQUEST_PLANT) ?: return null),
            savedStateHandle.get<Long>(REQUEST_REVISION) ?: return null,
            savedStateHandle.get<String>(REQUEST_OPERATION)?.let(::OperationId) ?: return null,
            LocalDate.parse(savedStateHandle.get<String>(REQUEST_DATE) ?: return null),
            ZoneId.of(savedStateHandle.get<String>(REQUEST_ZONE) ?: return null),
        )
    }
        .getOrNull()

    private fun restoreFailure(): WateringCompletionFailure? =
        savedStateHandle.get<String>(FAILURE)?.let {
            runCatching { WateringCompletionFailure.valueOf(it) }.getOrNull()
        }

    private fun clearDraft() {
        listOf(
                OPERATION,
                DATE,
                FAILURE,
                REQUEST_ACCOUNT,
                REQUEST_PLANT,
                REQUEST_OPERATION,
                REQUEST_DATE,
                REQUEST_REVISION,
                REQUEST_ZONE,
            )
            .forEach { savedStateHandle[it] = null }
        savedStateHandle[FROZEN] = false
    }

    private fun saveReceipt(receipt: WateringCompletionReceipt) {
        savedStateHandle[RESULT_ACCOUNT] = receipt.accountId.value
        savedStateHandle[RESULT_PLANT] = receipt.plantId.value
        savedStateHandle[RESULT_OPERATION] = receipt.operationId.value
        savedStateHandle[RESULT_RECORD] = receipt.recordId
        savedStateHandle[RESULT_WATERED] = receipt.wateredDate.toString()
        savedStateHandle[RESULT_DUE] = receipt.nextDueDate.toString()
        savedStateHandle[RESULT_PLANT_REVISION] = receipt.plantRevision
        savedStateHandle[RESULT_SCHEDULE_REVISION] = receipt.scheduleRevision
        savedStateHandle[RESULT_RECORDED] = receipt.recordedAt.toString()
        savedStateHandle[RESULT_ZONE] = receipt.accountZone.id
        savedStateHandle[RESULT_HASH] = receipt.requestHash
    }

    private fun restoreReceipt(): WateringCompletionReceipt? = runCatching {
        WateringCompletionReceipt(
            AccountId(savedStateHandle.get<String>(RESULT_ACCOUNT) ?: return null),
            PersonalPlantId(savedStateHandle.get<String>(RESULT_PLANT) ?: return null),
            OperationId(savedStateHandle.get<String>(RESULT_OPERATION) ?: return null),
            savedStateHandle.get<String>(RESULT_RECORD) ?: return null,
            LocalDate.parse(savedStateHandle.get<String>(RESULT_WATERED) ?: return null),
            LocalDate.parse(savedStateHandle.get<String>(RESULT_DUE) ?: return null),
            savedStateHandle.get<Long>(RESULT_PLANT_REVISION) ?: return null,
            savedStateHandle.get<Long>(RESULT_SCHEDULE_REVISION) ?: return null,
            Instant.parse(savedStateHandle.get<String>(RESULT_RECORDED) ?: return null),
            ZoneId.of(savedStateHandle.get<String>(RESULT_ZONE) ?: "UTC"),
            savedStateHandle.get<String>(RESULT_HASH).orEmpty(),
        )
    }
        .getOrNull()

    private data class DateValidation(
        val date: LocalDate?,
        val error: WateringCompletionValidationError?,
    )

    private companion object {
        const val OPERATION = "watering.operation"
        const val DATE = "watering.date"
        const val FROZEN = "watering.request.frozen"
        const val FAILURE = "watering.failure"
        const val REQUEST_ACCOUNT = "watering.request.account"
        const val REQUEST_PLANT = "watering.request.plant"
        const val REQUEST_OPERATION = "watering.request.operation"
        const val REQUEST_DATE = "watering.request.date"
        const val REQUEST_REVISION = "watering.request.revision"
        const val REQUEST_ZONE = "watering.request.zone"
        const val RESULT_ACCOUNT = "watering.result.account"
        const val RESULT_PLANT = "watering.result.plant"
        const val RESULT_OPERATION = "watering.result.operation"
        const val RESULT_RECORD = "watering.result.record"
        const val RESULT_WATERED = "watering.result.watered"
        const val RESULT_DUE = "watering.result.due"
        const val RESULT_PLANT_REVISION = "watering.result.plant-revision"
        const val RESULT_SCHEDULE_REVISION = "watering.result.schedule-revision"
        const val RESULT_RECORDED = "watering.result.recorded"
        const val RESULT_ZONE = "watering.result.zone"
        const val RESULT_HASH = "watering.result.hash"
    }
}

internal val WateringCompletionFailure.safeToRetry: Boolean
    get() =
        this == WateringCompletionFailure.REMOTE_WRITE_FAILED ||
            this == WateringCompletionFailure.DATABASE_UNAVAILABLE ||
            this == WateringCompletionFailure.INCONSISTENT_RECEIPT

internal val WateringCompletionFailure.requiresReconciliation: Boolean
    get() =
        this == WateringCompletionFailure.REVISION_CONFLICT ||
            this == WateringCompletionFailure.OUTBOX_MISMATCH ||
            this == WateringCompletionFailure.RECONCILIATION_REQUIRED
