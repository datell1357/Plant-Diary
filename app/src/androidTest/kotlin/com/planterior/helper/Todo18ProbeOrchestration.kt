package com.planterior.helper

/** Host-testable ordering seam for AndroidTest exact-event probes. */
internal fun <T> triggerSettleAndAwait(
    trigger: () -> Unit,
    settle: () -> Unit,
    await: () -> T,
): T {
    trigger()
    settle()
    return await()
}

/** Upstream completion must precede the frame that publishes its rendered result. */
internal fun <U, R> triggerAwaitSettleAndAwait(
    trigger: () -> Unit,
    awaitUpstream: () -> U,
    settle: () -> Unit,
    awaitRendered: () -> R,
): Pair<U, R> {
    trigger.invoke()
    val upstream = awaitUpstream.invoke()
    settle.invoke()
    return upstream to awaitRendered.invoke()
}

/** Checks correlation only after both exact subscriptions have accepted their events. */
internal fun <T> requireMatchingRenderedState(upstream: T, rendered: T) {
    require(upstream == rendered) { "Rendered state did not match the exact upstream state" }
}
