package com.planterior.helper.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Task
import com.google.common.util.concurrent.ListenableFuture
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

data class NotificationEndpointRegistration(
    val accountId: String,
    val installationId: String,
    val installationSecret: String,
    val nextInstallationSecret: String,
    val generation: Long,
    val token: String,
    val notificationsEnabled: Boolean,
)

data class NotificationEndpointUnregistration(
    val accountId: String,
    val installationId: String,
    val installationSecret: String,
    val generation: Long,
)

enum class NotificationEndpointRevocationResult {
    REVOKED,
    ALREADY_ABSENT,
}

class NotificationTokenStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun installationId(): String =
        synchronized(STATE_LOCK) {
            val existing = preferences.getString(INSTALLATION_ID, null)
            if (existing != null) return existing
            val created = UUID.randomUUID().toString().replace("-", "")
            preferences.edit(commit = true) { putString(INSTALLATION_ID, created) }
            created
        }

    fun installationSecret(): String =
        synchronized(STATE_LOCK) {
            val existing = preferences.getString(INSTALLATION_SECRET, null)
            if (existing != null) return existing
            val created =
                UUID.randomUUID().toString().replace("-", "") +
                    UUID.randomUUID().toString().replace("-", "")
            preferences.edit(commit = true) { putString(INSTALLATION_SECRET, created) }
            created
        }

    fun updateToken(token: String): Boolean =
        synchronized(STATE_LOCK) {
            require(token.isNotBlank())
            if (pendingTokenUnsafe() == token) return false
            preferences.edit(commit = true) { putString(PENDING_TOKEN, token) }
            true
        }

    fun pendingToken(): String? = synchronized(STATE_LOCK) { pendingTokenUnsafe() }

    fun updateCapability(notificationsEnabled: Boolean): Boolean =
        synchronized(STATE_LOCK) {
            if (
                preferences.contains(NOTIFICATIONS_ENABLED) &&
                    preferences.getBoolean(NOTIFICATIONS_ENABLED, false) == notificationsEnabled
            ) {
                return false
            }
            preferences.edit(commit = true) {
                putBoolean(NOTIFICATIONS_ENABLED, notificationsEnabled)
            }
            true
        }

    fun notificationsEnabled(): Boolean = synchronized(STATE_LOCK) { notificationsEnabledUnsafe() }

    fun clearLocalState() =
        synchronized(STATE_LOCK) {
            preferences.edit(commit = true) { clear() }
        }

    fun registrationFor(accountId: String): NotificationEndpointRegistration? =
        synchronized(STATE_LOCK) {
            val desiredKind = preferences.getString(DESIRED_KIND, null)
            if (
                desiredKind == DESIRED_UNREGISTER &&
                    !preferences.getBoolean(DESIRED_UNREGISTER_CONFIRMED, false)
            ) {
                return null
            }
            val desiredRegistration = desiredRegistrationUnsafe()
            if (desiredRegistration != null && !isRegisteredUnsafe(desiredRegistration)) {
                return if (desiredRegistration.accountId == accountId) desiredRegistration else null
            }
            if (desiredRegistration != null && desiredRegistration.accountId != accountId) {
                return null
            }
            val token = pendingTokenUnsafe() ?: return null
            val capability = notificationsEnabledUnsafe()
            if (
                desiredRegistration != null &&
                    desiredRegistration.token == token &&
                    desiredRegistration.notificationsEnabled == capability
            ) {
                return null
            }
            val currentSecret = installationSecret()
            val transferringOwner =
                desiredKind == DESIRED_UNREGISTER &&
                    preferences.getBoolean(DESIRED_UNREGISTER_CONFIRMED, false) &&
                    preferences.getString(DESIRED_ACCOUNT, null) != accountId
            val nextSecret =
                if (transferringOwner) {
                    UUID.randomUUID().toString().replace("-", "") +
                        UUID.randomUUID().toString().replace("-", "")
                } else {
                    currentSecret
                }
            val generation = nextGeneration()
            val registration =
                NotificationEndpointRegistration(
                    accountId,
                    installationId(),
                    currentSecret,
                    nextSecret,
                    generation,
                    token,
                    capability,
                )
            preferences.edit(commit = true) {
                putLong(LAST_GENERATION, registration.generation)
                putString(DESIRED_KIND, DESIRED_REGISTER)
                putString(DESIRED_ACCOUNT, registration.accountId)
                putString(DESIRED_TOKEN, registration.token)
                putBoolean(DESIRED_CAPABILITY, registration.notificationsEnabled)
                putString(DESIRED_CURRENT_SECRET, registration.installationSecret)
                putString(DESIRED_NEXT_SECRET, registration.nextInstallationSecret)
                putLong(DESIRED_GENERATION, registration.generation)
                clearRegisteredMarker()
            }
            registration
        }

    fun unresolvedRegistrationFor(accountId: String): NotificationEndpointRegistration? =
        synchronized(STATE_LOCK) {
            val registration = desiredRegistrationUnsafe() ?: return null
            if (isRegisteredUnsafe(registration)) return null
            check(registration.accountId == accountId) {
                "Another account's notification registration must be resolved first"
            }
            registration
        }

    fun markRegistered(registration: NotificationEndpointRegistration): Unit =
        synchronized(STATE_LOCK) {
            if (desiredRegistrationUnsafe() != registration) return
            preferences.edit(commit = true) {
                putString(INSTALLATION_SECRET, registration.nextInstallationSecret)
                putString(REGISTERED_ACCOUNT, registration.accountId)
                putLong(REGISTERED_GENERATION, registration.generation)
            }
        }

    // A constrained worker that never started has no command to revoke. Keep the confirmed
    // prior-owner tombstone as the stale-registration fence instead of replacing its epoch.
    fun beginLogoutUnregistration(accountId: String): NotificationEndpointUnregistration? =
        synchronized(STATE_LOCK) {
            val confirmedPriorOwnerTombstone =
                preferences.getString(DESIRED_KIND, null) == DESIRED_UNREGISTER &&
                    preferences.getBoolean(DESIRED_UNREGISTER_CONFIRMED, false) &&
                    preferences.getString(DESIRED_ACCOUNT, null) != accountId &&
                    preferences.getString(REGISTERED_ACCOUNT, null) == null
            if (confirmedPriorOwnerTombstone) return null
            beginUnregistration(accountId)
        }

    fun beginUnregistration(accountId: String): NotificationEndpointUnregistration =
        synchronized(STATE_LOCK) {
            val desiredKind = preferences.getString(DESIRED_KIND, null)
            val desiredAccount = preferences.getString(DESIRED_ACCOUNT, null)
            if (desiredKind == DESIRED_UNREGISTER) {
                check(desiredAccount == accountId) {
                    "Another account's notification revocation must be resolved first"
                }
                return NotificationEndpointUnregistration(
                    accountId,
                    installationId(),
                    requireNotNull(preferences.getString(DESIRED_CURRENT_SECRET, null)),
                    preferences.getLong(DESIRED_GENERATION, 0),
                )
            }
            val desiredRegistration = desiredRegistrationUnsafe()
            check(desiredRegistration == null || isRegisteredUnsafe(desiredRegistration)) {
                "Notification registration must be resolved before revocation"
            }
            check(desiredRegistration == null || desiredRegistration.accountId == accountId) {
                "Another account's registered endpoint must be revoked first"
            }
            val registeredAccount = preferences.getString(REGISTERED_ACCOUNT, null)
            check(registeredAccount == null || registeredAccount == accountId) {
                "Another account's registered endpoint must be revoked first"
            }
            val secret = installationSecret()
            val generation = nextGeneration()
            val unregistration =
                NotificationEndpointUnregistration(
                    accountId,
                    installationId(),
                    secret,
                    generation,
                )
            preferences.edit(commit = true) {
                putLong(LAST_GENERATION, unregistration.generation)
                putString(DESIRED_KIND, DESIRED_UNREGISTER)
                putString(DESIRED_ACCOUNT, accountId)
                putString(DESIRED_CURRENT_SECRET, secret)
                remove(DESIRED_NEXT_SECRET)
                remove(DESIRED_TOKEN)
                remove(DESIRED_CAPABILITY)
                putLong(DESIRED_GENERATION, generation)
                putBoolean(DESIRED_UNREGISTER_CONFIRMED, false)
                clearRegisteredMarker()
            }
            unregistration
        }

    fun markUnregistered(
        unregistration: NotificationEndpointUnregistration,
        result: NotificationEndpointRevocationResult,
    ) =
        synchronized(STATE_LOCK) {
            if (
                preferences.getString(DESIRED_KIND, null) == DESIRED_UNREGISTER &&
                    preferences.getString(DESIRED_ACCOUNT, null) == unregistration.accountId &&
                    preferences.getString(DESIRED_CURRENT_SECRET, null) ==
                        unregistration.installationSecret &&
                    preferences.getLong(DESIRED_GENERATION, 0) == unregistration.generation
            ) {
                preferences.edit(commit = true) {
                    clearRegisteredMarker()
                    if (result == NotificationEndpointRevocationResult.REVOKED) {
                        putBoolean(DESIRED_UNREGISTER_CONFIRMED, true)
                    } else {
                        remove(INSTALLATION_ID)
                        remove(INSTALLATION_SECRET)
                        remove(LAST_GENERATION)
                        remove(DESIRED_KIND)
                        remove(DESIRED_ACCOUNT)
                        remove(DESIRED_CURRENT_SECRET)
                        remove(DESIRED_NEXT_SECRET)
                        remove(DESIRED_GENERATION)
                        remove(DESIRED_UNREGISTER_CONFIRMED)
                    }
                }
            }
        }

    private fun pendingTokenUnsafe(): String? = preferences.getString(PENDING_TOKEN, null)

    private fun notificationsEnabledUnsafe(): Boolean =
        preferences.getBoolean(NOTIFICATIONS_ENABLED, false)

    private fun desiredRegistrationUnsafe(): NotificationEndpointRegistration? {
        if (preferences.getString(DESIRED_KIND, null) != DESIRED_REGISTER) return null
        return NotificationEndpointRegistration(
            accountId = requireNotNull(preferences.getString(DESIRED_ACCOUNT, null)),
            installationId = installationId(),
            installationSecret =
                requireNotNull(preferences.getString(DESIRED_CURRENT_SECRET, null)),
            nextInstallationSecret =
                requireNotNull(preferences.getString(DESIRED_NEXT_SECRET, null)),
            generation = preferences.getLong(DESIRED_GENERATION, 0),
            token = requireNotNull(preferences.getString(DESIRED_TOKEN, null)),
            notificationsEnabled = preferences.getBoolean(DESIRED_CAPABILITY, false),
        )
    }

    private fun isRegisteredUnsafe(registration: NotificationEndpointRegistration): Boolean =
        preferences.getString(REGISTERED_ACCOUNT, null) == registration.accountId &&
            preferences.getLong(REGISTERED_GENERATION, 0) == registration.generation &&
            preferences.getString(INSTALLATION_SECRET, null) == registration.nextInstallationSecret

    private fun nextGeneration(): Long {
        val current = preferences.getLong(LAST_GENERATION, 0)
        check(current < MAXIMUM_SAFE_SERVER_GENERATION) {
            "Notification endpoint generation is exhausted"
        }
        return current + 1
    }

    private fun android.content.SharedPreferences.Editor.clearRegisteredMarker() {
        remove(REGISTERED_ACCOUNT)
        remove(REGISTERED_GENERATION)
    }

    companion object {
        private val STATE_LOCK = Any()
        const val PREFERENCES = "notification-endpoint"
        private const val INSTALLATION_ID = "installation-id"
        private const val INSTALLATION_SECRET = "installation-secret"
        private const val PENDING_TOKEN = "pending-token"
        private const val NOTIFICATIONS_ENABLED = "notifications-enabled"
        private const val LAST_GENERATION = "last-generation"
        private const val DESIRED_KIND = "desired-kind"
        private const val DESIRED_ACCOUNT = "desired-account"
        private const val DESIRED_TOKEN = "desired-token"
        private const val DESIRED_CAPABILITY = "desired-capability"
        private const val DESIRED_GENERATION = "desired-generation"
        private const val DESIRED_CURRENT_SECRET = "desired-current-secret"
        private const val DESIRED_NEXT_SECRET = "desired-next-secret"
        private const val DESIRED_UNREGISTER_CONFIRMED = "desired-unregister-confirmed"
        private const val REGISTERED_ACCOUNT = "registered-account"
        private const val REGISTERED_GENERATION = "registered-generation"
        private const val DESIRED_REGISTER = "REGISTER"
        private const val DESIRED_UNREGISTER = "UNREGISTER"
        private const val MAXIMUM_SAFE_SERVER_GENERATION = 9_007_199_254_740_991L
    }
}

