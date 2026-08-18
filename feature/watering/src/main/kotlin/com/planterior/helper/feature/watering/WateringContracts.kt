package com.planterior.helper.feature.watering

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class WateringUnavailableReason {
    MISSING_LAST_WATERED_DATE,
    MISSING_PUBLIC_INTERVAL,
    INVALID_PUBLIC_INTERVAL,
}

sealed interface WateringScheduleStatus {
    data class Unavailable(val reason: WateringUnavailableReason) : WateringScheduleStatus

    data class Upcoming(val dueDate: LocalDate, val daysUntil: Long) : WateringScheduleStatus

    data class Due(val dueDate: LocalDate) : WateringScheduleStatus

    data class Overdue(val dueDate: LocalDate, val daysLate: Long) : WateringScheduleStatus
}

object WateringScheduleCalculator {
    fun calculate(
        lastWateredDate: LocalDate?,
        publicIntervalDays: Int?,
        accountZone: ZoneId,
        clock: Clock,
    ): WateringScheduleStatus {
        if (lastWateredDate == null) {
            return WateringScheduleStatus.Unavailable(
                WateringUnavailableReason.MISSING_LAST_WATERED_DATE
            )
        }
        if (publicIntervalDays == null) {
            return WateringScheduleStatus.Unavailable(
                WateringUnavailableReason.MISSING_PUBLIC_INTERVAL
            )
        }
        if (publicIntervalDays !in 1..365) {
            return WateringScheduleStatus.Unavailable(
                WateringUnavailableReason.INVALID_PUBLIC_INTERVAL
            )
        }
        return classify(lastWateredDate.plusDays(publicIntervalDays.toLong()), accountZone, clock)
    }

    fun classify(dueDate: LocalDate, accountZone: ZoneId, clock: Clock): WateringScheduleStatus {
        val today = LocalDate.now(clock.withZone(accountZone))
        return when {
            dueDate == today -> WateringScheduleStatus.Due(dueDate)
            dueDate > today ->
                WateringScheduleStatus.Upcoming(
                    dueDate,
                    ChronoUnit.DAYS.between(today, dueDate),
                )
            else ->
                WateringScheduleStatus.Overdue(
                    dueDate,
                    ChronoUnit.DAYS.between(dueDate, today),
                )
        }
    }
}

data class WateringPlantSnapshot(
    val accountId: AccountId,
    val plantId: PersonalPlantId,
    val displayName: String,
    val lastWateredDate: LocalDate?,
    val publicIntervalDays: Int?,
    val accountZone: ZoneId,
    val revision: Long,
)

sealed interface WateringLoad {
    data class Found(val snapshot: WateringPlantSnapshot) : WateringLoad

    data object Forbidden : WateringLoad

    data object NotFound : WateringLoad

    data object Failed : WateringLoad
}

data class WateringCompletionRequest(
    val accountId: AccountId,
    val plantId: PersonalPlantId,
    val expectedPlantRevision: Long,
    val operationId: OperationId,
    val wateredDate: LocalDate,
    val accountZone: ZoneId,
)

data class WateringCompletionReceipt(
    val accountId: AccountId,
    val plantId: PersonalPlantId,
    val operationId: OperationId,
    val recordId: String,
    val wateredDate: LocalDate,
    val nextDueDate: LocalDate,
    val plantRevision: Long,
    val scheduleRevision: Long,
    val recordedAt: Instant,
    val accountZone: ZoneId = ZoneId.of("UTC"),
    val requestHash: String = "",
)

enum class WateringCompletionFailure {
    REMOTE_WRITE_FAILED,
    REVISION_CONFLICT,
    INCONSISTENT_RECEIPT,
    DATABASE_UNAVAILABLE,
    OUTBOX_MISMATCH,
    RECONCILIATION_REQUIRED,
}

sealed interface WateringCompletionResult {
    data class Completed(val receipt: WateringCompletionReceipt) : WateringCompletionResult

    data class Failed(val failure: WateringCompletionFailure) : WateringCompletionResult

    data object Forbidden : WateringCompletionResult

    data object NotFound : WateringCompletionResult
}

interface WateringRepository {
    suspend fun load(plantId: PersonalPlantId): WateringLoad

    suspend fun complete(request: WateringCompletionRequest): WateringCompletionResult

    suspend fun reconcile(request: WateringCompletionRequest): WateringCompletionResult =
        complete(request)
}

object WateringRequestHash {
    fun calculate(request: WateringCompletionRequest): String {
        val canonical =
            JsonObject(
                    linkedMapOf(
                        "ownerUid" to JsonPrimitive(request.accountId.value),
                        "plantId" to JsonPrimitive(request.plantId.value),
                        "expectedPlantRevision" to JsonPrimitive(request.expectedPlantRevision),
                        "idempotencyKey" to JsonPrimitive(request.operationId.value),
                        "requestedWateredDate" to JsonPrimitive(request.wateredDate.toString()),
                    )
                )
                .toString()
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

fun interface WateringPreparationSource {
    suspend fun load(plantId: PersonalPlantId): WateringLoad
}

sealed interface WateringReceiptLookup {
    data class Found(val receipt: WateringCompletionReceipt) : WateringReceiptLookup

    data object Forbidden : WateringReceiptLookup

    data object NotFound : WateringReceiptLookup

    data object Failed : WateringReceiptLookup
}

interface WateringRemoteDataSource {
    fun activeAccount(): AccountId

    suspend fun receipt(
        accountId: AccountId,
        plantId: PersonalPlantId,
        operationId: OperationId,
    ): WateringReceiptLookup
}

enum class WateringCompletionValidationError {
    INVALID_DATE,
    FUTURE_DATE,
}

data class WateringCompletionDraft(
    val operationId: OperationId,
    val wateredDate: String,
    val frozen: Boolean = false,
)

sealed interface WateringConfirmationUiState {
    data object Loading : WateringConfirmationUiState

    data class Ready(
        val snapshot: WateringPlantSnapshot,
        val schedule: WateringScheduleStatus,
        val draft: WateringCompletionDraft,
        val nextDueDate: LocalDate?,
        val validationError: WateringCompletionValidationError? = null,
    ) : WateringConfirmationUiState

    data class Saving(
        val snapshot: WateringPlantSnapshot,
        val schedule: WateringScheduleStatus,
        val draft: WateringCompletionDraft,
        val nextDueDate: LocalDate?,
        val request: WateringCompletionRequest,
    ) : WateringConfirmationUiState

    data class Failure(
        val snapshot: WateringPlantSnapshot,
        val schedule: WateringScheduleStatus,
        val draft: WateringCompletionDraft,
        val nextDueDate: LocalDate?,
        val failure: WateringCompletionFailure,
        val request: WateringCompletionRequest? = null,
    ) : WateringConfirmationUiState

    data class Unavailable(
        val snapshot: WateringPlantSnapshot,
        val schedule: WateringScheduleStatus.Unavailable,
    ) : WateringConfirmationUiState

    data class Completed(val receipt: WateringCompletionReceipt) : WateringConfirmationUiState

    data object Forbidden : WateringConfirmationUiState

    data object NotFound : WateringConfirmationUiState

    data object Error : WateringConfirmationUiState
}
