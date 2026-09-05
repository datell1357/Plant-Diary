package com.planterior.helper.feature.minihome

import com.planterior.helper.core.model.AccountId

enum class MiniHomeOwnerOperationKind {
    LOAD,
    SAVE,
    RECONCILE,
    DISCARD,
}

enum class MiniHomeOwnerOperationStage {
    ENTERED,
    ACQUIRED,
    RETURNED,
    THREW,
    CANCELLED,
    RELEASED,
}

data class MiniHomeOwnerOperationObservation(
    val kind: MiniHomeOwnerOperationKind,
    val stage: MiniHomeOwnerOperationStage,
    val accountId: AccountId,
    val token: Long,
    val failure: Throwable? = null,
)

enum class MiniHomePublicationTransactionStage {
    CALL_ENTERED,
    BODY_ENTERED,
    BODY_RETURNED,
    RETURNED,
    THREW,
    CANCELLED,
}

data class MiniHomePublicationTransactionObservation(
    val stage: MiniHomePublicationTransactionStage,
    val accountId: AccountId,
    val readIdentity: MiniHomePublicationReadIdentity,
    val failure: Throwable? = null,
)

internal fun observeMiniHomeOwnerOperation(
    observe: (MiniHomeOwnerOperationObservation) -> Unit,
    observation: MiniHomeOwnerOperationObservation,
) {
    try {
        observe(observation)
    } catch (_: AssertionError) {} catch (_: Exception) {}
}

internal fun observeMiniHomePublicationTransaction(
    observe: (MiniHomePublicationTransactionObservation) -> Unit,
    observation: MiniHomePublicationTransactionObservation,
) {
    try {
        observe(observation)
    } catch (_: AssertionError) {} catch (_: Exception) {}
}
