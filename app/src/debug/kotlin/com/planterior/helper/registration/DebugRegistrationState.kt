package com.planterior.helper.registration

import android.content.Context
import com.planterior.helper.feature.registration.RegistrationUiState
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class DebugRegistrationStateEvent(
    val sequence: Long,
    val activityIdentity: Int,
    val state: RegistrationUiState,
)

fun currentDebugRegistrationState(): DebugRegistrationStateEvent? =
    DebugRegistrationStateEvents.current()

fun subscribeToDebugRegistrationStates(
    listener: (DebugRegistrationStateEvent) -> Unit
): AutoCloseable = DebugRegistrationStateEvents.subscribe(listener)

fun observeDebugRegistrationState(context: Context, state: RegistrationUiState) {
    DebugRegistrationStateEvents.publish(System.identityHashCode(context), state)
}

private object DebugRegistrationStateEvents {
    private val sequence = AtomicLong()
    private val latest = AtomicReference<DebugRegistrationStateEvent>()
    private val listeners = CopyOnWriteArraySet<(DebugRegistrationStateEvent) -> Unit>()

    fun current(): DebugRegistrationStateEvent? = latest.get()

    fun subscribe(listener: (DebugRegistrationStateEvent) -> Unit): AutoCloseable {
        check(listeners.add(listener)) { "debug registration state listener is already registered" }
        return AutoCloseable { listeners.remove(listener) }
    }

    fun publish(activityIdentity: Int, state: RegistrationUiState) {
        val event = DebugRegistrationStateEvent(sequence.incrementAndGet(), activityIdentity, state)
        latest.set(event)
        listeners.forEach { it(event) }
    }
}
