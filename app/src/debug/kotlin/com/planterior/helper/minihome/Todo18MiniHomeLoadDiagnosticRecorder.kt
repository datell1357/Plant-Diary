package com.planterior.helper.minihome

import java.util.ArrayDeque
import java.util.IdentityHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job

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

    private val lock = Any()
    private var nextLoadId = 1L
    private val reached = mutableListOf<Todo18MiniHomeLoadObservation>()
    private val loads = linkedMapOf<Todo18MiniHomeLoadId, LoadState>()
    private val activeLoads = IdentityHashMap<Job, ArrayDeque<Todo18MiniHomeLoad>>()
    private val failures = mutableListOf<String>()
    private val violations = mutableListOf<Todo18MiniHomeLoadProgressionViolation>()

    fun startLoad(): Todo18MiniHomeLoad =
        synchronized(lock) {
            val loadId = Todo18MiniHomeLoadId(nextLoadId++)
            loads[loadId] = LoadState()
            Todo18MiniHomeLoad(loadId, this)
        }

    suspend fun recordCurrent(diagnostic: Todo18MiniHomeLoadDiagnostic) {
        val job = checkNotNull(coroutineContext[Job])
        val load = synchronized(lock) { checkNotNull(activeLoads[job]?.peekLast()) }
        if (diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadEntered) {
            load.recordPublicationRead()
        } else {
            load.record(diagnostic)
        }
    }

    suspend fun <T> withLoad(load: Todo18MiniHomeLoad, block: suspend () -> T): T {
        val job = checkNotNull(coroutineContext[Job])
        synchronized(lock) { activeLoads.getOrPut(job, ::ArrayDeque).addLast(load) }
        return try {
            block()
        } finally {
            synchronized(lock) {
                val stack = activeLoads.getValue(job)
                check(stack.removeLast() === load)
                if (stack.isEmpty()) activeLoads.remove(job)
            }
        }
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
            if (observation.readId == null) {
                state.observations.any { it.diagnostic == observation.diagnostic }
            } else {
                state.observations.any { it.readId == observation.readId }
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
                diagnostic == Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
            Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered ->
                diagnostic == Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
            Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned ->
                diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadEntered ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
            Todo18MiniHomeLoadDiagnostic.PublicationReadEntered ->
                diagnostic == Todo18MiniHomeLoadDiagnostic.PublicationReadEntered ||
                    diagnostic is Todo18MiniHomeLoadDiagnostic.Terminal
            is Todo18MiniHomeLoadDiagnostic.Terminal -> false
        }
}
