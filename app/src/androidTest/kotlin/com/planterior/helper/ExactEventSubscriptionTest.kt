package com.planterior.helper

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactEventSubscriptionTest {
    private class EventSource<T> {
        private val listeners = mutableSetOf<(T) -> Unit>()

        fun emit(value: T) = listeners.toList().forEach { it(value) }

        fun subscribe(listener: (T) -> Unit): () -> Unit {
            listeners += listener
            return { listeners -= listener }
        }
    }

    @Test
    fun eventBeforeSubscriptionCannotSatisfyAwaiter() {
        val source = EventSource<String>()
        source.emit("home")

        ExactEventSubscription<String>({ it == "home" }, source::subscribe).use { subscription ->
            subscription.arm()
            assertFalse(subscription.hasAcceptedEvent())
        }
    }

    @Test
    fun replayedStaleEventDuringRegistrationCannotSatisfyAwaiter() {
        val subscription =
            ExactEventSubscription<String>({ it == "home" }) { listener ->
                listener("home")
                val unsubscribe: () -> Unit = {}
                unsubscribe
            }

        subscription.use {
            it.arm()
            assertFalse(it.hasAcceptedEvent())
        }
    }

    @Test
    fun wrongEventAndMissingExpectedEventFailTheBoundedAwait() {
        val source = EventSource<String>()
        ExactEventSubscription<String>({ it == "home" }, source::subscribe).use { subscription ->
            subscription.arm()
            source.emit("settings")
            assertFalse(subscription.hasAcceptedEvent())
            val failure = runCatching {
                subscription.await(1, TimeUnit.MILLISECONDS, "home")
            }
                .exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        }
    }

    @Test
    fun duplicateExpectedEventCannotMasqueradeAsOneTransition() {
        val source = EventSource<String>()
        ExactEventSubscription<String>({ it == "home" }, source::subscribe).use { subscription ->
            subscription.arm()
            source.emit("home")
            source.emit("home")

            val failure = runCatching {
                subscription.await(1, TimeUnit.SECONDS, "home")
            }
                .exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        }
    }
}
