package com.planterior.helper

import com.planterior.helper.core.model.ItemId
import com.planterior.helper.feature.shop.InventorySection
import com.planterior.helper.feature.shop.InventoryUiState
import java.util.concurrent.TimeUnit

/** Raw-to-frame synchronization for the initial inventory content. */
internal class Todo18InventoryViewingProbe(
    private val runtime: Todo18IntegratedRuntimeRule,
    private val compose: Todo18ComposeRule,
) {
    fun awaitContent(trigger: () -> Unit): Todo18InventoryStateEvent {
        val sink = runtime.renderedStateSink
        val expectedAccount = runtime.boundary.accountId
        val itemId = ItemId("todo18-planter")
        val rawFloor = sink.currentRawInventoryState()?.sequence ?: 0L
        val displayedFloor = sink.currentDisplayedInventoryState()?.sequence ?: 0L
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EVENT_TIMEOUT_MILLIS)
        val displayed =
            subscription(
                matches = { event ->
                    event.sequence > displayedFloor &&
                        event.matchesInitialContent(expectedAccount, itemId)
                },
                subscribe = sink::subscribeToDisplayedInventoryStates,
                current = sink::currentDisplayedInventoryState,
            )
        return displayed.use { displayedSubscription ->
            val raw =
                subscription(
                    matches = { event ->
                        event.sequence > rawFloor &&
                            event.matchesInitialContent(expectedAccount, itemId)
                    },
                    subscribe = sink::subscribeToRawInventoryStates,
                    current = sink::currentRawInventoryState,
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
                                "Todo18 inventory raw Content",
                            )
                        },
                        settle = compose::waitForIdle,
                        awaitRendered = {
                            displayedSubscription.await(
                                remainingNanos(deadlineNanos),
                                TimeUnit.NANOSECONDS,
                                "Todo18 inventory displayed Content",
                            )
                        },
                    )
                requireMatchingRenderedState(rawEvent.state, displayedEvent.state)
                displayedEvent
            }
        }
    }

    private fun subscription(
        matches: (Todo18InventoryStateEvent) -> Boolean,
        subscribe: ((Todo18InventoryStateEvent) -> Unit) -> AutoCloseable,
        current: () -> Todo18InventoryStateEvent?,
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
            diagnosticSequence = Todo18InventoryStateEvent::sequence,
        )

    private fun Todo18InventoryStateEvent.matchesInitialContent(
        expectedAccount: com.planterior.helper.core.model.AccountId,
        itemId: ItemId,
    ): Boolean {
        val content = state as? InventoryUiState.Content ?: return false
        return content.owner == expectedAccount &&
            !content.stale &&
            content.section == InventorySection.WAREHOUSE &&
            content.snapshot.catalog.any { it.id == itemId }
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private companion object {
        const val EVENT_TIMEOUT_MILLIS = 30_000L
    }
}
