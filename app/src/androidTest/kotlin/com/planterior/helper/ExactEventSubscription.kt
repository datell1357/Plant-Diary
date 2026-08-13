package com.planterior.helper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    private val callbackDepth = ThreadLocal.withInitial { 0 }
    private var accepting = true
    private var inFlight = 0
    private var detachStarted = false
    private var sourceDetached = false
    private var detachCompleted = false
    private var detachFailure: Throwable? = null
    private val drainCallbacks = mutableListOf<(Throwable?) -> Unit>()

    private val sourceCallback: (T) -> Unit = callback@{ value ->
        val acquired =
            synchronized(lock) {
                if (!accepting) return@callback
                inFlight += 1
                callbackDepth.set((callbackDepth.get() ?: 0) + 1)
                true
            }
        if (!acquired) return@callback

        try {
            observer.acquired()
            receiver(value)
        } finally {
            observer.releasing()
            val completion =
                synchronized(lock) {
                    callbackDepth.set((callbackDepth.get() ?: 0) - 1)
                    inFlight -= 1
                    check(inFlight >= 0) { "callback lease가 중복 반환되었다" }
                    takeDrainCompletionLocked()
                }
            completion?.invoke()
        }
    }

    init {
        try {
            register(sourceCallback)
        } catch (registrationFailure: Throwable) {
            synchronized(lock) {
                accepting = false
                detachStarted = true
            }
            runCatching { unregister(sourceCallback) }
                .exceptionOrNull()
                ?.let(registrationFailure::addSuppressed)
            throw registrationFailure
        }
    }

    val activeLeaseCount: Int
        get() = synchronized(lock) { inFlight }

    val isDrained: Boolean
        get() = synchronized(lock) { detachCompleted && inFlight == 0 }

    override fun detachAndDrain(onDrained: (Throwable?) -> Unit): Boolean {
        val callerOwnsLease = (callbackDepth.get() ?: 0) > 0
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

        if (alreadyCompleted) {
            onDrained(completedFailure)
            return callerOwnsLease
        }
        if (!ownsSourceDetach) return callerOwnsLease

        observer.detachStarted()
        val removalFailure = runCatching { unregister(sourceCallback) }.exceptionOrNull()
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
        try {
            registration = subscribe(::onEvent)
        } catch (failure: Throwable) {
            sourceFailure = failure
            eventOrCancellation.countDown()
        }
    }

    /** 구독 등록 중 전달되는 초기값과 트리거 이후 이벤트를 구분한다. */
    fun arm() {
        synchronized(lock) {
            check(phase == Phase.REGISTERED) { "이벤트 구독은 한 번만 시작할 수 있다" }
            phase = Phase.ARMED
        }
    }

    /** 정확한 이벤트 하나와 listener detach/drain terminal을 제한 시간 안에 기다린다. */
    fun await(timeout: Long, unit: TimeUnit, description: String): T {
        synchronized(lock) {
            check(phase != Phase.REGISTERED) { "이벤트 구독을 먼저 시작해야 한다" }
            check(!awaitClaimed) { "이벤트 구독 결과는 한 번만 기다릴 수 있다" }
            awaitClaimed = true
        }
        stateObserver.awaitClaimed()

        val signalled = eventOrCancellation.await(timeout, unit)
        val reason =
            synchronized(lock) {
                when {
                    sourceFailure != null -> CloseReason.SOURCE
                    closeReason == CloseReason.CANCELLED || phase == Phase.CLOSED ->
                        CloseReason.CANCELLED
                    signalled && matchingEventCount > 0 -> CloseReason.SUCCESS
                    else -> CloseReason.TIMEOUT
                }
            }
        stateObserver.closeSelected(reason.name)
        return when (
            val terminal =
                checkNotNull(
                    closeObservation(
                        reason,
                        CLOSE_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                        false,
                    )
                )
        ) {
            is Outcome.Success -> terminal.value
            is Outcome.Failure ->
                throw ExactEventException(terminal.failure, terminal.cause).also {
                    it.addSuppressed(IllegalStateException(description))
                }
        }
    }

    /** 등록 replay나 잘못된 값의 비수락을 시간 경과 없이 단언할 때 쓴다. */
    fun hasAcceptedEvent(): Boolean = synchronized(lock) { accepted != null }

    private fun onEvent(value: T) {
        val matched =
            try {
                matches(value)
            } catch (failure: Throwable) {
                recordSourceFailure(failure)
                return
            }
        if (!matched) return

        synchronized(lock) {
            if (phase != Phase.ARMED && phase != Phase.CLOSING) return
            matchingEventCount += 1
            if (matchingEventCount == 1) {
                accepted = value
                eventOrCancellation.countDown()
            }
        }
    }

    private fun recordSourceFailure(failure: Throwable) {
        synchronized(lock) {
            if (phase == Phase.CLOSED) return
            sourceFailure = sourceFailure ?: failure
            eventOrCancellation.countDown()
        }
    }

    private fun closeObservation(
        requestedReason: CloseReason,
        timeout: Long,
        unit: TimeUnit,
        permitReentrantReturn: Boolean,
    ): Outcome<T>? {
        val ownsDetach =
            synchronized(lock) {
                when (phase) {
                    Phase.CLOSED -> return checkNotNull(outcome)
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

        if (permitReentrantReturn && callerOwnsLease) return null
        if (!closed.await(timeout, unit)) {
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
        stateObserver.terminal(terminalLabel)
    }

    /** Callback 재진입 close는 자신을 기다리지 않고 lease의 finally가 terminal을 완성한다. */
    override fun close() {
        val terminal =
            closeObservation(
                CloseReason.CANCELLED,
                CLOSE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
                true,
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
