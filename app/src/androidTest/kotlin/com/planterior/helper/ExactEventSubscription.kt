package com.planterior.helper

import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Metadata를 받지 않고 helper 경계에서 관찰한 실행 사실만 보존하는 trace snapshot이다. */
internal data class ExactEventBehaviorSnapshot(
    val normalized: String,
    val hash: String,
)

/** 한 번에 하나의 instrumentation 실행을 기록하며 외부에는 append API를 노출하지 않는다. */
internal object ExactEventBehaviorTrace {
    fun start(): Capture = startBehaviorCapture()

    internal class Capture private constructor(private val recorder: BehaviorRecorder) {
        fun finish(): ExactEventBehaviorSnapshot = finishBehaviorCapture(recorder)

        internal companion object {
            fun create(recorder: BehaviorRecorder) = Capture(recorder)
        }
    }
}

internal enum class BehaviorComponent {
    REGISTRATION,
    SUBSCRIPTION,
}

internal enum class BehaviorThreadRole {
    CREATOR,
    WORKER,
}

internal enum class BehaviorTransition {
    CALLBACK_REJECTED,
    LEASE_ACQUIRED,
    CALLBACK_DISPATCH,
    CALLBACK_FAILED,
    LEASE_RELEASED,
    SOURCE_REGISTER_BEGIN,
    SOURCE_REGISTERED,
    SOURCE_REGISTER_FAILED,
    SOURCE_REGISTER_FAILURE_CLEANUP,
    DETACH_INVOKED,
    DETACH_LINEARIZED,
    DRAIN_OBSERVED_CLOSED,
    SOURCE_UNREGISTER_BEGIN,
    SOURCE_UNREGISTERED,
    SOURCE_UNREGISTER_FAILED,
    DRAIN_COMPLETED,
    SUBSCRIBE_BEGIN,
    SUBSCRIBED,
    SUBSCRIBE_FAILED,
    ARMED,
    AWAIT_INVOKED,
    AWAIT_CLAIMED,
    AWAIT_RESOLVED,
    AWAIT_RETURNED,
    AWAIT_THROWN,
    EVENT_RECEIVED,
    MATCH_FAILED,
    EVENT_CLASSIFIED,
    SOURCE_FAILURE_RECORDED,
    CLOSE_OBSERVATION_INVOKED,
    CLOSE_LINEARIZED,
    CLOSED_OUTCOME_OBSERVED,
    REENTRANT_CLOSE_RETURNED,
    TERMINAL_WAIT_RESOLVED,
    TERMINAL_WAIT_FAILED,
    TERMINALIZED,
    CLOSE_INVOKED,
    CLOSE_RETURNED,
}

internal enum class BehaviorPhase {
    NONE,
    REGISTERED,
    ARMED,
    CLOSING,
    CLOSED,
}

internal enum class BehaviorReason {
    NONE,
    SUCCESS,
    TIMEOUT,
    CANCELLED,
    SOURCE,
}

internal enum class BehaviorOutcome {
    NONE,
    SUCCESS,
    TIMEOUT,
    CANCELLED,
    DUPLICATE,
    SOURCE,
}

internal enum class BehaviorFailureCategory {
    NONE,
    REGISTRATION,
    REGISTRATION_CLEANUP,
    CALLBACK,
    MATCH,
    UNREGISTER,
    DETACH,
}

internal enum class BehaviorFlag {
    UNSET,
    FALSE,
    TRUE,
}

internal enum class BehaviorDeadline {
    NONE,
    BOUNDED,
}

/** 같은 terminal 신호에서 깨어나 서로 happens-before 관계가 없는 continuation이다. */
internal enum class BehaviorCausalBranch {
    NONE,
    AWAIT_CONTINUATION,
    CLOSE_CONTINUATION,
    DRAIN_CALLBACK_CONTINUATION,
    DRAIN_DETACH_CONTINUATION,
}

internal enum class ExactEventValueCategory {
    GENERIC,
    ROUTE_HOME,
    ROUTE_DETAILS,
    ROUTE_SETTINGS,
    GENERATION_CURRENT,
    GENERATION_STALE,
}

