package com.planterior.helper.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnalyticsEventQueueTest {
    private lateinit var database: PlanteriorDatabase
    private lateinit var queue: AnalyticsEventQueueDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    PlanteriorDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
        queue = database.analyticsEventQueueDao()
    }

    @After fun tearDown() = database.close()

    @Test
    fun `queue is owner revision scoped FIFO with deterministic event id tie break`() = runTest {
        enqueue("owner-a", "c", revision = 2, at = 3)
        enqueue("owner-a", "b", revision = 2, at = 1)
        enqueue("owner-a", "a", revision = 2, at = 1)
        enqueue("owner-a", "other-revision", revision = 1, at = 0)
        enqueue("owner-b", "other-owner", revision = 2, at = 0)

        val rows = queue.oldestBatch("owner-a", 2, expiredAtOrBeforeEpochMillis = -1, limit = 50)

        assertEquals(listOf("a", "b", "c"), rows.map { it.eventId })
    }

    @Test
    fun `enqueue expires boundary rows and ignores exact duplicate ids`() = runTest {
        enqueue("owner-a", "expired", at = 10)
        enqueue("owner-a", "retained", at = 11)

        val inserted =
            queue.enqueueBounded(
                entity("owner-a", "retained", at = 99),
                expiredAtOrBeforeEpochMillis = 10,
            )

        assertFalse(inserted)
        assertEquals(
            listOf("retained"),
            queue.oldestBatch("owner-a", 1, expiredAtOrBeforeEpochMillis = 10).map {
                it.eventId
            },
        )
    }

    @Test
    fun `cap keeps newest one thousand rows deterministically and never crosses owners`() =
        runTest {
            repeat(1_002) { index ->
                enqueue("owner-a", index.toString().padStart(4, '0'), at = index.toLong())
            }
            enqueue("owner-b", "retained", at = 0)

            assertEquals(1_000, queue.count("owner-a"))
            assertEquals(1, queue.count("owner-b"))
            val first = queue.oldestBatch("owner-a", 1, -1, 1).single()
            assertEquals("0002", first.eventId)
        }

    @Test
    fun `owner and stale revision purges are isolated`() = runTest {
        enqueue("owner-a", "old", revision = 1, at = 1)
        enqueue("owner-a", "current", revision = 2, at = 2)
        enqueue("owner-b", "other", revision = 1, at = 3)

        assertEquals(1, queue.purgeOtherRevisions("owner-a", 2))
        assertEquals(1, queue.count("owner-a"))
        assertEquals(1, queue.purgeOwner("owner-a"))
        assertEquals(1, queue.count("owner-b"))
    }

    private suspend fun enqueue(
        owner: String,
        id: String,
        revision: Int = 1,
        at: Long,
    ) {
        assertTrue(queue.enqueueBounded(entity(owner, id, revision, at), -1))
    }

    private fun entity(owner: String, id: String, revision: Int = 1, at: Long) =
        AnalyticsEventQueueEntity(owner, id, "APP_SESSION_STARTED", revision, at)
}
