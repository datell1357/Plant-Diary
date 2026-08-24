package com.planterior.helper.notification

import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class NotificationOpenConfirmationResult {
    CONFIRMED,
    NOT_FOUND_FOR_ACCOUNT,
    PERMANENT_FAILURE,
    RETRYABLE_FAILURE,
}

fun interface NotificationOpenCallable {
    suspend fun confirm(
        expectedOwnerUid: String,
        deliveryId: String,
    ): NotificationOpenConfirmationResult
}

class NotificationOpenConfirmationStore(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val confirmationMutex = Mutex()

    fun recordTap(uri: String?) {
        val parsed = uri?.toUri() ?: return
        if (
            parsed.scheme != "planterior" ||
                parsed.host != "collection" ||
                parsed.pathSegments.size != 2 ||
                parsed.pathSegments.firstOrNull() != "plant"
        ) {
            return
        }
        val deliveryId = parsed.getQueryParameter("deliveryId") ?: return
        if (!DELIVERY_ID.matches(deliveryId)) return
        synchronized(preferences) {
            preferences.edit(commit = true) {
                putStringSet(PENDING, rawPending() + deliveryId)
                if (!preferences.contains(firstObservedAtKey(deliveryId))) {
                    putLong(firstObservedAtKey(deliveryId), nowMillis())
                }
                remove(notFoundOwnersKey(deliveryId))
            }
        }
    }

    suspend fun confirmPending(ownerUid: String, callable: NotificationOpenCallable) =
        confirmationMutex.withLock {
            for (deliveryId in rawPending().sorted()) {
                if (!DELIVERY_ID.matches(deliveryId)) {
                    remove(deliveryId)
                    continue
                }
                if (ownerUid in notFoundOwners(deliveryId)) continue
                val firstObservedAt = firstObservedAt(deliveryId)
                val result =
                    try {
                        callable.confirm(ownerUid, deliveryId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        NotificationOpenConfirmationResult.RETRYABLE_FAILURE
                    }
                when (result) {
                    NotificationOpenConfirmationResult.CONFIRMED,
                    NotificationOpenConfirmationResult.PERMANENT_FAILURE -> remove(deliveryId)
                    NotificationOpenConfirmationResult.NOT_FOUND_FOR_ACCOUNT ->
                        if (absenceHorizonExpired(firstObservedAt)) {
                            synchronized(preferences) {
                                preferences.edit(commit = true) {
                                    putStringSet(
                                        notFoundOwnersKey(deliveryId),
                                        notFoundOwners(deliveryId) + ownerUid,
                                    )
                                }
                            }
                        }
                    NotificationOpenConfirmationResult.RETRYABLE_FAILURE -> Unit
                }
            }
        }

    internal fun pending(): Set<String> =
        rawPending().filterTo(mutableSetOf(), DELIVERY_ID::matches)

    fun clearLocalState() {
        synchronized(preferences) { preferences.edit(commit = true) { clear() } }
    }

    private fun rawPending(): Set<String> =
        preferences.getStringSet(PENDING, emptySet()).orEmpty().toSet()

    private fun notFoundOwners(deliveryId: String): Set<String> =
        preferences.getStringSet(notFoundOwnersKey(deliveryId), emptySet()).orEmpty().toSet()

    private fun firstObservedAt(deliveryId: String): Long =
        synchronized(preferences) {
            val key = firstObservedAtKey(deliveryId)
            if (preferences.contains(key)) return preferences.getLong(key, 0)
            val observedAt = nowMillis()
            preferences.edit(commit = true) { putLong(key, observedAt) }
            observedAt
        }

    private fun absenceHorizonExpired(firstObservedAt: Long): Boolean {
        val now = nowMillis()
        return now >= firstObservedAt && now - firstObservedAt >= NOT_FOUND_RETRY_HORIZON_MILLIS
    }

    private fun remove(deliveryId: String) {
        synchronized(preferences) {
            preferences.edit(commit = true) {
                putStringSet(PENDING, rawPending() - deliveryId)
                remove(notFoundOwnersKey(deliveryId))
                remove(firstObservedAtKey(deliveryId))
            }
        }
    }

    private fun notFoundOwnersKey(deliveryId: String) = "$NOT_FOUND_OWNERS_PREFIX$deliveryId"

    private fun firstObservedAtKey(deliveryId: String) = "$FIRST_OBSERVED_AT_PREFIX$deliveryId"

    companion object {
        internal const val PREFERENCES = "notification-open-confirmations"
        private const val PENDING = "pending-delivery-ids"
        private const val NOT_FOUND_OWNERS_PREFIX = "not-found-owners:"
        private const val FIRST_OBSERVED_AT_PREFIX = "first-observed-at:"
        // Live claims fit inside this bound; expired exact claims are recovered by the callable.
        internal const val NOT_FOUND_RETRY_HORIZON_MILLIS = 30 * 60 * 1000L
        private val DELIVERY_ID = Regex("^[0-9a-f-]{36}$")
    }
}

class FirebaseNotificationOpenCallable(private val functions: FirebaseFunctions) :
    NotificationOpenCallable {
    override suspend fun confirm(
        expectedOwnerUid: String,
        deliveryId: String,
    ): NotificationOpenConfirmationResult =
        try {
            functions
                .getHttpsCallable("confirmNotificationOpened")
                .call(
                    mapOf(
                        "expectedOwnerUid" to expectedOwnerUid,
                        "deliveryId" to deliveryId,
                    )
                )
                .awaitOpenConfirmation()
            NotificationOpenConfirmationResult.CONFIRMED
        } catch (error: CancellationException) {
            throw error
        } catch (error: FirebaseFunctionsException) {
            when (error.code) {
                FirebaseFunctionsException.Code.NOT_FOUND ->
                    NotificationOpenConfirmationResult.NOT_FOUND_FOR_ACCOUNT
                FirebaseFunctionsException.Code.ABORTED,
                FirebaseFunctionsException.Code.CANCELLED,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
                FirebaseFunctionsException.Code.INTERNAL,
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
                FirebaseFunctionsException.Code.UNAUTHENTICATED,
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.UNKNOWN ->
                    NotificationOpenConfirmationResult.RETRYABLE_FAILURE
                else -> NotificationOpenConfirmationResult.PERMANENT_FAILURE
            }
        } catch (_: Exception) {
            NotificationOpenConfirmationResult.RETRYABLE_FAILURE
        }
}

private suspend fun <T> Task<T>.awaitOpenConfirmation(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener {
            continuation.cancel(CancellationException("Firebase task cancelled"))
        }
    }
