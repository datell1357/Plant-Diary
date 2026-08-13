package com.planterior.helper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactEventSubscriptionTest {
    private class EventSource<T>(
        private val replayOnSubscribe: T? = null,
        private val capturedOnDetach: T? = null,
    ) {
        private val lock = Any()
        private var listener: ((T) -> Unit)? = null
        private var activeCallbacks = 0
        private var detached = false
        private val drained = CountDownLatch(1)
        val detachEntered = CountDownLatch(1)
        var detachCount = 0
            private set

        val listenerCount: Int
            get() = synchronized(lock) { if (listener == null) 0 else 1 }

        fun subscribe(receiver: (T) -> Unit): ExactEventRegistration {
            synchronized(lock) {
                check(listener == null)
                listener = receiver
            }
            replayOnSubscribe?.let(receiver)
            return ExactEventRegistration { detachAndDrain() }
        }

        fun capture(value: T): Dispatch? =
            synchronized(lock) {
                val target = listener ?: return@synchronized null
                activeCallbacks += 1
                Dispatch {
                    try {
                        target(value)
                    } finally {
                        callbackFinished()
                    }
                }
            }

        fun emit(value: T): Boolean {
            val dispatch = capture(value) ?: return false
            dispatch.invoke()
            return true
        }

        private fun detachAndDrain() {
            val captured =
                synchronized(lock) {
                    detachCount += 1
                    detachEntered.countDown()
                    check(!detached)
                    val target = listener
                    val dispatch =
                        if (target != null && capturedOnDetach != null) {
                            activeCallbacks += 1
                            Dispatch {
                                try {
                                    target(capturedOnDetach)
                                } finally {
                                    callbackFinished()
                                }
                            }
                        } else {
                            null
                        }
                    listener = null
                    detached = true
                    if (activeCallbacks == 0) drained.countDown()
                    dispatch
                }
            // 실제 source가 detach 중 이미 캡처한 callback을 동기 호출해도 helper lock을 잡지 않는다.
            captured?.invoke()
            check(drained.await(1, TimeUnit.SECONDS))
        }

        private fun callbackFinished() {
            synchronized(lock) {
                activeCallbacks -= 1
                check(activeCallbacks >= 0)
                if (detached && activeCallbacks == 0) drained.countDown()
            }
        }

        fun interface Dispatch {
            fun invoke()
        }
    }

    private fun subscription(source: EventSource<String>) =
        ExactEventSubscription({ it == "home" }, source::subscribe)

    @Test
    fun exactlyOnceEventSucceedsAndDetachesListener() {
        val source = EventSource<String>()
        subscription(source).use { subscription ->
            subscription.arm()
            assertTrue(source.emit("home"))
            assertEquals("home", subscription.await(1, TimeUnit.SECONDS, "home"))
            assertEquals(0, source.listenerCount)
            assertEquals(1, source.detachCount)
        }
    }

    @Test
    fun duplicateBeforeAwaitPreventsSuccess() {
        val source = EventSource<String>()
        subscription(source).use { subscription ->
            subscription.arm()
            source.emit("home")
            source.emit("home")

            assertFailure(ExactEventFailure.DUPLICATE) {
                subscription.await(1, TimeUnit.SECONDS, "home")
            }
        }
    }

    @Test
    fun duplicateAlreadyInFlightAtAwaitCompletionPreventsSuccess() {
        repeat(DETERMINISTIC_INTERLEAVINGS) {
            val source = EventSource<String>()
            val duplicateCaptured = CountDownLatch(1)
            val releaseDuplicate = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                subscription(source).use { subscription ->
                    subscription.arm()
                    val duplicate = checkNotNull(source.capture("home"))
                    val duplicateFuture = executor.submit {
                        duplicateCaptured.countDown()
                        check(releaseDuplicate.await(1, TimeUnit.SECONDS))
                        duplicate.invoke()
                    }
                    check(duplicateCaptured.await(1, TimeUnit.SECONDS))
                    source.emit("home")
                    val awaitFuture = executor.submitAwait(subscription)
                    check(source.detachEntered.await(1, TimeUnit.SECONDS))
                    assertFalse(awaitFuture.isDone)

                    releaseDuplicate.countDown()
                    duplicateFuture.get(1, TimeUnit.SECONDS)
                    assertFutureFailure(awaitFuture, ExactEventFailure.DUPLICATE)
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun callbackCapturedAtDetachBoundaryPreventsSuccessWithoutDeadlock() {
        val source = EventSource(capturedOnDetach = "home")
        subscription(source).use { subscription ->
            subscription.arm()
            source.emit("home")

            assertFailure(ExactEventFailure.DUPLICATE) {
                subscription.await(1, TimeUnit.SECONDS, "home")
            }
            assertEquals(1, source.detachCount)
        }
    }

    @Test
    fun eventAfterClosedIsRejectedByDetachedSource() {
        val source = EventSource<String>()
        subscription(source).use { subscription ->
            subscription.arm()
            source.emit("home")
            assertEquals("home", subscription.await(1, TimeUnit.SECONDS, "home"))

            assertFalse(source.emit("home"))
            assertEquals(1, source.detachCount)
        }
    }

    @Test
    fun wrongEventCannotSatisfyAwait() {
        val source = EventSource<String>()
        subscription(source).use { subscription ->
            subscription.arm()
            source.emit("settings")

            assertFailure(ExactEventFailure.TIMEOUT) {
                subscription.await(1, TimeUnit.MILLISECONDS, "home")
            }
        }
    }

    @Test
    fun staleGenerationReplayedDuringRegistrationCannotSatisfyAwait() {
        val source = EventSource(replayOnSubscribe = "home")
        subscription(source).use { subscription ->
            subscription.arm()
            assertFalse(subscription.hasAcceptedEvent())

            assertFailure(ExactEventFailure.TIMEOUT) {
                subscription.await(1, TimeUnit.MILLISECONDS, "home")
            }
        }
    }

    @Test
    fun missingEventTimesOutAndDetachesListener() {
        val source = EventSource<String>()
        subscription(source).use { subscription ->
            subscription.arm()

            assertFailure(ExactEventFailure.TIMEOUT) {
                subscription.await(1, TimeUnit.MILLISECONDS, "home")
            }
            assertEquals(0, source.listenerCount)
            assertEquals(1, source.detachCount)
        }
    }

    @Test
    fun closeCancelsBoundedAwaitAndDetachesListener() {
        val source = EventSource<String>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            subscription(source).use { subscription ->
                subscription.arm()
                val awaitEntered = CountDownLatch(1)
                val future =
                    executor.submit<String> {
                        awaitEntered.countDown()
                        subscription.await(1, TimeUnit.SECONDS, "home")
                    }
                check(awaitEntered.await(1, TimeUnit.SECONDS))

                subscription.close()
                assertFutureFailure(future, ExactEventFailure.CANCELLED)
                assertEquals(0, source.listenerCount)
                assertEquals(1, source.detachCount)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun java.util.concurrent.ExecutorService.submitAwait(
        subscription: ExactEventSubscription<String>
    ): Future<String> = submit<String> { subscription.await(1, TimeUnit.SECONDS, "home") }

    private fun assertFutureFailure(future: Future<String>, expected: ExactEventFailure) {
        val thrown =
            assertThrows(java.util.concurrent.ExecutionException::class.java) {
                future.get(1, TimeUnit.SECONDS)
            }
        assertEquals(expected, (thrown.cause as ExactEventException).failure)
    }

    private fun assertFailure(expected: ExactEventFailure, block: () -> Unit) {
        val thrown = assertThrows(ExactEventException::class.java, block)
        assertEquals(expected, thrown.failure)
    }

    private companion object {
        const val DETERMINISTIC_INTERLEAVINGS = 32
    }
}
