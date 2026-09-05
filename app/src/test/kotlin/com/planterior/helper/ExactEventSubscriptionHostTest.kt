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

    @Test
    fun prearmedRawAndDisplayedSubscriptionsAcceptInlineTriggerEventsAndDetach() {
        // Given
        val rawEvent = Event("mini-home-raw", 18)
        val displayedEvent = Event("mini-home-displayed", 19)
        val rawSource = EventSource<Event>()
        val displayedSource = EventSource<Event>()
        val raw = subscription(rawSource, rawEvent.route)
        val displayed = subscription(displayedSource, displayedEvent.route)
        raw.arm()
        displayed.arm()

        // When
        raw.trigger {
            displayed.trigger {
                rawSource.emit(rawEvent)
                displayedSource.emit(displayedEvent)
            }
        }
        val observedRaw = raw.await(BOUND, TimeUnit.SECONDS, "raw initial Viewing")
        val observedDisplayed =
            displayed.await(BOUND, TimeUnit.SECONDS, "displayed initial Viewing")

        // Then
        assertEquals(rawEvent, observedRaw)
        assertEquals(displayedEvent, observedDisplayed)
        assertEquals(0, rawSource.listenerCount)
        assertEquals(0, displayedSource.listenerCount)
    }

    private fun subscription(source: EventSource<Event>, route: String) =
        ExactEventSubscription<Event>(
            matches = { it.route == route },
            subscribe = { receiver ->
                source.register(receiver)
                source.registration(receiver)
            },
        )

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
