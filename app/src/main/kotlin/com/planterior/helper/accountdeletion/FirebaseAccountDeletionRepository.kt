package com.planterior.helper.accountdeletion

import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.DeletionRequestId
import com.planterior.helper.core.model.DeletionStatus
import com.planterior.helper.feature.settings.AccountDeletionCancellationResult
import com.planterior.helper.feature.settings.AccountDeletionCategory
import com.planterior.helper.feature.settings.AccountDeletionRepository
import com.planterior.helper.feature.settings.AccountDeletionRetryResult
import com.planterior.helper.feature.settings.AccountDeletionScope
import com.planterior.helper.feature.settings.AccountDeletionScopeHash
import com.planterior.helper.feature.settings.AccountDeletionWorkflow
import com.planterior.helper.feature.settings.ConfirmedAccountDeletionRequest
import com.planterior.helper.feature.settings.ConfirmedAccountDeletionRetry
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

fun interface AccountDeletionCallable {
    suspend fun call(name: String, data: Map<String, Any>): Any?
}

class FirebaseAccountDeletionCallable(private val functions: FirebaseFunctions) :
    AccountDeletionCallable {
    override suspend fun call(name: String, data: Map<String, Any>): Any? =
        functions.getHttpsCallable(name).call(data).awaitAccountDeletion().data
}

class FirebaseAccountDeletionRepository(
    private val owner: AccountId,
    private val callable: AccountDeletionCallable,
    private val idempotencyKey: () -> String = {
        UUID.randomUUID().toString().replace("-", "")
    },
) : AccountDeletionRepository {
    private var pendingRequestKey: String? = null
    private var pendingRetryKey: String? = null

    override suspend fun preview(): AccountDeletionScope {
        val response = callable.call("previewAccountDeletion", ownerPayload()).objectMap()
        val scope = response["scope"].objectMap()
        require(scope["categories"].stringList() == SERVER_SCOPES)
        require(scope.long("gracePeriodMillis") == ACCOUNT_DELETION_GRACE_MILLIS)
        return ACCOUNT_SCOPE
    }

    override suspend fun status(): AccountDeletionWorkflow? =
        callable.call("getAccountDeletionStatus", ownerPayload())?.workflow(owner)

    override suspend fun request(
        request: ConfirmedAccountDeletionRequest
    ): AccountDeletionWorkflow {
        require(request.scope == ACCOUNT_SCOPE)
        val key = pendingRequestKey ?: idempotencyKey().also { pendingRequestKey = it }
        requireValidIdempotencyKey(key)
        return callable
            .call(
                "requestAccountDeletion",
                ownerPayload() + mapOf("confirmed" to true, "idempotencyKey" to key),
            )
            .workflow(owner)
            .also { pendingRequestKey = null }
    }

    override suspend fun cancel(requestId: DeletionRequestId): AccountDeletionCancellationResult =
        AccountDeletionCancellationResult(
            expectedRequestId = requestId,
            workflow =
                callable
                    .call(
                        "cancelAccountDeletion",
                        ownerPayload() + mapOf("requestId" to requestId.value),
                    )
                    .workflow(owner),
        )

    override suspend fun retry(request: ConfirmedAccountDeletionRetry): AccountDeletionRetryResult {
        require(request.scope == ACCOUNT_SCOPE)
        val key = pendingRetryKey ?: idempotencyKey().also { pendingRetryKey = it }
        requireValidIdempotencyKey(key)
        val workflow =
            callable
                .call(
                    "retryAccountDeletion",
                    ownerPayload() + mapOf("confirmed" to true, "idempotencyKey" to key),
                )
                .workflow(owner)
        return AccountDeletionRetryResult(
                retriedRequestId = request.requestId,
                kind = request.kind,
                workflow = workflow,
            )
            .also { pendingRetryKey = null }
    }

    private fun ownerPayload(): Map<String, Any> = mapOf("expectedOwnerUid" to owner.value)
}

