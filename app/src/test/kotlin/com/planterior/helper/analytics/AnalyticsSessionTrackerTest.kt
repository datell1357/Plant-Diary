package com.planterior.helper.analytics

import com.planterior.helper.core.model.ClientProductEvent
import com.planterior.helper.core.model.ProductEventRecorder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsSessionTrackerTest {
    @Test
    fun `foreground before acknowledgement records nothing and acknowledgement starts one session`() {
        val events = mutableListOf<ClientProductEvent>()
        val tracker = AnalyticsSessionTracker(ProductEventRecorder(events::add), MutableClock())

        tracker.onForeground()
        assertEquals(emptyList<ClientProductEvent>(), events)

        tracker.authorizationChanged(true)
        tracker.authorizationChanged(true)
        assertEquals(listOf(ClientProductEvent.APP_SESSION_STARTED), events)
    }

    @Test
    fun `background threshold is deterministic at exactly thirty minutes`() {
        val events = mutableListOf<ClientProductEvent>()
        val clock = MutableClock()
        val tracker = AnalyticsSessionTracker(ProductEventRecorder(events::add), clock)
        tracker.authorizationChanged(true)
        tracker.onForeground()
        tracker.onBackground()

        clock.advance(SESSION_BACKGROUND_TIMEOUT.toMillis() - 1)
        tracker.onForeground()
        tracker.onBackground()
        clock.advance(SESSION_BACKGROUND_TIMEOUT.toMillis())
        tracker.onForeground()

        assertEquals(
            listOf(
                ClientProductEvent.APP_SESSION_STARTED,
                ClientProductEvent.APP_SESSION_STARTED,
            ),
            events,
        )
    }

    @Test
    fun `revocation and owner cleanup discard in memory session`() {
        val events = mutableListOf<ClientProductEvent>()
        val tracker = AnalyticsSessionTracker(ProductEventRecorder(events::add), MutableClock())
        tracker.authorizationChanged(true)
        tracker.onForeground()
        tracker.authorizationChanged(false)
        tracker.authorizationChanged(true)

        assertEquals(2, events.size)
    }

    private class MutableClock(private var now: Long = 0) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(now)

        fun advance(millis: Long) {
            now += millis
        }
    }
}