/** Hash 입력은 이 닫힌 typed schema뿐이며 자유 형식 문자열이나 generic 값 슬롯이 없다. */
internal sealed interface BehaviorPayload {
    data class Facts(
        val phase: BehaviorPhase = BehaviorPhase.NONE,
        val fromPhase: BehaviorPhase = BehaviorPhase.NONE,
        val toPhase: BehaviorPhase = BehaviorPhase.NONE,
        val reason: BehaviorReason = BehaviorReason.NONE,
        val outcome: BehaviorOutcome = BehaviorOutcome.NONE,
        val failure: BehaviorFailureCategory = BehaviorFailureCategory.NONE,
        val listenerCount: Int = -1,
        val inFlight: Int = -1,
        val generation: Int = -1,
        val matchingCount: Int = -1,
        val callerOwnsLease: BehaviorFlag = BehaviorFlag.UNSET,
        val ownsDetach: BehaviorFlag = BehaviorFlag.UNSET,
        val alreadyCompleted: BehaviorFlag = BehaviorFlag.UNSET,
        val signalled: BehaviorFlag = BehaviorFlag.UNSET,
        val matched: BehaviorFlag = BehaviorFlag.UNSET,
        val accepted: BehaviorFlag = BehaviorFlag.UNSET,
        val recorded: BehaviorFlag = BehaviorFlag.UNSET,
        val permitReentrantReturn: BehaviorFlag = BehaviorFlag.UNSET,
        val drained: BehaviorFlag = BehaviorFlag.UNSET,
        val deadline: BehaviorDeadline = BehaviorDeadline.NONE,
        val causalBranch: BehaviorCausalBranch = BehaviorCausalBranch.NONE,
        val valueCategory: ExactEventValueCategory = ExactEventValueCategory.GENERIC,
    ) : BehaviorPayload
}

internal data class BehaviorEvent(
    val sequence: Int,
    val threadRole: BehaviorThreadRole,
    val component: BehaviorComponent,
    val instance: Int,
    val transition: BehaviorTransition,
    val payload: BehaviorPayload.Facts,
)

internal data class BehaviorHandle(
    val recorder: BehaviorRecorder,
    val component: BehaviorComponent,
    val instance: Int,
)

private class BehaviorRecorderLock

internal class BehaviorRecorder {
    private val lock = BehaviorRecorderLock()
    private val creatorThread = Thread.currentThread()
    private val componentCounters = mutableMapOf<BehaviorComponent, Int>()
    private val events = mutableListOf<BehaviorEvent>()
    private val causalBranch = ThreadLocal.withInitial { BehaviorCausalBranch.NONE }
    private var nextSequence = 0

    fun newHandle(component: BehaviorComponent): BehaviorHandle =
        synchronized(lock) {
            val instance = componentCounters.getOrDefault(component, 0)
            componentCounters[component] = instance + 1
            BehaviorHandle(this, component, instance)
        }

    fun observe(
        handle: BehaviorHandle,
        transition: BehaviorTransition,
        payload: BehaviorPayload.Facts,
    ) {
        synchronized(lock) {
            val sequence = nextSequence++
            val activeBranch = checkNotNull(causalBranch.get())
            events +=
                BehaviorEvent(
                    sequence = sequence,
                    threadRole =
                        if (Thread.currentThread() === creatorThread) {
                            BehaviorThreadRole.CREATOR
                        } else {
                            BehaviorThreadRole.WORKER
                        },
                    component = handle.component,
                    instance = handle.instance,
                    transition = transition,
                    payload =
                        if (
                            payload.causalBranch == BehaviorCausalBranch.NONE &&
                                activeBranch != BehaviorCausalBranch.NONE
                        ) {
                            payload.copy(causalBranch = activeBranch)
                        } else {
                            payload
                        },
                )
        }
    }

    fun enterCausalBranch(branch: BehaviorCausalBranch) {
        check(branch != BehaviorCausalBranch.NONE) { "NONE branch에는 진입할 수 없다" }
        check(causalBranch.get() == BehaviorCausalBranch.NONE) { "causal branch가 중첩되었다" }
        causalBranch.set(branch)
    }

    fun leaveCausalBranch(branch: BehaviorCausalBranch) {
        check(causalBranch.get() == branch) { "다른 causal branch를 종료할 수 없다" }
        causalBranch.set(BehaviorCausalBranch.NONE)
    }

