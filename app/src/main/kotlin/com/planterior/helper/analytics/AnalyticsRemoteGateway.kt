package com.planterior.helper.analytics

import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

data class RemoteAnalyticsConsent(
    val granted: Boolean,
    val commandGeneration: Int,
)

data class AnalyticsConsentCommand(
    val ownerUid: String,
    val granted: Boolean,
    val commandGeneration: Int,
    val operationId: String,
)

data class AnalyticsEventCommand(
    val eventId: String,
    val eventName: String,
    val consentRevision: Int,
)

data class AnalyticsEventBatchCommand(
    val ownerUid: String,
    val events: List<AnalyticsEventCommand>,
) {
    init {
        require(ownerUid.isNotBlank())
        require(events.size in 1..MAX_ANALYTICS_EVENT_BATCH_SIZE)
    }
}

data class AnalyticsConsentAcknowledgement(
    val granted: Boolean,
    val commandGeneration: Int,
    val replayed: Boolean,
)

data class AnalyticsEventAcknowledgement(
    val eventId: String,
    val duplicate: Boolean,
)

interface AnalyticsRemoteGateway {
    suspend fun getConsent(ownerUid: String): RemoteAnalyticsConsent

    suspend fun setConsent(command: AnalyticsConsentCommand): AnalyticsConsentAcknowledgement

    suspend fun recordEvents(
        command: AnalyticsEventBatchCommand
    ): List<AnalyticsEventAcknowledgement>
}

class AnalyticsTransportException(cause: Throwable) : RuntimeException(cause)

class AnalyticsStaleOrDisabledException(cause: Throwable? = null) : RuntimeException(cause)

class AnalyticsPermanentSchemaException(cause: Throwable? = null) : RuntimeException(cause)

internal fun interface AnalyticsCallable {
    suspend fun call(name: String, payload: Map<String, *>): Any?
}

class FirebaseAnalyticsRemoteGateway internal constructor(private val callable: AnalyticsCallable) :
    AnalyticsRemoteGateway {
    constructor(
        functions: FirebaseFunctions
    ) : this(
        AnalyticsCallable { name, payload ->
            functions.getHttpsCallable(name).call(payload).awaitAnalytics().data
        }
    )

    override suspend fun getConsent(ownerUid: String): RemoteAnalyticsConsent {
        val value = call("getAnalyticsConsent", mapOf("ownerUid" to ownerUid)).objectMap()
        return RemoteAnalyticsConsent(
            granted = value.requiredBoolean("granted"),
            commandGeneration = value.requiredInt("commandGeneration"),
        )
    }

    override suspend fun setConsent(
        command: AnalyticsConsentCommand
    ): AnalyticsConsentAcknowledgement {
        val value =
            call(
                    "setAnalyticsConsent",
                    mapOf(
                        "ownerUid" to command.ownerUid,
                        "granted" to command.granted,
                        "commandGeneration" to command.commandGeneration,
                        "operationId" to command.operationId,
                    ),
                )
                .objectMap()
        return AnalyticsConsentAcknowledgement(
            granted = value.requiredBoolean("granted"),
            commandGeneration = value.requiredInt("commandGeneration"),
            replayed = value.requiredBoolean("replayed"),
        )
    }

    override suspend fun recordEvents(
        command: AnalyticsEventBatchCommand
    ): List<AnalyticsEventAcknowledgement> {
        val value =
            call(
                    "recordAnalyticsEvent",
                    mapOf(
                        "ownerUid" to command.ownerUid,
                        "events" to
                            command.events.map { event ->
                                mapOf(
                                    "schemaVersion" to 1,
                                    "eventId" to event.eventId,
                                    "eventName" to event.eventName,
                                    "consentRevision" to event.consentRevision,
                                )
                            },
                    ),
                )
                .objectMap()
        val results = value.requiredList("results")
        if (results.size != command.events.size) throw AnalyticsPermanentSchemaException()
        return results.mapIndexed { index, result ->
            val resultMap = result.objectMap()
            val eventId = resultMap.requiredString("eventId")
            if (
                eventId != command.events[index].eventId || !resultMap.requiredBoolean("accepted")
            ) {
                throw AnalyticsPermanentSchemaException()
            }
            AnalyticsEventAcknowledgement(
                eventId = eventId,
                duplicate = resultMap.requiredBoolean("duplicate"),
            )
        }
    }

    private suspend fun call(name: String, payload: Map<String, *>): Any? =
        try {
            callable.call(name, payload)
        } catch (error: CancellationException) {
            throw error
        } catch (error: FirebaseFunctionsException) {
            when (error.code) {
                FirebaseFunctionsException.Code.ABORTED,
                FirebaseFunctionsException.Code.CANCELLED,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
                FirebaseFunctionsException.Code.INTERNAL,
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.UNKNOWN -> throw AnalyticsTransportException(error)
                FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                FirebaseFunctionsException.Code.PERMISSION_DENIED,
                FirebaseFunctionsException.Code.UNAUTHENTICATED,
                FirebaseFunctionsException.Code.NOT_FOUND ->
                    throw AnalyticsStaleOrDisabledException(error)
                else -> throw AnalyticsPermanentSchemaException(error)
            }
        } catch (error: AnalyticsTransportException) {
            throw error
        } catch (error: AnalyticsStaleOrDisabledException) {
            throw error
        } catch (error: AnalyticsPermanentSchemaException) {
            throw error
        } catch (error: Exception) {
            throw AnalyticsTransportException(error)
        }
}

private fun Any?.objectMap(): Map<*, *> =
    this as? Map<*, *> ?: throw AnalyticsPermanentSchemaException()

private fun Map<*, *>.requiredBoolean(key: String): Boolean =
    this[key] as? Boolean ?: throw AnalyticsPermanentSchemaException()

private fun Map<*, *>.requiredString(key: String): String =
    this[key] as? String ?: throw AnalyticsPermanentSchemaException()

private fun Map<*, *>.requiredList(key: String): List<*> =
    this[key] as? List<*> ?: throw AnalyticsPermanentSchemaException()

private fun Map<*, *>.requiredInt(key: String): Int {
    val value = this[key] as? Number ?: throw AnalyticsPermanentSchemaException()
    val integer = value.toInt()
    if (integer < 0 || integer.toDouble() != value.toDouble()) {
        throw AnalyticsPermanentSchemaException()
    }
    return integer
}

private const val MAX_ANALYTICS_EVENT_BATCH_SIZE = 50

private suspend fun <T> Task<T>.awaitAnalytics(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel(CancellationException("Firebase task cancelled")) }
}
