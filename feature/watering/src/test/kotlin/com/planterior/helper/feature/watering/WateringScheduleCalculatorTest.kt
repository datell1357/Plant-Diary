package com.planterior.helper.feature.watering

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WateringScheduleCalculatorTest {
    @Test
    fun `august first plus ten is august eleventh and is overdue on august twelfth`() {
        val status =
            WateringScheduleCalculator.calculate(
                lastWateredDate = LocalDate.of(2026, 8, 1),
                publicIntervalDays = 10,
                accountZone = ZoneId.of("Asia/Seoul"),
                clock = fixed("2026-08-12T00:00:00Z"),
            )

        assertEquals(
            WateringScheduleStatus.Overdue(LocalDate.of(2026, 8, 11), daysLate = 1),
            status,
        )
    }

    @Test
    fun `due date equality is due and a later date is upcoming`() {
        val clock = fixed("2026-08-12T00:00:00Z")

        assertEquals(
            WateringScheduleStatus.Due(LocalDate.of(2026, 8, 12)),
            WateringScheduleCalculator.calculate(
                LocalDate.of(2026, 8, 2),
                10,
                ZoneId.of("Asia/Seoul"),
                clock,
            ),
        )
        assertEquals(
            WateringScheduleStatus.Upcoming(LocalDate.of(2026, 8, 13), daysUntil = 1),
            WateringScheduleCalculator.calculate(
                LocalDate.of(2026, 8, 3),
                10,
                ZoneId.of("Asia/Seoul"),
                clock,
            ),
        )
    }

    @Test
    fun `account timezone owns the date boundary rather than the device clock zone`() {
        val boundaryClock =
            Clock.fixed(
                Instant.parse("2026-08-10T15:30:00Z"),
                ZoneId.of("America/Los_Angeles"),
            )
        val dueDate = LocalDate.of(2026, 8, 1)

        assertTrue(
            WateringScheduleCalculator.calculate(
                dueDate,
                10,
                ZoneId.of("Asia/Seoul"),
                boundaryClock,
            ) is WateringScheduleStatus.Due
        )
        assertTrue(
            WateringScheduleCalculator.calculate(
                dueDate,
                10,
                ZoneId.of("America/Los_Angeles"),
                boundaryClock,
            ) is WateringScheduleStatus.Upcoming
        )
    }

    @Test
    fun `missing last date or public interval is unavailable and invalid intervals are never defaulted`() {
        assertEquals(
            WateringScheduleStatus.Unavailable(WateringUnavailableReason.MISSING_LAST_WATERED_DATE),
            WateringScheduleCalculator.calculate(
                null,
                10,
                ZoneId.of("Asia/Seoul"),
                fixed("2026-08-12T00:00:00Z"),
            ),
        )
        assertEquals(
            WateringScheduleStatus.Unavailable(WateringUnavailableReason.MISSING_PUBLIC_INTERVAL),
            WateringScheduleCalculator.calculate(
                LocalDate.of(2026, 8, 1),
                null,
                ZoneId.of("Asia/Seoul"),
                fixed("2026-08-12T00:00:00Z"),
            ),
        )
        listOf(0, 366).forEach { invalid ->
            assertEquals(
                WateringScheduleStatus.Unavailable(
                    WateringUnavailableReason.INVALID_PUBLIC_INTERVAL
                ),
                WateringScheduleCalculator.calculate(
                    LocalDate.of(2026, 8, 1),
                    invalid,
                    ZoneId.of("Asia/Seoul"),
                    fixed("2026-08-12T00:00:00Z"),
                ),
            )
        }
    }

    private fun fixed(value: String) =
        Clock.fixed(Instant.parse(value), ZoneId.of("America/Los_Angeles"))
}