object NotificationCapabilityPublisher {
    fun notificationsEnabled(context: Context): Boolean {
        val permissionGranted =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        return permissionGranted &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

interface NotificationEndpointGateway {
    suspend fun register(registration: NotificationEndpointRegistration)

    suspend fun unregister(
        unregistration: NotificationEndpointUnregistration
    ): NotificationEndpointRevocationResult
}

fun interface NotificationEndpointCallable {
    suspend fun call(name: String, data: Map<String, Any>): Any?
}

open class NotificationEndpointException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class TransientNotificationEndpointException(message: String, cause: Throwable? = null) :
    NotificationEndpointException(message, cause)

class PermanentNotificationEndpointException(message: String, cause: Throwable? = null) :
    NotificationEndpointException(message, cause)

internal object FirebaseEndpointFailureClassifier {
    fun isTransient(code: FirebaseFunctionsException.Code): Boolean =
        code in
            setOf(
                FirebaseFunctionsException.Code.ABORTED,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
                FirebaseFunctionsException.Code.INTERNAL,
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.UNKNOWN,
            )
}

object NotificationRegistrationRetryPolicy {
    private const val MAXIMUM_ATTEMPTS = 5

    fun shouldRetry(error: Throwable, runAttemptCount: Int): Boolean =
        error is TransientNotificationEndpointException && runAttemptCount < MAXIMUM_ATTEMPTS
}

enum class NotificationRegistrationResult {
    REGISTERED,
    ALREADY_REGISTERED,
    NO_SESSION,
}

class NotificationRegistrationTask(
    private val tokenStore: NotificationTokenStore,
    private val gateway: NotificationEndpointGateway,
    private val activeAccount: () -> String?,
) {
    suspend fun run(): NotificationRegistrationResult {
        val accountId = activeAccount() ?: return NotificationRegistrationResult.NO_SESSION
        val registration =
            tokenStore.registrationFor(accountId)
                ?: return NotificationRegistrationResult.ALREADY_REGISTERED
        gateway.register(registration)
        if (activeAccount() != accountId) return NotificationRegistrationResult.NO_SESSION
        tokenStore.markRegistered(registration)
        return NotificationRegistrationResult.REGISTERED
    }
}

class FirebaseNotificationEndpointGateway
internal constructor(private val callable: NotificationEndpointCallable) :
    NotificationEndpointGateway {
    constructor(
        functions: FirebaseFunctions
    ) : this(
        NotificationEndpointCallable { name, data ->
            functions.getHttpsCallable(name).call(data).await().data
        }
    )

    override suspend fun register(registration: NotificationEndpointRegistration) {
        val response =
            call(
                "registerNotificationEndpoint",
                mapOf(
                    "expectedOwnerUid" to registration.accountId,
                    "installationId" to registration.installationId,
                    "installationSecret" to registration.installationSecret,
                    "nextInstallationSecret" to registration.nextInstallationSecret,
                    "generation" to registration.generation,
                    "token" to registration.token,
                    "platform" to "ANDROID",
                    "notificationsEnabled" to registration.notificationsEnabled,
                ),
            )
        val value = response as? Map<*, *>
        if (value == null || value.keys != setOf("registered") || value["registered"] != true) {
            throw PermanentNotificationEndpointException(
                "Notification endpoint registration response is malformed"
            )
        }
    }

    override suspend fun unregister(
        unregistration: NotificationEndpointUnregistration
    ): NotificationEndpointRevocationResult {
        require(unregistration.accountId.isNotBlank())
        val response =
            call(
                "unregisterNotificationEndpoint",
                mapOf(
                    "expectedOwnerUid" to unregistration.accountId,
                    "installationId" to unregistration.installationId,
                    "installationSecret" to unregistration.installationSecret,
                    "generation" to unregistration.generation,
                ),
            )
        val value = response as? Map<*, *>
        if (
            value == null ||
                value.keys != setOf("unregistered", "status") ||
                value["unregistered"] != true
        ) {
            throw PermanentNotificationEndpointException(
                "Notification endpoint revocation response is malformed"
            )
        }
        return when (value["status"]) {
            "REVOKED" -> NotificationEndpointRevocationResult.REVOKED
            "ALREADY_ABSENT" -> NotificationEndpointRevocationResult.ALREADY_ABSENT
            else ->
                throw PermanentNotificationEndpointException(
                    "Notification endpoint revocation status is invalid"
                )
        }
    }

    private suspend fun call(name: String, data: Map<String, Any>): Any? {
        try {
            return callable.call(name, data)
        } catch (error: CancellationException) {
            throw error
        } catch (error: FirebaseFunctionsException) {
            if (FirebaseEndpointFailureClassifier.isTransient(error.code)) {
                throw TransientNotificationEndpointException(error.message.orEmpty(), error)
            }
            throw PermanentNotificationEndpointException(error.message.orEmpty(), error)
        } catch (error: NotificationEndpointException) {
            throw error
        } catch (error: Exception) {
            throw TransientNotificationEndpointException(error.message.orEmpty(), error)
        }
    }
}

class NotificationRegistrationWorker(
    appContext: Context,
    parameters: WorkerParameters,
    private val task: NotificationRegistrationTask,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result =
        try {
            task.run()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (NotificationRegistrationRetryPolicy.shouldRetry(error, runAttemptCount)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
}

class NotificationWorkerFactory(
    private val tokenStore: NotificationTokenStore,
    private val gateway: NotificationEndpointGateway,
    private val activeAccount: () -> String?,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        if (workerClassName == NotificationRegistrationWorker::class.java.name) {
            NotificationRegistrationWorker(
                appContext,
                workerParameters,
                NotificationRegistrationTask(tokenStore, gateway, activeAccount),
            )
        } else {
            null
        }
}

class NotificationWorkScheduler(private val workManager: WorkManager) {
    fun enqueueTokenRegistration() {
        workManager.enqueueUniqueWork(
            TOKEN_REGISTRATION_WORK,
            ExistingWorkPolicy.REPLACE,
            registrationRequest(),
        )
    }

    suspend fun cancelTokenRegistration() {
        workManager.cancelUniqueWork(TOKEN_REGISTRATION_WORK).result.awaitFuture()
    }

    companion object {
        const val TOKEN_REGISTRATION_WORK = "notification-token-registration"

        internal fun registrationRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<NotificationRegistrationWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel(CancellationException("Firebase task cancelled")) }
}

private suspend fun <T> ListenableFuture<T>.awaitFuture(): T =
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
