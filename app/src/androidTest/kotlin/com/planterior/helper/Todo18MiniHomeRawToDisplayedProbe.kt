package com.planterior.helper

import com.planterior.helper.diagnostic.Todo18ExactEventObserver
import java.util.concurrent.TimeUnit

/** Exact raw-to-frame synchronization for MiniHome transitions driven by asynchronous work. */
internal class Todo18MiniHomeRawToDisplayedProbe(
    private val runtime: Todo18IntegratedRuntimeRule,
    private val compose: Todo18ComposeRule,
) {
    fun await(
        matches: (Todo18MiniHomeStateEvent) -> Boolean,
        trigger: () -> Unit,
        observer: Todo18ExactEventObserver?,
    ): Todo18MiniHomeStateEvent {
        val sink = runtime.renderedStateSink
        val expectedAccount = runtime.boundary.accountId
        val rawFloor = sink.currentRawMiniHomeState()?.sequence ?: 0L
        val displayedFloor = sink.currentDisplayedMiniHomeState()?.sequence ?: 0L
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EVENT_TIMEOUT_MILLIS)
        val displayed =
            subscription(
                matches = { event ->
                    event.sequence > displayedFloor &&
                        event.state.owner == expectedAccount &&
                        matches(event)
                },
                subscribe = sink::subscribeToDisplayedMiniHomeStates,
                current = sink::currentDisplayedMiniHomeState,
                observer = observer,
            )
        return displayed.use { displayedSubscription ->
            val raw =
                subscription(
                    matches = { event ->
                        event.sequence > rawFloor &&
                            event.state.owner == expectedAccount &&
                            matches(event)
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
                            rawSubscription.await(
                                remainingNanos(deadlineNanos),
                                TimeUnit.NANOSECONDS,
                                "Todo18 MiniHome raw state",
                            )
                        },
                        settle = compose::waitForIdle,
                        awaitRendered = {
                            displayedSubscription.await(
                                remainingNanos(deadlineNanos),
                                TimeUnit.NANOSECONDS,
                                "Todo18 MiniHome displayed state",
                            )
                        },
                    )
                requireMatchingRenderedState(rawEvent.state, displayedEvent.state)
                displayedEvent
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

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private companion object {
        const val EVENT_TIMEOUT_MILLIS = 30_000L
    }
}
