package com.planterior.helper.registration

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.feature.registration.ExistingPersonalPlant
import com.planterior.helper.feature.registration.PendingRegistration
import com.planterior.helper.feature.registration.RegistrationAttempt
import com.planterior.helper.feature.registration.RegistrationCheckpoint
import com.planterior.helper.feature.registration.RegistrationContent
import com.planterior.helper.feature.registration.RegistrationFailure
import com.planterior.helper.feature.registration.RegistrationPersistenceDiagnosticObservation
import com.planterior.helper.feature.registration.RegistrationPersistenceDiagnosticStage
import com.planterior.helper.feature.registration.RegistrationRepository
import com.planterior.helper.feature.registration.RegistrationSession
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18RegistrationCommitDiagnosticRepositoryTest {
    @Test
    fun `debug repository records entry and preserves the completed result identity`() = runTest {
        // Given
        val completed =
            RegistrationAttempt.Completed(submission().toPersonalPlant(1, java.time.Instant.EPOCH))
        val events = mutableListOf<Todo18RegistrationCommitRepositoryEvent>()
        val repository =
            Todo18RegistrationCommitDiagnosticRepository(Delegate(completed), events::add)

        // When
        val actual = repository.register(submission(), RegistrationCheckpoint.NotStarted)

        // Then
        assertSame(completed, actual)
        assertEquals(
            listOf(
                Todo18RegistrationCommitRepositoryEvent.Entry::class,
                Todo18RegistrationCommitRepositoryEvent.Completed::class,
            ),
            events.map { it::class },
        )
        assertEquals(submission().operationId, events.first().operationId)
        assertEquals(submission().plantId, events.first().plantId)
    }

    @Test
    fun `diagnostic observer failure preserves the failed result identity`() = runTest {
        // Given
        val failed =
            RegistrationAttempt.Failed(
                RegistrationFailure.REMOTE_WRITE_FAILED,
                RegistrationCheckpoint.NotStarted,
            )
        val repository =
            Todo18RegistrationCommitDiagnosticRepository(Delegate(failed)) {
                throw AssertionError("diagnostic")
            }

        // When
        val actual = repository.register(submission(), RegistrationCheckpoint.NotStarted)

        // Then
        assertSame(failed, actual)
    }

    @Test
    fun `rendered sink records persistence stages with exact identity and elapsed order`() {
        val sink = com.planterior.helper.Todo18RenderedStateSink()
        val capture =
            sink.startDiagnosticCapture(
                com.planterior.helper.diagnostic.Todo18WaitId.REGISTRATION_COMMIT
            )
        val account = AccountId("owner")
        val operation = com.planterior.helper.core.model.OperationId("operation")
        val plant = PersonalPlantId("plant")
        RegistrationPersistenceDiagnosticStage.values().forEach { stage ->
            sink.onRegistrationPersistenceDiagnostic(
                RegistrationPersistenceDiagnosticObservation(stage, account, operation, plant)
            )
        }

        val events = capture.snapshot().pipeline
        assertEquals(
            listOf(
                com.planterior.helper.diagnostic.Todo18PipelineEventKind
                    .REGISTRATION_COMMITTED_READ_ENTERED,
                com.planterior.helper.diagnostic.Todo18PipelineEventKind
                    .REGISTRATION_COMMITTED_READ_RETURNED,
                com.planterior.helper.diagnostic.Todo18PipelineEventKind
                    .REGISTRATION_COMMITTED_READ_THREW,
                com.planterior.helper.diagnostic.Todo18PipelineEventKind
                    .REGISTRATION_COMMITTED_READ_CANCELLED,
                com.planterior.helper.diagnostic.Todo18PipelineEventKind
                    .REGISTRATION_CACHE_UPSERT_ENTERED,
                com.planterior.helper.diagnostic.Todo18PipelineEventKind
                    .REGISTRATION_CACHE_UPSERT_RETURNED,
                com.planterior.helper.diagnostic.Todo18PipelineEventKind
                    .REGISTRATION_CACHE_UPSERT_THREW,
                com.planterior.helper.diagnostic.Todo18PipelineEventKind
                    .REGISTRATION_CACHE_UPSERT_CANCELLED,
                com.planterior.helper.diagnostic.Todo18PipelineEventKind
                    .REGISTRATION_OUTBOX_REMOVE_ENTERED,
                com.planterior.helper.diagnostic.Todo18PipelineEventKind
                    .REGISTRATION_OUTBOX_REMOVE_RETURNED,
                com.planterior.helper.diagnostic.Todo18PipelineEventKind
                    .REGISTRATION_OUTBOX_REMOVE_THREW,
                com.planterior.helper.diagnostic.Todo18PipelineEventKind
                    .REGISTRATION_OUTBOX_REMOVE_CANCELLED,
                com.planterior.helper.diagnostic.Todo18PipelineEventKind
                    .REGISTRATION_COMPLETED_RETURNED,
            ),
            events.map { it.kind },
        )
        assertTrue(events.all { it.registrationAccountId == account })
        assertTrue(events.all { it.registrationOperationId == operation })
        assertTrue(events.all { it.registrationPlantId == plant })
        assertEquals(
            events.mapNotNull { it.elapsedNanos },
            events.mapNotNull { it.elapsedNanos }.sorted(),
        )
        capture.close()
    }

    private fun submission() =
        PendingRegistration(
            AccountId("owner"),
            PersonalPlantId("plant"),
            com.planterior.helper.core.model.OperationId("operation"),
            "Monstera",
            PlantContentId("content"),
            com.planterior.helper.core.model.RegistrationMethod.MANUAL,
            null,
            null,
        )

    private class Delegate(private val result: RegistrationAttempt) : RegistrationRepository {
        override suspend fun session() = RegistrationSession(AccountId("owner"), ZoneId.of("UTC"))

        override suspend fun searchPublicContents(query: String) = emptyList<RegistrationContent>()

        override suspend fun findDuplicates(
            accountId: AccountId,
            contentId: PlantContentId,
            excluding: PersonalPlantId,
        ) = emptyList<ExistingPersonalPlant>()

        override suspend fun register(
            submission: PendingRegistration,
            checkpoint: RegistrationCheckpoint,
        ) = result
    }
}
