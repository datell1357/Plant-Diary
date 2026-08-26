package com.planterior.helper.analytics

import com.planterior.helper.core.database.AnalyticsEventQueueDao
import com.planterior.helper.core.database.AnalyticsEventQueueEntity
import com.planterior.helper.core.model.ClientProductEvent
import com.planterior.helper.core.model.ProductEventRecorder
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class AnalyticsAuthorization(val ownerUid: String, val consentRevision: Int) {
    init {
        require(ownerUid.isNotBlank())
        require(consentRevision > 0)
    }
}

fun interface AnalyticsWorkEnqueuer {
    fun enqueue()
}

interface AnalyticsWorkController : AnalyticsWorkEnqueuer {
    suspend fun cancel()
}

class QueuedProductEventRecorder(
    private val queue: AnalyticsEventQueueDao,
    private val scope: CoroutineScope,
    private val workEnqueuer: AnalyticsWorkEnqueuer,
    private val clock: Clock = Clock.systemUTC(),
    private val eventId: () -> String = { UUID.randomUUID().toString() },
) : ProductEventRecorder {
    private val authorizationLock = Any()
    private var authorization: AnalyticsAuthorization? = null

    fun authorize(value: AnalyticsAuthorization) {
        synchronized(authorizationLock) { authorization = value }
    }

    fun disable() {
        synchronized(authorizationLock) { authorization = null }
    }

    fun currentAuthorization(): AnalyticsAuthorization? =
        synchronized(authorizationLock) { authorization }

    override fun record(event: ClientProductEvent) {
        val acknowledged = currentAuthorization() ?: return
        val id = eventId()
        val enqueuedAt = clock.millis()
        scope.launch {
            try {
                if (currentAuthorization() != acknowledged) return@launch
                queue.enqueueBounded(
                    AnalyticsEventQueueEntity(
                        accountId = acknowledged.ownerUid,
                        eventId = id,
                        eventName = event.event.name,
                        consentRevision = acknowledged.consentRevision,
                        enqueuedAtEpochMillis = enqueuedAt,
                    ),
                    expiredAtOrBeforeEpochMillis = enqueuedAt - RAW_EVENT_RETENTION.toMillis(),
                )
                if (currentAuthorization() == acknowledged) {
                    workEnqueuer.enqueue()
                } else {
                    queue.delete(acknowledged.ownerUid, acknowledged.consentRevision, id)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Analytics is deliberately best effort and cannot fail the product operation.
            }
        }
    }
}

class AnalyticsSessionTracker(
    private val recorder: ProductEventRecorder,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var enabled = false
    private var foreground = false
    private var sessionStarted = false
    private var backgroundAtEpochMillis: Long? = null

    @Synchronized
    fun authorizationChanged(isEnabled: Boolean) {
        enabled = isEnabled
        if (!isEnabled) {
            resetSession()
        } else if (foreground && !sessionStarted) {
            startSession()
        }
    }

    @Synchronized
    fun onForeground() {
        foreground = true
        if (!enabled) return
        val backgroundAt = backgroundAtEpochMillis
        if (
            !sessionStarted ||
                (backgroundAt != null &&
                    clock.millis() - backgroundAt >= SESSION_BACKGROUND_TIMEOUT.toMillis())
        ) {
            startSession()
        }
        backgroundAtEpochMillis = null
    }

    @Synchronized
    fun onBackground() {
        foreground = false
        if (enabled) backgroundAtEpochMillis = clock.millis()
    }

    @Synchronized
    fun reset() {
        enabled = false
        foreground = false
        resetSession()
    }

    private fun startSession() {
        sessionStarted = true
        backgroundAtEpochMillis = null
        recorder.record(ClientProductEvent.APP_SESSION_STARTED)
    }

    private fun resetSession() {
        sessionStarted = false
        backgroundAtEpochMillis = null
    }
}

val RAW_EVENT_RETENTION: Duration = Duration.ofDays(35)
val SESSION_BACKGROUND_TIMEOUT: Duration = Duration.ofMinutes(30)
