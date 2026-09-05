package com.planterior.helper

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.share.MiniHomeShareLinkState
import com.planterior.helper.feature.share.MiniHomeShareRenderState
import com.planterior.helper.feature.share.MiniHomeShareUiState
import java.util.concurrent.TimeUnit

/** Pre-armed raw-to-displayed synchronization for the MiniHome share screen. */
internal class Todo18ShareViewingProbe(
    private val runtime: Todo18IntegratedRuntimeRule,
    private val compose: Todo18ComposeRule,
) {
    fun awaitReady(trigger: () -> Unit): Todo18MiniHomeShareStateEvent {
        val sink = runtime.renderedStateSink
        val account = runtime.boundary.accountId
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)
        val rawFloor = sink.currentRawMiniHomeShareState()?.sequence ?: 0L
        val displayedFloor = sink.currentMiniHomeShareState()?.sequence ?: 0L
        val raw =
            subscription(
                floor = rawFloor,
                account = account,
                render = MiniHomeShareRenderState.Rendering,
                subscribe = sink::subscribeToRawMiniHomeShareStates,
                current = sink::currentRawMiniHomeShareState,
            )
        raw.use { rawSubscription ->
            val displayed =
                subscription(
                    floor = displayedFloor,
                    account = account,
                    render = MiniHomeShareRenderState.Ready,
                    subscribe = sink::subscribeToMiniHomeShareStates,
                    current = sink::currentMiniHomeShareState,
                )
            displayed.use { displayedSubscription ->
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
                                remaining(deadlineNanos),
                                TimeUnit.NANOSECONDS,
                                "Todo18 MiniHome share raw Ready",
                            )
                        },
                        settle = compose::waitForIdle,
                        awaitRendered = {
                            displayedSubscription.await(
                                remaining(deadlineNanos),
                                TimeUnit.NANOSECONDS,
                                "Todo18 MiniHome share displayed Ready",
                            )
                        },
                    )
                val rawReady = rawEvent.state as MiniHomeShareUiState.Ready
                val displayedReady = displayedEvent.state as MiniHomeShareUiState.Ready
                require(rawReady.copy(render = MiniHomeShareRenderState.Ready) == displayedReady) {
                    "MiniHome share displayed state did not equal raw state"
                }
                return displayedEvent
            }
        }
    }

    private fun subscription(
        floor: Long,
        account: AccountId,
        render: MiniHomeShareRenderState,
        subscribe: ((Todo18MiniHomeShareStateEvent) -> Unit) -> AutoCloseable,
        current: () -> Todo18MiniHomeShareStateEvent?,
    ): ExactEventSubscription<Todo18MiniHomeShareStateEvent> {
        return ExactEventSubscription(
            matches = { event ->
                val state = event.state
                event.sequence > floor &&
                    state.owner == account &&
                    state is MiniHomeShareUiState.Ready &&
                    state.render == render &&
                    state.link is MiniHomeShareLinkState.Idle
            },
            subscribe = { receiver ->
                lateinit var closeable: AutoCloseable
                LeasedExactEventRegistration(
                    receiver = receiver,
                    register = { dispatch ->
                        closeable = subscribe(dispatch)
                        current()?.let(dispatch)
                    },
                    unregister = { _ -> closeable.close() },
                )
            },
            diagnosticSequence = Todo18MiniHomeShareStateEvent::sequence,
            acceptRegistrationReplay = false,
        )
    }

    private fun remaining(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
    }
}
