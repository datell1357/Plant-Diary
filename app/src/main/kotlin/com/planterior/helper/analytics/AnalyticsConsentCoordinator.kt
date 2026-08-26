package com.planterior.helper.analytics

import com.planterior.helper.core.model.AnalyticsConsentState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AnalyticsConsentLocalBoundary {
    fun enable(authorization: AnalyticsAuthorization)

    fun disableImmediately()

    suspend fun prepareOwner(ownerUid: String)

    suspend fun cancelWorkAndPurge(ownerUid: String?)
}

class AnalyticsConsentCoordinator(
    private val remote: AnalyticsRemoteGateway,
    private val local: AnalyticsConsentLocalBoundary,
    private val operationId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutableState =
        MutableStateFlow<AnalyticsConsentState>(AnalyticsConsentState.Loading)
    val state: StateFlow<AnalyticsConsentState> = mutableState.asStateFlow()

    private val mutationMutex = Mutex()
    private val ownerLock = Any()
    private var ownerUid: String? = null
    private var ownerGeneration = 0L
    private var acknowledgedRevision = 0
    private var pending: AnalyticsConsentCommand? = null
    private var deletionBlockedOwner: String? = null

    suspend fun load(owner: String) = load(owner, explicitAfterDeletion = false)

    private suspend fun load(owner: String, explicitAfterDeletion: Boolean) {
        require(owner.isNotBlank())
        val blocked =
            synchronized(ownerLock) { deletionBlockedOwner == owner && !explicitAfterDeletion }
        if (blocked) return
        val alreadyLoaded =
            synchronized(ownerLock) {
                ownerUid == owner &&
                    mutableState.value !is AnalyticsConsentState.Loading &&
                    mutableState.value !is AnalyticsConsentState.FailedOff
            }
        if (alreadyLoaded) return
        val token =
            synchronized(ownerLock) {
                ownerGeneration += 1
                ownerUid = owner
                acknowledgedRevision = 0
                pending = null
                deletionBlockedOwner = null
                mutableState.value = AnalyticsConsentState.Loading
                ownerGeneration
            }
        local.disableImmediately()
        try {
            local.prepareOwner(owner)
            val consent = remote.getConsent(owner)
            if (!isCurrent(owner, token)) return
            if (!consent.granted) local.cancelWorkAndPurge(owner)
            if (!isCurrent(owner, token)) return
            synchronized(ownerLock) {
                if (!isCurrent(owner, token)) return
                acknowledgedRevision = consent.commandGeneration
                mutableState.value =
                    if (consent.granted) {
                        val authorization = AnalyticsAuthorization(owner, consent.commandGeneration)
                        local.enable(authorization)
                        AnalyticsConsentState.Enabled(consent.commandGeneration)
                    } else {
                        AnalyticsConsentState.Disabled
                    }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (isCurrent(owner, token)) mutableState.value = AnalyticsConsentState.FailedOff
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        mutationMutex.withLock {
            val owner = synchronized(ownerLock) { ownerUid } ?: return
            val existing = synchronized(ownerLock) { pending }
            if (existing != null) {
                execute(existing)
                return
            }
            when {
                enabled && mutableState.value is AnalyticsConsentState.Enabled -> return
                !enabled &&
                    mutableState.value !is AnalyticsConsentState.Enabled &&
                    mutableState.value !is AnalyticsConsentState.Enabling -> return
            }
            val command =
                AnalyticsConsentCommand(
                    ownerUid = owner,
                    granted = enabled,
                    commandGeneration = synchronized(ownerLock) { acknowledgedRevision + 1 },
                    operationId = operationId(),
                )
            synchronized(ownerLock) { pending = command }
            execute(command)
        }
    }

    suspend fun retry() {
        mutationMutex.withLock {
            val command = synchronized(ownerLock) { pending }
            if (command != null) {
                execute(command)
            } else {
                val owner = synchronized(ownerLock) { ownerUid } ?: return
                load(owner, explicitAfterDeletion = true)
            }
        }
    }

    suspend fun deletionReceived(owner: String): Boolean {
        require(owner.isNotBlank())
        synchronized(ownerLock) {
            val current = ownerUid
            if (current != null && current != owner) return false
            ownerGeneration += 1
            ownerUid = owner
            acknowledgedRevision = 0
            pending = null
            deletionBlockedOwner = owner
            mutableState.value = AnalyticsConsentState.FailedOff
        }
        local.disableImmediately()
        local.cancelWorkAndPurge(owner)
        return true
    }

    suspend fun clearLocalOwner(owner: String?): Boolean {
        val formerOwner =
            synchronized(ownerLock) {
                val current = ownerUid
                if (owner != null && current != null && owner != current) return false
                ownerGeneration += 1
                ownerUid = null
                acknowledgedRevision = 0
                pending = null
                deletionBlockedOwner = null
                mutableState.value = AnalyticsConsentState.Loading
                current ?: owner
            }
        local.disableImmediately()
        local.cancelWorkAndPurge(formerOwner)
        return true
    }

    private suspend fun execute(command: AnalyticsConsentCommand) {
        val token = synchronized(ownerLock) { ownerGeneration }
        if (!isCurrent(command.ownerUid, token)) return
        if (command.granted) {
            mutableState.value = AnalyticsConsentState.Enabling
        } else {
            mutableState.value = AnalyticsConsentState.Disabling
            local.disableImmediately()
            try {
                local.cancelWorkAndPurge(command.ownerUid)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isCurrent(command.ownerUid, token)) {
                    mutableState.value = AnalyticsConsentState.FailedOff
                }
                return
            }
        }
        try {
            val acknowledgement = remote.setConsent(command)
            if (!isCurrent(command.ownerUid, token)) return
            check(acknowledgement.granted == command.granted)
            check(acknowledgement.commandGeneration == command.commandGeneration)
            synchronized(ownerLock) {
                if (!isCurrent(command.ownerUid, token)) return
                acknowledgedRevision = acknowledgement.commandGeneration
                pending = null
                if (acknowledgement.granted) {
                    local.enable(
                        AnalyticsAuthorization(
                            command.ownerUid,
                            acknowledgement.commandGeneration,
                        )
                    )
                    mutableState.value =
                        AnalyticsConsentState.Enabled(acknowledgement.commandGeneration)
                } else {
                    mutableState.value = AnalyticsConsentState.Disabled
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (isCurrent(command.ownerUid, token)) {
                local.disableImmediately()
                mutableState.value = AnalyticsConsentState.FailedOff
            }
        }
    }

    private fun isCurrent(owner: String, token: Long): Boolean =
        synchronized(ownerLock) { ownerUid == owner && ownerGeneration == token }
}
