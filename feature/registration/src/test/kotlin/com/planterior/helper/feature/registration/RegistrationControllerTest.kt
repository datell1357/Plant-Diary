package com.planterior.helper.feature.registration

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.RegistrationMethod
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
class RegistrationControllerTest {
    private val candidate = RegistrationContent(PlantContentId("species-monstera"), "몬스테라")
    private val clock = Clock.fixed(Instant.parse("2026-08-18T03:00:00Z"), ZoneId.of("UTC"))

    @Test
    fun `identified candidate and direct entry map to canonical registration methods`() = runTest {
        val identifiedRepository = FakeRegistrationRepository()
        val identified =
            RegistrationController(
                RegistrationSeed.Identified(candidate),
                identifiedRepository,
                clock,
                idFactory = { PersonalPlantId("plant-stable") },
            )
        identified.start()
        identified.submit()
        assertEquals(RegistrationMethod.IDENTIFIED, identifiedRepository.saved.single().method)
        assertEquals(candidate.id, identifiedRepository.saved.single().contentId)

        val editedRepository = FakeRegistrationRepository()
        val edited =
            RegistrationController(
                RegistrationSeed.Identified(candidate),
                editedRepository,
                clock,
                idFactory = { PersonalPlantId("plant-edited") },
            )
        edited.start()
        edited.changeName("내 몬스테라")
        edited.submit()
        assertEquals(
            RegistrationMethod.IDENTIFICATION_EDITED,
            editedRepository.saved.single().method,
        )
        assertNull(editedRepository.saved.single().contentId)

        val manualRepository = FakeRegistrationRepository()
        val manual =
            RegistrationController(
                RegistrationSeed.Manual,
                manualRepository,
                clock,
                idFactory = { PersonalPlantId("plant-manual") },
            )
        manual.start()
        manual.changeName("  스킨답서스  ")
        manual.submit()
        assertEquals(RegistrationMethod.MANUAL, manualRepository.saved.single().method)
        assertEquals("스킨답서스", manualRepository.saved.single().displayName)

        val replacement = RegistrationContent(PlantContentId("species-pothos"), "스킨답서스")
        val replacedRepository = FakeRegistrationRepository()
        val replaced =
            RegistrationController(
                RegistrationSeed.Identified(candidate),
                replacedRepository,
                clock,
                idFactory = { PersonalPlantId("plant-replaced") },
            )
        replaced.start()
        replaced.selectContent(replacement)
        replaced.submit()
        assertEquals(
            RegistrationMethod.IDENTIFICATION_EDITED,
            replacedRepository.saved.single().method,
        )
        assertEquals(replacement.id, replacedRepository.saved.single().contentId)

        val searchedRepository = FakeRegistrationRepository()
        val searched =
            RegistrationController(
                RegistrationSeed.Manual,
                searchedRepository,
                clock,
                idFactory = { PersonalPlantId("plant-searched") },
            )
        searched.start()
        searched.selectContent(candidate)
        searched.submit()
        assertEquals(RegistrationMethod.MANUAL, searchedRepository.saved.single().method)
        assertEquals(candidate.id, searchedRepository.saved.single().contentId)
    }

    @Test
    fun `validation rejects blank long and account-zone future dates`() = runTest {
        val repository = FakeRegistrationRepository()
        val controller = RegistrationController(RegistrationSeed.Manual, repository, clock)
        controller.start()
        controller.changeName(" ")
        controller.submit()
        assertEquals(setOf(RegistrationValidationError.NAME_REQUIRED), controller.editing().errors)

        controller.changeName("🌱".repeat(101))
        controller.submit()
        assertEquals(setOf(RegistrationValidationError.NAME_TOO_LONG), controller.editing().errors)

        controller.changeName("🌱".repeat(100))
        controller.changeLastWateredDate("2026/08/18")
        controller.submit()
        assertEquals(
            setOf(RegistrationValidationError.INVALID_LAST_WATERED_DATE),
            controller.editing().errors,
        )

        controller.changeLastWateredDate("2026-08-19")
        controller.submit()
        assertEquals(
            setOf(RegistrationValidationError.FUTURE_LAST_WATERED_DATE),
            controller.editing().errors,
        )
        assertEquals(0, repository.saved.size)
    }

