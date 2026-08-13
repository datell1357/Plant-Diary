package com.planterior.helper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 트리거 전에 등록한 실제 이벤트 중 정확히 하나만 기다린다.
 *
 * 등록 과정에서 재생된 이전 값은 [arm] 전이라 무시되고, 다른 값은 조건과 맞지 않아 무시된다. 같은 값이 중복 전달되면 성공으로 숨기지 않고 실패한다.
 */
internal class ExactEventSubscription<T>(
    private val matches: (T) -> Boolean,
    subscribe: ((T) -> Unit) -> (() -> Unit),
) : AutoCloseable {
    private val armed = AtomicBoolean(false)
    private val accepted = AtomicReference<T?>()
    private val matchingEventCount = AtomicInteger(0)
    private val signal = CountDownLatch(1)
    private val unsubscribe = subscribe(::onEvent)

    /** 구독 등록 중 전달되는 초기값과 트리거 이후 이벤트를 구분한다. */
    fun arm() {
        check(armed.compareAndSet(false, true)) { "이벤트 구독은 한 번만 시작할 수 있다" }
    }

    /** 정확한 이벤트 하나를 제한 시간 안에 받았는지 검증한다. */
    fun await(timeout: Long, unit: TimeUnit, description: String): T {
        check(armed.get()) { "이벤트 구독을 먼저 시작해야 한다" }
        check(signal.await(timeout, unit)) { "$description 이벤트가 제한 시간 안에 오지 않았다" }
        check(matchingEventCount.get() == 1) {
            "$description 이벤트가 ${matchingEventCount.get()}번 전달되었다"
        }
        return checkNotNull(accepted.get())
    }

    /** 테스트가 시간 경과 없이 이전 값이나 잘못된 값의 비수락을 단언할 때 쓴다. */
    fun hasAcceptedEvent(): Boolean = accepted.get() != null

    private fun onEvent(value: T) {
        if (!armed.get() || !matches(value)) return
        if (matchingEventCount.incrementAndGet() == 1) {
            accepted.set(value)
            signal.countDown()
        }
    }

    override fun close() = unsubscribe()
}
