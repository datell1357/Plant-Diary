package com.planterior.helper.feature.registration

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RegistrationDiagnosticHookTest {
    private val candidate = RegistrationContent(PlantContentId("species-monstera"), "Monstera")

    @Test
    fun `select callback records requested id null-to-selected Idle transition once`() = runTest {
        val controller = RegistrationController(RegistrationSeed.Manual, Repository(candidate))
        controller.start()
        controller.search("monster")
        val before = controller.editing()
        assertNull(before.draft.selectedContent)
        assertTrue(before.search is RegistrationSearchState.Results)
        val events = mutableListOf<RegistrationDiagnosticEvent>()

        performRegistrationSelectContent(controller, candidate, events::add)

        val screen =
            events.filterIsInstance<RegistrationDiagnosticEvent.SelectContentScreen>().single()
        val transition =
            events
                .filterIsInstance<RegistrationDiagnosticEvent.SelectContentControllerTransition>()
                .single()
        assertEquals(candidate.id, screen.requestedContentId)
        assertEquals(candidate.id, transition.requestedContentId)
        assertNull((transition.before as RegistrationUiState.Editing).draft.selectedContent)
        val after = transition.after as RegistrationUiState.Editing
        assertEquals(candidate.id, after.draft.selectedContent?.id)
        assertEquals(RegistrationSearchState.Idle, after.search)
        assertEquals(controller.diagnosticIdentity, transition.controllerIdentity)
    }

    @Test
    fun `diagnostic callback failures do not alter selection or route publication`() = runTest {
        val controller = RegistrationController(RegistrationSeed.Manual, Repository(candidate))
        controller.start()
        controller.search("monster")
        var publications = 0

        performRegistrationSelectContent(controller, candidate) {
            throw AssertionError("diagnostic")
        }
        publishRegistrationRouteState(
            controller.diagnosticIdentity,
            controller.state.value,
            { throw IllegalStateException("diagnostic") },
        ) {
            publications += 1
        }

        assertEquals(candidate.id, controller.editing().draft.selectedContent?.id)
        assertEquals(RegistrationSearchState.Idle, controller.editing().search)
        assertEquals(1, publications)
    }

    @Test
    fun `submit records typed validation duplicate completion and navigation boundaries`() =
        runTest {
            // Given
            val events = mutableListOf<RegistrationDiagnosticEvent>()
            val repository = CompletingRepository(candidate)
            val controller =
                RegistrationController(
                    RegistrationSeed.Manual,
                    repository,
                    diagnosticObserver = events::add,
                )
            controller.start()
            controller.selectContent(candidate)

            // When
            performRegistrationSubmit(controller, events::add)

            // Then
            assertEquals(
                listOf(
                    RegistrationDiagnosticEvent.SubmitCallback::class,
                    RegistrationDiagnosticEvent.SubmitControllerEntry::class,
                    RegistrationDiagnosticEvent.SubmitValidation::class,
                    RegistrationDiagnosticEvent.DuplicateLookupBegin::class,
                    RegistrationDiagnosticEvent.DuplicateLookupResult::class,
                    RegistrationDiagnosticEvent.CompletedPublication::class,
                    RegistrationDiagnosticEvent.NavigationEnqueued::class,
                ),
                events.map { it::class },
            )
            val validation =
                events.filterIsInstance<RegistrationDiagnosticEvent.SubmitValidation>().single()
            assertEquals(RegistrationSubmitValidationOutcome.Accepted, validation.outcome)
            val duplicates =
                events
                    .filterIsInstance<RegistrationDiagnosticEvent.DuplicateLookupResult>()
                    .single()
            assertEquals(RegistrationDuplicateLookupOutcome.Empty, duplicates.outcome)
            assertEquals(controller.diagnosticIdentity, validation.controllerIdentity)
            assertEquals(controller.state.value, RegistrationUiState.Completed(repository.plant))
        }

    @Test
    fun `submit validation rejection records the existing error without repository entry`() =
        runTest {
            // Given
            val events = mutableListOf<RegistrationDiagnosticEvent>()
            val repository = CompletingRepository(candidate)
            val controller =
                RegistrationController(
                    RegistrationSeed.Manual,
                    repository,
                    diagnosticObserver = events::add,
                )
            controller.start()

            // When
            performRegistrationSubmit(controller, events::add)

            // Then
            val validation =
                events.filterIsInstance<RegistrationDiagnosticEvent.SubmitValidation>().single()
            assertEquals(
                RegistrationSubmitValidationOutcome.Rejected(
                    setOf(RegistrationValidationError.NAME_REQUIRED)
                ),
                validation.outcome,
            )
            assertTrue(events.none { it is RegistrationDiagnosticEvent.DuplicateLookupBegin })
            assertEquals(0, repository.registrations)
        }

    @Test
    fun `route audit precedes Task1 publication for selected content`() {
        val state =
            RegistrationUiState.Editing(
                RegistrationDraft(
                    plantId = PersonalPlantId("plant"),
                    operationId = null,
                    name = candidate.name,
                    selectedContent = candidate,
                    photo = null,
                    lastWateredDate = null,
                )
            )
        val order = mutableListOf<String>()
        val events = mutableListOf<RegistrationDiagnosticEvent>()

        publishRegistrationRouteState(
            controllerIdentity = RegistrationControllerIdentity(71),
            state = state,
            diagnosticObserver = { event ->
                events += event
                order += "route"
            },
            publish = { order += "publish" },
        )

        assertEquals(listOf("route", "publish"), order)
        val audit = events.single() as RegistrationDiagnosticEvent.RouteStateAudit
        assertEquals(RegistrationControllerIdentity(71), audit.controllerIdentity)
        assertEquals(
            candidate.id,
            (audit.state as RegistrationUiState.Editing).draft.selectedContent?.id,
        )
    }

    private class CompletingRepository(private val candidate: RegistrationContent) :
        RegistrationRepository {
        lateinit var plant: com.planterior.helper.core.model.PersonalPlant
        var registrations = 0

        override suspend fun session() =
            RegistrationSession(AccountId("diagnostic-owner"), ZoneId.of("UTC"))

        override suspend fun searchPublicContents(query: String) = listOf(candidate)

        override suspend fun findDuplicates(
            accountId: AccountId,
            contentId: PlantContentId,
            excluding: PersonalPlantId,
        ): List<ExistingPersonalPlant> = emptyList()

        override suspend fun register(
            submission: PendingRegistration,
            checkpoint: RegistrationCheckpoint,
        ): RegistrationAttempt {
            registrations += 1
            plant = submission.toPersonalPlant(1, java.time.Instant.parse("2026-08-28T00:00:00Z"))
            return RegistrationAttempt.Completed(plant)
        }
    }

    private class Repository(private val candidate: RegistrationContent) : RegistrationRepository {
        override suspend fun session() =
            RegistrationSession(AccountId("diagnostic-owner"), ZoneId.of("UTC"))

        override suspend fun searchPublicContents(query: String) = listOf(candidate)

        override suspend fun findDuplicates(
            accountId: AccountId,
            contentId: PlantContentId,
            excluding: PersonalPlantId,
        ): List<ExistingPersonalPlant> = emptyList()

        override suspend fun register(
            submission: PendingRegistration,
            checkpoint: RegistrationCheckpoint,
        ): RegistrationAttempt =
            RegistrationAttempt.Failed(RegistrationFailure.DATABASE_UNAVAILABLE, checkpoint)
    }
}
