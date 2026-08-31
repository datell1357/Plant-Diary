package com.planterior.helper

import com.planterior.helper.diagnostic.Todo18ExactEventObserver
import java.util.concurrent.TimeUnit

/** Sequence-floored synchronization over the exact sink installed by the Todo18 runtime rule. */
internal class Todo18RenderedStateProbe(
    private val runtime: Todo18IntegratedRuntimeRule,
    private val compose: Todo18ComposeRule,
) {
    fun awaitMiniHome(
        matches: (Todo18MiniHomeStateEvent) -> Boolean,
        trigger: () -> Unit,
        observer: Todo18ExactEventObserver? = null,
    ): Todo18MiniHomeStateEvent {
        val sink = runtime.renderedStateSink
        val floor = sink.currentDisplayedMiniHomeState()?.sequence ?: 0L
        val observed =
            ExactEventSubscription(
                    matches = { event -> event.sequence > floor && matches(event) },
                    subscribe = { receiver ->
                        leasedRegistration(
                            receiver,
                            sink::subscribeToDisplayedMiniHomeStates,
                            sink::currentDisplayedMiniHomeState,
                        )
                    },
                    diagnosticObserver = observer,
                    diagnosticSequence = Todo18MiniHomeStateEvent::sequence,
                )
                .use { subscription ->
                    subscription.arm()
                    triggerSettleAndAwait(
                        trigger = { subscription.trigger(trigger) },
                        settle = compose::waitForIdle,
                        await = {
                            subscription.await(
                                EVENT_TIMEOUT_MILLIS,
                                TimeUnit.MILLISECONDS,
                                "Todo18 MiniHome displayed state",
                            )
                        },
                    )
                }
        return observed
    }

    fun awaitMiniHomeViewingAfterLoad(
        barrier: Todo18MiniHomeDisplayedStateBarrier
    ): Todo18MiniHomeStateEvent {
        val sink = runtime.renderedStateSink
        return ExactEventSubscription(
                matches = { event ->
                    barrier.accepts(event, runtime.miniHomeLoadDiagnostics.snapshot())
                },
                subscribe = { receiver ->
                    leasedRegistration(
                        receiver,
                        sink::subscribeToDisplayedMiniHomeStates,
                        sink::currentDisplayedMiniHomeState,
                    )
                },
                diagnosticSequence = Todo18MiniHomeStateEvent::sequence,
                acceptRegistrationReplay = true,
            )
            .use { subscription ->
                subscription.arm()
                subscription.await(
                    EVENT_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS,
                    "Todo18 MiniHome displayed state after load",
                )
            }
    }

    fun awaitRegistration(
        matches: (Todo18RegistrationStateEvent) -> Boolean,
        trigger: () -> Unit,
        observer: Todo18ExactEventObserver? = null,
    ): Todo18RegistrationStateEvent {
        val sink = runtime.renderedStateSink
        val floor = sink.currentRegistrationState()?.sequence ?: 0L
        val observed =
            ExactEventSubscription(
                    matches = { event -> event.sequence >= floor && matches(event) },
                    subscribe = { receiver ->
                        leasedRegistration(
                            receiver,
                            sink::subscribeToRegistrationStates,
                            sink::currentRegistrationState,
                        )
                    },
                    diagnosticObserver = observer,
                    diagnosticSequence = Todo18RegistrationStateEvent::sequence,
                )
                .use { subscription ->
                    subscription.arm()
                    triggerSettleAndAwait(
                        trigger = { subscription.trigger(trigger) },
                        settle = compose::waitForIdle,
                        await = {
                            subscription.await(
                                EVENT_TIMEOUT_MILLIS,
                                TimeUnit.MILLISECONDS,
                                "Todo18 registration state",
                            )
                        },
                    )
                }
        return observed
    }

    fun awaitInventoryFeedback(
        matches: (Todo18InventoryFeedbackEvent) -> Boolean,
        trigger: () -> Unit,
    ): Todo18InventoryFeedbackEvent {
        val sink = runtime.renderedStateSink
        val floor = sink.currentInventoryFeedback()?.sequence ?: 0L
        return ExactEventSubscription(
                matches = { event -> event.sequence >= floor && matches(event) },
                subscribe = { receiver ->
                    leasedRegistration(
                        receiver,
                        sink::subscribeToInventoryFeedback,
                        sink::currentInventoryFeedback,
                    )
                },
                diagnosticSequence = Todo18InventoryFeedbackEvent::sequence,
            )
            .use { subscription ->
                subscription.arm()
                triggerSettleAndAwait(
                    trigger = { subscription.trigger(trigger) },
                    settle = compose::waitForIdle,
                    await = {
                        subscription.await(
                            EVENT_TIMEOUT_MILLIS,
                            TimeUnit.MILLISECONDS,
                            "Todo18 Inventory rendered feedback",
                        )
                    },
                )
            }
    }

    private fun <T> leasedRegistration(
        receiver: (T) -> Unit,
        subscribe: ((T) -> Unit) -> AutoCloseable,
        current: () -> T?,
    ): ExactEventRegistration {
        lateinit var closeable: AutoCloseable
        return LeasedExactEventRegistration(
            receiver = receiver,
            register = { dispatch ->
                closeable = subscribe(dispatch)
                current()?.let(dispatch)
            },
            unregister = { closeable.close() },
        )
    }

    private companion object {
        const val EVENT_TIMEOUT_MILLIS = 10_000L
    }
}
