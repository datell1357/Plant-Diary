package com.planterior.helper.minihome

internal data class Todo18MiniHomeLoadBoundaryStage(
    val kind: String?,
    val identity: String?,
    val loadId: Long?,
    val readId: Long?,
    val diagnosticOrder: Long?,
    val cacheOutcome: String?,
)

internal object Todo18MiniHomeLoadReceiptReducer {
    private const val LOAD_TERMINAL = "load-terminal"
    private const val PUBLICATION_READ_ENTERED = "publication-read-entered"
    private const val CACHE_APPLY_RETURNED = "cache-apply-returned"
    private val requiredKinds =
        listOf(
            "load-entered",
            "remote-load-entered",
            "remote-load-returned",
            "cache-apply-entered",
            CACHE_APPLY_RETURNED,
            PUBLICATION_READ_ENTERED,
            LOAD_TERMINAL,
        )
    private val terminalIdentities = setOf("Ready", "Forbidden", "Failed", "Cancelled")
    private val cacheOutcomes = setOf("current", "conflict")

    fun problems(
        expectedAccountId: String,
        progress: Todo18MiniHomeLoadProgress,
        stages: List<Todo18MiniHomeLoadBoundaryStage>,
    ): List<String> {
        val problems = mutableListOf<String>()
        requiredKinds.forEach { kind ->
            if (stages.none { it.kind == kind }) problems += "missing-$kind"
        }
        if (
            stages.any { stage ->
                stage.kind in requiredKinds - LOAD_TERMINAL && stage.identity != expectedAccountId
            }
        ) {
            problems += "load-stage-account-mismatch"
        }
        if (
            stages.any { stage ->
                stage.kind == null ||
                    stage.identity == null ||
                    stage.loadId == null ||
                    stage.diagnosticOrder == null ||
                    (stage.kind == PUBLICATION_READ_ENTERED && stage.readId == null) ||
                    (stage.kind != PUBLICATION_READ_ENTERED && stage.readId != null) ||
                    (stage.kind == CACHE_APPLY_RETURNED && stage.cacheOutcome !in cacheOutcomes) ||
                    (stage.kind != CACHE_APPLY_RETURNED && stage.cacheOutcome != null)
            }
        ) {
            problems += "load-diagnostic-malformed"
        }

        val expected = progress.observations.map { it.boundaryStage(expectedAccountId) }
        if (
            expected.zip(stages).any { (observation, stage) ->
                observation.kind == stage.kind &&
                    observation.diagnosticOrder == stage.diagnosticOrder &&
                    observation.loadId != stage.loadId
            }
        ) {
            problems += "load-stage-load-mismatch"
        }
        if (stages != expected) problems += "load-diagnostic-boundary-mismatch"
        if (
            stages
                .filter { it.kind == LOAD_TERMINAL }
                .any {
                    it.identity !in terminalIdentities
                }
        ) {
            problems += "unclassified-load-terminal"
        }
        if (
            progress.loads.any { load ->
                load.activeStage != null || load.lastReachedStage?.startsWith("terminal-") != true
            }
        ) {
            problems += "unclosed-load"
        }
        problems += progress.recorderFailures.map { "recorder-failed:$it" }
        problems += progress.progressionProblems()
        return problems.distinct()
    }

    private fun Todo18MiniHomeLoadObservation.boundaryStage(
        expectedAccountId: String
    ): Todo18MiniHomeLoadBoundaryStage {
        val diagnosticKind =
            if (diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal) {
                LOAD_TERMINAL
            } else {
                receiptStage
            }
        val identity =
            when (val value = diagnostic) {
                is Todo18MiniHomeLoadDiagnostic.CacheApplyEntered -> value.accountId.value
                is Todo18MiniHomeLoadDiagnostic.CacheApplyReturned -> value.accountId.value
                Todo18MiniHomeLoadDiagnostic.Ready -> "Ready"
                Todo18MiniHomeLoadDiagnostic.Forbidden -> "Forbidden"
                Todo18MiniHomeLoadDiagnostic.Failed -> "Failed"
                Todo18MiniHomeLoadDiagnostic.Cancelled -> "Cancelled"
                else -> expectedAccountId
            }
        return Todo18MiniHomeLoadBoundaryStage(
            kind = diagnosticKind,
            identity = identity,
            loadId = loadId.value,
            readId = readId?.ordinal,
            diagnosticOrder = order,
            cacheOutcome =
                (diagnostic as? Todo18MiniHomeLoadDiagnostic.CacheApplyReturned)?.let {
                    if (it.current) "current" else "conflict"
                },
        )
    }
}
