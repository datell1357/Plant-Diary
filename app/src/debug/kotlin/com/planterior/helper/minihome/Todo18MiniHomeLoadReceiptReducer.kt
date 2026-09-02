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
    val operationId: String? = null,
    val cacheTransactionResult: String? = null,
    val cacheTransactionFailureClass: String? = null,
    val cacheTransactionFailureMessage: String? = null,
    val publicationReadTerminalOutcome: String? = null,
    val publicationReadTerminalFailureClass: String? = null,
    val publicationReadTerminalFailureMessage: String? = null,
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
    private const val CACHE_TRANSACTION_CALL_ENTERED = "cache-transaction-call-entered"
    private const val CACHE_TRANSACTION_BODY_ENTERED = "cache-transaction-body-entered"
    private const val CACHE_TRANSACTION_BODY_RETURNED = "cache-transaction-body-returned"
    private const val CACHE_TRANSACTION_SCOPE_RETURNED = "cache-transaction-scope-returned"
    private const val CACHE_TRANSACTION_RETURNED = "cache-transaction-returned"
    private const val CACHE_TRANSACTION_THREW = "cache-transaction-threw"
    private const val CACHE_TRANSACTION_CANCELLED = "cache-transaction-cancelled"
    private const val PUBLICATION_READ_TERMINAL_RETURNED = "publication-read-terminal-returned"
    private const val PUBLICATION_READ_TERMINAL_THREW = "publication-read-terminal-threw"
    private const val PUBLICATION_READ_TERMINAL_CANCELLED = "publication-read-terminal-cancelled"
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
    private val cacheTransactionKinds =
        setOf(
            CACHE_TRANSACTION_CALL_ENTERED,
            CACHE_TRANSACTION_BODY_ENTERED,
            "cache-layout-apply",
            "cache-inventory-apply",
            "cache-current-snapshot",
            "cache-verified-inventory-decode",
            CACHE_TRANSACTION_BODY_RETURNED,
            CACHE_TRANSACTION_SCOPE_RETURNED,
            "cache-terminal-conflict",
            CACHE_TRANSACTION_RETURNED,
            CACHE_TRANSACTION_THREW,
            CACHE_TRANSACTION_CANCELLED,
        )
    private val cacheTransactionTerminalKinds =
        setOf(CACHE_TRANSACTION_RETURNED, CACHE_TRANSACTION_THREW, CACHE_TRANSACTION_CANCELLED)
    private val publicationReadTerminalKinds =
        setOf(
            PUBLICATION_READ_TERMINAL_RETURNED,
            PUBLICATION_READ_TERMINAL_THREW,
            PUBLICATION_READ_TERMINAL_CANCELLED,
        )

    fun problems(
        expectedAccountId: String,
        progress: Todo18MiniHomeLoadProgress,
        stages: List<Todo18MiniHomeLoadBoundaryStage>,
    ): List<String> {
        val problems = mutableListOf<String>()
        requiredKinds.forEach { kind ->
            if (stages.none { it.kind == kind }) problems += "missing-$kind"
        }
        if (progress.cacheTransactionTraceExpected) {
            val transactions = stages.filter { it.kind in cacheTransactionKinds }
            if (transactions.isEmpty()) {
                problems += "missing-$CACHE_TRANSACTION_CALL_ENTERED"
                problems += "missing-$CACHE_TRANSACTION_BODY_ENTERED"
                problems += "missing-cache-transaction-terminal"
            }
            transactions
                .groupBy { it.loadId }
                .values
                .forEach { transaction ->
                    if (transaction.none { it.kind == CACHE_TRANSACTION_CALL_ENTERED }) {
                        problems += "missing-$CACHE_TRANSACTION_CALL_ENTERED"
                    }
                    if (transaction.none { it.kind == CACHE_TRANSACTION_BODY_ENTERED }) {
                        problems += "missing-$CACHE_TRANSACTION_BODY_ENTERED"
                    }
                    val terminals = transaction.filter { it.kind in cacheTransactionTerminalKinds }
                    if (terminals.isEmpty()) problems += "missing-cache-transaction-terminal"
                    if (terminals.size > 1) {
                        problems += "cache-transaction-terminal-cardinality-mismatch"
                    }
                    if (
                        transaction.count { it.kind == CACHE_TRANSACTION_CALL_ENTERED } > 1 ||
                            transaction.count { it.kind == CACHE_TRANSACTION_BODY_ENTERED } > 1
                    ) {
                        problems += "cache-transaction-entry-cardinality-mismatch"
                    }
                    val firstCall = transaction.indexOfFirst {
                        it.kind == CACHE_TRANSACTION_CALL_ENTERED
                    }
                    val firstBody = transaction.indexOfFirst {
                        it.kind == CACHE_TRANSACTION_BODY_ENTERED
                    }
                    if (firstCall >= 0 && firstBody >= 0 && firstBody <= firstCall) {
                        problems += "cache-transaction-entry-order-mismatch"
                    }
                    if (transaction.mapNotNull { it.operationId }.distinct().size > 1) {
                        problems += "cache-transaction-operation-identity-mismatch"
                    }
                    terminals.singleOrNull()?.let { terminal ->
                        val bodyReturned = transaction.filter {
                            it.kind == CACHE_TRANSACTION_BODY_RETURNED
                        }
                        val scopeReturned = transaction.filter {
                            it.kind == CACHE_TRANSACTION_SCOPE_RETURNED
                        }
                        val decodeIndex = transaction.indexOfFirst {
                            it.kind == "cache-verified-inventory-decode"
                        }
                        val bodyReturnedIndex = transaction.indexOfFirst {
                            it.kind == CACHE_TRANSACTION_BODY_RETURNED
                        }
                        val scopeReturnedIndex = transaction.indexOfFirst {
                            it.kind == CACHE_TRANSACTION_SCOPE_RETURNED
                        }
                        val terminalIndex = transaction.indexOf(terminal)
                        val current =
                            terminal.kind == CACHE_TRANSACTION_RETURNED &&
                                terminal.cacheTransactionResult == "current"
                        if (current) {
                            if (bodyReturned.size != 1 || scopeReturned.size != 1) {
                                problems += "cache-transaction-post-decode-cardinality-mismatch"
                            }
                            if (
                                decodeIndex < 0 ||
                                    bodyReturnedIndex <= decodeIndex ||
                                    scopeReturnedIndex <= bodyReturnedIndex ||
                                    terminalIndex <= scopeReturnedIndex
                            ) {
                                problems += "cache-transaction-post-decode-order-mismatch"
                            }
                        } else if (bodyReturned.isNotEmpty() || scopeReturned.isNotEmpty()) {
                            problems += "cache-transaction-post-decode-stage-forbidden"
                        }
                        when (terminal.kind) {
                            CACHE_TRANSACTION_RETURNED -> {
                                if (terminal.cacheTransactionResult !in cacheOutcomes) {
                                    problems += "cache-transaction-result-mismatch"
                                }
                            }
                            CACHE_TRANSACTION_THREW,
                            CACHE_TRANSACTION_CANCELLED -> {
                                if (terminal.cacheTransactionFailureClass == null) {
                                    problems += "cache-transaction-failure-missing"
                                }
                            }
                        }
                    }
                }
        }
        if (progress.publicationReadTerminalExpected) {
            stages
                .filter { it.kind == PUBLICATION_READ_ENTERED }
                .forEach { entered ->
                    val terminalCount = stages.count {
                        it.kind in publicationReadTerminalKinds &&
                            it.loadId == entered.loadId &&
                            it.readId == entered.readId
                    }
                    when (terminalCount) {
                        0 -> problems += "missing-publication-read-terminal"
                        1 -> Unit
                        else -> problems += "publication-read-terminal-cardinality-mismatch"
                    }
                }
            stages
                .filter { it.kind in publicationReadTerminalKinds }
                .forEach { terminal ->
                    val entered = stages.filter {
                        it.kind == PUBLICATION_READ_ENTERED &&
                            it.loadId == terminal.loadId &&
                            it.readId == terminal.readId
                    }
                    if (entered.size != 1) {
                        problems += "publication-read-terminal-entry-mismatch"
                    } else if (stages.indexOf(terminal) <= stages.indexOf(entered.single())) {
                        problems += "publication-read-terminal-order-mismatch"
                    }
                    val returned = stages.filter {
                        it.kind == PUBLICATION_READ_RETURNED &&
                            it.loadId == terminal.loadId &&
                            it.readId == terminal.readId
                    }
                    if (terminal.kind == PUBLICATION_READ_TERMINAL_RETURNED) {
                        if (returned.size != 1) {
                            problems += "publication-read-terminal-return-mismatch"
                        } else if (stages.indexOf(returned.single()) <= stages.indexOf(terminal)) {
                            problems += "publication-read-terminal-order-mismatch"
                        }
                    } else if (returned.isNotEmpty()) {
                        problems += "publication-read-terminal-return-mismatch"
                    }
                }
        }
        if (
            stages.any { stage ->
                stage.kind in
                    (requiredKinds - LOAD_TERMINAL +
                        pendingReadKinds +
                        cacheTransactionKinds +
                        publicationReadTerminalKinds) && stage.identity != expectedAccountId
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
                    (stage.kind in (publicationReadKinds + publicationReadTerminalKinds) &&
                        stage.readId == null) ||
                    (stage.kind !in (publicationReadKinds + publicationReadTerminalKinds) &&
                        stage.readId != null) ||
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
                        stage.pendingReadOutcome != "cancelled") ||
                    (stage.kind !in cacheTransactionKinds && stage.operationId != null) ||
                    (stage.kind !in cacheTransactionKinds &&
                        (stage.cacheTransactionResult != null ||
                            stage.cacheTransactionFailureClass != null ||
                            stage.cacheTransactionFailureMessage != null)) ||
                    (stage.kind in setOf(CACHE_TRANSACTION_RETURNED, "cache-terminal-conflict") &&
                        stage.cacheTransactionResult !in cacheOutcomes) ||
                    (stage.kind !in setOf(CACHE_TRANSACTION_RETURNED, "cache-terminal-conflict") &&
                        stage.cacheTransactionResult != null) ||
                    (stage.kind !in setOf(CACHE_TRANSACTION_THREW, CACHE_TRANSACTION_CANCELLED) &&
                        (stage.cacheTransactionFailureClass != null ||
                            stage.cacheTransactionFailureMessage != null)) ||
                    (stage.kind in setOf(CACHE_TRANSACTION_THREW, CACHE_TRANSACTION_CANCELLED) &&
                        stage.cacheTransactionFailureClass == null) ||
                    (stage.kind !in publicationReadTerminalKinds &&
                        (stage.publicationReadTerminalOutcome != null ||
                            stage.publicationReadTerminalFailureClass != null ||
                            stage.publicationReadTerminalFailureMessage != null)) ||
                    (stage.kind == PUBLICATION_READ_TERMINAL_RETURNED &&
                        stage.publicationReadTerminalOutcome != "returned") ||
                    (stage.kind == PUBLICATION_READ_TERMINAL_THREW &&
                        stage.publicationReadTerminalOutcome != "threw") ||
                    (stage.kind == PUBLICATION_READ_TERMINAL_CANCELLED &&
                        stage.publicationReadTerminalOutcome != "cancelled") ||
                    (stage.kind in
                        setOf(
                            PUBLICATION_READ_TERMINAL_THREW,
                            PUBLICATION_READ_TERMINAL_CANCELLED,
                        ) && stage.publicationReadTerminalFailureClass == null)
            }
        ) {
            problems += "load-diagnostic-malformed"
        }
        val reads =
            stages.filter { it.kind in publicationReadKinds }.groupBy { it.loadId to it.readId }
        if (
            reads.any { (identity, read) ->
                val terminal = stages.singleOrNull {
                    it.kind in publicationReadTerminalKinds &&
                        it.loadId == identity.first &&
                        it.readId == identity.second
                }
                read.map { it.kind } !=
                    when (terminal?.kind) {
                        PUBLICATION_READ_TERMINAL_THREW,
                        PUBLICATION_READ_TERMINAL_CANCELLED -> listOf(PUBLICATION_READ_ENTERED)
                        else -> listOf(PUBLICATION_READ_ENTERED, PUBLICATION_READ_RETURNED)
                    }
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
            operationId =
                (diagnostic as? Todo18MiniHomeLoadDiagnostic.CacheTransaction)
                    ?.observation
                    ?.operationId
                    ?.value,
            cacheTransactionResult =
                (diagnostic as? Todo18MiniHomeLoadDiagnostic.CacheTransaction)
                    ?.observation
                    ?.result
                    ?.name
                    ?.lowercase(),
            cacheTransactionFailureClass =
                (diagnostic as? Todo18MiniHomeLoadDiagnostic.CacheTransaction)
                    ?.observation
                    ?.failure
                    ?.javaClass
                    ?.name,
            cacheTransactionFailureMessage =
                (diagnostic as? Todo18MiniHomeLoadDiagnostic.CacheTransaction)
                    ?.observation
                    ?.failure
                    ?.message,
            publicationReadTerminalOutcome =
                (diagnostic as? Todo18MiniHomeLoadDiagnostic.PublicationReadTerminal)?.let {
                    terminal ->
                    when (terminal.outcome) {
                        com.planterior.helper.feature.minihome
                            .MiniHomePublicationReadTerminalOutcome
                            .Returned -> "returned"
                        is com.planterior.helper.feature.minihome.MiniHomePublicationReadTerminalOutcome.Threw ->
                            "threw"
                        is com.planterior.helper.feature.minihome.MiniHomePublicationReadTerminalOutcome.Cancelled ->
                            "cancelled"
                    }
                },
            publicationReadTerminalFailureClass =
                (diagnostic as? Todo18MiniHomeLoadDiagnostic.PublicationReadTerminal)?.let {
                    terminal ->
                    when (val outcome = terminal.outcome) {
                        is com.planterior.helper.feature.minihome.MiniHomePublicationReadTerminalOutcome.Threw ->
                            outcome.failure.javaClass.name
                        is com.planterior.helper.feature.minihome.MiniHomePublicationReadTerminalOutcome.Cancelled ->
                            outcome.failure.javaClass.name
                        else -> null
                    }
                },
            publicationReadTerminalFailureMessage =
                (diagnostic as? Todo18MiniHomeLoadDiagnostic.PublicationReadTerminal)?.let {
                    terminal ->
                    when (val outcome = terminal.outcome) {
                        is com.planterior.helper.feature.minihome.MiniHomePublicationReadTerminalOutcome.Threw ->
                            outcome.failure.message
                        is com.planterior.helper.feature.minihome.MiniHomePublicationReadTerminalOutcome.Cancelled ->
                            outcome.failure.message
                        else -> null
                    }
                },
        )
    }
}