    fun snapshot(): ExactEventBehaviorSnapshot {
        val captured = synchronized(lock) { events.toList() }
        captured.forEachIndexed { index, event ->
            check(event.sequence == index) { "behavior trace sequence가 단조 증가하지 않았다" }
        }
        val canonical = canonicalizeCausalBranches(captured)
        val normalized = buildString {
            canonical.forEachIndexed { index, event ->
                if (index > 0) append(';')
                append("seq=").append(index)
                append("|hb=")
                when (event.predecessors.size) {
                    0 -> append("ROOT")
                    1 -> append(event.predecessors.single())
                    else ->
                        event.predecessors.joinTo(
                            this,
                            prefix = "{",
                            postfix = "}",
                            separator = ",",
                        )
                }
                append("->").append(index)
                append("|thread=").append(event.event.threadRole.name)
                append("|component=")
                    .append(event.event.component.name)
                    .append('#')
                    .append(event.event.instance)
                append("|transition=").append(event.event.transition.name)
                appendTypedPayload(event.event.payload)
            }
        }
        val hash =
            MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        return ExactEventBehaviorSnapshot(normalized, hash)
    }

    private data class CanonicalEvent(
        val event: BehaviorEvent,
        val predecessors: List<Int>,
    )

    /**
     * Recorder lock 획득 순서는 happens-before가 아니다. 같은 terminal 신호에서 시작한 typed branch만 fork/join으로
     * canonicalize하고, 각 branch 내부와 일반 event의 관찰 순서는 그대로 보존한다.
     */
    private fun canonicalizeCausalBranches(captured: List<BehaviorEvent>): List<CanonicalEvent> {
        val canonical = mutableListOf<CanonicalEvent>()
        var predecessors = emptyList<Int>()
        var cursor = 0
        while (cursor < captured.size) {
            val branch = captured[cursor].payload.causalBranch
            if (branch == BehaviorCausalBranch.NONE) {
                canonical += CanonicalEvent(captured[cursor], predecessors)
                predecessors = listOf(canonical.lastIndex)
                cursor += 1
                continue
            }

            val branchEvents =
                captured.drop(cursor).takeWhile {
                    it.payload.causalBranch != BehaviorCausalBranch.NONE
                }
            val branchEnds = mutableListOf<Int>()
            BehaviorCausalBranch.entries
                .filterNot { it == BehaviorCausalBranch.NONE }
                .forEach { causalBranch ->
                    var branchPredecessors = predecessors
                    branchEvents
                        .filter { it.payload.causalBranch == causalBranch }
                        .forEach { event ->
                            canonical += CanonicalEvent(event, branchPredecessors)
                            branchPredecessors = listOf(canonical.lastIndex)
                        }
                    if (branchPredecessors !== predecessors) {
                        branchEnds += branchPredecessors.single()
                    }
                }
            predecessors = branchEnds
            cursor += branchEvents.size
        }
        return canonical
    }

    private fun StringBuilder.appendTypedPayload(payload: BehaviorPayload.Facts) {
        append("|phase=").append(payload.phase.name)
        append("|from=").append(payload.fromPhase.name)
        append("|to=").append(payload.toPhase.name)
        append("|reason=").append(payload.reason.name)
        append("|outcome=").append(payload.outcome.name)
        append("|failure=").append(payload.failure.name)
        append("|listeners=").append(payload.listenerCount)
        append("|inFlight=").append(payload.inFlight)
        append("|generation=").append(payload.generation)
        append("|matching=").append(payload.matchingCount)
        append("|callerLease=").append(payload.callerOwnsLease.name)
        append("|ownsDetach=").append(payload.ownsDetach.name)
        append("|alreadyCompleted=").append(payload.alreadyCompleted.name)
        append("|signalled=").append(payload.signalled.name)
        append("|matched=").append(payload.matched.name)
        append("|accepted=").append(payload.accepted.name)
        append("|recorded=").append(payload.recorded.name)
        append("|reentrant=").append(payload.permitReentrantReturn.name)
        append("|drained=").append(payload.drained.name)
        append("|deadline=").append(payload.deadline.name)
        append("|branch=").append(payload.causalBranch.name)
        append("|valueCategory=").append(payload.valueCategory.name)
    }
}

