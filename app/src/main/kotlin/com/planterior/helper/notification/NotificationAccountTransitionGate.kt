package com.planterior.helper.notification

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object NotificationAccountTransitionGate {
    private val mutex = Mutex()
    private val epoch = AtomicLong(0)

    suspend fun transition(cancelFormerOwnerNotifications: () -> Unit, action: suspend () -> Unit) {
        mutex.withLock {
            epoch.incrementAndGet()
            try {
                cancelFormerOwnerNotifications()
                action()
            } finally {
                epoch.incrementAndGet()
            }
        }
    }

    suspend fun postIfCurrent(
        ownerUid: String,
        currentOwnerUid: () -> String?,
        afterInitialOwnerCheck: suspend () -> Unit = {},
        post: () -> Boolean,
    ): Boolean {
        if (currentOwnerUid() != ownerUid) return false
        val observedEpoch = epoch.get()
        afterInitialOwnerCheck()
        return mutex.withLock {
            if (epoch.get() != observedEpoch || currentOwnerUid() != ownerUid) {
                false
            } else {
                post()
            }
        }
    }
}
