package com.planterior.helper.feature.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes owner cache writes with terminal local-owner removal. */
class AccountSyncWriteGate {
    private val mutex = Mutex()
    private val removedOwners = mutableSetOf<String>()

    suspend fun <T> writeIfCurrent(accountUid: String, write: suspend () -> T): T = mutex.withLock {
        if (accountUid in removedOwners) throw SyncNotAttemptedException()
        write()
    }

    suspend fun removeOwner(accountUid: String, action: suspend () -> Boolean): Boolean =
        mutex.withLock {
            if (!action()) return@withLock false
            removedOwners += accountUid
            true
        }
}