private class BehaviorCaptureLock

private val behaviorCaptureLock = BehaviorCaptureLock()
private var activeBehaviorRecorder: BehaviorRecorder? = null

private fun startBehaviorCapture(): ExactEventBehaviorTrace.Capture =
    synchronized(behaviorCaptureLock) {
        check(activeBehaviorRecorder == null) { "behavior capture는 한 번에 하나만 실행할 수 있다" }
        val recorder = BehaviorRecorder()
        activeBehaviorRecorder = recorder
        ExactEventBehaviorTrace.Capture.create(recorder)
    }

private fun finishBehaviorCapture(recorder: BehaviorRecorder): ExactEventBehaviorSnapshot =
    synchronized(behaviorCaptureLock) {
        check(activeBehaviorRecorder === recorder) { "다른 behavior capture를 종료할 수 없다" }
        activeBehaviorRecorder = null
        recorder.snapshot()
    }

private fun newBehaviorHandle(component: BehaviorComponent): BehaviorHandle? =
    synchronized(behaviorCaptureLock) { activeBehaviorRecorder?.newHandle(component) }

private fun BehaviorHandle?.observe(
    transition: BehaviorTransition,
    payload: BehaviorPayload.Facts = BehaviorPayload.Facts(),
) {
    if (this != null) recorder.observe(this, transition, payload)
}

private fun BehaviorHandle?.enterCausalBranch(branch: BehaviorCausalBranch) {
    if (this != null && branch != BehaviorCausalBranch.NONE) recorder.enterCausalBranch(branch)
}

private fun BehaviorHandle?.leaveCausalBranch(branch: BehaviorCausalBranch) {
    if (this != null && branch != BehaviorCausalBranch.NONE) recorder.leaveCausalBranch(branch)
}

