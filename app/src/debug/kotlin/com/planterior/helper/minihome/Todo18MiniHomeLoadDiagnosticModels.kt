package com.planterior.helper.minihome

import com.planterior.helper.feature.minihome.MiniHomePendingReadIdentity

@JvmInline internal value class Todo18MiniHomeLoadId(val value: Long)

internal data class Todo18MiniHomePublicationReadId(
    val loadId: Todo18MiniHomeLoadId,
    val ordinal: Long,
)

internal data class Todo18MiniHomeLoadObservation(
    val order: Long,
    val loadId: Todo18MiniHomeLoadId,
    val readId: Todo18MiniHomePublicationReadId?,
    val diagnostic: Todo18MiniHomeLoadDiagnostic,
) {
    val receiptStage: String
        get() = diagnostic.receiptStage

    val pendingReadId: MiniHomePendingReadIdentity?
        get() =
            when (val value = diagnostic) {
                is Todo18MiniHomeLoadDiagnostic.PendingReadEntered -> value.identity
                is Todo18MiniHomeLoadDiagnostic.PendingReadReturned -> value.identity
                is Todo18MiniHomeLoadDiagnostic.PendingReadThrew -> value.identity
                is Todo18MiniHomeLoadDiagnostic.PendingReadCancelled -> value.identity
                else -> null
            }
}

internal enum class Todo18MiniHomeLoadViolationKind(val receiptValue: String) {
    DUPLICATE_STAGE("duplicate-stage"),
    OUT_OF_ORDER_STAGE("out-of-order-stage"),
    STAGE_AFTER_TERMINAL("stage-after-terminal"),
    MULTIPLE_TERMINAL("multiple-terminal"),
}

internal data class Todo18MiniHomeLoadProgressionViolation(
    val kind: Todo18MiniHomeLoadViolationKind,
    val loadId: Todo18MiniHomeLoadId,
    val readId: Todo18MiniHomePublicationReadId?,
    val observedStage: String,
    val previousStage: String?,
)

internal data class Todo18MiniHomePerLoadProgress(
    val loadId: Todo18MiniHomeLoadId,
    val activeStage: String?,
    val lastReachedStage: String?,
    val reachedStages: List<String>,
    val publicationReadIds: List<Todo18MiniHomePublicationReadId>,
    val pendingReadIds: List<MiniHomePendingReadIdentity> = emptyList(),
)

internal data class Todo18MiniHomeLoadProgress(
    val activeStage: String?,
    val lastReachedStage: String?,
    val reachedStages: List<String>,
    val recorderFailures: List<String>,
    val progressionViolations: List<Todo18MiniHomeLoadProgressionViolation> = emptyList(),
    val observations: List<Todo18MiniHomeLoadObservation> = emptyList(),
    val loads: List<Todo18MiniHomePerLoadProgress> = emptyList(),
) {
    val valid: Boolean
        get() = progressionViolations.isEmpty()

    fun progressionProblems(): List<String> = progressionViolations.map { violation ->
        buildString {
            append("invalid-load-progression:")
            append(violation.kind.receiptValue)
            append(':')
            append(violation.observedStage)
            append(":load=")
            append(violation.loadId.value)
            violation.readId?.let {
                append(":read=")
                append(it.ordinal)
            }
        }
    }
}
