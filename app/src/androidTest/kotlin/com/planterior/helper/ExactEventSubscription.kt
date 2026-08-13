package com.planterior.helper

import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Metadata를 받지 않고 helper 경계에서 관찰한 실행 사실만 보존하는 trace snapshot이다. */
internal data class ExactEventBehaviorSnapshot(
    val normalized: String,
    val hash: String,
)

/** 한 번에 하나의 instrumentation scenario를 기록한다. Scenario에는 append API를 노출하지 않는다. */
internal object ExactEventBehaviorTrace {
    fun start(): Capture = startBehaviorCapture()

    internal class Capture private constructor(private val token: Any) {
        fun finish(): ExactEventBehaviorSnapshot = finishBehaviorCapture(token)

        internal companion object {
            fun create(token: Any) = Capture(token)
        }
    }
}

private enum class BehaviorComponent {
    REGISTRATION,
    SUBSCRIPTION,
}

private data class BehaviorHandle(
    val recorder: BehaviorRecorder,
    val component: BehaviorComponent,
    val instance: Int,
)

private data class BehaviorEvent(
    val sequence: Int,
    val creatorThread: Boolean,
    val component: BehaviorComponent,
    val instance: Int,
    val transition: String,
    val facts: String,
)

private class BehaviorRecorder {
    private val lock = Any()
    private val creatorThread = Thread.currentThread()
    private val componentCounters = mutableMapOf<BehaviorComponent, Int>()
    private val events = mutableListOf<BehaviorEvent>()
    private var nextSequence = 0

    fun newHandle(component: BehaviorComponent): BehaviorHandle =
        synchronized(lock) {
            val instance = componentCounters.getOrDefault(component, 0)
            componentCounters[component] = instance + 1
            BehaviorHandle(this, component, instance)
        }

    fun observe(handle: BehaviorHandle, transition: String, facts: String) {
        synchronized(lock) {
            events +=
                BehaviorEvent(
                    sequence = nextSequence++,
                    creatorThread = Thread.currentThread() === creatorThread,
                    component = handle.component,
                    instance = handle.instance,
                    transition = transition,
                    facts = facts,
                )
        }
    }

    fun snapshot(): ExactEventBehaviorSnapshot {
        val captured = synchronized(lock) { events.toList() }
        captured.forEachIndexed { index, event ->
            check(event.sequence == index) { "behavior trace sequence가 단조 증가하지 않았다" }
        }
        val normalized =
            captured
                .map { event ->
                    listOf(
                            "thread=${if (event.creatorThread) "creator" else "worker"}",
                            "component=${event.component}#${event.instance}",
                            "transition=${event.transition}",
                            "facts=${event.facts}",
                        )
                        .joinToString("|")
                }
                .sorted()
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedBy { it.key }
                .joinToString(";") { (transition, count) -> "$transition|count=$count" }
        val hash =
            MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        return ExactEventBehaviorSnapshot(normalized, hash)
    }
}

private val behaviorCaptureLock = Any()
private var activeBehaviorRecorder: BehaviorRecorder? = null

private fun startBehaviorCapture(): ExactEventBehaviorTrace.Capture =
    synchronized(behaviorCaptureLock) {
        check(activeBehaviorRecorder == null) { "behavior capture는 한 번에 하나만 실행할 수 있다" }
        val recorder = BehaviorRecorder()
        activeBehaviorRecorder = recorder
        ExactEventBehaviorTrace.Capture.create(recorder)
    }

private fun finishBehaviorCapture(token: Any): ExactEventBehaviorSnapshot =
    synchronized(behaviorCaptureLock) {
        val recorder = token as BehaviorRecorder
        check(activeBehaviorRecorder === recorder) { "다른 behavior capture를 종료할 수 없다" }
        activeBehaviorRecorder = null
        recorder.snapshot()
    }

private fun newBehaviorHandle(component: BehaviorComponent): BehaviorHandle? =
    synchronized(behaviorCaptureLock) { activeBehaviorRecorder?.newHandle(component) }

private fun BehaviorHandle?.observe(transition: String, vararg facts: Pair<String, Any?>) {
    if (this == null) return
    val normalizedFacts =
        facts.joinToString(",") { (key, value) -> "$key=${canonicalBehaviorValue(value)}" }
    recorder.observe(this, transition, normalizedFacts)
}

