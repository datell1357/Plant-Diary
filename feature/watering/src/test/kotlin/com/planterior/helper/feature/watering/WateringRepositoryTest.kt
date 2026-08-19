package com.planterior.helper.feature.watering

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.data.RemoteMutationCommand
import com.planterior.helper.core.data.RemoteMutationGateway
import com.planterior.helper.core.data.RemoteMutationResult
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.CachedWateringScheduleEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class WateringRepositoryTest {
    private lateinit var database: PlanteriorDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    PlanteriorDatabase::class.java,
                )
                .build()
    }

    @After fun tearDown() = database.close()

    @Test
    fun `request hash matches the server canonical watering envelope`() {
        assertEquals(
            "878509f1e1dc72768fbe28201271defe254cbea7c5400122ad09dedd24a9045b",
            WateringRequestHash.calculate(request()),
        )
    }

    @Test
    fun `production Room config reconciles successful watering from a main coroutine`() = runTest {
        database.cacheDao().upsertPlant(cachedPlant())
        val result =
            repository(
                    FakeRemote(receipt = WateringReceiptLookup.Found(receipt())),
                    RecordingGateway(RemoteMutationResult.Applied(5)),
                )
                .complete(request())

        assertEquals(WateringCompletionResult.Completed(receipt()), result)
        assertTrue(database.syncDao().pending("account-a").isEmpty())
    }

    @Test
    fun `remote failure preserves cached plant and schedule then exact retry succeeds`() = runTest {
        val oldPlant = cachedPlant(lastWateredDate = "2026-08-01", revision = 4)
        val oldSchedule = cachedSchedule(dueDate = "2026-08-11", revision = 2)
        database.cacheDao().upsertPlant(oldPlant)
        database.cacheDao().upsertSchedule(oldSchedule)
        val gateway = SequencedGateway()
        val remote = FakeRemote()
        val repository = repository(remote, gateway)
        val request = request()

        assertEquals(
            WateringCompletionResult.Failed(WateringCompletionFailure.REMOTE_WRITE_FAILED),
            repository.complete(request),
        )
        assertEquals(oldPlant, database.cacheDao().plant("account-a", "plant-a"))
        assertEquals(oldSchedule, database.cacheDao().schedule("account-a", "plant-a"))
        assertEquals(1, database.syncDao().pending("account-a").size)

        remote.receipt = WateringReceiptLookup.Found(receipt())
        assertEquals(WateringCompletionResult.Completed(receipt()), repository.complete(request))

        assertEquals(1, gateway.commands.map { it.operationId }.distinct().size)
        assertEquals(
            "2026-08-12",
            database.cacheDao().plant("account-a", "plant-a")?.lastWateredDate,
        )
        assertEquals("2026-08-22", database.cacheDao().schedule("account-a", "plant-a")?.dueDate)
        assertTrue(database.syncDao().pending("account-a").isEmpty())
        assertEquals(
            setOf("wateredDate"),
            Json.parseToJsonElement(gateway.commands.first().draftPayload).jsonObject.keys,
        )
    }

    @Test
    fun `callable failures classify only explicit transient codes as exact retry`() = runTest {
        database.cacheDao().upsertPlant(cachedPlant())
        val transient =
            listOf("ABORTED", "DEADLINE_EXCEEDED", "INTERNAL", "RESOURCE_EXHAUSTED", "UNAVAILABLE")
        for (code in transient) {
            val result =
                repository(FakeRemote(), RecordingGateway(RemoteMutationResult.Failed(code)))
                    .complete(request().copy(operationId = OperationId("watering-$code")))
            assertEquals(
                WateringCompletionResult.Failed(WateringCompletionFailure.REMOTE_WRITE_FAILED),
                result,
            )
        }

        for (code in
            listOf(
                "INVALID_ARGUMENT",
                "FAILED_PRECONDITION",
                "ALREADY_EXISTS",
                "MALFORMED_RESPONSE",
            )) {
            val result =
                repository(FakeRemote(), RecordingGateway(RemoteMutationResult.Failed(code)))
                    .complete(request().copy(operationId = OperationId("watering-$code")))
            assertEquals(
                WateringCompletionResult.Failed(WateringCompletionFailure.RECONCILIATION_REQUIRED),
                result,
            )
        }
        assertEquals(
            WateringCompletionResult.Forbidden,
            repository(
                    FakeRemote(),
                    RecordingGateway(RemoteMutationResult.Failed("PERMISSION_DENIED")),
                )
                .complete(request().copy(operationId = OperationId("watering-permission"))),
        )
    }

    @Test
    fun `applied response keeps outbox until immutable receipt and cache reconciliation succeed`() =
        runTest {
            database.cacheDao().upsertPlant(cachedPlant())
            val remote = FakeRemote(receipt = WateringReceiptLookup.Failed)
            val gateway = RecordingGateway(RemoteMutationResult.Applied(5))
            val repository = repository(remote, gateway)

            assertEquals(
                WateringCompletionResult.Failed(WateringCompletionFailure.INCONSISTENT_RECEIPT),
                repository.complete(request()),
            )

            assertEquals(
                listOf("watering-operation-stable"),
                database.syncDao().pending("account-a").map { it.operationId },
            )

            remote.receipt = WateringReceiptLookup.Found(receipt())
            assertEquals(
                WateringCompletionResult.Completed(receipt()),
                repository.reconcile(request()),
            )
            assertEquals(1, gateway.commands.size)
            assertTrue(database.syncDao().pending("account-a").isEmpty())
        }

    @Test
    fun `first completion caches authoritative due schedule without invented notification preferences`() =
        runTest {
            database.cacheDao().upsertPlant(cachedPlant())
            val remote = FakeRemote(receipt = WateringReceiptLookup.Found(receipt()))
            val repository = repository(remote, RecordingGateway(RemoteMutationResult.Applied(5)))

            assertEquals(
                WateringCompletionResult.Completed(receipt()),
                repository.complete(request()),
            )

            val schedule = requireNotNull(database.cacheDao().schedule("account-a", "plant-a"))
            assertEquals("2026-08-22", schedule.dueDate)
            assertEquals("Asia/Seoul", schedule.zoneId)
            assertEquals(null, schedule.reminderTime)
            assertEquals(null, schedule.enabled)
            assertTrue(database.syncDao().pending("account-a").isEmpty())
        }

    @Test
    fun `same idempotency key twice uses one outbox identity and one committed record`() = runTest {
        database.cacheDao().upsertPlant(cachedPlant())
        database.cacheDao().upsertSchedule(cachedSchedule())
        val remote = FakeRemote(receipt = WateringReceiptLookup.Found(receipt()))
        val gateway = RecordingGateway(RemoteMutationResult.Duplicate(5))
        val repository = repository(remote, gateway)

        val first = repository.complete(request())
        val duplicate = repository.complete(request())

        assertEquals(WateringCompletionResult.Completed(receipt()), first)
        assertEquals(WateringCompletionResult.Completed(receipt()), duplicate)
        assertEquals(
            listOf(OperationId("watering-operation-stable")),
            gateway.commands.map { it.operationId }.distinct(),
        )
        assertEquals(
            listOf("watering-operation-stable", "watering-operation-stable"),
            remote.receiptOperations,
        )
    }

    @Test
    fun `account switch before or after a suspension never mutates either account cache`() =
        runTest {
            database.cacheDao().upsertPlant(cachedPlant())
            database.cacheDao().upsertSchedule(cachedSchedule())
            val switchedBefore = FakeRemote(activeAccount = AccountId("account-b"))
            val before = repository(switchedBefore, RecordingGateway()).complete(request())
            assertEquals(WateringCompletionResult.Forbidden, before)

            val switchedAfter = FakeRemote(receipt = WateringReceiptLookup.Found(receipt()))
            switchedAfter.afterReceipt = { switchedAfter.activeAccount = AccountId("account-b") }
            val after =
                repository(switchedAfter, RecordingGateway(RemoteMutationResult.Applied(5)))
                    .complete(request())
            assertEquals(WateringCompletionResult.Forbidden, after)
            assertEquals(
                "2026-08-01",
                database.cacheDao().plant("account-a", "plant-a")?.lastWateredDate,
            )
            assertEquals(
                "2026-08-11",
                database.cacheDao().schedule("account-a", "plant-a")?.dueDate,
            )
        }

    @Test
    fun `cancellation during server receipt reconciliation keeps local outbox and cache intact`() =
        runTest {
            database.cacheDao().upsertPlant(cachedPlant())
            database.cacheDao().upsertSchedule(cachedSchedule())
            val receiptEntered = CompletableDeferred<Unit>()
            val remote = FakeRemote(receipt = WateringReceiptLookup.Found(receipt()))
            remote.afterReceipt = {
                receiptEntered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            val completion = CompletableDeferred<Throwable?>()
            val job = launch {
                repository(remote, RecordingGateway(RemoteMutationResult.Applied(5)))
                    .complete(request())
            }
            job.invokeOnCompletion(completion::complete)

            receiptEntered.await()
            val cancellation = CancellationException("confirmation left during reconcile")
            job.cancel(cancellation)

            assertSame(cancellation, completion.await())
            assertEquals(
                "2026-08-01",
                database.cacheDao().plant("account-a", "plant-a")?.lastWateredDate,
            )
            assertEquals(
                listOf("watering-operation-stable"),
                database.syncDao().pending("account-a").map { it.operationId },
            )
        }

    @Test
    fun `server success followed by database shutdown propagates Room cancellation`() = runTest {
        database.cacheDao().upsertPlant(cachedPlant())
        val gateway = RecordingGateway(RemoteMutationResult.Applied(5))
        val remote = FakeRemote(receipt = WateringReceiptLookup.Found(receipt()))
        remote.afterReceipt = { database.close() }

        try {
            repository(remote, gateway).complete(request())
            fail("CancellationException expected")
        } catch (_: CancellationException) {
            assertEquals(1, gateway.commands.size)
            assertFalse(database.isOpen)
        }
    }

    @Test
    fun `repository preserves cancellation at callable and receipt boundaries`() = runTest {
        database.cacheDao().upsertPlant(cachedPlant())
        database.cacheDao().upsertSchedule(cachedSchedule())
        val callableCancellation = CancellationException("confirmation left")
        try {
            repository(FakeRemote(), RemoteMutationGateway { throw callableCancellation })
                .complete(request())
            fail("CancellationException expected")
        } catch (error: CancellationException) {
            assertSame(callableCancellation, error)
        }
        assertEquals(
            listOf("watering-operation-stable"),
            database.syncDao().pending("account-a").map { it.operationId },
        )

        val receiptCancellation = CancellationException("receipt read left")
        try {
            repository(
                    FakeRemote(receiptFailure = receiptCancellation),
                    RecordingGateway(RemoteMutationResult.Applied(5)),
                )
                .complete(request())
            fail("CancellationException expected")
        } catch (error: CancellationException) {
            assertSame(receiptCancellation, error)
        }
    }

    private fun repository(remote: FakeRemote, gateway: RemoteMutationGateway) =
        OutboxWateringRepository(
            database = database,
            preparationSource = WateringPreparationSource { WateringLoad.Found(snapshot()) },
            remote = remote,
            gateway = gateway,
            now = { Instant.parse("2026-08-12T00:30:00Z") },
        )

    private fun request() =
        WateringCompletionRequest(
            accountId = AccountId("account-a"),
            plantId = PersonalPlantId("plant-a"),
            expectedPlantRevision = 4,
            operationId = OperationId("watering-operation-stable"),
            wateredDate = LocalDate.of(2026, 8, 12),
            accountZone = ZoneId.of("Asia/Seoul"),
        )

    private fun snapshot() =
        WateringPlantSnapshot(
            accountId = AccountId("account-a"),
            plantId = PersonalPlantId("plant-a"),
            displayName = "몬스테라",
            lastWateredDate = LocalDate.of(2026, 8, 1),
            publicIntervalDays = 10,
            accountZone = ZoneId.of("Asia/Seoul"),
            revision = 4,
        )

    private fun receipt() =
        WateringCompletionReceipt(
            accountId = AccountId("account-a"),
            plantId = PersonalPlantId("plant-a"),
            operationId = OperationId("watering-operation-stable"),
            recordId = "watering-operation-stable",
            wateredDate = LocalDate.of(2026, 8, 12),
            nextDueDate = LocalDate.of(2026, 8, 22),
            plantRevision = 5,
            scheduleRevision = 3,
            recordedAt = Instant.parse("2026-08-12T00:30:00Z"),
            accountZone = ZoneId.of("Asia/Seoul"),
            requestHash = WateringRequestHash.calculate(request()),
        )

    private fun cachedPlant(lastWateredDate: String = "2026-08-01", revision: Long = 4) =
        CachedPlantEntity(
            accountId = "account-a",
            plantId = "plant-a",
            displayName = "몬스테라",
            representativePhotoPath = null,
            revision = revision,
            updatedAtEpochMillis = 1,
            contentId = "species-a",
            registrationMethod = "IDENTIFIED",
            lastWateredDate = lastWateredDate,
        )

    private fun cachedSchedule(dueDate: String = "2026-08-11", revision: Long = 2) =
        CachedWateringScheduleEntity(
            accountId = "account-a",
            scheduleId = "plant-a",
            plantId = "plant-a",
            dueDate = dueDate,
            reminderTime = "09:00",
            zoneId = "Asia/Seoul",
            revision = revision,
            updatedAtEpochMillis = 1,
        )

    private open class RecordingGateway(
        private val result: RemoteMutationResult = RemoteMutationResult.Applied(5)
    ) : RemoteMutationGateway {
        val commands = mutableListOf<RemoteMutationCommand>()

        override suspend fun apply(command: RemoteMutationCommand): RemoteMutationResult {
            commands += command
            return result
        }
    }

    private class SequencedGateway : RecordingGateway() {
        override suspend fun apply(command: RemoteMutationCommand): RemoteMutationResult {
            super.apply(command)
            return if (commands.size == 1) RemoteMutationResult.Failed("UNAVAILABLE")
            else RemoteMutationResult.Duplicate(5)
        }
    }

    private class FakeRemote(
        activeAccount: AccountId = AccountId("account-a"),
        var receipt: WateringReceiptLookup = WateringReceiptLookup.Failed,
        private val receiptFailure: Throwable? = null,
    ) : WateringRemoteDataSource {
        var activeAccount = activeAccount
        var afterReceipt: suspend () -> Unit = {}
        val receiptOperations = mutableListOf<String>()

        override fun activeAccount() = activeAccount

        override suspend fun receipt(
            accountId: AccountId,
            plantId: PersonalPlantId,
            operationId: OperationId,
        ): WateringReceiptLookup {
            receiptFailure?.let { throw it }
            receiptOperations += operationId.value
            afterReceipt()
            return receipt
        }
    }
}