    @Test
    fun `duplicate offers open add another and cancel without accidental writes`() = runTest {
        val existing = ExistingPersonalPlant(PersonalPlantId("existing-plant"), "기존 몬스테라")
        val repository = FakeRegistrationRepository(duplicates = listOf(existing))
        val controller =
            RegistrationController(
                RegistrationSeed.Manual,
                repository,
                clock,
                navigationIdentityFactory = { "existing-event" },
            )
        controller.start()
        controller.selectContent(candidate)
        controller.submit()
        assertEquals(
            existing,
            (controller.state.value as RegistrationUiState.DuplicateFound).existing.single(),
        )
        assertEquals(0, repository.saved.size)

        controller.openExisting(existing.id)
        assertEquals(
            RegistrationNavigationEvent(
                identity = "existing-event",
                ownerAccountId = AccountId("account-a"),
                plantId = existing.id,
                kind = RegistrationNavigationKind.OPEN_EXISTING,
            ),
            controller.navigationEvent.value,
        )
        assertEquals(0, repository.saved.size)

        controller.cancelDuplicate()
        assertTrue(controller.state.value is RegistrationUiState.Editing)
        controller.submit()
        controller.addAnother()
        assertEquals(1, repository.saved.size)
    }

    @Test
    fun `failed retry preserves stable ids payload and checkpoint`() = runTest {
        val repository = FakeRegistrationRepository(failFirst = true)
        val controller =
            RegistrationController(
                RegistrationSeed.Manual,
                repository,
                clock,
                idFactory = { PersonalPlantId("plant-stable") },
            )
        controller.start()
        controller.changeName("고무나무")
        controller.submit()
        val failed = controller.state.value as RegistrationUiState.SaveFailed
        controller.retry()
        assertEquals(2, repository.saved.size)
        assertEquals(repository.saved[0], repository.saved[1])
        assertEquals(failed.checkpoint, repository.checkpoints[1])
    }

    @Test
    fun `session duplicate and search failures remain visible and retryable`() = runTest {
        val repository =
            FakeRegistrationRepository(
                failSession = true,
                failDuplicates = true,
                failSearch = true,
            )
        val controller = RegistrationController(RegistrationSeed.Manual, repository, clock)

        controller.start()
        assertEquals(
            RegistrationFailure.PROFILE_UNAVAILABLE,
            (controller.state.value as RegistrationUiState.SessionFailed).failure,
        )

        repository.failSession = false
        controller.retry()
        controller.search("몬스테라")
        assertEquals(RegistrationSearchState.Failed, controller.editing().search)

        repository.failSearch = false
        controller.search("없는 식물")
        assertEquals(RegistrationSearchState.Empty, controller.editing().search)

        repository.searchResults = listOf(candidate)
        controller.search("몬")
        assertEquals(
            listOf(candidate),
            (controller.editing().search as RegistrationSearchState.Results).items,
        )

        controller.selectContent(candidate)
        controller.submit()
        assertEquals(
            RegistrationFailure.DUPLICATE_CHECK_UNAVAILABLE,
            controller.editing().failure,
        )
    }

    @Test
    fun `double submit while persistence is suspended writes once`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository =
            FakeRegistrationRepository(
                beforeRegister = {
                    entered.complete(Unit)
                    release.await()
                }
            )
        val controller = RegistrationController(RegistrationSeed.Manual, repository, clock)
        controller.start()
        controller.changeName("고무나무")

        val first = launch { controller.submit() }
        entered.await()
        controller.submit()
        release.complete(Unit)
        first.join()

        assertEquals(1, repository.saved.size)
    }

    private class FakeRegistrationRepository(
        private val duplicates: List<ExistingPersonalPlant> = emptyList(),
        private val failFirst: Boolean = false,
        var failSession: Boolean = false,
        var failDuplicates: Boolean = false,
        var failSearch: Boolean = false,
        var searchResults: List<RegistrationContent> = emptyList(),
        private val beforeRegister: suspend () -> Unit = {},
    ) : RegistrationRepository {
        val saved = mutableListOf<PendingRegistration>()
        val checkpoints = mutableListOf<RegistrationCheckpoint>()

        override suspend fun session(): RegistrationSession {
            if (failSession) error("profile unavailable")
            return RegistrationSession(AccountId("account-a"), ZoneId.of("Asia/Seoul"))
        }

        override suspend fun searchPublicContents(query: String): List<RegistrationContent> {
            if (failSearch) error("search unavailable")
            return searchResults
        }

        override suspend fun findDuplicates(
            accountId: AccountId,
            contentId: PlantContentId,
            excluding: PersonalPlantId,
        ): List<ExistingPersonalPlant> {
            if (failDuplicates) error("duplicate lookup unavailable")
            return duplicates
        }

        override suspend fun register(
            submission: PendingRegistration,
            checkpoint: RegistrationCheckpoint,
        ): RegistrationAttempt {
            beforeRegister()
            saved += submission
            checkpoints += checkpoint
            return if (failFirst && saved.size == 1) {
                RegistrationAttempt.Failed(RegistrationFailure.REMOTE_WRITE_FAILED, checkpoint)
            } else {
                RegistrationAttempt.Completed(
                    submission.toPersonalPlant(1, Instant.parse("2026-08-18T03:00:00Z"))
                )
            }
        }
    }
}