private fun canonicalBehaviorValue(value: Any?): String =
    when (value) {
        null -> "null"
        is String -> "string:${value.replace("|", "_").replace(";", "_")}"
        is Enum<*> -> "enum:${value.javaClass.simpleName}:${value.name}"
        is Throwable -> "throwable:${value.javaClass.simpleName}"
        else ->
            "${value.javaClass.simpleName}:${value.toString().replace("|", "_").replace(";", "_")}"
    }

/**
 * Source adapter와 구독 사이의 유일한 registration 계약이다.
 *
 * Adapter는 callback을 외부로 넘기기 전에 자신의 lock 아래에서 in-flight lease를 증가시킨다. [detachAndDrain]은 같은 lock
 * 아래에서 새 lease를 막은 뒤 source listener를 제거하고, 제거 전에 획득된 모든 lease가 반환된 뒤에만 [onDrained]을 호출한다. 제거 또는
 * callback 처리 실패는 [onDrained]에 전달한다. 현재 callback이 재진입해 닫는 경우에는 자신을 기다리는 교착을 피하려고 `true`를 반환하며,
 * terminal 전이는 callback의 `finally`가 lease를 반환한 뒤 일어난다.
 *
 * Adapter lock -> source/helper lock 순서로 lock을 중첩하지 않는다. Source 등록/제거, callback 호출, [onDrained] 호출은
 * 모두 adapter lock 밖에서 실행한다.
 */
internal fun interface ExactEventRegistration {
    fun detachAndDrain(onDrained: (Throwable?) -> Unit): Boolean
}

/** Lease 선형화 지점을 결정적 테스트 scheduler에 노출한다. 제품 adapter는 기본 no-op observer를 쓴다. */
internal interface ExactEventLeaseObserver {
    fun acquired() = Unit

    fun detachStarted() = Unit

    fun releasing() = Unit

    fun drained() = Unit

    companion object {
        val NONE = object : ExactEventLeaseObserver {}
    }
}

/**
 * NavController와 ActivityLifecycleCallbacks가 공유하는 lease/drain adapter이다.
 *
 * [register]와 [unregister]는 adapter lock 밖에서 호출된다. 등록 중 동기 replay도 정상적으로 lease를 얻으며, 등록 실패 시 가능한
 * 제거를 시도한 뒤 원래 실패를 전파한다. 제거 실패도 이미 획득된 lease가 모두 반환된 다음 terminal source failure로 전달된다.
 */
