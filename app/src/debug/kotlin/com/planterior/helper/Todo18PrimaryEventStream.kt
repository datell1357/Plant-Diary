package com.planterior.helper

import com.planterior.helper.feature.minihome.MiniHomeUiState
import com.planterior.helper.feature.registration.RegistrationUiState
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

internal data class Todo18MiniHomeStateEvent(
    val sequence: Long,
    val state: MiniHomeUiState,
)

internal data class Todo18RegistrationStateEvent(
    val sequence: Long,
    val state: RegistrationUiState,
)

internal class Todo18PrimaryEventStream<T> {
    private val latest = AtomicReference<T?>()
    private val listeners = CopyOnWriteArraySet<(T) -> Unit>()

    fun current(): T? = latest.get()

    fun listenerCount(): Int = listeners.size

    fun subscribe(listener: (T) -> Unit): AutoCloseable {
        check(listeners.add(listener)) { "Todo18 rendered-state listener is already registered" }
        return AutoCloseable { listeners.remove(listener) }
    }

    fun publish(event: T) {
        latest.set(event)
        listeners.forEach { it(event) }
    }
}
