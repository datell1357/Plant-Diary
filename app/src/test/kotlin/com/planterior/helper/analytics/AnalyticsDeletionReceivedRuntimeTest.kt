package com.planterior.helper.analytics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.database.AnalyticsEventQueueEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AnalyticsConsentState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class AnalyticsDeletionReceivedRuntimeTest {
    private lateinit var context: Context
    private lateinit var database: PlanteriorDatabase
    private lateinit var remote: RecordingRemote
    private lateinit var work: RecordingWorkController
    private lateinit var runtime: AnalyticsRuntime

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, PlanteriorDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        remote = RecordingRemote()
        work = RecordingWorkController()
        runtime = AnalyticsRuntime(context, database, remote, { work }) { "owner" }
    }

    @After
    fun tearDown() {
        runtime.close()
        database.close()
    }

    @Test
    fun `received guard removes queue work consent and session without remote revoke`() = runTest {
        runtime.consent.load("owner")
        runtime.sessionTracker.onForeground()
        database.analyticsEventQueueDao().purgeOwner("owner")
        database
            .analyticsEventQueueDao()
            .enqueueBounded(
                AnalyticsEventQueueEntity(
                    accountId = "owner",
                    eventId = "11111111-1111-4111-8111-111111111111",
                    eventName = "APP_SESSION_STARTED",
                    consentRevision = 4,
                    enqueuedAtEpochMillis = 1,
                ),
                expiredAtOrBeforeEpochMillis = -1,
            )
        assertEquals(true, work.enqueued)

        runtime.deletionReceived("owner")

        assertNull(runtime.recorder.currentAuthorization())
        assertEquals(AnalyticsConsentState.FailedOff, runtime.consent.state.value)
        assertEquals(0, database.analyticsEventQueueDao().count("owner"))
        assertEquals(false, work.enqueued)
        assertEquals(2, work.cancelCalls)
        runtime.sessionTracker.onForeground()
        assertEquals(0, database.analyticsEventQueueDao().count("owner"))
        assertEquals(0, remote.setCalls)
    }

    private class RecordingWorkController : AnalyticsWorkController {
        var enqueued = false
        var cancelCalls = 0

        override fun enqueue() {
            enqueued = true
        }

        override suspend fun cancel() {
            cancelCalls += 1
            enqueued = false
        }
    }

    private class RecordingRemote : AnalyticsRemoteGateway {
        var setCalls = 0

        override suspend fun getConsent(ownerUid: String) = RemoteAnalyticsConsent(true, 4)

        override suspend fun setConsent(
            command: AnalyticsConsentCommand
        ): AnalyticsConsentAcknowledgement {
            setCalls += 1
            error("Deletion received must not send a consent command")
        }

        override suspend fun recordEvents(
            command: AnalyticsEventBatchCommand
        ): List<AnalyticsEventAcknowledgement> = error("not used")
    }
}
