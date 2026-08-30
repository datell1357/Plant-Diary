package com.planterior.helper.analytics

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import com.planterior.helper.core.database.AnalyticsEventQueueDao
import com.planterior.helper.core.database.AnalyticsEventQueueEntity
import com.planterior.helper.core.database.RoomTransactionOwner
import com.planterior.helper.core.database.RoomTransactionOwnerDiagnostics
import com.planterior.helper.core.model.ClientProductEvent
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

data class AnalyticsWorkerSession(
    val firebaseUid: String?,
    val authorization: AnalyticsAuthorization?,
)

fun interface AnalyticsWorkerSessionProvider {
    fun current(): AnalyticsWorkerSession
}

enum class AnalyticsDeliveryTaskResult {
    COMPLETE,
    RETRY,
}

class AnalyticsDeliveryTask(
    private val queue: AnalyticsEventQueueDao,
    private val remote: AnalyticsRemoteGateway,
    private val sessionProvider: AnalyticsWorkerSessionProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val transactionOwners: RoomTransactionOwnerDiagnostics =
        RoomTransactionOwnerDiagnostics(),
) {
    suspend fun run(): AnalyticsDeliveryTaskResult {
        val initial = sessionProvider.current()
        val firebaseUid = initial.firebaseUid
        if (firebaseUid == null) {
            write { queue.purgeAll() }
            return AnalyticsDeliveryTaskResult.COMPLETE
        }
        val authorization = initial.authorization
        if (authorization == null) {
            write { queue.purgeOtherOwners(firebaseUid) }
            return AnalyticsDeliveryTaskResult.COMPLETE
        }
        if (firebaseUid != authorization.ownerUid) {
            write { queue.purgeOwner(authorization.ownerUid) }
            return AnalyticsDeliveryTaskResult.COMPLETE
        }
        val expiryBoundary = clock.millis() - RAW_EVENT_RETENTION.toMillis()
        write { queue.purgeExpired(authorization.ownerUid, expiryBoundary) }
        write { queue.purgeOtherRevisions(authorization.ownerUid, authorization.consentRevision) }
        while (true) {
            val batch =
                queue.oldestBatch(
                    authorization.ownerUid,
                    authorization.consentRevision,
                    expiryBoundary,
                    DELIVERY_BATCH_SIZE,
                )
            if (batch.isEmpty()) return AnalyticsDeliveryTaskResult.COMPLETE
            val deliverable = batch.mapNotNull { row ->
                val event = row.clientEventOrNull()
                if (event == null) {
                    write { queue.delete(row.accountId, row.consentRevision, row.eventId) }
                    null
                } else {
                    row to
                        AnalyticsEventCommand(
                            eventId = row.eventId,
                            eventName = event.event.name,
                            consentRevision = row.consentRevision,
                        )
                }
            }
            if (deliverable.isEmpty()) continue
            if (!stillAuthorized(authorization)) {
                write { queue.purgeOwner(authorization.ownerUid) }
                return AnalyticsDeliveryTaskResult.COMPLETE
            }
            val acknowledgements =
                try {
                    remote.recordEvents(
                        AnalyticsEventBatchCommand(
                            ownerUid = authorization.ownerUid,
                            events = deliverable.map { it.second },
                        )
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: AnalyticsTransportException) {
                    return AnalyticsDeliveryTaskResult.RETRY
                } catch (_: AnalyticsStaleOrDisabledException) {
                    write { queue.purgeOwner(authorization.ownerUid) }
                    return AnalyticsDeliveryTaskResult.COMPLETE
                } catch (_: AnalyticsPermanentSchemaException) {
                    deliverable.forEach { (row) ->
                        write { queue.delete(row.accountId, row.consentRevision, row.eventId) }
                    }
                    continue
                }
            if (!stillAuthorized(authorization)) {
                write { queue.purgeOwner(authorization.ownerUid) }
                return AnalyticsDeliveryTaskResult.COMPLETE
            }
            if (
                acknowledgements.size != deliverable.size ||
                    acknowledgements.zip(deliverable).any { (acknowledgement, event) ->
                        acknowledgement.eventId != event.second.eventId
                    }
            ) {
                deliverable.forEach { (row) ->
                    write { queue.delete(row.accountId, row.consentRevision, row.eventId) }
                }
                continue
            }
            acknowledgements.zip(deliverable).forEach { (_, event) ->
                val row = event.first
                write { queue.delete(row.accountId, row.consentRevision, row.eventId) }
            }
        }
    }

    private fun stillAuthorized(expected: AnalyticsAuthorization): Boolean {
        val current = sessionProvider.current()
        return current.firebaseUid == expected.ownerUid && current.authorization == expected
    }

    private suspend fun <T> write(block: suspend () -> T): T =
        transactionOwners.observe(RoomTransactionOwner.ANALYTICS_WORKER_DELIVERY, block)
}

class AnalyticsDeliveryWorker(
    appContext: Context,
    parameters: WorkerParameters,
    private val task: AnalyticsDeliveryTask,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result =
        try {
            when (task.run()) {
                AnalyticsDeliveryTaskResult.COMPLETE -> Result.success()
                AnalyticsDeliveryTaskResult.RETRY -> Result.retry()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
}

class AnalyticsWorkScheduler(private val workManager: WorkManager) : AnalyticsWorkController {
    override fun enqueue() {
        workManager.enqueueUniqueWork(
            ANALYTICS_DELIVERY_WORK,
            ExistingWorkPolicy.KEEP,
            request(),
        )
    }

    override suspend fun cancel() {
        workManager.cancelUniqueWork(ANALYTICS_DELIVERY_WORK).result.awaitAnalyticsFuture()
    }

    companion object {
        const val ANALYTICS_DELIVERY_WORK = "first-party-analytics-delivery"

        internal fun request(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<AnalyticsDeliveryWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
    }
}

private fun AnalyticsEventQueueEntity.clientEventOrNull(): ClientProductEvent? {
    if (runCatching { UUID.fromString(eventId) }.isFailure) return null
    return ClientProductEvent.entries.firstOrNull { it.event.name == eventName }
}

private suspend fun <T> ListenableFuture<T>.awaitAnalyticsFuture(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            },
            Executor { command -> command.run() },
        )
        continuation.invokeOnCancellation { cancel(true) }
    }

private const val DELIVERY_BATCH_SIZE = 50