private fun Any?.workflow(owner: AccountId): AccountDeletionWorkflow {
    val response = objectMap()
    require(response["ownerUid"] == owner.value)
    val requestId = response.string("requestId")
    require(requestId.matches(Regex("^[A-Za-z0-9_-]{8,128}$")))
    val status = DeletionStatus.valueOf(response.string("status"))
    val requestedAt = Instant.ofEpochMilli(response.long("requestedAtMillis"))
    val scheduledAt = Instant.ofEpochMilli(response.long("scheduledForMillis"))
    require(scheduledAt == requestedAt.plusMillis(ACCOUNT_DELETION_GRACE_MILLIS))
    val completedScopes = response["completedScopes"].canonicalServerScopes()
    val failedScopes = response["failedScopes"].canonicalServerScopes()
    require(completedScopes.intersect(failedScopes).isEmpty())
    val completedAtMillis = response.nullableLong("completedAtMillis")
    when (status) {
        DeletionStatus.RECEIVED,
        DeletionStatus.CANCELLED -> {
            require(completedScopes.isEmpty())
            require(failedScopes.isEmpty())
            require(completedAtMillis == null)
        }
        DeletionStatus.PROCESSING -> {
            require(failedScopes.isEmpty())
            require(completedScopes.size < SERVER_SCOPES.size)
            require(completedAtMillis == null)
        }
        DeletionStatus.COMPLETED -> {
            require(completedScopes == SERVER_SCOPES.toSet())
            require(failedScopes.isEmpty())
            require(completedAtMillis != null)
            require(completedAtMillis >= scheduledAt.toEpochMilli())
        }
        DeletionStatus.FAILED -> {
            require(completedScopes.isEmpty())
            require(failedScopes == SERVER_SCOPES.toSet())
            require(completedAtMillis == null)
        }
        DeletionStatus.PARTIALLY_FAILED -> {
            require(completedScopes.isNotEmpty())
            require(failedScopes.isNotEmpty())
            require(completedScopes + failedScopes == SERVER_SCOPES.toSet())
            require(completedAtMillis == null)
        }
    }
    val remainingScopes = SERVER_SCOPES.toSet() - completedScopes
    val completedCategories = CATEGORY_SERVER_SCOPES.filterValues(completedScopes::containsAll).keys
    val remainingCategories =
        CATEGORY_SERVER_SCOPES.filterValues { scopes -> scopes.any(remainingScopes::contains) }.keys
    return AccountDeletionWorkflow(
        requestId = DeletionRequestId(requestId),
        scope = ACCOUNT_SCOPE,
        requestedAt = requestedAt,
        scheduledAt = scheduledAt,
        status = status,
        completedCategories = completedCategories,
        remainingCategories = remainingCategories,
    )
}

private fun Any?.objectMap(): Map<String, Any?> {
    require(this is Map<*, *>)
    require(keys.all { it is String })
    @Suppress("UNCHECKED_CAST")
    return this as Map<String, Any?>
}

private fun Any?.stringList(): List<String> {
    require(this is List<*>)
    require(all { it is String })
    @Suppress("UNCHECKED_CAST")
    return this as List<String>
}

private fun Any?.canonicalServerScopes(): Set<String> {
    val scopes = stringList()
    require(scopes.distinct().size == scopes.size)
    require(SERVER_SCOPES.containsAll(scopes))
    require(scopes == scopes.sortedBy(SERVER_SCOPES::indexOf))
    return scopes.toSet()
}

private fun Map<String, Any?>.string(key: String): String =
    requireNotNull(get(key)) as? String ?: error("Invalid account deletion response")

private fun Map<String, Any?>.long(key: String): Long = requireNotNull(get(key)).contractLong()

private fun Map<String, Any?>.nullableLong(key: String): Long? {
    require(containsKey(key))
    return get(key)?.contractLong()
}

private fun Any.contractLong(): Long {
    require(this is Number)
    val result = toLong()
    if (this !is Byte && this !is Short && this !is Int && this !is Long) {
        val decimal = toDouble()
        require(decimal.isFinite() && decimal == result.toDouble())
    }
    return result
}

private fun requireValidIdempotencyKey(value: String) {
    require(value.matches(Regex("^[A-Za-z0-9_-]{8,128}$")))
}

private suspend fun <T> Task<T>.awaitAccountDeletion(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener {
            continuation.cancel(CancellationException("Firebase task cancelled"))
        }
    }

private const val ACCOUNT_DELETION_GRACE_MILLIS = 7L * 24 * 60 * 60 * 1_000

internal val SERVER_SCOPES =
    listOf(
        "PUBLIC_SHARES",
        "NOTIFICATION_ENDPOINT_OWNERS",
        "PRIVATE_MEDIA_RESERVATIONS",
        "IDENTIFICATION_ORIGINALS",
        "PLANT_PHOTOS",
        "SHARE_IMAGES",
        "USER_DOCUMENTS",
        "AUTH_ACCOUNT",
    )

private val CATEGORY_SERVER_SCOPES =
    linkedMapOf(
            AccountDeletionCategory.FIRESTORE_ACCOUNT_DATA to setOf("USER_DOCUMENTS"),
            AccountDeletionCategory.NOTIFICATION_LINKS to setOf("NOTIFICATION_ENDPOINT_OWNERS"),
            AccountDeletionCategory.PUBLIC_SHARES to setOf("PUBLIC_SHARES"),
            AccountDeletionCategory.IDENTIFICATION_MEDIA to setOf("IDENTIFICATION_ORIGINALS"),
            AccountDeletionCategory.ACCOUNT_MEDIA to setOf("PLANT_PHOTOS", "SHARE_IMAGES"),
            AccountDeletionCategory.PRIVATE_MEDIA_RESERVATIONS to
                setOf("PRIVATE_MEDIA_RESERVATIONS"),
            AccountDeletionCategory.AUTH_ACCOUNT to setOf("AUTH_ACCOUNT"),
        )
        .also { mappings ->
            require(mappings.values.flatten().toSet() == SERVER_SCOPES.toSet())
        }

private val ACCOUNT_SCOPE =
    AccountDeletionScope(
        hash = AccountDeletionScopeHash(SERVER_SCOPES.joinToString("\u0000").sha256()),
        categories = AccountDeletionCategory.entries.toList(),
    )

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8)).joinToString("") { byte
        ->
        "%02x".format(byte)
    }
