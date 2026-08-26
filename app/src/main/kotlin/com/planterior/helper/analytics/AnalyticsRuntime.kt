package com.planterior.helper.analytics

import android.content.Context
import androidx.work.WorkManager
import com.planterior.helper.core.database.PlanteriorDatabase
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

class AnalyticsRuntime(
    context: Context,
    private val database: PlanteriorDatabase,
    val remote: AnalyticsRemoteGateway,
    private val workController: () -> AnalyticsWorkController? = {
        try {
            AnalyticsWorkScheduler(WorkManager.getInstance(context.applicationContext))
        } catch (_: IllegalStateException) {
            // A host without WorkManager (for example a preview/test shell) keeps analytics
            // off-path.
            null
        }
    },
    private val firebaseUid: () -> String?,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val recorder =
        QueuedProductEventRecorder(
            database.analyticsEventQueueDao(),
            scope,
            AnalyticsWorkEnqueuer { workController()?.enqueue() },
        )
    val sessionTracker = AnalyticsSessionTracker(recorder)
    val consent =
        AnalyticsConsentCoordinator(
            remote,
            object : AnalyticsConsentLocalBoundary {
                override fun enable(authorization: AnalyticsAuthorization) {
                    recorder.authorize(authorization)
                    sessionTracker.authorizationChanged(true)
                    workController()?.enqueue()
                }

                override fun disableImmediately() {
                    recorder.disable()
                    sessionTracker.authorizationChanged(false)
                }

                override suspend fun prepareOwner(ownerUid: String) {
                    workController()?.cancel()
                    database.analyticsEventQueueDao().purgeOtherOwners(ownerUid)
                }

                override suspend fun cancelWorkAndPurge(ownerUid: String?) {
                    var failure: Exception? = null
                    try {
                        workController()?.cancel()
                    } catch (error: Exception) {
                        failure = error
                    }
                    try {
                        if (ownerUid == null) {
                            database.analyticsEventQueueDao().purgeAll()
                        } else {
                            database.analyticsEventQueueDao().purgeOwner(ownerUid)
                        }
                    } catch (error: Exception) {
                        failure?.addSuppressed(error) ?: run { failure = error }
                    }
                    failure?.let { throw it }
                }
            },
        )

    fun workerTask(): AnalyticsDeliveryTask =
        AnalyticsDeliveryTask(
            database.analyticsEventQueueDao(),
            remote,
            AnalyticsWorkerSessionProvider {
                AnalyticsWorkerSession(firebaseUid(), recorder.currentAuthorization())
            },
        )

    suspend fun deletionReceived(ownerUid: String) {
        withContext(NonCancellable) {
            val authorization = recorder.currentAuthorization()
            if (authorization != null && authorization.ownerUid != ownerUid) return@withContext
            if (consent.deletionReceived(ownerUid)) sessionTracker.reset()
        }
    }

    suspend fun clearLocalOwner(ownerUid: String?) {
        if (consent.clearLocalOwner(ownerUid)) sessionTracker.reset()
    }

    override fun close() {
        recorder.disable()
        sessionTracker.reset()
        scope.cancel()
    }
}
