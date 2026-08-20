package com.planterior.helper.feature.minihome

internal enum class MiniHomeOutboxPhase {
    PENDING,
    MAY_HAVE_COMMITTED,
    RECONCILIATION_REQUIRED;

    companion object {
        fun persisted(value: String): MiniHomeOutboxPhase =
            entries.firstOrNull { it.name == value }
                ?: when (value) {
                    "CONFLICT",
                    "FAILED" -> RECONCILIATION_REQUIRED
                    else -> PENDING
                }
    }
}

internal data class MiniHomeOutboxTransition(
    val phase: MiniHomeOutboxPhase,
    val reason: MiniHomeSaveFailure?,
    val mayTransmitExactRequest: Boolean,
) {
    val requiresCorrection: Boolean
        get() = !mayTransmitExactRequest && reason?.requiresCorrection == true

    val requiresExplicitReconciliation: Boolean
        get() = !mayTransmitExactRequest && !requiresCorrection
}

/** The single transmission policy for every durable mini-home phase/reason combination. */
internal object MiniHomeOutboxTransitionTable {
    val rows: List<MiniHomeOutboxTransition> =
        MiniHomeOutboxPhase.entries.flatMap { phase ->
            (listOf(null) + MiniHomeSaveFailure.entries).map { reason ->
                MiniHomeOutboxTransition(
                    phase,
                    reason,
                    phase != MiniHomeOutboxPhase.RECONCILIATION_REQUIRED &&
                        (reason == null || reason.retryable),
                )
            }
        }

    fun transition(state: String, reason: String?): MiniHomeOutboxTransition {
        val failure = reason?.let { code ->
            MiniHomeSaveFailure.entries.firstOrNull { it.name == code }
        }
        val phase =
            if (state == "FAILED" && failure?.retryable == true) {
                MiniHomeOutboxPhase.MAY_HAVE_COMMITTED
            } else {
                MiniHomeOutboxPhase.persisted(state)
            }
        return rows.single { it.phase == phase && it.reason == failure }
    }
}