private fun Boolean.behaviorFlag(): BehaviorFlag =
    if (this) BehaviorFlag.TRUE else BehaviorFlag.FALSE

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

    fun unregistered() = Unit

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
                    behavior.observe(
                        BehaviorTransition.CALLBACK_REJECTED,
                        BehaviorPayload.Facts(listenerCount = 0, inFlight = inFlight),
                    )
                    null
                } else {
                    inFlight += 1
                    callbackGeneration += 1
                    callbackDepth.set((callbackDepth.get() ?: 0) + 1)
                    AcquiredLease(callbackGeneration, inFlight).also { acquired ->
                        behavior.observe(
                            BehaviorTransition.LEASE_ACQUIRED,
                            BehaviorPayload.Facts(
                                generation = acquired.generation,
                                listenerCount = 1,
                                inFlight = acquired.inFlight,
                            ),
                        )
                    }
                }
            }
        if (lease == null) return@callback

        var causalBranch = BehaviorCausalBranch.NONE
        var branchEntered = false
        try {
            observer.acquired()
            causalBranch =
                synchronized(lock) {
                    if (detachStarted) {
                        BehaviorCausalBranch.DRAIN_CALLBACK_CONTINUATION
                    } else {
                        BehaviorCausalBranch.NONE
                    }
                }
            behavior.enterCausalBranch(causalBranch)
            branchEntered = causalBranch != BehaviorCausalBranch.NONE
            behavior.observe(
                BehaviorTransition.CALLBACK_DISPATCH,
                BehaviorPayload.Facts(generation = lease.generation),
            )
            receiver(value)
        } catch (failure: Throwable) {
            behavior.observe(
                BehaviorTransition.CALLBACK_FAILED,
                BehaviorPayload.Facts(
                    generation = lease.generation,
                    failure = BehaviorFailureCategory.CALLBACK,
                ),
            )
            throw failure
        } finally {
            observer.releasing()
            val completion =
                synchronized(lock) {
                    callbackDepth.set((callbackDepth.get() ?: 0) - 1)
                    inFlight -= 1
                    check(inFlight >= 0) { "callback lease가 중복 반환되었다" }
                    behavior.observe(
                        BehaviorTransition.LEASE_RELEASED,
                        BehaviorPayload.Facts(
                            generation = lease.generation,
                            listenerCount = if (accepting) 1 else 0,
                            inFlight = inFlight,
                        ),
                    )
                    takeDrainCompletionLocked()
                }
            if (branchEntered) behavior.leaveCausalBranch(causalBranch)
            completion?.invoke()
        }
    }

    init {
        behavior.observe(
            BehaviorTransition.SOURCE_REGISTER_BEGIN,
            BehaviorPayload.Facts(listenerCount = 0, inFlight = 0),
        )
        try {
            register(sourceCallback)
            behavior.observe(
                BehaviorTransition.SOURCE_REGISTERED,
                BehaviorPayload.Facts(listenerCount = 1, inFlight = 0),
            )
        } catch (registrationFailure: Throwable) {
            synchronized(lock) {
                accepting = false
                detachStarted = true
            }
            behavior.observe(
                BehaviorTransition.SOURCE_REGISTER_FAILED,
                BehaviorPayload.Facts(
                    failure = BehaviorFailureCategory.REGISTRATION,
                    listenerCount = 0,
                    inFlight = 0,
                ),
            )
            val cleanupFailure = runCatching { unregister(sourceCallback) }.exceptionOrNull()
            cleanupFailure?.let(registrationFailure::addSuppressed)
            behavior.observe(
                BehaviorTransition.SOURCE_REGISTER_FAILURE_CLEANUP,
                BehaviorPayload.Facts(
                    failure =
                        if (cleanupFailure == null) {
                            BehaviorFailureCategory.NONE
                        } else {
                            BehaviorFailureCategory.REGISTRATION_CLEANUP
                        },
                    listenerCount = 0,
                    inFlight = 0,
                ),
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
            BehaviorTransition.DETACH_INVOKED,
            BehaviorPayload.Facts(
                callerOwnsLease = callerOwnsLease.behaviorFlag(),
                listenerCount = if (synchronized(lock) { accepting }) 1 else 0,
                inFlight = activeLeaseCount,
            ),
        )
        val ownsSourceDetach: Boolean
        val alreadyCompleted: Boolean
        val completedFailure: Throwable?
        val inFlightAtDetach: Int
        val drainForked: Boolean
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
            inFlightAtDetach = inFlight
            drainForked = ownsSourceDetach && inFlightAtDetach > 0
        }

        behavior.observe(
            BehaviorTransition.DETACH_LINEARIZED,
            BehaviorPayload.Facts(
                ownsDetach = ownsSourceDetach.behaviorFlag(),
                alreadyCompleted = alreadyCompleted.behaviorFlag(),
                listenerCount = if (synchronized(lock) { accepting }) 1 else 0,
                inFlight = inFlightAtDetach,
            ),
        )
        if (alreadyCompleted) {
            behavior.observe(
                BehaviorTransition.DRAIN_OBSERVED_CLOSED,
                BehaviorPayload.Facts(
                    failure =
                        if (completedFailure == null) {
                            BehaviorFailureCategory.NONE
                        } else {
                            BehaviorFailureCategory.UNREGISTER
                        },
                    listenerCount = 0,
                    inFlight = activeLeaseCount,
                ),
            )
            onDrained(completedFailure)
            return callerOwnsLease
        }
        if (!ownsSourceDetach) return callerOwnsLease

        behavior.observe(
            BehaviorTransition.SOURCE_UNREGISTER_BEGIN,
            BehaviorPayload.Facts(listenerCount = 1, inFlight = inFlightAtDetach),
        )
        observer.detachStarted()
        val removalFailure = runCatching { unregister(sourceCallback) }.exceptionOrNull()
        behavior.observe(
            if (removalFailure == null) {
                BehaviorTransition.SOURCE_UNREGISTERED
            } else {
                BehaviorTransition.SOURCE_UNREGISTER_FAILED
            },
            BehaviorPayload.Facts(
                failure =
                    if (removalFailure == null) {
                        BehaviorFailureCategory.NONE
                    } else {
                        BehaviorFailureCategory.UNREGISTER
                    },
                listenerCount = 0,
                inFlight = inFlightAtDetach,
                causalBranch =
                    if (drainForked) {
                        BehaviorCausalBranch.DRAIN_DETACH_CONTINUATION
                    } else {
                        BehaviorCausalBranch.NONE
                    },
            ),
        )
        observer.unregistered()
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
                BehaviorTransition.DRAIN_COMPLETED,
                BehaviorPayload.Facts(
                    failure =
                        if (failure == null) {
                            BehaviorFailureCategory.NONE
                        } else {
                            BehaviorFailureCategory.UNREGISTER
                        },
                    listenerCount = 0,
                    inFlight = 0,
                ),
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
    private val classify: (T) -> ExactEventValueCategory = { ExactEventValueCategory.GENERIC },
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
        behavior.observe(
            BehaviorTransition.SUBSCRIBE_BEGIN,
            BehaviorPayload.Facts(phase = phase.behaviorPhase()),
        )
        try {
            registration = subscribe(::onEvent)
            behavior.observe(
                BehaviorTransition.SUBSCRIBED,
                BehaviorPayload.Facts(phase = phase.behaviorPhase()),
            )
        } catch (failure: Throwable) {
            sourceFailure = failure
            eventOrCancellation.countDown()
            behavior.observe(
                BehaviorTransition.SUBSCRIBE_FAILED,
                BehaviorPayload.Facts(
                    phase = phase.behaviorPhase(),
                    failure = BehaviorFailureCategory.REGISTRATION,
                ),
            )
        }
    }

    /** 구독 등록 중 전달되는 초기값과 트리거 이후 이벤트를 구분한다. */
    fun arm() {
        synchronized(lock) {
            check(phase == Phase.REGISTERED) { "이벤트 구독은 한 번만 시작할 수 있다" }
            val previous = phase
            phase = Phase.ARMED
            behavior.observe(
                BehaviorTransition.ARMED,
                BehaviorPayload.Facts(
                    fromPhase = previous.behaviorPhase(),
                    toPhase = phase.behaviorPhase(),
                ),
            )
        }
    }

    /** 정확한 이벤트 하나와 listener detach/drain terminal을 제한 시간 안에 기다린다. */
    fun await(timeout: Long, unit: TimeUnit, description: String): T {
        behavior.observe(
            BehaviorTransition.AWAIT_INVOKED,
            BehaviorPayload.Facts(
                phase = synchronized(lock) { phase }.behaviorPhase(),
                deadline = BehaviorDeadline.BOUNDED,
            ),
        )
        synchronized(lock) {
            check(phase != Phase.REGISTERED) { "이벤트 구독을 먼저 시작해야 한다" }
            check(!awaitClaimed) { "이벤트 구독 결과는 한 번만 기다릴 수 있다" }
            awaitClaimed = true
            behavior.observe(
                BehaviorTransition.AWAIT_CLAIMED,
                BehaviorPayload.Facts(phase = phase.behaviorPhase()),
            )
        }
        stateObserver.awaitClaimed()

        val signalled = eventOrCancellation.await(timeout, unit)
        val reason =
            synchronized(lock) {
                val selected =
                    when {
                        sourceFailure != null -> CloseReason.SOURCE
                        closeReason == CloseReason.CANCELLED || phase == Phase.CLOSED ->
                            CloseReason.CANCELLED
                        signalled && matchingEventCount > 0 -> CloseReason.SUCCESS
                        else -> CloseReason.TIMEOUT
                    }
                behavior.observe(
                    BehaviorTransition.AWAIT_RESOLVED,
                    BehaviorPayload.Facts(
                        signalled = signalled.behaviorFlag(),
                        reason = selected.behaviorReason(),
                        matchingCount = matchingEventCount,
                        phase = phase.behaviorPhase(),
                    ),
                )
                selected
            }
        stateObserver.closeSelected(reason.name)
        val terminal =
            checkNotNull(
                closeObservation(
                    reason,
                    CLOSE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                    false,
                    BehaviorCausalBranch.AWAIT_CONTINUATION,
                )
            )
        return when (terminal) {
            is Outcome.Success -> {
                behavior.observe(
                    BehaviorTransition.AWAIT_RETURNED,
                    BehaviorPayload.Facts(
                        outcome = BehaviorOutcome.SUCCESS,
                        causalBranch = BehaviorCausalBranch.AWAIT_CONTINUATION,
                    ),
                )
                terminal.value
            }
            is Outcome.Failure -> {
                behavior.observe(
                    BehaviorTransition.AWAIT_THROWN,
                    BehaviorPayload.Facts(
                        outcome = terminal.failure.behaviorOutcome(),
                        failure =
                            if (terminal.cause == null) {
                                BehaviorFailureCategory.NONE
                            } else {
                                BehaviorFailureCategory.DETACH
                            },
                        causalBranch = BehaviorCausalBranch.AWAIT_CONTINUATION,
                    ),
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
        val valueCategory = classify(value)
        synchronized(lock) {
            behavior.observe(
                BehaviorTransition.EVENT_RECEIVED,
                BehaviorPayload.Facts(
                    phase = phase.behaviorPhase(),
                    valueCategory = valueCategory,
                ),
            )
        }
        val matched =
            try {
                matches(value)
            } catch (failure: Throwable) {
                behavior.observe(
                    BehaviorTransition.MATCH_FAILED,
                    BehaviorPayload.Facts(
                        failure = BehaviorFailureCategory.MATCH,
                        valueCategory = valueCategory,
                    ),
                )
                recordSourceFailure(failure)
                return
            }
        synchronized(lock) {
            val observation =
                if (!matched || (phase != Phase.ARMED && phase != Phase.CLOSING)) {
                    EventObservation(matched, false, matchingEventCount, phase)
                } else {
                    matchingEventCount += 1
                    if (matchingEventCount == 1) accepted = value
                    EventObservation(true, true, matchingEventCount, phase)
                }
            behavior.observe(
                BehaviorTransition.EVENT_CLASSIFIED,
                BehaviorPayload.Facts(
                    matched = observation.matched.behaviorFlag(),
                    accepted = observation.acceptedGeneration.behaviorFlag(),
                    matchingCount = observation.matchingCount,
                    phase = observation.phase.behaviorPhase(),
                    valueCategory = valueCategory,
                ),
            )
            if (observation.acceptedGeneration && observation.matchingCount == 1) {
                eventOrCancellation.countDown()
            }
        }
    }

    private data class EventObservation(
        val matched: Boolean,
        val acceptedGeneration: Boolean,
        val matchingCount: Int,
        val phase: Phase,
    )

    private fun recordSourceFailure(failure: Throwable) {
        synchronized(lock) {
            val recorded = phase != Phase.CLOSED
            if (recorded) sourceFailure = sourceFailure ?: failure
            behavior.observe(
                BehaviorTransition.SOURCE_FAILURE_RECORDED,
                BehaviorPayload.Facts(
                    recorded = recorded.behaviorFlag(),
                    failure = BehaviorFailureCategory.MATCH,
                    phase = phase.behaviorPhase(),
                ),
            )
            if (recorded) eventOrCancellation.countDown()
        }
    }

    private fun closeObservation(
        requestedReason: CloseReason,
        timeout: Long,
        unit: TimeUnit,
        permitReentrantReturn: Boolean,
        causalBranch: BehaviorCausalBranch,
    ): Outcome<T>? {
        var immediateOutcome: Outcome<T>? = null
        val transition =
            synchronized(lock) {
                behavior.observe(
                    BehaviorTransition.CLOSE_OBSERVATION_INVOKED,
                    BehaviorPayload.Facts(
                        reason = requestedReason.behaviorReason(),
                        phase = phase.behaviorPhase(),
                        permitReentrantReturn = permitReentrantReturn.behaviorFlag(),
                    ),
                )
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
                            }
                            false
                        }
                        Phase.REGISTERED,
                        Phase.ARMED -> {
                            phase = Phase.CLOSING
                            closeReason = requestedReason
                            true
                        }
                    }
                behavior.observe(
                    BehaviorTransition.CLOSE_LINEARIZED,
                    BehaviorPayload.Facts(
                        reason = requestedReason.behaviorReason(),
                        fromPhase = previous.behaviorPhase(),
                        toPhase = phase.behaviorPhase(),
                        ownsDetach = ownsDetach.behaviorFlag(),
                        matchingCount = matchingEventCount,
                    ),
                )
                if (requestedReason == CloseReason.CANCELLED && previous != Phase.CLOSED) {
                    eventOrCancellation.countDown()
                }
                Triple(previous, phase, ownsDetach)
            }
        val ownsDetach = transition.third
        immediateOutcome?.let {
            behavior.observe(
                BehaviorTransition.CLOSED_OUTCOME_OBSERVED,
                BehaviorPayload.Facts(outcome = it.behaviorOutcome()),
            )
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
            behavior.observe(
                BehaviorTransition.REENTRANT_CLOSE_RETURNED,
                BehaviorPayload.Facts(callerOwnsLease = BehaviorFlag.TRUE),
            )
            return null
        }
        val drained = closed.await(timeout, unit)
        behavior.observe(
            BehaviorTransition.TERMINAL_WAIT_RESOLVED,
            BehaviorPayload.Facts(
                drained = drained.behaviorFlag(),
                phase = synchronized(lock) { phase }.behaviorPhase(),
                causalBranch = causalBranch,
            ),
        )
        if (!drained) {
            behavior.observe(
                BehaviorTransition.TERMINAL_WAIT_FAILED,
                BehaviorPayload.Facts(outcome = BehaviorOutcome.SOURCE),
            )
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
                val terminal = checkNotNull(outcome)
                behavior.observe(
                    BehaviorTransition.TERMINALIZED,
                    BehaviorPayload.Facts(
                        outcome = terminal.behaviorOutcome(),
                        matchingCount = matchingEventCount,
                        phase = phase.behaviorPhase(),
                    ),
                )
                closed.countDown()
                outcomeLabel(terminal)
            }
        stateObserver.terminal(terminalLabel)
    }

    private fun Phase.behaviorPhase(): BehaviorPhase =
        when (this) {
            Phase.REGISTERED -> BehaviorPhase.REGISTERED
            Phase.ARMED -> BehaviorPhase.ARMED
            Phase.CLOSING -> BehaviorPhase.CLOSING
            Phase.CLOSED -> BehaviorPhase.CLOSED
        }

    private fun CloseReason.behaviorReason(): BehaviorReason =
        when (this) {
            CloseReason.SUCCESS -> BehaviorReason.SUCCESS
            CloseReason.TIMEOUT -> BehaviorReason.TIMEOUT
            CloseReason.CANCELLED -> BehaviorReason.CANCELLED
            CloseReason.SOURCE -> BehaviorReason.SOURCE
        }

    private fun ExactEventFailure.behaviorOutcome(): BehaviorOutcome =
        when (this) {
            ExactEventFailure.TIMEOUT -> BehaviorOutcome.TIMEOUT
            ExactEventFailure.CANCELLED -> BehaviorOutcome.CANCELLED
            ExactEventFailure.DUPLICATE -> BehaviorOutcome.DUPLICATE
            ExactEventFailure.SOURCE -> BehaviorOutcome.SOURCE
        }

    private fun Outcome<T>.behaviorOutcome(): BehaviorOutcome =
        when (this) {
            is Outcome.Success -> BehaviorOutcome.SUCCESS
            is Outcome.Failure -> failure.behaviorOutcome()
        }

    private fun outcomeLabel(value: Outcome<T>): String =
        when (value) {
            is Outcome.Success -> "SUCCESS"
            is Outcome.Failure -> value.failure.name
        }

    /** Callback 재진입 close는 자신을 기다리지 않고 lease의 finally가 terminal을 완성한다. */
    override fun close() {
        behavior.observe(
            BehaviorTransition.CLOSE_INVOKED,
            BehaviorPayload.Facts(phase = synchronized(lock) { phase }.behaviorPhase()),
        )
        val terminal =
            closeObservation(
                CloseReason.CANCELLED,
                CLOSE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
                true,
                BehaviorCausalBranch.CLOSE_CONTINUATION,
            )
        behavior.observe(
            BehaviorTransition.CLOSE_RETURNED,
            BehaviorPayload.Facts(
                outcome = terminal?.behaviorOutcome() ?: BehaviorOutcome.NONE,
                phase = synchronized(lock) { phase }.behaviorPhase(),
                causalBranch = BehaviorCausalBranch.CLOSE_CONTINUATION,
            ),
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