internal class LeasedExactEventRegistration<T>(
    private val receiver: (T) -> Unit,
    private val register: ((T) -> Unit) -> Unit,
    private val unregister: ((T) -> Unit) -> Unit,
    private val observer: ExactEventLeaseObserver = ExactEventLeaseObserver.NONE,
) : ExactEventRegistration {
    private val lock = Any()
    private val behavior = newBehaviorHandle(BehaviorComponent.REGISTRATION)
    private val callbackDepth = ThreadLocal.withInitial { 0 }
    private var accepting = true
    private var inFlight = 0
    private var detachStarted = false
    private var sourceDetached = false
    private var detachCompleted = false
    private var detachFailure: Throwable? = null
    private var callbackGeneration = 0
    private val drainCallbacks = mutableListOf<(Throwable?) -> Unit>()

    private data class AcquiredLease(
        val generation: Int,
        val inFlight: Int,
    )

    private val sourceCallback: (T) -> Unit = callback@{ value ->
        val lease =
            synchronized(lock) {
                if (!accepting) {
                    null
                } else {
                    inFlight += 1
                    callbackGeneration += 1
                    callbackDepth.set((callbackDepth.get() ?: 0) + 1)
                    AcquiredLease(callbackGeneration, inFlight)
                }
            }
        if (lease == null) {
            behavior.observe(
                "CALLBACK_REJECTED",
                "value" to value,
                "listenerCount" to 0,
                "inFlight" to activeLeaseCount,
            )
            return@callback
        }

        behavior.observe(
            "LEASE_ACQUIRED",
            "generation" to lease.generation,
            "value" to value,
            "listenerCount" to 1,
            "inFlight" to lease.inFlight,
        )
        try {
            observer.acquired()
            behavior.observe(
                "CALLBACK_DISPATCH",
                "generation" to lease.generation,
                "value" to value,
            )
            receiver(value)
        } catch (failure: Throwable) {
            behavior.observe(
                "CALLBACK_FAILED",
                "generation" to lease.generation,
                "failure" to failure,
            )
            throw failure
        } finally {
            observer.releasing()
            val release =
                synchronized(lock) {
                    callbackDepth.set((callbackDepth.get() ?: 0) - 1)
                    inFlight -= 1
                    check(inFlight >= 0) { "callback lease가 중복 반환되었다" }
                    Triple(inFlight, accepting, takeDrainCompletionLocked())
                }
            behavior.observe(
                "LEASE_RELEASED",
                "generation" to lease.generation,
                "listenerCount" to if (release.second) 1 else 0,
                "inFlight" to release.first,
            )
            release.third?.invoke()
        }
    }

    init {
        behavior.observe("SOURCE_REGISTER_BEGIN", "listenerCount" to 0, "inFlight" to 0)
        try {
            register(sourceCallback)
            behavior.observe("SOURCE_REGISTERED", "listenerCount" to 1, "inFlight" to 0)
        } catch (registrationFailure: Throwable) {
            synchronized(lock) {
                accepting = false
                detachStarted = true
            }
            behavior.observe(
                "SOURCE_REGISTER_FAILED",
                "failure" to registrationFailure,
                "listenerCount" to 0,
                "inFlight" to 0,
            )
            val cleanupFailure = runCatching { unregister(sourceCallback) }.exceptionOrNull()
            cleanupFailure?.let(registrationFailure::addSuppressed)
            behavior.observe(
                "SOURCE_REGISTER_FAILURE_CLEANUP",
                "failure" to cleanupFailure,
                "listenerCount" to 0,
                "inFlight" to 0,
            )
            throw registrationFailure
        }
    }

    val activeLeaseCount: Int
        get() = synchronized(lock) { inFlight }

    val isDrained: Boolean
        get() = synchronized(lock) { detachCompleted && inFlight == 0 }

    override fun detachAndDrain(onDrained: (Throwable?) -> Unit): Boolean {
        val callerOwnsLease = (callbackDepth.get() ?: 0) > 0
        behavior.observe(
            "DETACH_INVOKED",
            "callerOwnsLease" to callerOwnsLease,
            "listenerCount" to if (synchronized(lock) { accepting }) 1 else 0,
            "inFlight" to activeLeaseCount,
        )
        val ownsSourceDetach: Boolean
        val alreadyCompleted: Boolean
        val completedFailure: Throwable?
        synchronized(lock) {
            alreadyCompleted = detachCompleted
            completedFailure = detachFailure
            if (alreadyCompleted) {
                ownsSourceDetach = false
            } else {
                drainCallbacks += onDrained
                ownsSourceDetach = !detachStarted
                if (ownsSourceDetach) {
                    accepting = false
                    detachStarted = true
                }
            }
        }

        behavior.observe(
            "DETACH_LINEARIZED",
            "ownsDetach" to ownsSourceDetach,
            "alreadyCompleted" to alreadyCompleted,
            "listenerCount" to if (synchronized(lock) { accepting }) 1 else 0,
            "inFlight" to activeLeaseCount,
        )
        if (alreadyCompleted) {
            behavior.observe(
                "DRAIN_OBSERVED_CLOSED",
                "failure" to completedFailure,
                "listenerCount" to 0,
                "inFlight" to activeLeaseCount,
            )
            onDrained(completedFailure)
            return callerOwnsLease
        }
        if (!ownsSourceDetach) return callerOwnsLease

        behavior.observe(
            "SOURCE_UNREGISTER_BEGIN",
            "listenerCount" to 1,
            "inFlight" to activeLeaseCount,
        )
        observer.detachStarted()
        val removalFailure = runCatching { unregister(sourceCallback) }.exceptionOrNull()
        behavior.observe(
            if (removalFailure == null) "SOURCE_UNREGISTERED" else "SOURCE_UNREGISTER_FAILED",
            "failure" to removalFailure,
            "listenerCount" to 0,
            "inFlight" to activeLeaseCount,
        )
        val completion =
            synchronized(lock) {
                sourceDetached = true
                detachFailure = removalFailure
                takeDrainCompletionLocked()
            }
        completion?.invoke()
        return callerOwnsLease
    }

    private fun takeDrainCompletionLocked(): (() -> Unit)? {
        if (!detachStarted || !sourceDetached || inFlight != 0 || detachCompleted) return null
        detachCompleted = true
        val failure = detachFailure
        val callbacks = drainCallbacks.toList()
        drainCallbacks.clear()
        return {
            behavior.observe(
                "DRAIN_COMPLETED",
                "failure" to failure,
                "listenerCount" to 0,
                "inFlight" to 0,
            )
            observer.drained()
            callbacks.forEach { it(failure) }
        }
    }
}

