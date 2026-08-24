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
    PURGE_ROOM,
    CLEAR_NOTIFICATIONS,
    CLEAR_WEATHER,
    CLEAR_SHARE_CACHE,
    SIGN_OUT_LOCAL,
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
            try {
                actions.run(phase, command)
                journal.markCompleted(command, phase)
            } catch (_: Exception) {
                failed += phase
            }
        }
        return TerminalAccountDeletionCleanupResult(command, failed)
    }

    private fun mutex(owner: AccountId): Mutex =
        synchronized(OWNER_MUTEXES) {
            OWNER_MUTEXES.getOrPut(owner) { Mutex() }
        }

    private companion object {
        val OWNER_MUTEXES = mutableMapOf<AccountId, Mutex>()
    }
}

class SharedPreferencesTerminalAccountDeletionCleanupJournal(context: Context) :
    TerminalAccountDeletionCleanupJournal {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun begin(command: TerminalAccountDeletionCleanupCommand) =
        synchronized(LOCK) {
            val id = command.journalId()
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
            preferences
                .getStringSet("${command.journalId()}.phases", emptySet())
                .orEmpty()
                .mapNotNullTo(linkedSetOf()) { value ->
                    TerminalCleanupPhase.entries.firstOrNull { it.name == value }
                }
        }

    override fun markCompleted(
        command: TerminalAccountDeletionCleanupCommand,
        phase: TerminalCleanupPhase,
    ) =
        synchronized(LOCK) {
            val key = "${command.journalId()}.phases"
            val completed = preferences.getStringSet(key, emptySet()).orEmpty().toSet()
            preferences.edit(commit = true) { putStringSet(key, completed + phase.name) }
        }

    private fun commandIds(): Set<String> =
        preferences.getStringSet(COMMAND_IDS, emptySet()).orEmpty().toSet()

    private fun TerminalAccountDeletionCleanupCommand.journalId(): String {
        val bytes =
            MessageDigest.getInstance("SHA-256")
                .digest("${owner.value}\u0000${operationId.value}".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val PREFERENCES = "terminal-account-deletion-cleanup"
        const val COMMAND_IDS = "commands"
        val LOCK = Any()
    }
}
