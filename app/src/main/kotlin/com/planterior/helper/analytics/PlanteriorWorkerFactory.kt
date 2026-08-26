package com.planterior.helper.analytics

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.planterior.helper.notification.NotificationWorkerFactory

class PlanteriorWorkerFactory(
    private val notificationFactory: NotificationWorkerFactory,
    private val analyticsRuntime: () -> AnalyticsRuntime?,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (workerClassName == AnalyticsDeliveryWorker::class.java.name) {
            val runtime = analyticsRuntime() ?: return null
            return AnalyticsDeliveryWorker(appContext, workerParameters, runtime.workerTask())
        }
        return notificationFactory.createWorker(appContext, workerClassName, workerParameters)
    }
}
