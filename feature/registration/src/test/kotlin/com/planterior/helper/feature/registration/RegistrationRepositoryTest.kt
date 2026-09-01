package com.planterior.helper.feature.registration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.data.PrivateMediaReference
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
                "private-media-v2/reservation_plant_stable",
                remote.lastPhotoPath,
            )
            val payload = gateway.commands.first().draftPayload
            assertTrue(payload.contains("\"representativeMediaReference\""))
            assertTrue(payload.contains("\"reservationId\":\"reservation_plant_stable\""))
            assertTrue(payload.contains("\"generation\":\"7\""))
            assertTrue(!payload.contains("representativePhotoPath"))
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

    @Test
    fun `persistence diagnostics preserve exact success identity and ordering`() = runTest {
        val observed = mutableListOf<RegistrationPersistenceDiagnosticObservation>()
        val repository =
            FirebaseRegistrationRepository(
                database,
                FakeRegistrationRemote(),
                SuccessGateway,
                Instant::now,
                observed::add,
            )

        val actual = repository.register(pending(), RegistrationCheckpoint.NotStarted)

        assertSame(
            actual,
            (observed.last().terminal as RegistrationPersistenceDiagnosticTerminal.Returned).value,
        )
        assertEquals(
            listOf(
                RegistrationPersistenceDiagnosticStage.COMMITTED_READ_ENTERED,
                RegistrationPersistenceDiagnosticStage.COMMITTED_READ_RETURNED,
                RegistrationPersistenceDiagnosticStage.CACHE_UPSERT_ENTERED,
                RegistrationPersistenceDiagnosticStage.CACHE_UPSERT_RETURNED,
                RegistrationPersistenceDiagnosticStage.OUTBOX_REMOVE_ENTERED,
                RegistrationPersistenceDiagnosticStage.OUTBOX_REMOVE_RETURNED,
                RegistrationPersistenceDiagnosticStage.COMPLETED_RETURNED,
            ),
            observed.map(RegistrationPersistenceDiagnosticObservation::stage),
        )
        assertTrue(
            observed.all {
                it.accountId == pending().accountId &&
                    it.operationId == pending().operationId &&
                    it.plantId == pending().plantId
            }
        )
    }

    @Test
    fun `observer faults cannot replace successful result`() = runTest {
        val failures = mutableListOf<Throwable>()
        val observerFailures =
            listOf<Throwable>(
                RuntimeException("runtime observer"),
                AssertionError("assertion observer"),
                CancellationException("cancel observer"),
            )
        observerFailures.forEach { observerFailure ->
            val repository =
                FirebaseRegistrationRepository(
                    database,
                    FakeRegistrationRemote(),
                    SuccessGateway,
                    Instant::now,
                    onPersistenceDiagnostic = { throw observerFailure },
                    onDiagnosticFailure = failures::add,
                )

            val actual = repository.register(pending(), RegistrationCheckpoint.NotStarted)

            assertTrue(actual is RegistrationAttempt.Completed)
        }
        observerFailures.forEach { observerFailure ->
            assertTrue(failures.any { it === observerFailure })
        }
    }

    @Test
    fun `held cache upsert has no premature terminal or completed observation`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observed = mutableListOf<RegistrationPersistenceDiagnosticObservation>()
        val repository =
            FirebaseRegistrationRepository(
                database,
                FakeRegistrationRemote(),
                SuccessGateway,
                Instant::now,
                onPersistenceDiagnostic = { observation ->
                    synchronized(observed) { observed += observation }
                },
                cacheUpsert = { entity ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    database.cacheDao().upsertPlant(entity)
                },
            )
        var actual: RegistrationAttempt? = null
        val worker = Thread {
            kotlinx.coroutines.runBlocking {
                actual = repository.register(pending(), RegistrationCheckpoint.NotStarted)
            }
        }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        synchronized(observed) {
            assertTrue(
                observed.none {
                    it.stage == RegistrationPersistenceDiagnosticStage.CACHE_UPSERT_RETURNED
                }
            )
            assertTrue(
                observed.none {
                    it.stage == RegistrationPersistenceDiagnosticStage.COMPLETED_RETURNED
                }
            )
        }
        release.countDown()
        worker.join(5_000)
        assertTrue(actual is RegistrationAttempt.Completed)
    }

    @Test
    fun `committed read cancellation keeps the exact failure identity`() = runTest {
        val cancellation = CancellationException("read cancelled")
        val observed = mutableListOf<RegistrationPersistenceDiagnosticObservation>()
        val repository =
            FirebaseRegistrationRepository(
                database,
                FakeRegistrationRemote(readFailure = cancellation),
                SuccessGateway,
                Instant::now,
                observed::add,
            )

        try {
            repository.register(pending(), RegistrationCheckpoint.NotStarted)
            fail("CancellationException expected")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
            val terminal =
                observed
                    .single {
                        it.stage == RegistrationPersistenceDiagnosticStage.COMMITTED_READ_CANCELLED
                    }
                    .terminal as RegistrationPersistenceDiagnosticTerminal.Cancelled
            assertSame(cancellation, terminal.failure)
        }
    }

    @Test
    fun `committed read exception returns failed result and keeps exact failure identity`() =
        runTest {
            val failure = IllegalStateException("read failed")
            val observed = mutableListOf<RegistrationPersistenceDiagnosticObservation>()
            val repository =
                FirebaseRegistrationRepository(
                    database,
                    FakeRegistrationRemote(readFailure = failure),
                    SuccessGateway,
                    Instant::now,
                    observed::add,
                )

            val actual =
                repository.register(pending(), RegistrationCheckpoint.NotStarted)
                    as RegistrationAttempt.Failed

            assertEquals(RegistrationFailure.INCONSISTENT_RECEIPT, actual.failure)
            val terminal =
                observed
                    .single {
                        it.stage == RegistrationPersistenceDiagnosticStage.COMMITTED_READ_THREW
                    }
                    .terminal as RegistrationPersistenceDiagnosticTerminal.Threw
            assertSame(failure, terminal.failure)
        }

    @Test
    fun `cache upsert diagnostics preserve exact exception and cancellation identity`() = runTest {
        val failure = IllegalStateException("cache failed")
        val failureObservations = mutableListOf<RegistrationPersistenceDiagnosticObservation>()
        val failedRepository =
            FirebaseRegistrationRepository(
                database,
                FakeRegistrationRemote(),
                SuccessGateway,
                Instant::now,
                failureObservations::add,
                cacheUpsert = { throw failure },
            )

        val failed =
            failedRepository.register(pending(), RegistrationCheckpoint.NotStarted)
                as RegistrationAttempt.Failed

        assertEquals(RegistrationFailure.CACHE_WRITE_FAILED, failed.failure)
        assertSame(
            failure,
            (failureObservations
                    .single {
                        it.stage == RegistrationPersistenceDiagnosticStage.CACHE_UPSERT_THREW
                    }
                    .terminal as RegistrationPersistenceDiagnosticTerminal.Threw)
                .failure,
        )

        val cancellation = CancellationException("cache cancelled")
        val cancellationObservations = mutableListOf<RegistrationPersistenceDiagnosticObservation>()
        val cancelledRepository =
            FirebaseRegistrationRepository(
                database,
                FakeRegistrationRemote(),
                SuccessGateway,
                Instant::now,
                cancellationObservations::add,
                cacheUpsert = { throw cancellation },
            )

        val actual =
            try {
                cancelledRepository.register(pending(), RegistrationCheckpoint.NotStarted)
                fail("CancellationException expected")
            } catch (error: CancellationException) {
                error
            }

        assertSame(cancellation, actual)
        assertSame(
            cancellation,
            (cancellationObservations
                    .single {
                        it.stage == RegistrationPersistenceDiagnosticStage.CACHE_UPSERT_CANCELLED
                    }
                    .terminal as RegistrationPersistenceDiagnosticTerminal.Cancelled)
                .failure,
        )
    }

    @Test
    fun `outbox remove diagnostics preserve exact exception and cancellation identity`() = runTest {
        val failure = IllegalStateException("outbox failed")
        val failureObservations = mutableListOf<RegistrationPersistenceDiagnosticObservation>()
        val failedRepository =
            FirebaseRegistrationRepository(
                database,
                FakeRegistrationRemote(),
                SuccessGateway,
                Instant::now,
                failureObservations::add,
                outboxRemove = { _, _ -> throw failure },
            )

        val failed =
            failedRepository.register(pending(), RegistrationCheckpoint.NotStarted)
                as RegistrationAttempt.Failed

        assertEquals(RegistrationFailure.DATABASE_UNAVAILABLE, failed.failure)
        assertSame(
            failure,
            (failureObservations
                    .single {
                        it.stage == RegistrationPersistenceDiagnosticStage.OUTBOX_REMOVE_THREW
                    }
                    .terminal as RegistrationPersistenceDiagnosticTerminal.Threw)
                .failure,
        )

        val cancellation = CancellationException("outbox cancelled")
        val cancellationObservations = mutableListOf<RegistrationPersistenceDiagnosticObservation>()
        val cancelledRepository =
            FirebaseRegistrationRepository(
                database,
                FakeRegistrationRemote(),
                SuccessGateway,
                Instant::now,
                cancellationObservations::add,
                outboxRemove = { _, _ -> throw cancellation },
            )

        val actual =
            try {
                cancelledRepository.register(pending(), RegistrationCheckpoint.NotStarted)
                fail("CancellationException expected")
            } catch (error: CancellationException) {
                error
            }

        assertSame(cancellation, actual)
        assertSame(
            cancellation,
            (cancellationObservations
                    .single {
                        it.stage == RegistrationPersistenceDiagnosticStage.OUTBOX_REMOVE_CANCELLED
                    }
                    .terminal as RegistrationPersistenceDiagnosticTerminal.Cancelled)
                .failure,
        )
    }

    @Test
    fun `observer faults cannot replace product failure or cancellation`() = runTest {
        val observerFailures =
            listOf<Throwable>(
                RuntimeException("runtime observer"),
                AssertionError("assertion observer"),
                CancellationException("cancel observer"),
            )
        observerFailures.forEachIndexed { index, observerFailure ->
            val reported = mutableListOf<Throwable>()
            val productFailure = IllegalStateException("product failure $index")
            val failedRepository =
                FirebaseRegistrationRepository(
                    database,
                    FakeRegistrationRemote(readFailure = productFailure),
                    SuccessGateway,
                    Instant::now,
                    onPersistenceDiagnostic = { throw observerFailure },
                    onDiagnosticFailure = reported::add,
                )
            val failedSubmission = pending("failed-$index")

            val failed =
                failedRepository.register(failedSubmission, RegistrationCheckpoint.NotStarted)
                    as RegistrationAttempt.Failed

            assertEquals(RegistrationFailure.INCONSISTENT_RECEIPT, failed.failure)
            assertTrue(reported.any { it === observerFailure })

            val cancellation = CancellationException("product cancellation $index")
            val cancelledRepository =
                FirebaseRegistrationRepository(
                    database,
                    FakeRegistrationRemote(readFailure = cancellation),
                    SuccessGateway,
                    Instant::now,
                    onPersistenceDiagnostic = { throw observerFailure },
                    onDiagnosticFailure = reported::add,
                )
            val cancelledSubmission = pending("cancelled-$index")

            val actual =
                try {
                    cancelledRepository.register(
                        cancelledSubmission,
                        RegistrationCheckpoint.NotStarted,
                    )
                    fail("CancellationException expected")
                } catch (error: CancellationException) {
                    error
                }

            assertSame(cancellation, actual)
            assertTrue(reported.any { it === observerFailure })
        }
    }

    private fun pending(operationId: String = "operation-stable") =
        PendingRegistration(
            accountId = AccountId("account-a"),
            plantId = PersonalPlantId("plant-stable"),
            operationId = OperationId(operationId),
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

    private object SuccessGateway : RemoteMutationGateway {
        override suspend fun apply(command: RemoteMutationCommand): RemoteMutationResult =
            RemoteMutationResult.Applied(7)
    }

    private class FakeRegistrationRemote(
        private val activeAccount: AccountId = AccountId("account-a"),
        private val uploadFailure: Throwable? = null,
        private val readFailure: Throwable? = null,
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
            operationId: OperationId,
            photo: RepresentativePhoto,
        ): PrivateMediaReference {
            uploads += 1
            uploadFailure?.let { throw it }
            assertEquals("operation-stable", operationId.value)
            return PrivateMediaReference("reservation_plant_stable", "7").also {
                lastPhotoPath = it.storagePath
            }
        }

        override suspend fun readCommitted(
            submission: PendingRegistration,
            revision: Long,
            mediaReference: PrivateMediaReference?,
        ) =
            readFailure?.let { throw it }
                ?: submission.toPersonalPlant(
                    revision,
                    Instant.parse("2026-08-18T00:00:00Z"),
                    mediaReference?.storagePath,
                )
    }
}
