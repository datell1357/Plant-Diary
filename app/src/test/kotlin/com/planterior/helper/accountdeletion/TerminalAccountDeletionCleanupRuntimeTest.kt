package com.planterior.helper.accountdeletion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.DeletionRequestId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class TerminalAccountDeletionCleanupRuntimeTest {
    @Test
    fun `cleanup is serialized per owner and duplicate completion is idempotent`() = runTest {
        val journal = InMemoryTerminalCleanupJournal()
        val firstLocationStarted = CompletableDeferred<Unit>()
        val releaseFirstLocation = CompletableDeferred<Unit>()
        var locationCalls = 0
        val calls = mutableListOf<Pair<TerminalCleanupPhase, String>>()
        val actions = TerminalAccountDeletionCleanupActions { phase, command ->
            calls += phase to command.operationId.value
            if (phase == TerminalCleanupPhase.CANCEL_LOCATION && ++locationCalls == 1) {
                firstLocationStarted.complete(Unit)
                releaseFirstLocation.await()
            }
        }
        val runtime = TerminalAccountDeletionCleanupRuntime(journal, actions)
        val first = command("request-one")
        val second = command("request-two")

        val firstCleanup = async { runtime.execute(first) }
        firstLocationStarted.await()
        val secondCleanup = async { runtime.execute(second) }

        assertFalse(calls.any { it.second == second.operationId.value })
        releaseFirstLocation.complete(Unit)
        firstCleanup.await()
        secondCleanup.await()
        runtime.execute(first)

        assertEquals(
            TerminalCleanupPhase.entries,
            calls.filter { it.second == "request-one" }.map { it.first },
        )
        assertEquals(
            TerminalCleanupPhase.entries,
            calls.filter { it.second == "request-two" }.map { it.first },
        )
    }

    @Test
    fun `failed phases remain durable while sign out and exit still run then restart retries only failures`() =
        runTest {
            val journal = InMemoryTerminalCleanupJournal()
            val command = command("request-restart")
            val firstCalls = mutableListOf<TerminalCleanupPhase>()
            val firstRuntime =
                TerminalAccountDeletionCleanupRuntime(
                    journal,
                    TerminalAccountDeletionCleanupActions { phase, _ ->
                        firstCalls += phase
                        if (phase == TerminalCleanupPhase.PURGE_ROOM) error("database unavailable")
                    },
                )

            firstRuntime.execute(command)

            assertEquals(TerminalCleanupPhase.entries, firstCalls)
            assertTrue(TerminalCleanupPhase.SIGN_OUT_LOCAL in journal.completedPhases(command))
            assertTrue(TerminalCleanupPhase.EMIT_EXIT in journal.completedPhases(command))
            assertFalse(TerminalCleanupPhase.PURGE_ROOM in journal.completedPhases(command))
            val restartCalls = mutableListOf<TerminalCleanupPhase>()
            TerminalAccountDeletionCleanupRuntime(
                    journal,
                    TerminalAccountDeletionCleanupActions { phase, _ -> restartCalls += phase },
                )
                .retryIncompleteAfterSignedOutStartup()

            assertEquals(listOf(TerminalCleanupPhase.PURGE_ROOM), restartCalls)
            assertEquals(TerminalCleanupPhase.entries.toSet(), journal.completedPhases(command))
        }

    @Test
    fun `purge waits for durable sign out and retry preserves prerequisite order`() = runTest {
        val journal = InMemoryTerminalCleanupJournal()
        val command = command("request-sign-out-crash")
        val firstCalls = mutableListOf<TerminalCleanupPhase>()
        TerminalAccountDeletionCleanupRuntime(
                journal,
                TerminalAccountDeletionCleanupActions { phase, _ ->
                    firstCalls += phase
                    if (phase == TerminalCleanupPhase.SIGN_OUT_LOCAL) error("process stopped")
                },
            )
            .execute(command)

        assertFalse(TerminalCleanupPhase.PURGE_ROOM in firstCalls)
        assertFalse(TerminalCleanupPhase.SIGN_OUT_LOCAL in journal.completedPhases(command))
        assertFalse(TerminalCleanupPhase.PURGE_ROOM in journal.completedPhases(command))

        val retryCalls = mutableListOf<TerminalCleanupPhase>()
        TerminalAccountDeletionCleanupRuntime(
                journal,
                TerminalAccountDeletionCleanupActions { phase, _ -> retryCalls += phase },
            )
            .retryIncompleteAfterSignedOutStartup()

        assertEquals(
            listOf(TerminalCleanupPhase.SIGN_OUT_LOCAL, TerminalCleanupPhase.PURGE_ROOM),
            retryCalls,
        )
        assertEquals(TerminalCleanupPhase.entries.toSet(), journal.completedPhases(command))
    }

    @Test
    fun `completed cleanup removes raw owner operation and phase recovery data`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences =
            context.getSharedPreferences(
                "terminal-account-deletion-cleanup",
                Context.MODE_PRIVATE,
            )
        preferences.edit().clear().commit()
        val command = command("request-must-not-remain")
        val journal = SharedPreferencesTerminalAccountDeletionCleanupJournal(context)

        TerminalAccountDeletionCleanupRuntime(journal) { _, _ -> }.execute(command)

        assertTrue(journal.commands().isEmpty())
        assertEquals(TerminalCleanupPhase.entries.toSet(), journal.completedPhases(command))
        preferences.all.forEach { (key, value) ->
            val serialized = "$key=$value"
            if (
                serialized.contains(command.owner.value) ||
                    serialized.contains(command.operationId.value)
            ) {
                fail("raw deletion command remained in preferences: $key")
            }
        }
    }

    @Test
    fun `phase journal survives runtime recreation keyed by owner and operation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("terminal-account-deletion-cleanup", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val command = command("request-durable")
        val first = SharedPreferencesTerminalAccountDeletionCleanupJournal(context)
        first.begin(command)
        first.markCompleted(command, TerminalCleanupPhase.CANCEL_LOCATION)

        val recreated = SharedPreferencesTerminalAccountDeletionCleanupJournal(context)

        assertEquals(listOf(command), recreated.commands())
        assertEquals(
            setOf(TerminalCleanupPhase.CANCEL_LOCATION),
            recreated.completedPhases(command),
        )
    }

    private fun command(requestId: String) =
        TerminalAccountDeletionCleanupCommand(
            owner = AccountId("owner-one"),
            operationId = DeletionRequestId(requestId),
        )

    private class InMemoryTerminalCleanupJournal : TerminalAccountDeletionCleanupJournal {
        private val phases =
            linkedMapOf<TerminalAccountDeletionCleanupCommand, MutableSet<TerminalCleanupPhase>>()

        override fun begin(command: TerminalAccountDeletionCleanupCommand) {
            phases.getOrPut(command) { linkedSetOf() }
        }

        override fun commands(): List<TerminalAccountDeletionCleanupCommand> = phases.keys.toList()

        override fun completedPhases(
            command: TerminalAccountDeletionCleanupCommand
        ): Set<TerminalCleanupPhase> = phases[command].orEmpty().toSet()

        override fun markCompleted(
            command: TerminalAccountDeletionCleanupCommand,
            phase: TerminalCleanupPhase,
        ) {
            phases.getOrPut(command) { linkedSetOf() } += phase
        }

        override fun finish(command: TerminalAccountDeletionCleanupCommand) = Unit
    }
}
