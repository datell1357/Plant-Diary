package com.planterior.helper.minihome

internal data class Todo18MiniHomeLoadBoundaryStage(
    val kind: String?,
    val identity: String?,
    val loadId: Long?,
    val readId: Long?,
    val diagnosticOrder: Long?,
    val cacheOutcome: String?,
    val pendingReadLoadId: Long? = null,
    val pendingReadId: Long? = null,
    val pendingReadOutcome: String? = null,
)

internal object Todo18MiniHomeLoadReceiptReducer {
    private const val LOAD_TERMINAL = "load-terminal"
    private const val PUBLICATION_READ_ENTERED = "publication-read-entered"
    private const val PUBLICATION_READ_RETURNED = "publication-read-returned"
    private const val CACHE_APPLY_RETURNED = "cache-apply-returned"
    private const val PENDING_READ_ENTERED = "pending-read-entered"
    private const val PENDING_READ_RETURNED = "pending-read-returned"
    private const val PENDING_READ_THREW = "pending-read-threw"
    private const val PENDING_READ_CANCELLED = "pending-read-cancelled"
    private const val PENDING_READ_CARDINALITY_MISMATCH = "pending-read-cardinality-mismatch"
    private const val PENDING_READ_ORDER_MISMATCH = "pending-read-order-mismatch"
    private val pendingReadKinds =
        setOf(
            PENDING_READ_ENTERED,
            PENDING_READ_RETURNED,
            PENDING_READ_THREW,
            PENDING_READ_CANCELLED,
        )
    private val requiredKinds =
        listOf(
            "load-entered",
            "remote-load-entered",
            "remote-load-returned",
            "cache-apply-entered",
            CACHE_APPLY_RETURNED,
            PUBLICATION_READ_ENTERED,
            PUBLICATION_READ_RETURNED,
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
                stage.kind in (requiredKinds - LOAD_TERMINAL + pendingReadKinds) &&
                    stage.identity != expectedAccountId
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
                    (stage.kind in publicationReadKinds && stage.readId == null) ||
                    (stage.kind !in publicationReadKinds && stage.readId != null) ||
                    (stage.kind == CACHE_APPLY_RETURNED && stage.cacheOutcome !in cacheOutcomes) ||
                    (stage.kind != CACHE_APPLY_RETURNED && stage.cacheOutcome != null) ||
                    (stage.kind in pendingReadKinds &&
                        (stage.pendingReadLoadId == null || stage.pendingReadId == null)) ||
                    (stage.kind !in pendingReadKinds &&
                        (stage.pendingReadLoadId != null ||
                            stage.pendingReadId != null ||
                            stage.pendingReadOutcome != null)) ||
                    (stage.kind == PENDING_READ_ENTERED && stage.pendingReadOutcome != null) ||
                    (stage.kind == PENDING_READ_RETURNED &&
                        stage.pendingReadOutcome != "returned") ||
                    (stage.kind == PENDING_READ_THREW && stage.pendingReadOutcome != "threw") ||
                    (stage.kind == PENDING_READ_CANCELLED &&
                        stage.pendingReadOutcome != "cancelled")
            }
        ) {
            problems += "load-diagnostic-malformed"
        }
        val reads =
            stages.filter { it.kind in publicationReadKinds }.groupBy { it.loadId to it.readId }
        if (
            reads.values.any { read ->
                read.map { it.kind } != listOf(PUBLICATION_READ_ENTERED, PUBLICATION_READ_RETURNED)
            }
        ) {
            problems += "publication-read-identity-mismatch"
        }
        val pendingReads =
            stages
                .filter { it.kind in pendingReadKinds }
                .groupBy { it.loadId to (it.pendingReadLoadId to it.pendingReadId) }
        if (
            pendingReads.values.any { pendingRead ->
                pendingRead.map { it.kind } !in
                    listOf(
                        listOf(PENDING_READ_ENTERED, PENDING_READ_RETURNED),
                        listOf(PENDING_READ_ENTERED, PENDING_READ_THREW),
                        listOf(PENDING_READ_ENTERED, PENDING_READ_CANCELLED),
                    )
            }
        ) {
            problems += "pending-read-identity-mismatch"
        }
        val firstPublicationReadReturnedIndex = stages.indexOfFirst {
            it.kind == PUBLICATION_READ_RETURNED
        }
        val secondPublicationReadEnteredIndex =
            stages
                .withIndex()
                .filter { (_, stage) -> stage.kind == PUBLICATION_READ_ENTERED }
                .getOrNull(1)
                ?.index
        if (
            firstPublicationReadReturnedIndex < 0 ||
                secondPublicationReadEnteredIndex == null ||
                firstPublicationReadReturnedIndex >= secondPublicationReadEnteredIndex
        ) {
            problems += PENDING_READ_ORDER_MISMATCH
            problems += PENDING_READ_CARDINALITY_MISMATCH
        } else {
            val firstPublicationReadId = stages[firstPublicationReadReturnedIndex].readId
            val secondPublicationReadId = stages[secondPublicationReadEnteredIndex].readId
            if (
                firstPublicationReadId == null ||
                    secondPublicationReadId == null ||
                    secondPublicationReadId <= firstPublicationReadId
            ) {
                problems += PENDING_READ_ORDER_MISMATCH
            }
            val selectedPendingReads =
                pendingReads.values.filter { pendingRead ->
                    val enteredStage = pendingRead.firstOrNull { it.kind == PENDING_READ_ENTERED }
                    val terminalStage = pendingRead.firstOrNull {
                        it.kind in pendingReadTerminalKinds
                    }
                    val enteredIndex = stages.indexOfFirst { it === enteredStage }
                    val terminalIndex = stages.indexOfFirst { it === terminalStage }
                    enteredIndex > firstPublicationReadReturnedIndex &&
                        terminalIndex > enteredIndex &&
                        terminalIndex < secondPublicationReadEnteredIndex
                }
            if (pendingReads.isNotEmpty() && selectedPendingReads.isEmpty()) {
                problems += PENDING_READ_ORDER_MISMATCH
            }
            if (selectedPendingReads.size != 1) {
                problems += PENDING_READ_CARDINALITY_MISMATCH
            } else {
                val selectedPendingRead = selectedPendingReads.single()
                val selectedEnteredStage = selectedPendingRead.firstOrNull {
                    it.kind == PENDING_READ_ENTERED
                }
                val selectedTerminalStage = selectedPendingRead.firstOrNull {
                    it.kind in pendingReadTerminalKinds
                }
                val selectedEnteredIndex = stages.indexOfFirst { it === selectedEnteredStage }
                val selectedTerminalIndex = stages.indexOfFirst { it === selectedTerminalStage }
                if (
                    selectedPendingRead.size != 2 ||
                        selectedPendingRead.count { it.kind == PENDING_READ_ENTERED } != 1 ||
                        selectedPendingRead.count { it.kind in pendingReadTerminalKinds } != 1 ||
                        selectedEnteredIndex <= firstPublicationReadReturnedIndex ||
                        selectedTerminalIndex <= selectedEnteredIndex ||
                        selectedTerminalIndex >= secondPublicationReadEnteredIndex
                ) {
                    problems += PENDING_READ_CARDINALITY_MISMATCH
                }
            }
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

    private val publicationReadKinds = setOf(PUBLICATION_READ_ENTERED, PUBLICATION_READ_RETURNED)

    private val pendingReadTerminalKinds =
        setOf(PENDING_READ_RETURNED, PENDING_READ_THREW, PENDING_READ_CANCELLED)

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
            pendingReadLoadId = pendingReadId?.loadId?.value,
            pendingReadId = pendingReadId?.queryOrdinal,
            pendingReadOutcome =
                when (diagnostic) {
                    is Todo18MiniHomeLoadDiagnostic.PendingReadReturned -> "returned"
                    is Todo18MiniHomeLoadDiagnostic.PendingReadThrew -> "threw"
                    is Todo18MiniHomeLoadDiagnostic.PendingReadCancelled -> "cancelled"
                    is Todo18MiniHomeLoadDiagnostic.PendingReadEntered -> null
                    else -> null
                },
        )
    }
}
