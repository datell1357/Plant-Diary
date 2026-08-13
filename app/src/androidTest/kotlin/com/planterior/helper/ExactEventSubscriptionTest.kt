package com.planterior.helper

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class ExactEventSubscriptionTest {
    /** Scheduler metadata는 barrier 제약에만 쓰며 behavior recorder나 hash API로 전달되지 않는다. */
    private data class Schedule(
        val name: String,
        val partialOrderId: String,
        val order: List<String>,
        val boundaryTag: String = "boundary:$name",
    )

    /** Barrier는 scenario를 제어할 뿐 behavior identity를 기록하지 않는다. */
    private class DeterministicScheduler(val definition: Schedule) {
        private val reached = definition.order.associateWith { CountDownLatch(1) }
        private val claimed = ConcurrentHashMap.newKeySet<String>()

        fun step(id: String) {
            val index = definition.order.indexOf(id)
            check(index >= 0) { "${definition.name}: 정의되지 않은 checkpoint $id" }
            if (index > 0) {
                check(
                    reached.getValue(definition.order[index - 1]).await(BOUND, TimeUnit.SECONDS)
                ) {
                    "${definition.name}: ${definition.order[index - 1]} 이전에 $id 교착"
                }
            }
            check(claimed.add(id)) { "${definition.name}: checkpoint 중복 $id" }
            reached.getValue(id).countDown()
        }

        fun await(id: String) {
            check(reached.getValue(id).await(BOUND, TimeUnit.SECONDS)) {
                "${definition.name}: checkpoint 미도달 $id"
            }
        }

        fun assertComplete() {
            assertEquals(definition.name, definition.order.toSet(), claimed)
        }
    }

    private class EventSource<T>(
        private val failRegistration: Boolean = false,
        private val failUnregister: Boolean = false,
    ) {
        private val lock = Any()
        private val listeners = linkedSetOf<(T) -> Unit>()

        val listenerCount: Int
            get() = synchronized(lock) { listeners.size }

        fun register(listener: (T) -> Unit) {
            if (failRegistration) throw SourceProblem("register")
            synchronized(lock) { check(listeners.add(listener)) }
        }

        fun unregister(listener: (T) -> Unit) {
            synchronized(lock) { listeners.remove(listener) }
            if (failUnregister) throw SourceProblem("unregister")
        }

        fun capture(value: T, listenerIndex: Int = 0): Dispatch {
            val target =
                synchronized(lock) {
                    checkNotNull(listeners.elementAtOrNull(listenerIndex)) { "listener 없음" }
                }
            return Dispatch { target(value) }
        }

        fun emit(value: T): Int {
            val captures = synchronized(lock) { listeners.map { Dispatch { it(value) } } }
            captures.forEach(Dispatch::invoke)
            return captures.size
        }

        fun interface Dispatch {
            fun invoke()
        }
    }

    private class SourceProblem(message: String) : IllegalStateException(message)

    private class LeaseHooks(
        private val onAcquired: () -> Unit = {},
        private val onDetachStarted: () -> Unit = {},
        private val onReleasing: () -> Unit = {},
        private val onUnregistered: () -> Unit = {},
        private val onDrained: () -> Unit = {},
    ) : ExactEventLeaseObserver {
        override fun acquired() = onAcquired()

        override fun detachStarted() = onDetachStarted()

        override fun releasing() = onReleasing()

        override fun unregistered() = onUnregistered()

        override fun drained() = onDrained()
    }

    private class StateHooks(
        private val onAwaitClaimed: () -> Unit = {},
        private val onCloseSelected: (String) -> Unit = {},
        private val onCloseLinearized: (String, Boolean) -> Unit = { _, _ -> },
        private val onTerminal: (String) -> Unit = {},
    ) : ExactEventStateObserver {
        override fun awaitClaimed() = onAwaitClaimed()

        override fun closeSelected(reason: String) = onCloseSelected(reason)

        override fun closeLinearized(reason: String, ownsDetach: Boolean) =
            onCloseLinearized(reason, ownsDetach)

        override fun terminal(outcome: String) = onTerminal(outcome)
    }

    private data class Fixture<T>(
        val source: EventSource<T>,
        val subscription: ExactEventSubscription<T>,
        val registration: AtomicReference<LeasedExactEventRegistration<T>?>,
    )

    private fun <T> fixture(
        source: EventSource<T> = EventSource(),
        matches: (T) -> Boolean,
        classify: (T) -> ExactEventValueCategory = { ExactEventValueCategory.GENERIC },
        leaseObserver: ExactEventLeaseObserver = ExactEventLeaseObserver.NONE,
        stateObserver: ExactEventStateObserver = ExactEventStateObserver.NONE,
    ): Fixture<T> {
        val registration = AtomicReference<LeasedExactEventRegistration<T>?>()
        val subscription =
            ExactEventSubscription(
                matches = matches,
                classify = classify,
                subscribe = { receiver ->
                    LeasedExactEventRegistration(
                            receiver,
                            source::register,
                            source::unregister,
                            leaseObserver,
                        )
                        .also(registration::set)
                },
                stateObserver = stateObserver,
            )
        return Fixture(source, subscription, registration)
    }

    @Test
    fun scheduleDefinitionsAreNamedAndStructurallyDistinct() {
        assertEquals(SCHEDULES.size, SCHEDULES.map { it.name }.toSet().size)
        assertEquals(SCHEDULES.size, SCHEDULES.map { it.partialOrderId }.toSet().size)
        assertEquals(SCHEDULES.size, SCHEDULES.map { it.boundaryTag }.toSet().size)
        SCHEDULES.forEach { schedule ->
            assertTrue(schedule.name, schedule.order.isNotEmpty())
            assertEquals(schedule.name, schedule.order.size, schedule.order.toSet().size)
            assertTrue(schedule.partialOrderId, schedule.partialOrderId.startsWith("po:"))
            assertTrue(schedule.boundaryTag, schedule.boundaryTag.startsWith("boundary:"))
        }
        assertFalse(SCHEDULES.any { it.name.contains("repeat", ignoreCase = true) })
    }

    @Test
    fun fakeMetadataCloneCollidesInIndependentBehaviorOracle() {
        val originalMetadata =
            Schedule("original", "po:original", listOf("original-step"), "boundary:original")
        val clonedMetadata =
            Schedule("renamed", "po:changed", listOf("different-expected"), "boundary:changed")
        val original = captureExactlyOnceBehavior()
        val clone = captureExactlyOnceBehavior()

        assertEquals(original.normalized, clone.normalized)
        assertEquals(original.hash, clone.hash)
        val collision =
            assertThrows(IllegalStateException::class.java) {
                requireUniqueBehaviorTraces(
                    listOf(originalMetadata.name to original, clonedMetadata.name to clone)
                )
            }
        assertTrue(collision.message, collision.message!!.contains("original"))
        assertTrue(collision.message, collision.message!!.contains("renamed"))
    }

    @Test
    fun runnerMetadataMutationCannotManufactureUniqueBehavior() {
        fun execute(definition: Schedule): ExactEventBehaviorSnapshot {
            val scheduler = DeterministicScheduler(definition)
            return captureBehavior {
                definition.order.forEach(scheduler::step)
                val fixture = fixture<String>(matches = { it == "home" })
                fixture.subscription.arm()
                fixture.source.emit("home")
                assertEquals(
                    "home",
                    fixture.subscription.await(BOUND, TimeUnit.SECONDS, "home"),
                )
                fixture.subscription.close()
                assertClean(fixture)
                scheduler.assertComplete()
            }
        }

        val original = execute(Schedule("runner-a", "po:a", listOf("a"), "boundary:a"))
        val mutated =
            execute(
                Schedule(
                    "runner-b",
                    "po:invented<order",
                    listOf("invented", "order"),
                    "boundary:b",
                )
            )

        assertEquals(original.normalized, mutated.normalized)
        assertEquals(original.hash, mutated.hash)
        val collision =
            assertThrows(IllegalStateException::class.java) {
                requireUniqueBehaviorTraces(listOf("runner-a" to original, "runner-b" to mutated))
            }
        assertTrue(collision.message, collision.message!!.contains("runner-a"))
        assertTrue(collision.message, collision.message!!.contains("runner-b"))
    }

    @Test
    fun oppositeObservedCallbackOrdersWithSameTypedEventHashDifferently() {
        val event = OpaqueEvent("ignored")
        val firstThenSecond = captureSharedCallbackOrder(event, reverse = false)
        val secondThenFirst = captureSharedCallbackOrder(event, reverse = true)

        assertFalse(firstThenSecond.normalized == secondThenFirst.normalized)
        assertFalse(firstThenSecond.hash == secondThenFirst.hash)

        fun orderErasingMutation(snapshot: ExactEventBehaviorSnapshot): List<String> =
            snapshot.normalized
                .split(';')
                .map { token ->
                    token.substringAfter("|thread=")
                }
                .sorted()

        assertEquals(orderErasingMutation(firstThenSecond), orderErasingMutation(secondThenFirst))
    }

    @Test
    fun identicalObservedOrderIsStableAndOpaqueLabelsCannotEnterHash() {
        val baseline = captureSharedCallbackOrder(OpaqueEvent("first-label"), reverse = false)
        val sameOrder =
            captureSharedCallbackOrder(OpaqueEvent("second-unique-label"), reverse = false)
        val throwingLabel =
            captureSharedCallbackOrder(
                OpaqueEvent("never-render") { throw AssertionError("toString must not run") },
                reverse = false,
            )

        assertEquals(baseline.normalized, sameOrder.normalized)
        assertEquals(baseline.hash, sameOrder.hash)
        assertEquals(baseline.normalized, throwingLabel.normalized)
        assertEquals(baseline.hash, throwingLabel.hash)
    }

    @Test
    fun errorMessagesDoNotAffectCategoryButBehaviorFactsDo() {
        val firstError = captureMatchFailure("first secret message")
        val secondError = captureMatchFailure("different unique message")
        val success = captureExactlyOnceBehavior()
        val duplicate = captureDuplicateBehavior()
        val home = captureRouteBehavior(RouteCategory.HOME)
        val detailsThenHome = captureRouteBehavior(RouteCategory.DETAILS)

        assertEquals(firstError.normalized, secondError.normalized)
        assertEquals(firstError.hash, secondError.hash)
        assertFalse(success.hash == duplicate.hash)
        assertFalse(success.hash == firstError.hash)
        assertFalse(home.hash == detailsThenHome.hash)
    }

    @Test
    fun differentRuntimeOrdersWithIdenticalMetadataHaveDifferentBehaviorHashes() {
        val metadata = Schedule("same", "po:same", listOf("same"), "boundary:same")
        val eventBeforeAwait = captureExactlyOnceBehavior()
        val awaitBeforeEvent = captureAwaitBeforeEventBehavior()

        assertEquals(metadata, metadata.copy())
        assertFalse(eventBeforeAwait.normalized == awaitBeforeEvent.normalized)
        assertFalse(eventBeforeAwait.hash == awaitBeforeEvent.hash)
    }

    @Test
    fun behaviorRecorderApiAcceptsOnlyClosedTypedRuntimePayloads() {
        val captureApi =
            (ExactEventBehaviorTrace::class.java.declaredMethods.toList() +
                    ExactEventBehaviorTrace.Capture::class.java.declaredMethods.toList())
                .filterNot { it.isSynthetic }
        assertFalse(
            captureApi.any { it.name.contains("record", true) || it.name.contains("append", true) }
        )
        assertTrue(captureApi.all { it.parameterTypes.isEmpty() })

        val observe = BehaviorRecorder::class.java.declaredMethods.single { it.name == "observe" }
        assertEquals(
            listOf(
                BehaviorHandle::class.java,
                BehaviorTransition::class.java,
                BehaviorPayload.Facts::class.java,
            ),
            observe.parameterTypes.toList(),
        )
        val forbiddenInputTypes = setOf(String::class.java, Any::class.java)
        assertTrue(observe.parameterTypes.none(forbiddenInputTypes::contains))

        val payloadFields =
            BehaviorPayload.Facts::class.java.declaredFields.filterNot {
                it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
        assertTrue(payloadFields.isNotEmpty())
        assertTrue(
            payloadFields.all { field ->
                field.type.isPrimitive || field.type.isEnum
            }
        )
        assertTrue(
            BehaviorEvent::class
                .java
                .declaredFields
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .none { field ->
                    field.type in forbiddenInputTypes || field.type == String::class.java
                }
        )
        assertFalse(
            (BehaviorTransition.entries.map { it.name } +
                    BehaviorPayload.Facts::class.java.declaredFields.map { it.name })
                .any { token ->
                    listOf("scenario", "expected", "label", "tag", "partialOrder", "message").any {
                        token.contains(it, ignoreCase = true)
                    }
                }
        )
    }

    @Test
    fun firstEmitAndAwaitLinearizationsSucceed() {
        runSchedule("first-emit-before-await") { scheduler ->
            val fixture = fixture<String>(matches = { it == "home" })
            fixture.subscription.arm()
            scheduler.step("emit")
            assertEquals(1, fixture.source.emit("home"))
            scheduler.step("await")
            assertEquals("home", fixture.subscription.await(BOUND, TimeUnit.SECONDS, "home"))
            assertClean(fixture)
        }

        runSchedule("await-before-first-emit") { scheduler ->
            val fixture =
                fixture<String>(
                    matches = { it == "home" },
                    stateObserver =
                        StateHooks(
                            onAwaitClaimed = { scheduler.step("await-claimed") },
                            onTerminal = { scheduler.step("terminal") },
                        ),
                )
            val executor = Executors.newSingleThreadExecutor()
            try {
                fixture.subscription.arm()
                val result = executor.submitAwait(fixture.subscription)
                scheduler.await("await-claimed")
                scheduler.step("emit")
                fixture.source.emit("home")
                scheduler.await("terminal")
                assertEquals("home", result.get(BOUND, TimeUnit.SECONDS))
                assertClean(fixture)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun duplicateSchedulesFailAtEachDrainBoundary() {
        runSchedule("duplicate-before-await") { scheduler ->
            val fixture = fixture<String>(matches = { it == "home" })
            fixture.subscription.arm()
            scheduler.step("first")
            fixture.source.emit("home")
            scheduler.step("duplicate")
            fixture.source.emit("home")
            scheduler.step("await")
            assertFailure(ExactEventFailure.DUPLICATE) {
                fixture.subscription.await(BOUND, TimeUnit.SECONDS, "home")
            }
            assertClean(fixture)
        }

        runSchedule("duplicate-during-await") { scheduler ->
            val fixture =
                fixture<String>(
                    matches = { it == "home" },
                    stateObserver =
                        StateHooks(
                            onAwaitClaimed = { scheduler.step("await-claimed") },
                            onCloseSelected = {
                                scheduler.step("success-selected")
                                scheduler.await("duplicate-received")
                            },
                        ),
                )
            val executor = Executors.newSingleThreadExecutor()
            try {
                fixture.subscription.arm()
                val result = executor.submitAwait(fixture.subscription)
                scheduler.await("await-claimed")
                scheduler.step("first")
                fixture.source.emit("home")
                scheduler.await("success-selected")
                fixture.source.emit("home")
                scheduler.step("duplicate-received")
                assertFutureFailure(result, ExactEventFailure.DUPLICATE)
                assertClean(fixture)
            } finally {
                executor.shutdownNow()
            }
        }

        runBlockedDuplicateSchedule("duplicate-acquired-before-detach")
        runDuplicateAtDrainCompletion()
    }

    private fun runBlockedDuplicateSchedule(name: String) {
        runSchedule(name) { scheduler ->
            val acquisition = AtomicInteger()
            val fixture =
                fixture<String>(
                    matches = { it == "home" },
                    leaseObserver =
                        LeaseHooks(
                            onAcquired = {
                                if (acquisition.incrementAndGet() == 1) {
                                    scheduler.step("duplicate-acquired")
                                    scheduler.await("duplicate-release")
                                }
                            },
                            onDetachStarted = { scheduler.step("detach-started") },
                        ),
                )
            val executor = Executors.newFixedThreadPool(2)
            try {
                fixture.subscription.arm()
                val duplicate = fixture.source.capture("home")
                val duplicateFuture = executor.submit { duplicate.invoke() }
                scheduler.await("duplicate-acquired")
                scheduler.step("first")
                fixture.source.emit("home")
                val result = executor.submitAwait(fixture.subscription)
                scheduler.await("detach-started")
                assertFalse(result.isDone)
                scheduler.step("duplicate-release")
                duplicateFuture.get(BOUND, TimeUnit.SECONDS)
                assertFutureFailure(result, ExactEventFailure.DUPLICATE)
                assertClean(fixture)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun runDuplicateAtDrainCompletion() {
        runSchedule("duplicate-at-drain-completion") { scheduler ->
            val releaseCount = AtomicInteger()
            val fixture =
                fixture<String>(
                    matches = { it == "home" },
                    leaseObserver =
                        LeaseHooks(
                            onReleasing = {
                                if (releaseCount.incrementAndGet() == 2) {
                                    scheduler.step("duplicate-counted")
                                    scheduler.await("duplicate-release")
                                }
                            },
                            onDetachStarted = { scheduler.step("detach-started") },
                            onDrained = { scheduler.step("drain-complete") },
                        ),
                )
            val executor = Executors.newFixedThreadPool(2)
            try {
                fixture.subscription.arm()
                scheduler.step("first")
                fixture.source.emit("home")
                val duplicateFuture = executor.submit { fixture.source.emit("home") }
                scheduler.await("duplicate-counted")
                val result = executor.submitAwait(fixture.subscription)
                scheduler.await("detach-started")
                scheduler.step("duplicate-release")
                duplicateFuture.get(BOUND, TimeUnit.SECONDS)
                scheduler.await("drain-complete")
                assertFutureFailure(result, ExactEventFailure.DUPLICATE)
                assertClean(fixture)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun cancellationSchedulesHaveOneCancelledTerminal() {
        runSchedule("cancellation-before-emit") { scheduler ->
            val fixture = fixture<String>(matches = { it == "home" })
            fixture.subscription.arm()
            scheduler.step("close")
            fixture.subscription.close()
            scheduler.step("emit-rejected")
            assertEquals(0, fixture.source.emit("home"))
            assertFailure(ExactEventFailure.CANCELLED) {
                fixture.subscription.await(BOUND, TimeUnit.SECONDS, "home")
            }
            assertClean(fixture)
        }
        runEmitAcquiredThenCancellation()
        runCancellationWhileDuplicateDrainWaits()

        runSchedule("cancellation-after-terminal") { scheduler ->
            val fixture = fixture<String>(matches = { it == "home" })
            fixture.subscription.arm()
            scheduler.step("emit")
            fixture.source.emit("home")
            scheduler.step("success")
            assertEquals("home", fixture.subscription.await(BOUND, TimeUnit.SECONDS, "home"))
            scheduler.step("late-close")
            fixture.subscription.close()
            assertClean(fixture)
        }
    }

    private fun runEmitAcquiredThenCancellation() {
        runSchedule("emit-acquired-then-cancellation") { scheduler ->
            val fixture =
                fixture<String>(
                    matches = { it == "home" },
                    leaseObserver =
                        LeaseHooks(
                            onAcquired = {
                                scheduler.step("cancel-event-acquired")
                                scheduler.await("cancel-event-release")
                            },
                            onDetachStarted = { scheduler.step("cancel-detach-started") },
                        ),
                    stateObserver =
                        StateHooks(
                            onAwaitClaimed = { scheduler.step("cancel-await-claimed") },
                            onCloseLinearized = { reason, ownsDetach ->
                                if (reason == "CANCELLED") {
                                    if (ownsDetach) {
                                        scheduler.step("cancel-owner-linearized")
                                        scheduler.await("cancel-await-linearized")
                                    } else {
                                        scheduler.step("cancel-await-linearized")
                                    }
                                }
                            },
                            onTerminal = { scheduler.step("cancel-terminal") },
                        ),
                )
            val executor = Executors.newFixedThreadPool(3)
            try {
                fixture.subscription.arm()
                val awaitFuture = executor.submitAwait(fixture.subscription)
                scheduler.await("cancel-await-claimed")
                val emitFuture = executor.submit { fixture.source.emit("home") }
                scheduler.await("cancel-event-acquired")
                val closeFuture = executor.submit { fixture.subscription.close() }
                scheduler.await("cancel-detach-started")
                scheduler.step("cancel-event-release")
                emitFuture.get(BOUND, TimeUnit.SECONDS)
                scheduler.await("cancel-terminal")
                closeFuture.get(BOUND, TimeUnit.SECONDS)
                assertFutureFailure(awaitFuture, ExactEventFailure.CANCELLED)
                assertClean(fixture)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun runCancellationWhileDuplicateDrainWaits() {
        runSchedule("cancellation-while-drain-waits") { scheduler ->
            val acquisitions = AtomicInteger()
            val fixture =
                fixture<String>(
                    matches = { it == "home" },
                    leaseObserver =
                        LeaseHooks(
                            onAcquired = {
                                if (acquisitions.incrementAndGet() == 2) {
                                    scheduler.step("drain-duplicate-acquired")
                                    scheduler.await("drain-duplicate-release")
                                }
                            },
                            onDetachStarted = { scheduler.step("success-drain-started") },
                        ),
                    stateObserver =
                        StateHooks(
                            onCloseLinearized = { reason, ownsDetach ->
                                if (reason == "CANCELLED" && !ownsDetach) {
                                    scheduler.step("drain-cancellation-linearized")
                                }
                            },
                            onTerminal = { scheduler.step("drain-cancel-terminal") },
                        ),
                )
            val executor = Executors.newFixedThreadPool(3)
            try {
                fixture.subscription.arm()
                scheduler.step("drain-first-event")
                fixture.source.emit("home")
                val duplicateFuture = executor.submit { fixture.source.emit("home") }
                scheduler.await("drain-duplicate-acquired")
                val awaitFuture = executor.submitAwait(fixture.subscription)
                scheduler.await("success-drain-started")
                val closeFuture = executor.submit { fixture.subscription.close() }
                scheduler.await("drain-cancellation-linearized")
                scheduler.step("drain-duplicate-release")
                duplicateFuture.get(BOUND, TimeUnit.SECONDS)
                scheduler.await("drain-cancel-terminal")
                closeFuture.get(BOUND, TimeUnit.SECONDS)
                assertFutureFailure(awaitFuture, ExactEventFailure.CANCELLED)
                assertClean(fixture)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun timeoutSchedulesRespectAcquiredCallbackBoundary() {
        runSchedule("timeout-before-emit") { scheduler ->
            val fixture = fixture<String>(matches = { it == "home" })
            fixture.subscription.arm()
            scheduler.step("timeout")
            assertFailure(ExactEventFailure.TIMEOUT) {
                fixture.subscription.await(0, TimeUnit.NANOSECONDS, "home")
            }
            scheduler.step("emit-rejected")
            assertEquals(0, fixture.source.emit("home"))
            assertClean(fixture)
        }

        runTimeoutWithLease("emit-acquired-before-timeout", "home", ExactEventFailure.TIMEOUT, true)
        runTimeoutWithLease(
            "timeout-while-drain-waits",
            "settings",
            ExactEventFailure.TIMEOUT,
            false,
        )
    }

    private fun runTimeoutWithLease(
        name: String,
        event: String,
        expectedFailure: ExactEventFailure,
        succeeds: Boolean,
    ) {
        runSchedule(name) { scheduler ->
            val fixture =
                fixture<String>(
                    matches = { it == "home" },
                    leaseObserver =
                        LeaseHooks(
                            onAcquired = {
                                scheduler.step("emit-acquired")
                                scheduler.await("emit-release")
                            },
                            onDetachStarted = { scheduler.step("timeout-detach") },
                        ),
                )
            val executor = Executors.newFixedThreadPool(2)
            try {
                fixture.subscription.arm()
                val emitFuture = executor.submit { fixture.source.emit(event) }
                scheduler.await("emit-acquired")
                val result =
                    executor.submit<String> {
                        fixture.subscription.await(1, TimeUnit.MILLISECONDS, "home")
                    }
                scheduler.await("timeout-detach")
                scheduler.step("emit-release")
                emitFuture.get(BOUND, TimeUnit.SECONDS)
                if (succeeds) {
                    assertEquals("home", result.get(BOUND, TimeUnit.SECONDS))
                } else {
                    assertFutureFailure(result, expectedFailure)
                }
                assertClean(fixture)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun closeSchedulesAreIdempotentAndDrainConcurrentCallback() {
        runSchedule("close-before-emit") { scheduler ->
            val fixture = fixture<String>(matches = { it == "home" })
            fixture.subscription.arm()
            scheduler.step("close")
            fixture.subscription.close()
            scheduler.step("emit")
            assertEquals(0, fixture.source.emit("home"))
            assertClean(fixture)
        }
        runCloseConcurrentWithAcquiredCallbackThenObserveClosed()

        runSchedule("double-close") { scheduler ->
            val fixture =
                fixture<String>(
                    matches = { it == "home" },
                    leaseObserver =
                        LeaseHooks(
                            onDetachStarted = {
                                scheduler.step("first-detach")
                                scheduler.await("first-detach-release")
                            }
                        ),
                    stateObserver =
                        StateHooks(
                            onCloseLinearized = { _, ownsDetach ->
                                if (!ownsDetach) scheduler.step("second-close-linearized")
                            }
                        ),
                )
            val executor = Executors.newFixedThreadPool(2)
            try {
                fixture.subscription.arm()
                val first = executor.submit { fixture.subscription.close() }
                scheduler.await("first-detach")
                scheduler.step("second-close")
                val second = executor.submit { fixture.subscription.close() }
                scheduler.await("second-close-linearized")
                scheduler.step("first-detach-release")
                first.get(BOUND, TimeUnit.SECONDS)
                second.get(BOUND, TimeUnit.SECONDS)
                assertClean(fixture)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun runCloseConcurrentWithAcquiredCallbackThenObserveClosed() {
        runSchedule("close-concurrent-with-acquired-callback") { scheduler ->
            val fixture =
                fixture<String>(
                    matches = { it == "home" },
                    leaseObserver =
                        LeaseHooks(
                            onAcquired = {
                                scheduler.step("close-callback-acquired")
                                scheduler.await("close-callback-release")
                            },
                            onDetachStarted = { scheduler.step("close-detach-started") },
                        ),
                    stateObserver =
                        StateHooks(
                            onCloseLinearized = { reason, ownsDetach ->
                                if (reason == "CANCELLED" && ownsDetach) {
                                    scheduler.step("close-linearized")
                                }
                            },
                            onTerminal = { scheduler.step("close-terminal") },
                        ),
                )
            val executor = Executors.newFixedThreadPool(2)
            try {
                fixture.subscription.arm()
                val callbackFuture = executor.submit { fixture.source.emit("home") }
                scheduler.await("close-callback-acquired")
                val closeFuture = executor.submit { fixture.subscription.close() }
                scheduler.await("close-detach-started")
                scheduler.step("close-callback-release")
                callbackFuture.get(BOUND, TimeUnit.SECONDS)
                scheduler.await("close-terminal")
                closeFuture.get(BOUND, TimeUnit.SECONDS)
                fixture.subscription.close()
                scheduler.step("second-close-observed-closed")
                assertFailure(ExactEventFailure.CANCELLED) {
                    fixture.subscription.await(BOUND, TimeUnit.SECONDS, "home")
                }
                assertClean(fixture)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun sourceFailuresAndCallbackReentrancyAreTerminalWithoutLeaks() {
        runSchedule("source-registration-failure") { scheduler ->
            val fixture =
                fixture(
                    source = EventSource<String>(failRegistration = true),
                    matches = { it == "home" },
                )
            fixture.subscription.arm()
            scheduler.step("registration-failed")
            assertFailure(ExactEventFailure.SOURCE) {
                fixture.subscription.await(BOUND, TimeUnit.SECONDS, "home")
            }
            assertClean(fixture)
        }

        runSchedule("source-unregister-failure") { scheduler ->
            val fixture =
                fixture(
                    source = EventSource<String>(failUnregister = true),
                    matches = { it == "home" },
                    leaseObserver = LeaseHooks(onDetachStarted = { scheduler.step("unregister") }),
                )
            fixture.subscription.arm()
            scheduler.step("emit")
            fixture.source.emit("home")
            assertFailure(ExactEventFailure.SOURCE) {
                fixture.subscription.await(BOUND, TimeUnit.SECONDS, "home")
            }
            assertClean(fixture)
        }

        runSchedule("callback-throws") { scheduler ->
            val fixture =
                fixture<String>(
                    matches = {
                        scheduler.step("callback")
                        throw SourceProblem("callback")
                    }
                )
            fixture.subscription.arm()
            fixture.source.emit("home")
            scheduler.step("source-terminal")
            assertFailure(ExactEventFailure.SOURCE) {
                fixture.subscription.await(BOUND, TimeUnit.SECONDS, "home")
            }
            assertClean(fixture)
        }

        runSchedule("source-callback-invocation-throws") { scheduler ->
            val source = EventSource<String>()
            val registration =
                LeasedExactEventRegistration<String>(
                    receiver = {
                        scheduler.step("receiver-throws")
                        throw SourceProblem("receiver")
                    },
                    register = source::register,
                    unregister = source::unregister,
                    observer = LeaseHooks(onDrained = { scheduler.step("lease-drained") }),
                )
            assertThrows(SourceProblem::class.java) { source.emit("home") }
            scheduler.step("detach")
            val drainFailure = AtomicReference<Throwable?>()
            registration.detachAndDrain(drainFailure::set)
            assertEquals(null, drainFailure.get())
            assertEquals(0, source.listenerCount)
            assertEquals(0, registration.activeLeaseCount)
            assertTrue(registration.isDrained)
        }

        runSchedule("callback-reenters-emit") { scheduler ->
            val source = EventSource<String>()
            val nested = AtomicBoolean()
            val fixture =
                fixture(
                    source = source,
                    matches = {
                        if (nested.compareAndSet(false, true)) {
                            scheduler.step("outer-callback")
                            source.emit("home")
                            scheduler.step("nested-callback")
                        }
                        it == "home"
                    },
                )
            fixture.subscription.arm()
            source.emit("home")
            scheduler.step("await")
            assertFailure(ExactEventFailure.DUPLICATE) {
                fixture.subscription.await(BOUND, TimeUnit.SECONDS, "home")
            }
            assertClean(fixture)
        }

        runSchedule("callback-reenters-close") { scheduler ->
            val reference = AtomicReference<ExactEventSubscription<String>>()
            val fixture =
                fixture<String>(
                    matches = {
                        scheduler.step("callback")
                        reference.get().close()
                        scheduler.step("close-returned")
                        true
                    }
                )
            reference.set(fixture.subscription)
            fixture.subscription.arm()
            fixture.source.emit("home")
            scheduler.step("await-cancelled")
            assertFailure(ExactEventFailure.CANCELLED) {
                fixture.subscription.await(BOUND, TimeUnit.SECONDS, "home")
            }
            assertClean(fixture)
        }
    }

    @Test
    fun simultaneousSubscribersRemainIsolated() {
        runSchedule("two-subscribers-one-close") { scheduler ->
            val source = EventSource<String>()
            val first = fixture(source, matches = { it == "first" })
            val second = fixture(source, matches = { it == "second" })
            first.subscription.arm()
            second.subscription.arm()
            scheduler.step("close-first")
            first.subscription.close()
            assertEquals(1, source.listenerCount)
            scheduler.step("emit-second")
            source.emit("second")
            scheduler.step("await-second")
            assertEquals(
                "second",
                second.subscription.await(BOUND, TimeUnit.SECONDS, "second"),
            )
            assertFailure(ExactEventFailure.CANCELLED) {
                first.subscription.await(BOUND, TimeUnit.SECONDS, "first")
            }
            assertClean(first)
            assertClean(second)
        }

        runSchedule("two-subscribers-cancel-and-independent-events") { scheduler ->
            val source = EventSource<String>()
            val first =
                fixture(
                    source,
                    matches = { it == "first" },
                    leaseObserver =
                        LeaseHooks(onDetachStarted = { scheduler.step("first-cancel-detach") }),
                    stateObserver =
                        StateHooks(
                            onAwaitClaimed = { scheduler.step("first-await-claimed") },
                            onCloseLinearized = { _, ownsDetach ->
                                if (ownsDetach) {
                                    scheduler.step("owner-cancel-linearized")
                                    scheduler.await("await-cancel-linearized")
                                } else {
                                    scheduler.step("await-cancel-linearized")
                                }
                            },
                        ),
                )
            val second = fixture(source, matches = { it == "second" })
            val executor = Executors.newSingleThreadExecutor()
            try {
                first.subscription.arm()
                second.subscription.arm()
                val cancelled = executor.submitAwait(first.subscription)
                scheduler.await("first-await-claimed")
                scheduler.step("cancel-first")
                first.subscription.close()
                assertFutureFailure(cancelled, ExactEventFailure.CANCELLED)
                scheduler.step("wrong-for-second")
                source.emit("first")
                assertFalse(second.subscription.hasAcceptedEvent())
                scheduler.step("second-event")
                source.emit("second")
                assertEquals(
                    "second",
                    second.subscription.await(BOUND, TimeUnit.SECONDS, "second"),
                )
                assertClean(first)
                assertClean(second)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun lifecycleGenerationDrainsCapturedOldActivityAfterNewEvent() {
        runSchedule("lifecycle-old-captured-new-generation-stale-release") { scheduler ->
            val acquisition = AtomicInteger()
            val source = EventSource<GenerationEvent>()
            val fixture =
                fixture(
                    source,
                    matches = { it.kind == "ready" && it.generation == 2 },
                    classify = { event ->
                        if (event.generation == 2) {
                            ExactEventValueCategory.GENERATION_CURRENT
                        } else {
                            ExactEventValueCategory.GENERATION_STALE
                        }
                    },
                    leaseObserver =
                        LeaseHooks(
                            onAcquired = {
                                if (acquisition.incrementAndGet() == 1) {
                                    scheduler.step("old-acquired")
                                    scheduler.await("old-release")
                                }
                            },
                            onDetachStarted = { scheduler.step("lifecycle-detach") },
                            onUnregistered = { scheduler.step("lifecycle-unregistered") },
                        ),
                )
            val executor = Executors.newFixedThreadPool(2)
            try {
                fixture.subscription.arm()
                val old = fixture.source.capture(GenerationEvent("ready", 1))
                val oldFuture = executor.submit { old.invoke() }
                scheduler.await("old-acquired")
                scheduler.step("new-ready")
                fixture.source.emit(GenerationEvent("ready", 2))
                val result =
                    executor.submit<GenerationEvent> {
                        fixture.subscription.await(BOUND, TimeUnit.SECONDS, "generation 2")
                    }
                scheduler.await("lifecycle-detach")
                scheduler.await("lifecycle-unregistered")
                scheduler.step("old-release")
                oldFuture.get(BOUND, TimeUnit.SECONDS)
                assertEquals(2, result.get(BOUND, TimeUnit.SECONDS).generation)
                assertClean(fixture)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun navigationSchedulesRejectWrongStaleAndDuplicateRoutesAndAcceptBack() {
        runSchedule("nav-wrong-route") { scheduler ->
            val fixture =
                fixture<RouteEvent>(
                    matches = { it.route == "home" && it.generation == 2 },
                    classify = ::routeValueCategory,
                )
            fixture.subscription.arm()
            scheduler.step("wrong")
            fixture.source.emit(RouteEvent("settings", 2))
            scheduler.step("home")
            fixture.source.emit(RouteEvent("home", 2))
            assertEquals("home", fixture.subscription.await(BOUND, TimeUnit.SECONDS, "home").route)
            assertClean(fixture)
        }

        runSchedule("nav-stale-generation") { scheduler ->
            val source = EventSource<RouteEvent>()
            val replayingRegistration: (((RouteEvent) -> Unit) -> Unit) = { listener ->
                source.register(listener)
                scheduler.step("registration-replay")
                listener(RouteEvent("home", 1))
            }
            val registration = AtomicReference<LeasedExactEventRegistration<RouteEvent>?>()
            val subscription =
                ExactEventSubscription(
                    matches = { it.route == "home" && it.generation == 2 },
                    classify = ::routeValueCategory,
                    subscribe = { receiver ->
                        LeasedExactEventRegistration(
                                receiver,
                                replayingRegistration,
                                source::unregister,
                            )
                            .also(registration::set)
                    },
                )
            val fixture = Fixture(source, subscription, registration)
            subscription.arm()
            assertFalse(subscription.hasAcceptedEvent())
            scheduler.step("generation-two")
            source.emit(RouteEvent("home", 2))
            assertEquals(2, subscription.await(BOUND, TimeUnit.SECONDS, "home").generation)
            assertClean(fixture)
        }

        runSchedule("nav-duplicate-route") { scheduler ->
            val fixture =
                fixture<RouteEvent>(
                    matches = { it.route == "details" },
                    classify = ::routeValueCategory,
                )
            fixture.subscription.arm()
            scheduler.step("first-details")
            fixture.source.emit(RouteEvent("details", 2))
            scheduler.step("duplicate-details")
            fixture.source.emit(RouteEvent("details", 2))
            assertFailure(ExactEventFailure.DUPLICATE) {
                fixture.subscription.await(BOUND, TimeUnit.SECONDS, "details")
            }
            assertClean(fixture)
        }

        runSchedule("nav-back-to-home") { scheduler ->
            val fixture =
                fixture<RouteEvent>(
                    matches = { it.route == "home" },
                    classify = ::routeValueCategory,
                )
            fixture.subscription.arm()
            scheduler.step("details")
            fixture.source.emit(RouteEvent("details", 2))
            scheduler.step("back-home")
            fixture.source.emit(RouteEvent("home", 2))
            assertEquals("home", fixture.subscription.await(BOUND, TimeUnit.SECONDS, "home").route)
            assertClean(fixture)
        }
    }

    private data class GenerationEvent(val kind: String, val generation: Int)

    private data class RouteEvent(val route: String, val generation: Int)

    private fun routeValueCategory(event: RouteEvent): ExactEventValueCategory =
        when (event.route) {
            "home" -> ExactEventValueCategory.ROUTE_HOME
            "details" -> ExactEventValueCategory.ROUTE_DETAILS
            "settings" -> ExactEventValueCategory.ROUTE_SETTINGS
            else -> ExactEventValueCategory.GENERIC
        }

    private class OpaqueEvent(
        private val label: String,
        private val render: () -> String = { label },
    ) {
        override fun toString(): String = render()
    }

    private enum class RouteCategory {
        HOME,
        DETAILS,
    }

    private fun captureSharedCallbackOrder(
        event: OpaqueEvent,
        reverse: Boolean,
    ): ExactEventBehaviorSnapshot = captureBehavior {
        val source = EventSource<OpaqueEvent>()
        val first = fixture(source, matches = { true })
        val second = fixture(source, matches = { true })
        first.subscription.arm()
        second.subscription.arm()
        val firstDispatch = source.capture(event, listenerIndex = 0)
        val secondDispatch = source.capture(event, listenerIndex = 1)
        if (reverse) {
            secondDispatch.invoke()
            firstDispatch.invoke()
        } else {
            firstDispatch.invoke()
            secondDispatch.invoke()
        }
        assertTrue(first.subscription.await(BOUND, TimeUnit.SECONDS, "opaque") === event)
        assertTrue(second.subscription.await(BOUND, TimeUnit.SECONDS, "opaque") === event)
        assertClean(first)
        assertClean(second)
    }

    private fun captureMatchFailure(message: String): ExactEventBehaviorSnapshot = captureBehavior {
        val fixture = fixture<OpaqueEvent>(matches = { throw SourceProblem(message) })
        fixture.subscription.arm()
        fixture.source.emit(OpaqueEvent("ignored"))
        assertFailure(ExactEventFailure.SOURCE) {
            fixture.subscription.await(BOUND, TimeUnit.SECONDS, "opaque")
        }
        assertClean(fixture)
    }

    private fun captureDuplicateBehavior(): ExactEventBehaviorSnapshot = captureBehavior {
        val fixture = fixture<OpaqueEvent>(matches = { true })
        val event = OpaqueEvent("ignored")
        fixture.subscription.arm()
        fixture.source.emit(event)
        fixture.source.emit(event)
        assertFailure(ExactEventFailure.DUPLICATE) {
            fixture.subscription.await(BOUND, TimeUnit.SECONDS, "opaque")
        }
        assertClean(fixture)
    }

    private fun captureRouteBehavior(route: RouteCategory): ExactEventBehaviorSnapshot =
        captureBehavior {
            val fixture =
                fixture<RouteCategory>(
                    matches = { it == RouteCategory.HOME },
                    classify = { value ->
                        when (value) {
                            RouteCategory.HOME -> ExactEventValueCategory.ROUTE_HOME
                            RouteCategory.DETAILS -> ExactEventValueCategory.ROUTE_DETAILS
                        }
                    },
                )
            fixture.subscription.arm()
            if (route == RouteCategory.DETAILS) fixture.source.emit(RouteCategory.DETAILS)
            fixture.source.emit(RouteCategory.HOME)
            assertEquals(
                RouteCategory.HOME,
                fixture.subscription.await(BOUND, TimeUnit.SECONDS, "route"),
            )
            assertClean(fixture)
        }

    private fun captureExactlyOnceBehavior(): ExactEventBehaviorSnapshot = captureBehavior {
        val fixture = fixture<String>(matches = { it == "home" })
        fixture.subscription.arm()
        fixture.source.emit("home")
        assertEquals("home", fixture.subscription.await(BOUND, TimeUnit.SECONDS, "home"))
        fixture.subscription.close()
        assertClean(fixture)
    }

    private fun captureAwaitBeforeEventBehavior(): ExactEventBehaviorSnapshot = captureBehavior {
        val awaitClaimed = CountDownLatch(1)
        val fixture =
            fixture<String>(
                matches = { it == "home" },
                stateObserver = StateHooks(onAwaitClaimed = awaitClaimed::countDown),
            )
        val executor = Executors.newSingleThreadExecutor()
        try {
            fixture.subscription.arm()
            val result = executor.submitAwait(fixture.subscription)
            check(awaitClaimed.await(BOUND, TimeUnit.SECONDS))
            fixture.source.emit("home")
            assertEquals("home", result.get(BOUND, TimeUnit.SECONDS))
            fixture.subscription.close()
            assertClean(fixture)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun captureBehavior(block: () -> Unit): ExactEventBehaviorSnapshot {
        val capture = ExactEventBehaviorTrace.start()
        return try {
            block()
            capture.finish()
        } catch (failure: Throwable) {
            capture.finish()
            throw failure
        }
    }

    private fun requireUniqueBehaviorTraces(
        executions: List<Pair<String, ExactEventBehaviorSnapshot>>
    ) {
        val collisions = executions.groupBy { it.second.normalized }.filterValues { it.size > 1 }
        check(collisions.isEmpty()) {
            "metadata-free behavior trace collision: " +
                collisions.values.joinToString { collision -> collision.joinToString { it.first } }
        }
    }

    private fun runSchedule(name: String, block: (DeterministicScheduler) -> Unit) {
        val definition = SCHEDULES.single { it.name == name }
        val scheduler = DeterministicScheduler(definition)
        val capture = ExactEventBehaviorTrace.start()
        val snapshot =
            try {
                block(scheduler)
                scheduler.assertComplete()
                capture.finish()
            } catch (failure: Throwable) {
                capture.finish()
                throw failure
            }
        check(EXECUTED_BEHAVIORS.putIfAbsent(definition.name, snapshot) == null) {
            "${definition.name}: schedule이 두 번 실행되었다"
        }
        EXECUTED_BOUNDARIES.merge(definition.boundaryTag, 1, Int::plus)
        Log.i(TRACE_LOG_TAG, "${definition.name}|${snapshot.hash}")
    }

    private fun <T> assertClean(fixture: Fixture<T>) {
        assertEquals(0, fixture.source.listenerCount)
        fixture.registration.get()?.let { registration ->
            assertEquals(0, registration.activeLeaseCount)
            assertTrue(registration.isDrained)
        }
    }

    private fun ExecutorService.submitAwait(
        subscription: ExactEventSubscription<String>
    ): Future<String> = submit<String> { subscription.await(BOUND, TimeUnit.SECONDS, "home") }

    private fun assertFutureFailure(future: Future<*>, expected: ExactEventFailure) {
        val thrown =
            assertThrows(java.util.concurrent.ExecutionException::class.java) {
                future.get(BOUND, TimeUnit.SECONDS)
            }
        assertEquals(expected, (thrown.cause as ExactEventException).failure)
    }

    private fun assertFailure(expected: ExactEventFailure, block: () -> Unit) {
        val thrown = assertThrows(ExactEventException::class.java, block)
        assertEquals(expected, thrown.failure)
    }

    companion object {
        const val BOUND = 3L
        private const val TRACE_LOG_TAG = "ExactEventBehavior"
        private val EXECUTED_BEHAVIORS = ConcurrentHashMap<String, ExactEventBehaviorSnapshot>()
        private val EXECUTED_BOUNDARIES = ConcurrentHashMap<String, Int>()

        @BeforeClass
        @JvmStatic
        fun resetExecutedTraceAudit() {
            EXECUTED_BEHAVIORS.clear()
            EXECUTED_BOUNDARIES.clear()
        }

        @AfterClass
        @JvmStatic
        fun verifyExecutedTraceAudit() {
            assertEquals(29, SCHEDULES.size)
            assertEquals(SCHEDULES.map { it.name }.toSet(), EXECUTED_BEHAVIORS.keys)
            val executions = EXECUTED_BEHAVIORS.entries.map { it.key to it.value }
            val collisions =
                executions.groupBy { it.second.normalized }.filterValues { it.size > 1 }
            assertTrue(
                "metadata-free behavior collisions: " +
                    collisions.values.joinToString { group -> group.joinToString { it.first } },
                collisions.isEmpty(),
            )
            assertEquals(29, EXECUTED_BEHAVIORS.values.map { it.hash }.toSet().size)
            assertEquals(SCHEDULES.map { it.boundaryTag }.toSet(), EXECUTED_BOUNDARIES.keys)
            assertTrue(EXECUTED_BOUNDARIES.values.all { it == 1 })
        }

        private val SCHEDULES =
            listOf(
                Schedule("first-emit-before-await", "po:emit<await", listOf("emit", "await")),
                Schedule(
                    "await-before-first-emit",
                    "po:await<emit<terminal",
                    listOf("await-claimed", "emit", "terminal"),
                ),
                Schedule(
                    "duplicate-before-await",
                    "po:first<duplicate<await",
                    listOf("first", "duplicate", "await"),
                ),
                Schedule(
                    "duplicate-during-await",
                    "po:await<first<select<duplicate",
                    listOf("await-claimed", "first", "success-selected", "duplicate-received"),
                ),
                Schedule(
                    "duplicate-acquired-before-detach",
                    "po:dup-acquire<first<detach<dup-release",
                    listOf("duplicate-acquired", "first", "detach-started", "duplicate-release"),
                ),
                Schedule(
                    "duplicate-at-drain-completion",
                    "po:first<dup-count<detach<dup-release<drain",
                    listOf(
                        "first",
                        "duplicate-counted",
                        "detach-started",
                        "duplicate-release",
                        "drain-complete",
                    ),
                ),
                Schedule(
                    "cancellation-before-emit",
                    "po:cancel<rejected-emit",
                    listOf("close", "emit-rejected"),
                ),
                Schedule(
                    "emit-acquired-then-cancellation",
                    "po:await<acquire<cancel-owner<await-cancel<detach<release<terminal",
                    listOf(
                        "cancel-await-claimed",
                        "cancel-event-acquired",
                        "cancel-owner-linearized",
                        "cancel-await-linearized",
                        "cancel-detach-started",
                        "cancel-event-release",
                        "cancel-terminal",
                    ),
                ),
                Schedule(
                    "cancellation-while-drain-waits",
                    "po:first<dup-acquire<success-drain<cancel<dup-release<cancel-terminal",
                    listOf(
                        "drain-first-event",
                        "drain-duplicate-acquired",
                        "success-drain-started",
                        "drain-cancellation-linearized",
                        "drain-duplicate-release",
                        "drain-cancel-terminal",
                    ),
                ),
                Schedule(
                    "cancellation-after-terminal",
                    "po:emit<success<late-cancel",
                    listOf("emit", "success", "late-close"),
                ),
                Schedule(
                    "timeout-before-emit",
                    "po:timeout<rejected-emit",
                    listOf("timeout", "emit-rejected"),
                ),
                Schedule(
                    "emit-acquired-before-timeout",
                    "po:acquire<timeout-detach<release",
                    listOf("emit-acquired", "timeout-detach", "emit-release"),
                ),
                Schedule(
                    "timeout-while-drain-waits",
                    "po:wrong-acquire<timeout-wait<wrong-release",
                    listOf("emit-acquired", "timeout-detach", "emit-release"),
                ),
                Schedule("close-before-emit", "po:close<emit", listOf("close", "emit")),
                Schedule(
                    "close-concurrent-with-acquired-callback",
                    "po:acquire<close<detach<release<terminal<second-close-observe",
                    listOf(
                        "close-callback-acquired",
                        "close-linearized",
                        "close-detach-started",
                        "close-callback-release",
                        "close-terminal",
                        "second-close-observed-closed",
                    ),
                ),
                Schedule(
                    "double-close",
                    "po:first-detach<second-close<second-linearized<detach-release",
                    listOf(
                        "first-detach",
                        "second-close",
                        "second-close-linearized",
                        "first-detach-release",
                    ),
                ),
                Schedule(
                    "source-registration-failure",
                    "po:registration-failure",
                    listOf("registration-failed"),
                ),
                Schedule(
                    "source-unregister-failure",
                    "po:emit<unregister-failure",
                    listOf("emit", "unregister"),
                ),
                Schedule(
                    "callback-throws",
                    "po:callback-throw<source-terminal",
                    listOf("callback", "source-terminal"),
                ),
                Schedule(
                    "source-callback-invocation-throws",
                    "po:receiver-throw<detach<lease-drain",
                    listOf("receiver-throws", "detach", "lease-drained"),
                ),
                Schedule(
                    "callback-reenters-emit",
                    "po:outer<nested<await",
                    listOf("outer-callback", "nested-callback", "await"),
                ),
                Schedule(
                    "callback-reenters-close",
                    "po:callback<reentrant-close-return<await-cancel",
                    listOf("callback", "close-returned", "await-cancelled"),
                ),
                Schedule(
                    "two-subscribers-one-close",
                    "po:close-first<emit-second<await-second",
                    listOf("close-first", "emit-second", "await-second"),
                ),
                Schedule(
                    "two-subscribers-cancel-and-independent-events",
                    "po:await-first<cancel-first<owner-cancel<await-cancel<detach<wrong<right",
                    listOf(
                        "first-await-claimed",
                        "cancel-first",
                        "owner-cancel-linearized",
                        "await-cancel-linearized",
                        "first-cancel-detach",
                        "wrong-for-second",
                        "second-event",
                    ),
                ),
                Schedule(
                    "lifecycle-old-captured-new-generation-stale-release",
                    "po:old-acquire<new-ready<detach<unregistered<old-release",
                    listOf(
                        "old-acquired",
                        "new-ready",
                        "lifecycle-detach",
                        "lifecycle-unregistered",
                        "old-release",
                    ),
                ),
                Schedule(
                    "nav-wrong-route",
                    "po:wrong-route<home-route",
                    listOf("wrong", "home"),
                ),
                Schedule(
                    "nav-stale-generation",
                    "po:registration-replay<generation-two",
                    listOf("registration-replay", "generation-two"),
                ),
                Schedule(
                    "nav-duplicate-route",
                    "po:first-details<duplicate-details",
                    listOf("first-details", "duplicate-details"),
                ),
                Schedule(
                    "nav-back-to-home",
                    "po:details<back-home",
                    listOf("details", "back-home"),
                ),
            )
    }
}
