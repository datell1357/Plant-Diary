package com.planterior.helper

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExactEventSubscriptionHostTest {
    @Test
    fun defaultMatchingConstructorReplayRemainsUnacceptedUntilArmAndLaterMatch() {
        // Given
        val replay = Event("home", 1)
        val later = Event("home", 2)
        val source = EventSource<Event>()
        val subscription =
            ExactEventSubscription<Event>(
                matches = { it.route == "home" },
                subscribe = { receiver ->
                    source.register(receiver)
                    receiver(replay)
                    source.registration(receiver)
                },
            )

        // When
        val acceptedBeforeArm = subscription.hasAcceptedEvent()
        subscription.arm()
        source.emit(later)

        // Then
        assertFalse(acceptedBeforeArm)
        assertEquals(later, subscription.await(BOUND, TimeUnit.SECONDS, "later match"))
        assertEquals(0, source.listenerCount)
    }

    @Test
    fun crossThreadRegistrationCallbackIsRejected() {
        // Given
        val replay = Event("home", 1)
        val later = Event("home", 2)
        val source = EventSource<Event>()
        val subscription =
            ExactEventSubscription<Event>(
                matches = { it.route == "home" },
                subscribe = { receiver ->
                    source.register(receiver)
                    Thread { receiver(replay) }
                        .also {
                            it.start()
                            it.join()
                        }
                    source.registration(receiver)
                },
                acceptRegistrationReplay = true,
            )

        // When
        val acceptedBeforeArm = subscription.hasAcceptedEvent()
        subscription.arm()
        source.emit(later)

        // Then
        assertFalse(acceptedBeforeArm)
        assertEquals(later, subscription.await(BOUND, TimeUnit.SECONDS, "later match"))
        assertEquals(0, source.listenerCount)
    }

    @Test
    fun matchingCallbackAfterConstructorWindowClosesIsRejected() {
        // Given
        val replay = Event("home", 1)
        val later = Event("home", 2)
        val source = EventSource<Event>()
        val subscription =
            ExactEventSubscription<Event>(
                matches = { it.route == "home" },
                subscribe = { receiver ->
                    source.register(receiver)
                    source.registration(receiver)
                },
                acceptRegistrationReplay = true,
            )

        // When
        source.emit(replay)
        val acceptedBeforeArm = subscription.hasAcceptedEvent()
        subscription.arm()
        source.emit(later)

        // Then
        assertFalse(acceptedBeforeArm)
        assertEquals(later, subscription.await(BOUND, TimeUnit.SECONDS, "later match"))
        assertEquals(0, source.listenerCount)
    }

    @Test
    fun armDoesNotRequestReplay() {
        // Given
        val replay = Event("home", 1)
        val source = EventSource<Event>()
        val subscription =
            ExactEventSubscription<Event>(
                matches = { it.route == "home" },
                subscribe = { receiver ->
                    source.register(receiver)
                    source.replay(receiver, replay)
                    source.registration(receiver)
                },
                acceptRegistrationReplay = true,
            )
        assertEquals(1, source.registrationCount)
        assertEquals(1, source.replayRequestCount)

        // When
        subscription.arm()

        // Then
        assertEquals(1, source.registrationCount)
        assertEquals(1, source.replayRequestCount)
        assertEquals(replay, subscription.await(BOUND, TimeUnit.SECONDS, "constructor replay"))
        assertEquals(0, source.listenerCount)
    }

    private data class Event(val route: String, val ordinal: Int)

    private class EventSource<T> {
        private val lock = Any()
        private var listener: ((T) -> Unit)? = null
        var registrationCount = 0
            private set

        var replayRequestCount = 0
            private set

        val listenerCount: Int
            get() = synchronized(lock) { if (listener == null) 0 else 1 }

        fun register(receiver: (T) -> Unit) {
            synchronized(lock) {
                check(listener == null)
                listener = receiver
                registrationCount += 1
            }
        }

        fun replay(receiver: (T) -> Unit, value: T) {
            replayRequestCount += 1
            receiver(value)
        }

        fun emit(value: T) = checkNotNull(synchronized(lock) { listener })(value)

        fun registration(receiver: (T) -> Unit) = ExactEventRegistration { onDrained ->
            synchronized(lock) {
                check(listener === receiver)
                listener = null
            }
            onDrained(null)
            false
        }
    }

    private companion object {
        const val BOUND = 2L
    }
}
