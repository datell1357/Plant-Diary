package com.planterior.helper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * listener 제거와 이미 캡처된 callback 소진을 하나의 경계로 제공한다.
 *
 * [detachAndDrain]은 새 callback lease를 원자적으로 막고, 제거 전에 source가 캡처한 모든 callback이 반환된 뒤에만 끝나야 한다. 반환
 * 뒤에는 listener를 다시 호출할 수 없다. 구현은 callback이나 제거 과정이 listener를 동기 호출할 수 있으므로 helper의 lock을 획득한다고 가정하면
 * 안 된다.
 */
internal fun interface ExactEventRegistration {
    fun detachAndDrain()
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
 * 등록 중 재생된 이전 값은 [arm] 전이라 무시한다. 첫 일치 이벤트가 오면 [await]은 곧바로 성공하지 않고 source를 detach-and-drain한다. 따라서
 * 제거 전에 이미 캡처되어 실행 중인 중복 callback까지 같은 관찰 세대에 선형화한 뒤 정확히 하나였을 때만 성공한다. timeout과 [close]도 같은 닫기 전이를
 * 사용한다.
 */
internal class ExactEventSubscription<T>(
    private val matches: (T) -> Boolean,
    subscribe: ((T) -> Unit) -> ExactEventRegistration,
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
    // Source가 subscribe 안에서 동기 replay해도 위 상태가 먼저 완전히 초기화되어 있다.
    private val registration = subscribe(::onEvent)

    /** 구독 등록 중 전달되는 초기값과 트리거 이후 이벤트를 구분한다. */
    fun arm() {
        synchronized(lock) {
            check(phase == Phase.REGISTERED) { "이벤트 구독은 한 번만 시작할 수 있다" }
            phase = Phase.ARMED
        }
    }

    /** 정확한 이벤트 하나를 제한 시간 안에 받고, listener 제거와 in-flight callback 소진까지 검증한다. */
    fun await(timeout: Long, unit: TimeUnit, description: String): T {
        synchronized(lock) {
            check(phase != Phase.REGISTERED) { "이벤트 구독을 먼저 시작해야 한다" }
            check(!awaitClaimed) { "이벤트 구독 결과는 한 번만 기다릴 수 있다" }
            awaitClaimed = true
        }

        val signalled = eventOrCancellation.await(timeout, unit)
        val reason =
            synchronized(lock) {
                when {
                    closeReason == CloseReason.CANCELLED || phase == Phase.CLOSED ->
                        CloseReason.CANCELLED
                    !signalled -> CloseReason.TIMEOUT
                    matchingEventCount > 0 -> CloseReason.SUCCESS
                    else -> CloseReason.CANCELLED
                }
            }
        return when (val terminal = closeObservation(reason, timeout, unit)) {
            is Outcome.Success -> terminal.value
            is Outcome.Failure ->
                throw ExactEventException(terminal.failure).also {
                    it.addSuppressed(IllegalStateException(description))
                }
        }
    }

    /** 시간 경과 없이 등록 replay나 잘못된 값의 비수락을 단언할 때 쓴다. */
    fun hasAcceptedEvent(): Boolean = synchronized(lock) { accepted != null }

    private fun onEvent(value: T) {
        if (!matches(value)) return
        synchronized(lock) {
            if (
                phase != Phase.ARMED &&
                    !(phase == Phase.CLOSING && closeReason == CloseReason.SUCCESS)
            ) {
                return
            }
            matchingEventCount += 1
            if (matchingEventCount == 1) {
                accepted = value
                eventOrCancellation.countDown()
            }
        }
    }

    private fun closeObservation(
        requestedReason: CloseReason,
        timeout: Long,
        unit: TimeUnit,
    ): Outcome<T> {
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

        if (ownsDetach) {
            val sourceFailure = runCatching { registration.detachAndDrain() }.exceptionOrNull()
            synchronized(lock) {
                outcome =
                    when {
                        sourceFailure != null -> Outcome.Failure(ExactEventFailure.SOURCE)
                        closeReason == CloseReason.CANCELLED ->
                            Outcome.Failure(ExactEventFailure.CANCELLED)
                        closeReason == CloseReason.TIMEOUT ->
                            Outcome.Failure(ExactEventFailure.TIMEOUT)
                        matchingEventCount != 1 -> Outcome.Failure(ExactEventFailure.DUPLICATE)
                        else -> Outcome.Success(checkNotNull(accepted))
                    }
                phase = Phase.CLOSED
                closed.countDown()
            }
            sourceFailure?.let { throw ExactEventException(ExactEventFailure.SOURCE, it) }
        } else if (!closed.await(timeout, unit)) {
            throw ExactEventException(ExactEventFailure.SOURCE)
        }
        return synchronized(lock) { checkNotNull(outcome) }
    }

    /** 닫기는 진행 중인 await과 같은 terminal transition에서 cancellation으로 선형화된다. */
    override fun close() {
        closeObservation(CloseReason.CANCELLED, CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
    }

    private sealed interface Outcome<out T> {
        data class Success<T>(val value: T) : Outcome<T>

        data class Failure(val failure: ExactEventFailure) : Outcome<Nothing>
    }

    private companion object {
        const val CLOSE_TIMEOUT_SECONDS = 10L
    }
}
