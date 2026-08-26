package com.planterior.helper.analytics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.database.AnalyticsEventQueueEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnalyticsDeliveryTaskTest {
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
    fun `response loss retains exact event id and duplicate acknowledgement deletes it`() =
        runTest {
            val id = "11111111-1111-4111-8111-111111111111"
            enqueue(id)
            var losesResponse = true
            val commands = mutableListOf<AnalyticsEventBatchCommand>()
            val task = task { command ->
                commands += command
                if (losesResponse) throw AnalyticsTransportException(IllegalStateException())
                listOf(AnalyticsEventAcknowledgement(id, duplicate = true))
            }

            assertEquals(AnalyticsDeliveryTaskResult.RETRY, task.run())
            assertEquals(1, database.analyticsEventQueueDao().count("owner"))
            losesResponse = false
            assertEquals(AnalyticsDeliveryTaskResult.COMPLETE, task.run())

            assertEquals(
                listOf(listOf(id), listOf(id)),
                commands.map { batch -> batch.events.map { it.eventId } },
            )
            assertEquals(0, database.analyticsEventQueueDao().count("owner"))
        }

    @Test
    fun `worker uploads FIFO rows in one batch and consumes ordered results`() = runTest {
        val firstId = "11111111-1111-4111-8111-111111111111"
        val secondId = "22222222-2222-4222-8222-222222222222"
        enqueue(firstId)
        enqueue(secondId, eventName = "CARE_INFORMATION_VIEWED")
        val batches = mutableListOf<AnalyticsEventBatchCommand>()
        val task = task { batch ->
            batches += batch
            batch.events.map { AnalyticsEventAcknowledgement(it.eventId, duplicate = false) }
        }

        assertEquals(AnalyticsDeliveryTaskResult.COMPLETE, task.run())
        assertEquals(1, batches.size)
        assertEquals("owner", batches.single().ownerUid)
        assertEquals(listOf(firstId, secondId), batches.single().events.map { it.eventId })
        assertEquals(0, database.analyticsEventQueueDao().count("owner"))
    }

    @Test
    fun `worker limits every remote batch to fifty events`() = runTest {
        repeat(51) { index ->
            enqueue("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}")
        }
        val batchSizes = mutableListOf<Int>()
        val task = task { batch ->
            batchSizes += batch.events.size
            batch.events.map { AnalyticsEventAcknowledgement(it.eventId, duplicate = false) }
        }

        assertEquals(AnalyticsDeliveryTaskResult.COMPLETE, task.run())
        assertEquals(listOf(50, 1), batchSizes)
        assertEquals(0, database.analyticsEventQueueDao().count("owner"))
    }

    @Test
    fun `owner switch after server response purges former owner before applying response`() =
        runTest {
            val id = "11111111-1111-4111-8111-111111111111"
            enqueue(id)
            val callStarted = CompletableDeferred<Unit>()
            val response = CompletableDeferred<List<AnalyticsEventAcknowledgement>>()
            var session =
                AnalyticsWorkerSession(
                    "owner",
                    AnalyticsAuthorization("owner", consentRevision = 3),
                )
            val task =
                task(session = { session }) {
                    callStarted.complete(Unit)
                    response.await()
                }
            val delivery = async { task.run() }

            callStarted.await()
            session = AnalyticsWorkerSession("other", null)
            response.complete(listOf(AnalyticsEventAcknowledgement(id, duplicate = false)))

            assertEquals(AnalyticsDeliveryTaskResult.COMPLETE, delivery.await())
            assertEquals(0, database.analyticsEventQueueDao().count("owner"))
        }

    @Test
    fun `signed out worker purges every former owner row without a network call`() = runTest {
        enqueue("11111111-1111-4111-8111-111111111111")
        var calls = 0
        val task =
            task(session = { AnalyticsWorkerSession(null, null) }) {
                calls += 1
                emptyList()
            }

        assertEquals(AnalyticsDeliveryTaskResult.COMPLETE, task.run())
        assertEquals(0, calls)
        assertEquals(0, database.analyticsEventQueueDao().count("owner"))
    }

    @Test
    fun `server only or malformed queued names are permanently deleted without a call`() = runTest {
        enqueue(
            "11111111-1111-4111-8111-111111111111",
            eventName = "ACCOUNT_DELETION_COMPLETED",
        )
        enqueue("not-a-uuid", eventName = "APP_SESSION_STARTED")
        var calls = 0
        val task = task {
            calls += 1
            emptyList()
        }

        assertEquals(AnalyticsDeliveryTaskResult.COMPLETE, task.run())
        assertEquals(0, calls)
        assertEquals(0, database.analyticsEventQueueDao().count("owner"))
    }

    private suspend fun enqueue(
        id: String,
        eventName: String = "APP_SESSION_STARTED",
    ) {
        database
            .analyticsEventQueueDao()
            .enqueueBounded(
                AnalyticsEventQueueEntity("owner", id, eventName, 3, 1),
                expiredAtOrBeforeEpochMillis = -1,
            )
    }

    private fun task(
        session: () -> AnalyticsWorkerSession = {
            AnalyticsWorkerSession("owner", AnalyticsAuthorization("owner", 3))
        },
        record: suspend (AnalyticsEventBatchCommand) -> List<AnalyticsEventAcknowledgement>,
    ) =
        AnalyticsDeliveryTask(
            database.analyticsEventQueueDao(),
            object : AnalyticsRemoteGateway {
                override suspend fun getConsent(ownerUid: String): RemoteAnalyticsConsent =
                    error("not used")

                override suspend fun setConsent(
                    command: AnalyticsConsentCommand
                ): AnalyticsConsentAcknowledgement = error("not used")

                override suspend fun recordEvents(
                    command: AnalyticsEventBatchCommand
                ): List<AnalyticsEventAcknowledgement> = record(command)
            },
            AnalyticsWorkerSessionProvider(session),
            java.time.Clock.fixed(java.time.Instant.ofEpochMilli(1), java.time.ZoneOffset.UTC),
        )
}
