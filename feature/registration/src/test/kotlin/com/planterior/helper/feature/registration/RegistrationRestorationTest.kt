package com.planterior.helper.feature.registration

import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RegistrationRestorationTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-18T03:00:00Z"), ZoneId.of("UTC"))

    @Test
    fun `failed submission restores stable ids and checkpoint and remains retryable`() = runTest {
        val handle = SavedStateHandle()
        val repository = RestoreRepository()
        val original =
            RegistrationController(
                RegistrationSeed.Manual,
                repository,
                clock,
                idFactory = { PersonalPlantId("plant-restored") },
                savedStateHandle = handle,
            )
        original.start()
        original.changeName("고무나무")
        original.submit()
        val failed = original.state.value as RegistrationUiState.SaveFailed

        val restored =
            RegistrationController(
                RegistrationSeed.Manual,
                repository,
                clock,
                idFactory = { error("restoration must not generate another plant id") },
                savedStateHandle = handle,
            )
        restored.start()
        val restoredFailure = restored.state.value as RegistrationUiState.SaveFailed
        assertEquals(failed.submission, restoredFailure.submission)
        assertEquals(failed.checkpoint, restoredFailure.checkpoint)

        restored.retry()
        assertTrue(restored.state.value is RegistrationUiState.Completed)
        assertEquals(1, repository.saved.map { it.operationId }.distinct().size)
    }

    @Test
    fun `saved state never contains raw representative photo bytes`() = runTest {
        val handle = SavedStateHandle()
        val controller =
            RegistrationController(
                RegistrationSeed.Manual,
                RestoreRepository(),
                clock,
                savedStateHandle = handle,
            )
        controller.start()
        controller.changeName("고무나무")
        controller.setPhoto(RepresentativePhoto.Bytes(byteArrayOf(1, 2, 3), "jpg", "image/jpeg"))
        controller.submit()

        assertFalse(handle.keys().any { containsByteArray(handle.get<Any?>(it)) })
    }

    @Test
    fun `stale search completion cannot overwrite a newer search`() = runTest {
        val old = CompletableDeferred<List<RegistrationContent>>()
        val newer = CompletableDeferred<List<RegistrationContent>>()
        val repository =
            RestoreRepository(
                search = { query -> if (query == "old") old.await() else newer.await() }
            )
        val controller = RegistrationController(RegistrationSeed.Manual, repository, clock)
        controller.start()

        val first = launch { controller.search("old") }
        val second = launch { controller.search("new") }
        val expected = RegistrationContent(PlantContentId("species-new"), "새 결과")
        newer.complete(listOf(expected))
        second.join()
        old.complete(listOf(RegistrationContent(PlantContentId("species-old"), "옛 결과")))
        first.join()

        assertEquals(
            listOf(expected),
            (controller.editing().search as RegistrationSearchState.Results).items,
        )
    }

    private fun containsByteArray(value: Any?): Boolean =
        when (value) {
            is ByteArray -> true
            is android.os.Bundle -> value.keySet().any { containsByteArray(value.get(it)) }
            is Collection<*> -> value.any(::containsByteArray)
            else -> false
        }

    private class RestoreRepository(
        private val search: suspend (String) -> List<RegistrationContent> = { emptyList() }
    ) : RegistrationRepository {
        val saved = mutableListOf<PendingRegistration>()

        override suspend fun session() =
            RegistrationSession(AccountId("account-a"), ZoneId.of("Asia/Seoul"))

        override suspend fun searchPublicContents(query: String) = search(query)

        override suspend fun findDuplicates(
            accountId: AccountId,
            contentId: PlantContentId,
            excluding: PersonalPlantId,
        ) = emptyList<ExistingPersonalPlant>()

        override suspend fun register(
            submission: PendingRegistration,
            checkpoint: RegistrationCheckpoint,
        ): RegistrationAttempt {
            saved += submission
            return if (saved.size == 1) {
                RegistrationAttempt.Failed(
                    RegistrationFailure.REMOTE_WRITE_FAILED,
                    RegistrationCheckpoint.PhotoStored(
                        com.planterior.helper.core.data.PrivateMediaReference(
                            "reservation_plant_restore",
                            "7",
                        )
                    ),
                )
            } else {
                RegistrationAttempt.Completed(
                    submission.toPersonalPlant(1, Instant.parse("2026-08-18T03:00:00Z"))
                )
            }
        }
    }
}
