package com.planterior.helper.accountdeletion

import android.content.Context
import androidx.core.content.edit
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.DeletionRequestId
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TerminalAccountDeletionCleanupCommand(
    val owner: AccountId,
    val operationId: DeletionRequestId,
)

enum class TerminalCleanupPhase {
    CANCEL_LOCATION,
    CANCEL_NOTIFICATION_WORK,
    SIGN_OUT_LOCAL,
    PURGE_ROOM,
    CLEAR_ANALYTICS,
    CLEAR_NOTIFICATIONS,
    CLEAR_WEATHER,
    CLEAR_SHARE_CACHE,
    EMIT_EXIT,
}

fun interface TerminalAccountDeletionCleanupActions {
    suspend fun run(
        phase: TerminalCleanupPhase,
        command: TerminalAccountDeletionCleanupCommand,
    )
}

interface TerminalAccountDeletionCleanupJournal {
    fun begin(command: TerminalAccountDeletionCleanupCommand)

    fun commands(): List<TerminalAccountDeletionCleanupCommand>

    fun completedPhases(command: TerminalAccountDeletionCleanupCommand): Set<TerminalCleanupPhase>

    fun markCompleted(
        command: TerminalAccountDeletionCleanupCommand,
        phase: TerminalCleanupPhase,
    )

    /** Replaces the raw recovery command with its opaque idempotency digest. */
    fun finish(command: TerminalAccountDeletionCleanupCommand)
}

data class TerminalAccountDeletionCleanupResult(
    val command: TerminalAccountDeletionCleanupCommand,
    val failedPhases: Set<TerminalCleanupPhase>,
) {
    val complete: Boolean
        get() = failedPhases.isEmpty()
}

class TerminalAccountDeletionCleanupRuntime(
    private val journal: TerminalAccountDeletionCleanupJournal,
    private val actions: TerminalAccountDeletionCleanupActions,
) {
    private val ownerMutexes = mutableMapOf<AccountId, Mutex>()

    suspend fun execute(
        command: TerminalAccountDeletionCleanupCommand
    ): TerminalAccountDeletionCleanupResult =
        withContext(NonCancellable) {
            journal.begin(command)
            mutex(command.owner).withLock { runIncomplete(command) }
        }

    suspend fun retryIncompleteAfterSignedOutStartup(): List<TerminalAccountDeletionCleanupResult> =
        withContext(NonCancellable) {
            journal
                .commands()
                .filter { journal.completedPhases(it).size < TerminalCleanupPhase.entries.size }
                .map { command -> mutex(command.owner).withLock { runIncomplete(command) } }
        }

    private suspend fun runIncomplete(
        command: TerminalAccountDeletionCleanupCommand
    ): TerminalAccountDeletionCleanupResult {
        val failed = linkedSetOf<TerminalCleanupPhase>()
        for (phase in TerminalCleanupPhase.entries) {
            if (phase in journal.completedPhases(command)) continue
            if (
                phase == TerminalCleanupPhase.PURGE_ROOM &&
                    TerminalCleanupPhase.SIGN_OUT_LOCAL !in journal.completedPhases(command)
            ) {
                failed += phase
                continue
            }
            try {
                actions.run(phase, command)
                journal.markCompleted(command, phase)
            } catch (_: Exception) {
                failed += phase
            }
        }
        if (failed.isEmpty()) journal.finish(command)
        return TerminalAccountDeletionCleanupResult(command, failed)
    }

    private fun mutex(owner: AccountId): Mutex =
        synchronized(ownerMutexes) { ownerMutexes.getOrPut(owner) { Mutex() } }
}

class SharedPreferencesTerminalAccountDeletionCleanupJournal(context: Context) :
    TerminalAccountDeletionCleanupJournal {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun begin(command: TerminalAccountDeletionCleanupCommand) =
        synchronized(LOCK) {
            val id = command.journalId()
            if (id in completedCommandIds()) return@synchronized
            preferences.edit(commit = true) {
                putStringSet(COMMAND_IDS, commandIds() + id)
                putString("$id.owner", command.owner.value)
                putString("$id.operation", command.operationId.value)
                if (!preferences.contains("$id.phases")) putStringSet("$id.phases", emptySet())
            }
        }

    override fun commands(): List<TerminalAccountDeletionCleanupCommand> =
        synchronized(LOCK) {
            commandIds().sorted().mapNotNull { id ->
                runCatching {
                    TerminalAccountDeletionCleanupCommand(
                        AccountId(requireNotNull(preferences.getString("$id.owner", null))),
                        DeletionRequestId(
                            requireNotNull(preferences.getString("$id.operation", null))
                        ),
                    )
                }
                    .getOrNull()
            }
        }

    override fun completedPhases(
        command: TerminalAccountDeletionCleanupCommand
    ): Set<TerminalCleanupPhase> =
        synchronized(LOCK) {
            val id = command.journalId()
            if (id in completedCommandIds())
                return@synchronized TerminalCleanupPhase.entries.toSet()
            preferences.getStringSet("$id.phases", emptySet()).orEmpty().mapNotNullTo(
                linkedSetOf()
            ) { value ->
                TerminalCleanupPhase.entries.firstOrNull { it.name == value }
            }
        }

    override fun markCompleted(
        command: TerminalAccountDeletionCleanupCommand,
        phase: TerminalCleanupPhase,
    ) =
        synchronized(LOCK) {
            val id = command.journalId()
            if (id in completedCommandIds()) return@synchronized
            val key = "$id.phases"
            val completed = preferences.getStringSet(key, emptySet()).orEmpty().toSet()
            preferences.edit(commit = true) { putStringSet(key, completed + phase.name) }
        }

    override fun finish(command: TerminalAccountDeletionCleanupCommand) =
        synchronized(LOCK) {
            val id = command.journalId()
            if (id in completedCommandIds()) return@synchronized
            val completed =
                preferences.getStringSet("$id.phases", emptySet()).orEmpty().mapNotNull { value ->
                    TerminalCleanupPhase.entries.firstOrNull { it.name == value }
                }
            check(completed.size == TerminalCleanupPhase.entries.size)
            preferences.edit(commit = true) {
                putStringSet(COMMAND_IDS, commandIds() - id)
                putStringSet(COMPLETED_COMMAND_IDS, completedCommandIds() + id)
                remove("$id.owner")
                remove("$id.operation")
                remove("$id.phases")
            }
        }

    private fun commandIds(): Set<String> =
        preferences.getStringSet(COMMAND_IDS, emptySet()).orEmpty().toSet()

    private fun completedCommandIds(): Set<String> =
        preferences.getStringSet(COMPLETED_COMMAND_IDS, emptySet()).orEmpty().toSet()

    private fun TerminalAccountDeletionCleanupCommand.journalId(): String {
        val bytes =
            MessageDigest.getInstance("SHA-256")
                .digest("${owner.value}\u0000${operationId.value}".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val PREFERENCES = "terminal-account-deletion-cleanup"
        const val COMMAND_IDS = "commands"
        const val COMPLETED_COMMAND_IDS = "completed-command-digests"
        val LOCK = Any()
    }
}
