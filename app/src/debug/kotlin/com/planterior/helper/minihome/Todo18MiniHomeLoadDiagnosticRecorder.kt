package com.planterior.helper.minihome

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.minihome.MiniHomeCacheTransactionDiagnosticObservation
import com.planterior.helper.feature.minihome.MiniHomePublicationReadTerminalOutcome
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal class Todo18MiniHomeLoad(
    val id: Todo18MiniHomeLoadId,
    private val recorder: Todo18MiniHomeLoadDiagnosticRecorder,
) {
    fun record(diagnostic: Todo18MiniHomeLoadDiagnostic): Todo18MiniHomeLoadObservation =
        recorder.record(id, diagnostic, null)

    fun recordPublicationRead(): Todo18MiniHomePublicationReadId =
        recorder.recordPublicationRead(id, null)

    fun recordPublicationRead(readId: Todo18MiniHomePublicationReadId) {
        require(readId.loadId == id)
        recorder.recordPublicationRead(id, readId)
    }
}

/** Instance-owned progress recorder shared by the debug repository and its deterministic seams. */
internal class Todo18MiniHomeLoadDiagnosticRecorder(
    private val observer: (Todo18MiniHomeLoadObservation) -> Unit
) {
    private data class LoadState(
        val observations: MutableList<Todo18MiniHomeLoadObservation> = mutableListOf(),
        var nextReadOrdinal: Long = 1L,
    )

    private sealed class LoadOutcome<out T> {
        data class Returned<T>(val value: T) : LoadOutcome<T>()

        data class Failed(val failure: Throwable) : LoadOutcome<Nothing>()
    }

    private val lock = Any()
    private var nextLoadId = 1L
    private val reached = mutableListOf<Todo18MiniHomeLoadObservation>()
    private val loads = linkedMapOf<Todo18MiniHomeLoadId, LoadState>()
    private val failures = mutableListOf<String>()
    private val violations = mutableListOf<Todo18MiniHomeLoadProgressionViolation>()
    private var cacheTransactionTraceExpected = false
    private var publicationReadTerminalExpected = false

    fun startLoad(): Todo18MiniHomeLoad =
        synchronized(lock) {
            val loadId = Todo18MiniHomeLoadId(nextLoadId++)
            loads[loadId] = LoadState()
            Todo18MiniHomeLoad(loadId, this)
        }

    suspend fun recordCurrent(diagnostic: Todo18MiniHomeLoadDiagnostic) {
        val load = checkNotNull(currentLoad(coroutineContext))
        if (diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadEntered) {
            load.recordPublicationRead()
        } else {
            load.record(diagnostic)
        }
    }

    suspend fun recordCurrentIfActive(diagnostic: Todo18MiniHomeLoadDiagnostic): Boolean {
        val load = currentLoad(coroutineContext) ?: return false
        if (diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadEntered) {
            load.recordPublicationRead()
        } else {
            load.record(diagnostic)
        }
        return true
    }

    suspend fun recordCurrentPublicationRead(
        diagnostic: Todo18MiniHomeLoadDiagnostic,
        ordinal: Long,
    ) {
        require(
            diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadEntered ||
                diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadReturned
        )
        val load = checkNotNull(currentLoad(coroutineContext))
        val readId = Todo18MiniHomePublicationReadId(load.id, ordinal)
        if (diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadEntered) {
            load.recordPublicationRead(readId)
        } else {
            record(load.id, diagnostic, readId)
        }
    }

    suspend fun recordCurrentCacheTransaction(
        observation: MiniHomeCacheTransactionDiagnosticObservation
    ) = recordCurrent(Todo18MiniHomeLoadDiagnostic.CacheTransaction(observation))

    suspend fun recordCurrentCacheTransactionIfActive(
        observation: MiniHomeCacheTransactionDiagnosticObservation
    ): Boolean = recordCurrentIfActive(Todo18MiniHomeLoadDiagnostic.CacheTransaction(observation))

    suspend fun recordCurrentPublicationReadTerminal(
        accountId: AccountId,
        readIdentity: Long,
        outcome: MiniHomePublicationReadTerminalOutcome,
    ) {
        val load = checkNotNull(currentLoad(coroutineContext))
        record(
            load.id,
            Todo18MiniHomeLoadDiagnostic.PublicationReadTerminal(
                accountId,
                readIdentity,
                outcome,
            ),
            Todo18MiniHomePublicationReadId(load.id, readIdentity),
        )
    }

    suspend fun recordCurrentPublicationReadTerminalIfActive(
        accountId: AccountId,
        readIdentity: Long,
        outcome: MiniHomePublicationReadTerminalOutcome,
    ): Boolean {
        val load = currentLoad(coroutineContext) ?: return false
        record(
            load.id,
            Todo18MiniHomeLoadDiagnostic.PublicationReadTerminal(
                accountId,
                readIdentity,
                outcome,
            ),
            Todo18MiniHomePublicationReadId(load.id, readIdentity),
        )
        return true
    }

    internal fun expectCacheTransactionTrace() =
        synchronized(lock) {
            cacheTransactionTraceExpected = true
        }

    internal fun expectPublicationReadTerminal() =
        synchronized(lock) {
            publicationReadTerminalExpected = true
        }

    internal fun recordDiagnosticFailure(failure: Throwable) =
        synchronized(lock) {
            failures += "injected-observer:${failure.javaClass.name}"
        }

    suspend fun <T> withLoad(load: Todo18MiniHomeLoad, block: suspend () -> T): T {
        val outcome =
            withContext(LoadContext(this, load)) {
                try {
                    LoadOutcome.Returned(block())
                } catch (failure: CancellationException) {
                    LoadOutcome.Failed(failure)
                } catch (failure: Throwable) {
                    LoadOutcome.Failed(failure)
                }
            }
        return when (outcome) {
            is LoadOutcome.Returned -> outcome.value
            is LoadOutcome.Failed -> throw outcome.failure
        }
    }

    private fun currentLoad(context: CoroutineContext): Todo18MiniHomeLoad? =
        context[LoadContext]?.takeIf { it.owner === this }?.load

    private class LoadContext(
        val owner: Todo18MiniHomeLoadDiagnosticRecorder,
        val load: Todo18MiniHomeLoad,
    ) : CoroutineContext.Element {
        override val key: CoroutineContext.Key<*>
            get() = Key

        companion object Key : CoroutineContext.Key<LoadContext>
    }

    fun snapshot(): Todo18MiniHomeLoadProgress =
        synchronized(lock) {
            val perLoad = loads.map { (loadId, state) ->
                val terminalReached =
                    state.observations.any {
                        it.diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
                    }
                Todo18MiniHomePerLoadProgress(
                    loadId = loadId,
                    activeStage =
                        state.observations.lastOrNull()?.receiptStage?.takeUnless {
                            terminalReached
                        },
                    lastReachedStage = state.observations.lastOrNull()?.receiptStage,
                    reachedStages =
                        state.observations.map(Todo18MiniHomeLoadObservation::receiptStage),
                    publicationReadIds =
                        state.observations.mapNotNull(Todo18MiniHomeLoadObservation::readId),
                    pendingReadIds =
                        state.observations.mapNotNull(Todo18MiniHomeLoadObservation::pendingReadId),
                )
            }
            val latestLoad = reached.lastOrNull()?.loadId
            Todo18MiniHomeLoadProgress(
                activeStage = perLoad.firstOrNull { it.loadId == latestLoad }?.activeStage,
                lastReachedStage = reached.lastOrNull()?.receiptStage,
                reachedStages = reached.map(Todo18MiniHomeLoadObservation::receiptStage),
                recorderFailures = failures.toList(),
                progressionViolations = violations.toList(),
                observations = reached.toList(),
                loads = perLoad,
                cacheTransactionTraceExpected = cacheTransactionTraceExpected,
                publicationReadTerminalExpected = publicationReadTerminalExpected,
            )
        }

    internal fun record(
        loadId: Todo18MiniHomeLoadId,
        diagnostic: Todo18MiniHomeLoadDiagnostic,
        readId: Todo18MiniHomePublicationReadId?,
    ): Todo18MiniHomeLoadObservation =
        synchronized(lock) {
            val state = loads.getValue(loadId)
            val observation =
                Todo18MiniHomeLoadObservation(
                    order = reached.size + 1L,
                    loadId = loadId,
                    readId = readId,
                    diagnostic = diagnostic,
                )
            progressionViolation(state, observation)?.let(violations::add)
            state.observations += observation
            reached += observation
            try {
                observer(observation)
            } catch (failure: AssertionError) {
                failures += "${observation.receiptStage}:${failure.javaClass.name}"
            } catch (failure: Exception) {
                failures += "${observation.receiptStage}:${failure.javaClass.name}"
            }
            observation
        }

    internal fun recordPublicationRead(
        loadId: Todo18MiniHomeLoadId,
        explicitReadId: Todo18MiniHomePublicationReadId?,
    ): Todo18MiniHomePublicationReadId =
        synchronized(lock) {
            val state = loads.getValue(loadId)
            val readId =
                explicitReadId ?: Todo18MiniHomePublicationReadId(loadId, state.nextReadOrdinal++)
            if (explicitReadId != null) {
                state.nextReadOrdinal = maxOf(state.nextReadOrdinal, explicitReadId.ordinal + 1L)
            }
            record(loadId, Todo18MiniHomeLoadDiagnostic.PublicationReadEntered, readId)
            readId
        }

    private fun progressionViolation(
        state: LoadState,
        observation: Todo18MiniHomeLoadObservation,
    ): Todo18MiniHomeLoadProgressionViolation? {
        val previous = state.observations.lastOrNull()
        val terminalReached =
            state.observations.any {
                it.diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
            }
        val duplicate =
            if (observation.pendingReadId != null) {
                state.observations.any {
                    it.pendingReadId == observation.pendingReadId &&
                        it.receiptStage == observation.receiptStage
                }
            } else if (observation.readId == null) {
                state.observations.any { it.receiptStage == observation.receiptStage }
            } else {
                state.observations.any {
                    it.readId == observation.readId && it.receiptStage == observation.receiptStage
                }
            }
        val kind =
            when {
                terminalReached &&
                    observation.diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal ->
                    Todo18MiniHomeLoadViolationKind.MULTIPLE_TERMINAL
                terminalReached -> Todo18MiniHomeLoadViolationKind.STAGE_AFTER_TERMINAL
                duplicate -> Todo18MiniHomeLoadViolationKind.DUPLICATE_STAGE
                !legalTransition(previous?.diagnostic, observation.diagnostic) ->
                    Todo18MiniHomeLoadViolationKind.OUT_OF_ORDER_STAGE
                else -> return null
            }
        return Todo18MiniHomeLoadProgressionViolation(
            kind = kind,
            loadId = observation.loadId,
            readId = observation.readId,
            observedStage = observation.receiptStage,
            previousStage = previous?.receiptStage,
        )
    }

    private fun legalTransition(
        previous: Todo18MiniHomeLoadDiagnostic?,
        diagnostic: Todo18MiniHomeLoadDiagnostic,
    ): Boolean =
        when (previous) {
            null -> diagnostic == Todo18MiniHomeLoadDiagnostic.LoadEntered
            Todo18MiniHomeLoadDiagnostic.LoadEntered ->
                diagnostic is Todo18MiniHomeLoadDiagnostic.PendingReadEntered ||
                    diagnostic == Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
            Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered ->
                diagnostic == Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
            Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned ->
                diagnostic is Todo18MiniHomeLoadDiagnostic.CacheApplyEntered ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.PendingReadEntered ||
                    diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadEntered ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
            is Todo18MiniHomeLoadDiagnostic.CacheApplyEntered ->
                diagnostic is Todo18MiniHomeLoadDiagnostic.CacheTransaction ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.CacheApplyReturned ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
            is Todo18MiniHomeLoadDiagnostic.CacheTransaction ->
                when (previous.receiptStage) {
                    "cache-transaction-call-entered" ->
                        diagnostic is Todo18MiniHomeLoadDiagnostic.CacheTransaction &&
                            diagnostic.receiptStage == "cache-transaction-body-entered"
                    "cache-transaction-body-entered" ->
                        diagnostic is Todo18MiniHomeLoadDiagnostic.CacheTransaction &&
                            diagnostic.receiptStage in
                                setOf(
                                    "cache-layout-apply",
                                    "cache-transaction-threw",
                                    "cache-transaction-cancelled",
                                    "cache-terminal-conflict",
                                )
                    "cache-layout-apply" ->
                        diagnostic is Todo18MiniHomeLoadDiagnostic.CacheTransaction &&
                            diagnostic.receiptStage in
                                setOf(
                                    "cache-inventory-apply",
                                    "cache-terminal-conflict",
                                    "cache-transaction-threw",
                                    "cache-transaction-cancelled",
                                )
                    "cache-inventory-apply" ->
                        diagnostic is Todo18MiniHomeLoadDiagnostic.CacheTransaction &&
                            diagnostic.receiptStage in
                                setOf(
                                    "cache-current-snapshot",
                                    "cache-terminal-conflict",
                                    "cache-transaction-threw",
                                    "cache-transaction-cancelled",
                                )
                    "cache-current-snapshot" ->
                        diagnostic is Todo18MiniHomeLoadDiagnostic.CacheTransaction &&
                            diagnostic.receiptStage in
                                setOf(
                                    "cache-verified-inventory-decode",
                                    "cache-terminal-conflict",
                                    "cache-transaction-threw",
                                    "cache-transaction-cancelled",
                                )
                    "cache-verified-inventory-decode" ->
                        diagnostic is Todo18MiniHomeLoadDiagnostic.CacheTransaction &&
                            diagnostic.receiptStage in
                                setOf(
                                    "cache-transaction-body-returned",
                                    "cache-transaction-returned",
                                    "cache-transaction-threw",
                                    "cache-transaction-cancelled",
                                )
                    "cache-transaction-body-returned" ->
                        diagnostic is Todo18MiniHomeLoadDiagnostic.CacheTransaction &&
                            diagnostic.receiptStage in
                                setOf(
                                    "cache-transaction-scope-returned",
                                    "cache-transaction-threw",
                                    "cache-transaction-cancelled",
                                )
                    "cache-transaction-scope-returned" ->
                        diagnostic is Todo18MiniHomeLoadDiagnostic.CacheTransaction &&
                            diagnostic.receiptStage in
                                setOf(
                                    "cache-transaction-returned",
                                    "cache-transaction-threw",
                                    "cache-transaction-cancelled",
                                )
                    "cache-terminal-conflict" ->
                        diagnostic is Todo18MiniHomeLoadDiagnostic.CacheTransaction &&
                            diagnostic.receiptStage == "cache-transaction-returned"
                    "cache-transaction-returned" ->
                        diagnostic is Todo18MiniHomeLoadDiagnostic.CacheApplyReturned
                    "cache-transaction-threw",
                    "cache-transaction-cancelled" ->
                        diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
                    else -> false
                }
            is Todo18MiniHomeLoadDiagnostic.CacheApplyReturned ->
                diagnostic is Todo18MiniHomeLoadDiagnostic.PendingReadEntered ||
                    diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadEntered ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
            is Todo18MiniHomeLoadDiagnostic.PendingReadEntered ->
                diagnostic is Todo18MiniHomeLoadDiagnostic.PendingReadReturned ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.PendingReadThrew ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.PendingReadCancelled
            is Todo18MiniHomeLoadDiagnostic.PendingReadReturned,
            is Todo18MiniHomeLoadDiagnostic.PendingReadThrew,
            is Todo18MiniHomeLoadDiagnostic.PendingReadCancelled ->
                diagnostic is Todo18MiniHomeLoadDiagnostic.PendingReadEntered ||
                    diagnostic == Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered ||
                    diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadEntered ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
            Todo18MiniHomeLoadDiagnostic.PublicationReadEntered ->
                diagnostic is Todo18MiniHomeLoadDiagnostic.PublicationReadTerminal ||
                    diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadReturned ||
                    diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadEntered ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
            is Todo18MiniHomeLoadDiagnostic.PublicationReadTerminal ->
                diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadReturned ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
            Todo18MiniHomeLoadDiagnostic.PublicationReadReturned ->
                diagnostic is Todo18MiniHomeLoadDiagnostic.PendingReadEntered ||
                    diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadEntered ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
            is Todo18MiniHomeLoadDiagnostic.Terminal -> false
        }
}
