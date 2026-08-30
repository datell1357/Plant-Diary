package com.planterior.helper.core.database

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

enum class RoomTransactionOwner {
    ACCOUNT_SESSION_CACHE_ACTIVATION,
    ACCOUNT_SYNC_WRITE,
    ANALYTICS_ENQUEUE,
    ANALYTICS_CONSENT_PURGE,
    ANALYTICS_WORKER_DELIVERY,
}

@JvmInline value class RoomTransactionOwnerToken(val value: Long)

sealed interface RoomTransactionOwnerObservation {
    val token: RoomTransactionOwnerToken
    val owner: RoomTransactionOwner

    data class Began(
        override val token: RoomTransactionOwnerToken,
        override val owner: RoomTransactionOwner,
    ) : RoomTransactionOwnerObservation

    sealed interface Terminal : RoomTransactionOwnerObservation

    data class Returned(
        override val token: RoomTransactionOwnerToken,
        override val owner: RoomTransactionOwner,
    ) : Terminal

    data class Threw(
        override val token: RoomTransactionOwnerToken,
        override val owner: RoomTransactionOwner,
        val failure: Throwable,
    ) : Terminal

    data class Cancelled(
        override val token: RoomTransactionOwnerToken,
        override val owner: RoomTransactionOwner,
        val failure: CancellationException,
    ) : Terminal
}

fun interface RoomTransactionOwnerObserver {
    fun observe(observation: RoomTransactionOwnerObservation)
}

class RoomTransactionOwnerDiagnostics(
    private val observer: RoomTransactionOwnerObserver = RoomTransactionOwnerObserver {}
) {
    suspend fun <T> observe(owner: RoomTransactionOwner, block: suspend () -> T): T =
        observeToken(owner, block)

    fun <T> observeImmediate(owner: RoomTransactionOwner, block: () -> T): T =
        observeTokenImmediate(owner, block)

    private suspend fun <T> observeToken(
        owner: RoomTransactionOwner,
        block: suspend () -> T,
    ): T {
        val token = RoomTransactionOwnerToken(nextToken.incrementAndGet())
        emit(RoomTransactionOwnerObservation.Began(token, owner))
        return try {
            block().also { emit(RoomTransactionOwnerObservation.Returned(token, owner)) }
        } catch (failure: CancellationException) {
            emit(RoomTransactionOwnerObservation.Cancelled(token, owner, failure))
            throw failure
        } catch (failure: Throwable) {
            emit(RoomTransactionOwnerObservation.Threw(token, owner, failure))
            throw failure
        }
    }

    private fun <T> observeTokenImmediate(owner: RoomTransactionOwner, block: () -> T): T {
        val token = RoomTransactionOwnerToken(nextToken.incrementAndGet())
        emit(RoomTransactionOwnerObservation.Began(token, owner))
        return try {
            block().also { emit(RoomTransactionOwnerObservation.Returned(token, owner)) }
        } catch (failure: CancellationException) {
            emit(RoomTransactionOwnerObservation.Cancelled(token, owner, failure))
            throw failure
        } catch (failure: Throwable) {
            emit(RoomTransactionOwnerObservation.Threw(token, owner, failure))
            throw failure
        }
    }

    private fun emit(observation: RoomTransactionOwnerObservation) {
        try {
            observer.observe(observation)
        } catch (_: AssertionError) {
            return
        } catch (_: Exception) {
            return
        }
    }

    private companion object {
        val nextToken = AtomicLong()
    }
}
