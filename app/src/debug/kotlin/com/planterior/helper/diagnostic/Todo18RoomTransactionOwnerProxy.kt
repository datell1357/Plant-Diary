package com.planterior.helper.diagnostic

import com.planterior.helper.core.database.RoomTransactionOwnerDiagnostics
import com.planterior.helper.core.database.RoomTransactionOwnerObservation
import com.planterior.helper.core.database.RoomTransactionOwnerObserver
import java.util.concurrent.atomic.AtomicReference

private object Todo18RoomTransactionOwnerProxy {
    val listener = AtomicReference<((RoomTransactionOwnerObservation) -> Unit)?>(null)
    val diagnostics =
        RoomTransactionOwnerDiagnostics(
            RoomTransactionOwnerObserver { observation -> listener.get()?.invoke(observation) }
        )
}

internal fun roomTransactionOwnerDiagnostics(): RoomTransactionOwnerDiagnostics =
    Todo18RoomTransactionOwnerProxy.diagnostics

internal fun attachTodo18RoomTransactionOwnerListener(
    listener: (RoomTransactionOwnerObservation) -> Unit
): AutoCloseable {
    check(Todo18RoomTransactionOwnerProxy.listener.compareAndSet(null, listener))
    return AutoCloseable {
        check(Todo18RoomTransactionOwnerProxy.listener.compareAndSet(listener, null))
    }
}
