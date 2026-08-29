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
