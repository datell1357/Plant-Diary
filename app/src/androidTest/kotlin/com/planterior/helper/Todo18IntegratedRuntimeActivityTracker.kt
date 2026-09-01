package com.planterior.helper

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.Collections
import java.util.IdentityHashMap

internal class Todo18IntegratedRuntimeActivityTracker {
    private val lifecycleLock = Any()
    private var createdActivities = 0
    private var destroyedActivities = 0
    private val activeMainActivities =
        Collections.newSetFromMap(IdentityHashMap<MainActivity, Boolean>())

    internal val activityCreateCount: Int
        get() = synchronized(lifecycleLock) { createdActivities }

    internal val activityDestroyCount: Int
        get() = synchronized(lifecycleLock) { destroyedActivities }

    internal val activityActiveCount: Int
        get() = synchronized(lifecycleLock) { activeMainActivities.size }

    internal val activityCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is MainActivity) {
                    synchronized(lifecycleLock) {
                        createdActivities += 1
                        activeMainActivities += activity
                    }
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (activity is MainActivity) {
                    synchronized(lifecycleLock) {
                        destroyedActivities += 1
                        activeMainActivities -= activity
                    }
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        }

    internal fun currentMainActivityCount(): Int {
        var count = -1
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            val active = Collections.newSetFromMap(IdentityHashMap<MainActivity, Boolean>())
            Stage.values()
                .filterNot { it == Stage.DESTROYED }
                .forEach { stage ->
                    monitor
                        .getActivitiesInStage(stage)
                        .filterIsInstance<MainActivity>()
                        .forEach(active::add)
                }
            count = active.size
        }
        return count
    }
}
