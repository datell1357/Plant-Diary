package com.planterior.helper.feature.registration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.data.RemoteMutationCommand
import com.planterior.helper.core.data.RemoteMutationGateway
import com.planterior.helper.core.data.RemoteMutationResult
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.RegistrationMethod
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RegistrationRepositoryTest {
    private lateinit var database: PlanteriorDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    PlanteriorDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
    }

    @After fun tearDown() = database.close()

    @Test
    fun `remote failure retains one stable outbox and retry duplicate becomes immediately visible`() =
        runTest {
            val remote = FakeRegistrationRemote()
            val gateway = SequencedGateway()
            val repository =
                FirebaseRegistrationRepository(database, remote, gateway) {
                    Instant.parse("2026-08-18T00:00:00Z")
                }
            val pending = pending()

            val failed = repository.register(pending, RegistrationCheckpoint.NotStarted)
            assertEquals(
                RegistrationFailure.REMOTE_WRITE_FAILED,
                (failed as RegistrationAttempt.Failed).failure,
            )
            assertEquals(1, database.syncDao().pending("account-a").size)
            assertNull(database.cacheDao().plant("account-a", "plant-stable"))

            val completed = repository.register(pending, failed.checkpoint)
            assertNotNull((completed as RegistrationAttempt.Completed).plant)
            assertEquals(
                1,
                gateway.commands.map(RemoteMutationCommand::operationId).distinct().size,
            )
            assertTrue(
                requireNotNull(database.cacheDao().plant("account-a", "plant-stable"))
                    .detailsComplete
            )
            assertEquals(listOf("CREATE"), gateway.commands.map { it.mutationType }.distinct())
            assertEquals(
                listOf("plant-stable"),
                database.cacheDao().plants("account-a").map(CachedPlantEntity::plantId),
            )
            assertEquals(0, database.syncDao().pending("account-a").size)
        }

    @Test
    fun `representative photo checkpoint prevents a second upload after callable failure`() =
        runTest {
            val remote = FakeRegistrationRemote()
            val gateway = SequencedGateway()
            val repository = FirebaseRegistrationRepository(database, remote, gateway, Instant::now)
            val pending =
                pending()
                    .copy(
                        photo = RepresentativePhoto.Bytes(byteArrayOf(1, 2), "webp", "image/webp")
                    )

            val failed =
                repository.register(pending, RegistrationCheckpoint.NotStarted)
                    as RegistrationAttempt.Failed
            repository.register(pending, failed.checkpoint)

            assertEquals(1, remote.uploads)
            assertEquals(
                "plant-photos/account-a/plant-stable/representative.webp",
                remote.lastPhotoPath,
            )
        }

    @Test
    fun `account switch rejects registration before photo upload or callable write`() = runTest {
        val remote = FakeRegistrationRemote(activeAccount = AccountId("account-b"))
        val gateway = SequencedGateway()
        val repository = FirebaseRegistrationRepository(database, remote, gateway, Instant::now)

        val result = repository.register(pending(), RegistrationCheckpoint.NotStarted)

        assertEquals(
            RegistrationFailure.UNAUTHENTICATED,
            (result as RegistrationAttempt.Failed).failure,
        )
        assertEquals(0, remote.uploads)
        assertEquals(0, gateway.commands.size)
        assertEquals(0, database.syncDao().pending("account-a").size)
    }

    @Test
    fun `existing operation cannot be replayed with a changed frozen payload`() = runTest {
        database
            .syncDao()
            .enqueue(
                com.planterior.helper.core.database.OperationOutboxEntity(
                    "operation-stable",
                    "account-a",
                    "personalPlants",
                    "plant-stable",
                    "CREATE",
                    0,
                    "{\"displayName\":\"다른 식물\"}",
                    1,
                )
            )
        val gateway = SequencedGateway()
        val repository =
            FirebaseRegistrationRepository(
                database,
                FakeRegistrationRemote(),
                gateway,
                Instant::now,
            )

        val result = repository.register(pending(), RegistrationCheckpoint.NotStarted)

        assertEquals(
            RegistrationFailure.OUTBOX_MISMATCH,
            (result as RegistrationAttempt.Failed).failure,
        )
        assertEquals(0, gateway.commands.size)
    }

    @Test
    fun `photo upload cancellation propagates to the caller`() = runTest {
        val cancellation = CancellationException("screen left")
        val remote = FakeRegistrationRemote(uploadFailure = cancellation)
        val repository =
            FirebaseRegistrationRepository(database, remote, SequencedGateway(), Instant::now)
        val submission =
            pending()
                .copy(photo = RepresentativePhoto.Bytes(byteArrayOf(1, 2), "webp", "image/webp"))

        try {
            repository.register(submission, RegistrationCheckpoint.NotStarted)
            fail("CancellationException expected")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
    }

    private fun pending() =
        PendingRegistration(
            accountId = AccountId("account-a"),
            plantId = PersonalPlantId("plant-stable"),
            operationId = OperationId("operation-stable"),
            displayName = "몬스테라",
            contentId = null,
            method = RegistrationMethod.MANUAL,
            photo = null,
            lastWateredDate = null,
        )

    private class SequencedGateway : RemoteMutationGateway {
        val commands = mutableListOf<RemoteMutationCommand>()

        override suspend fun apply(command: RemoteMutationCommand): RemoteMutationResult {
            commands += command
            return if (commands.size == 1) RemoteMutationResult.Failed("UNAVAILABLE")
            else RemoteMutationResult.Duplicate(1)
        }
    }

    private class FakeRegistrationRemote(
        private val activeAccount: AccountId = AccountId("account-a"),
        private val uploadFailure: Throwable? = null,
    ) : RegistrationRemoteDataSource {
        var uploads = 0
        var lastPhotoPath: String? = null

        override fun activeAccount() = activeAccount

        override suspend fun accountZone(accountId: AccountId) = ZoneId.of("Asia/Seoul")

        override suspend fun search(query: String) = emptyList<RegistrationContent>()

        override suspend fun duplicates(accountId: AccountId, contentId: String) =
            emptyList<ExistingPersonalPlant>()

        override suspend fun uploadRepresentativePhoto(
            accountId: AccountId,
            plantId: PersonalPlantId,
            photo: RepresentativePhoto,
        ): String {
            uploads += 1
            uploadFailure?.let { throw it }
            return "plant-photos/${accountId.value}/${plantId.value}/representative.${photo.extension}"
                .also { lastPhotoPath = it }
        }

        override suspend fun readCommitted(
            submission: PendingRegistration,
            revision: Long,
            photoPath: String?,
        ) = submission.toPersonalPlant(revision, Instant.parse("2026-08-18T00:00:00Z"), photoPath)
    }
}