/** Helper 상태 선형화 지점을 결정적 scheduler에 노출한다. */
internal interface ExactEventStateObserver {
    fun awaitClaimed() = Unit

    fun closeSelected(reason: String) = Unit

    fun closeLinearized(reason: String, ownsDetach: Boolean) = Unit

    fun terminal(outcome: String) = Unit

    companion object {
        val NONE = object : ExactEventStateObserver {}
    }
}

/** 기계적으로 단언하는 exact-event 종료 사유이다. */
internal enum class ExactEventFailure {
    TIMEOUT,
    CANCELLED,
    DUPLICATE,
    SOURCE,
}

/** exact-event 관찰이 성공으로 닫히지 못했음을 나타낸다. */
internal class ExactEventException(
    val failure: ExactEventFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.name, cause)

/**
 * 트리거 전에 등록한 실제 이벤트 관찰을 단일 lock 상태 기계로 닫는다.
 *
 * Callback lease는 helper lock을 잡기 전에 adapter에서 획득된다. 닫기 중 도착한 callback은 제거 전에 이미 획득된 callback이므로 동일
 * 세대에 포함된다. 성공은 detach/drain completion 뒤 정확히 한 이벤트일 때만 확정된다.
 */
internal class ExactEventSubscription<T>(
    private val matches: (T) -> Boolean,
    subscribe: ((T) -> Unit) -> ExactEventRegistration,
    private val stateObserver: ExactEventStateObserver = ExactEventStateObserver.NONE,
) : AutoCloseable {
    private val lock = Any()
    private val behavior = newBehaviorHandle(BehaviorComponent.SUBSCRIPTION)
    private val eventOrCancellation = CountDownLatch(1)
    private val closed = CountDownLatch(1)
    private var phase = Phase.REGISTERED
    private var closeReason: CloseReason? = null
    private var outcome: Outcome<T>? = null
    private var accepted: T? = null
    private var matchingEventCount = 0
    private var awaitClaimed = false
    private var sourceFailure: Throwable? = null
    private var registration: ExactEventRegistration? = null

    init {
        behavior.observe("SUBSCRIBE_BEGIN", "phase" to phase)
        try {
            registration = subscribe(::onEvent)
            behavior.observe("SUBSCRIBED", "phase" to phase, "sourceFailure" to null)
        } catch (failure: Throwable) {
            sourceFailure = failure
            eventOrCancellation.countDown()
            behavior.observe("SUBSCRIBE_FAILED", "phase" to phase, "sourceFailure" to failure)
        }
    }

    /** 구독 등록 중 전달되는 초기값과 트리거 이후 이벤트를 구분한다. */
    fun arm() {
        val transition =
            synchronized(lock) {
                check(phase == Phase.REGISTERED) { "이벤트 구독은 한 번만 시작할 수 있다" }
                val previous = phase
                phase = Phase.ARMED
                previous to phase
            }
        behavior.observe("ARMED", "from" to transition.first, "to" to transition.second)
    }

    /** 정확한 이벤트 하나와 listener detach/drain terminal을 제한 시간 안에 기다린다. */
    fun await(timeout: Long, unit: TimeUnit, description: String): T {
        behavior.observe(
            "AWAIT_INVOKED",
            "phase" to synchronized(lock) { phase },
            "timeoutNanos" to unit.toNanos(timeout),
        )
        val claimedPhase =
            synchronized(lock) {
                check(phase != Phase.REGISTERED) { "이벤트 구독을 먼저 시작해야 한다" }
                check(!awaitClaimed) { "이벤트 구독 결과는 한 번만 기다릴 수 있다" }
                awaitClaimed = true
                phase
            }
        behavior.observe("AWAIT_CLAIMED", "phase" to claimedPhase)
        stateObserver.awaitClaimed()

        val signalled = eventOrCancellation.await(timeout, unit)
        val reasonAndCount =
            synchronized(lock) {
                val reason =
                    when {
                        sourceFailure != null -> CloseReason.SOURCE
                        closeReason == CloseReason.CANCELLED || phase == Phase.CLOSED ->
                            CloseReason.CANCELLED
                        signalled && matchingEventCount > 0 -> CloseReason.SUCCESS
                        else -> CloseReason.TIMEOUT
                    }
                Triple(reason, matchingEventCount, phase)
            }
        val reason = reasonAndCount.first
        behavior.observe(
            "AWAIT_RESOLVED",
            "signalled" to signalled,
            "reason" to reason,
            "matchingCount" to reasonAndCount.second,
            "phase" to reasonAndCount.third,
        )
        stateObserver.closeSelected(reason.name)
        val terminal =
            checkNotNull(
                closeObservation(
                    reason,
                    CLOSE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                    false,
                )
            )
        return when (terminal) {
            is Outcome.Success -> {
                behavior.observe(
                    "AWAIT_RETURNED",
                    "outcome" to "SUCCESS",
                    "value" to terminal.value,
                )
                terminal.value
            }
            is Outcome.Failure -> {
                behavior.observe(
                    "AWAIT_THROWN",
                    "outcome" to terminal.failure,
                    "failure" to terminal.cause,
                )
                throw ExactEventException(terminal.failure, terminal.cause).also {
                    it.addSuppressed(IllegalStateException(description))
                }
            }
        }
    }

    /** 등록 replay나 잘못된 값의 비수락을 시간 경과 없이 단언할 때 쓴다. */
    fun hasAcceptedEvent(): Boolean = synchronized(lock) { accepted != null }

    private fun onEvent(value: T) {
        behavior.observe(
            "EVENT_RECEIVED",
            "value" to value,
            "phase" to synchronized(lock) { phase },
        )
        val matched =
            try {
                matches(value)
            } catch (failure: Throwable) {
                behavior.observe("MATCH_FAILED", "value" to value, "failure" to failure)
                recordSourceFailure(failure)
                return
            }
        val observation =
            synchronized(lock) {
                if (!matched || (phase != Phase.ARMED && phase != Phase.CLOSING)) {
                    EventObservation(matched, false, matchingEventCount, phase)
                } else {
                    matchingEventCount += 1
                    if (matchingEventCount == 1) {
                        accepted = value
                        eventOrCancellation.countDown()
                    }
                    EventObservation(true, true, matchingEventCount, phase)
                }
            }
        behavior.observe(
            "EVENT_CLASSIFIED",
            "value" to value,
            "matched" to observation.matched,
            "acceptedGeneration" to observation.acceptedGeneration,
            "matchingCount" to observation.matchingCount,
            "phase" to observation.phase,
        )
    }

    private data class EventObservation(
        val matched: Boolean,
        val acceptedGeneration: Boolean,
        val matchingCount: Int,
        val phase: Phase,
    )

    private fun recordSourceFailure(failure: Throwable) {
        val recorded =
            synchronized(lock) {
                if (phase == Phase.CLOSED) {
                    false
                } else {
                    sourceFailure = sourceFailure ?: failure
                    eventOrCancellation.countDown()
                    true
                }
            }
        behavior.observe(
            "SOURCE_FAILURE_RECORDED",
            "recorded" to recorded,
            "failure" to failure,
            "phase" to synchronized(lock) { phase },
        )
    }

    private fun closeObservation(
        requestedReason: CloseReason,
        timeout: Long,
        unit: TimeUnit,
        permitReentrantReturn: Boolean,
    ): Outcome<T>? {
        behavior.observe(
            "CLOSE_OBSERVATION_INVOKED",
            "reason" to requestedReason,
            "phase" to synchronized(lock) { phase },
            "permitReentrantReturn" to permitReentrantReturn,
        )
        var immediateOutcome: Outcome<T>? = null
        val transition =
            synchronized(lock) {
                val previous = phase
                val ownsDetach =
                    when (phase) {
                        Phase.CLOSED -> {
                            immediateOutcome = checkNotNull(outcome)
                            false
                        }
                        Phase.CLOSING -> {
                            if (requestedReason == CloseReason.CANCELLED) {
                                closeReason = CloseReason.CANCELLED
                                eventOrCancellation.countDown()
                            }
                            false
                        }
                        Phase.REGISTERED,
                        Phase.ARMED -> {
                            phase = Phase.CLOSING
                            closeReason = requestedReason
                            if (requestedReason == CloseReason.CANCELLED) {
                                eventOrCancellation.countDown()
                            }
                            true
                        }
                    }
                Triple(previous, phase, ownsDetach)
            }
        val ownsDetach = transition.third
        behavior.observe(
            "CLOSE_LINEARIZED",
            "reason" to requestedReason,
            "from" to transition.first,
            "to" to transition.second,
            "ownsDetach" to ownsDetach,
            "matchingCount" to synchronized(lock) { matchingEventCount },
        )
        immediateOutcome?.let {
            behavior.observe("CLOSED_OUTCOME_OBSERVED", "outcome" to outcomeLabel(it))
            return it
        }

        stateObserver.closeLinearized(requestedReason.name, ownsDetach)

        var callerOwnsLease = false
        if (ownsDetach) {
            val currentRegistration = registration
            if (currentRegistration == null) {
                completeObservation(null)
            } else {
                try {
                    callerOwnsLease = currentRegistration.detachAndDrain(::completeObservation)
                } catch (failure: Throwable) {
                    completeObservation(failure)
                }
            }
        }

        if (permitReentrantReturn && callerOwnsLease) {
            behavior.observe("REENTRANT_CLOSE_RETURNED", "callerOwnsLease" to true)
            return null
        }
        val drained = closed.await(timeout, unit)
        behavior.observe(
            "TERMINAL_WAIT_RESOLVED",
            "drained" to drained,
            "phase" to synchronized(lock) { phase },
        )
        if (!drained) {
            behavior.observe("TERMINAL_WAIT_FAILED", "outcome" to ExactEventFailure.SOURCE)
            throw ExactEventException(
                ExactEventFailure.SOURCE,
                IllegalStateException("listener detach/drain이 제한 시간 안에 끝나지 않았다"),
            )
        }
        return synchronized(lock) { checkNotNull(outcome) }
    }

    private fun completeObservation(detachFailure: Throwable?) {
        val terminalLabel =
            synchronized(lock) {
                if (phase == Phase.CLOSED) return
                sourceFailure = sourceFailure ?: detachFailure
                outcome =
                    when {
                        sourceFailure != null ->
                            Outcome.Failure(ExactEventFailure.SOURCE, sourceFailure)
                        closeReason == CloseReason.CANCELLED ->
                            Outcome.Failure(ExactEventFailure.CANCELLED)
                        matchingEventCount > 1 -> Outcome.Failure(ExactEventFailure.DUPLICATE)
                        matchingEventCount == 1 -> Outcome.Success(checkNotNull(accepted))
                        closeReason == CloseReason.TIMEOUT ->
                            Outcome.Failure(ExactEventFailure.TIMEOUT)
                        else -> Outcome.Failure(ExactEventFailure.SOURCE)
                    }
                phase = Phase.CLOSED
                closed.countDown()
                when (val terminal = checkNotNull(outcome)) {
                    is Outcome.Success -> "SUCCESS"
                    is Outcome.Failure -> terminal.failure.name
                }
            }
        behavior.observe(
            "TERMINALIZED",
            "outcome" to terminalLabel,
            "matchingCount" to synchronized(lock) { matchingEventCount },
            "phase" to synchronized(lock) { phase },
        )
        stateObserver.terminal(terminalLabel)
    }

    private fun outcomeLabel(value: Outcome<T>): String =
        when (value) {
            is Outcome.Success -> "SUCCESS"
            is Outcome.Failure -> value.failure.name
        }

    /** Callback 재진입 close는 자신을 기다리지 않고 lease의 finally가 terminal을 완성한다. */
    override fun close() {
        behavior.observe("CLOSE_INVOKED", "phase" to synchronized(lock) { phase })
        val terminal =
            closeObservation(
                CloseReason.CANCELLED,
                CLOSE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
                true,
            )
        behavior.observe(
            "CLOSE_RETURNED",
            "outcome" to terminal?.let(::outcomeLabel),
            "phase" to synchronized(lock) { phase },
        )
        if (terminal is Outcome.Failure && terminal.failure == ExactEventFailure.SOURCE) {
            throw ExactEventException(terminal.failure, terminal.cause)
        }
    }

    private enum class Phase {
        REGISTERED,
        ARMED,
        CLOSING,
        CLOSED,
    }

    private enum class CloseReason {
        SUCCESS,
        TIMEOUT,
        CANCELLED,
        SOURCE,
    }

    private sealed interface Outcome<out T> {
        data class Success<T>(val value: T) : Outcome<T>

        data class Failure(
            val failure: ExactEventFailure,
            val cause: Throwable? = null,
        ) : Outcome<Nothing>
    }

    private companion object {
        const val CLOSE_TIMEOUT_SECONDS = 10L
    }
}
