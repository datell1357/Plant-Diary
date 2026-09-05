package com.planterior.helper

import com.planterior.helper.diagnostic.Todo18ExactEventObserver
import com.planterior.helper.feature.minihome.MiniHomeUiState
import java.util.concurrent.TimeUnit

/** Raw-to-frame bridge for initial MiniHome Viewing without polling product state. */
internal class Todo18InitialMiniHomeViewingProbe(
    private val runtime: Todo18IntegratedRuntimeRule,
    private val compose: Todo18ComposeRule,
) {
    fun await(
        trigger: () -> Unit,
        observer: Todo18ExactEventObserver?,
    ): Todo18MiniHomeStateEvent {
        val sink = runtime.renderedStateSink
        val expectedAccount = runtime.boundary.accountId
        val rawFloor = sink.currentRawMiniHomeState()?.sequence ?: 0L
        val displayedFloor = sink.currentDisplayedMiniHomeState()?.sequence ?: 0L
        val loadIdFloor =
            runtime.miniHomeLoadDiagnostics.snapshot().loads.maxOfOrNull { it.loadId.value } ?: 0L
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EVENT_TIMEOUT_MILLIS)
        val displayed =
            subscription(
                matches = { event ->
                    val viewing = event.state as? MiniHomeUiState.Viewing
                    event.sequence > displayedFloor &&
                        viewing?.owner == expectedAccount &&
                        (event.loadIdentity?.value ?: 0L) > loadIdFloor
                },
                subscribe = sink::subscribeToDisplayedMiniHomeStates,
                current = sink::currentDisplayedMiniHomeState,
                observer = observer,
            )
        displayed.use { displayedSubscription ->
            val raw =
                subscription(
                    matches = { event ->
                        val viewing = event.state as? MiniHomeUiState.Viewing
                        event.sequence > rawFloor &&
                            viewing?.owner == expectedAccount &&
                            (event.loadIdentity?.value ?: 0L) > loadIdFloor
                    },
                    subscribe = sink::subscribeToRawMiniHomeStates,
                    current = sink::currentRawMiniHomeState,
                )
            raw.use { rawSubscription ->
                rawSubscription.arm()
                displayedSubscription.arm()
                val (rawEvent, displayedEvent) =
                    triggerAwaitSettleAndAwait(
                        trigger = {
                            rawSubscription.trigger {
                                displayedSubscription.trigger(trigger)
                            }
                        },
                        awaitUpstream = {
                            rawSubscription
                                .await(
                                    remainingNanos(deadlineNanos),
                                    TimeUnit.NANOSECONDS,
                                    "Todo18 MiniHome raw initial Viewing",
                                )
                                .also { validateRaw(it, loadIdFloor) }
                        },
                        settle = compose::waitForIdle,
                        awaitRendered = {
                            displayedSubscription.await(
                                remainingNanos(deadlineNanos),
                                TimeUnit.NANOSECONDS,
                                "Todo18 MiniHome displayed initial Viewing",
                            )
                        },
                    )
                require(displayedEvent.loadIdentity == rawEvent.loadIdentity) {
                    "MiniHome displayed Viewing did not match raw load " +
                        requireNotNull(rawEvent.loadIdentity).value
                }
                return displayedEvent
            }
        }
    }

    private fun subscription(
        matches: (Todo18MiniHomeStateEvent) -> Boolean,
        subscribe: ((Todo18MiniHomeStateEvent) -> Unit) -> AutoCloseable,
        current: () -> Todo18MiniHomeStateEvent?,
        observer: Todo18ExactEventObserver? = null,
    ) =
        ExactEventSubscription(
            matches = matches,
            subscribe = { receiver ->
                lateinit var closeable: AutoCloseable
                LeasedExactEventRegistration(
                    receiver = receiver,
                    register = { dispatch ->
                        closeable = subscribe(dispatch)
                        current()?.let(dispatch)
                    },
                    unregister = { closeable.close() },
                )
            },
            diagnosticObserver = observer,
            diagnosticSequence = Todo18MiniHomeStateEvent::sequence,
        )

    private fun validateRaw(
        raw: Todo18MiniHomeStateEvent,
        loadIdFloor: Long,
    ) {
        val rawLoadId = requireNotNull(raw.loadIdentity).value
        val progress = runtime.miniHomeLoadDiagnostics.snapshot()
        require(progress.valid) { "MiniHome load diagnostics were invalid for raw Viewing" }
        val load =
            progress.loads.singleOrNull {
                it.loadId.value == rawLoadId && it.loadId.value > loadIdFloor
            } ?: error("MiniHome raw Viewing did not identify one new load")
        require(load.lastReachedStage == "terminal-ready") {
            "MiniHome raw Viewing preceded terminal-ready: ${load.lastReachedStage}"
        }
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private companion object {
        const val EVENT_TIMEOUT_MILLIS = 30_000L
    }
}
