package com.planterior.helper.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DomainContractTest {
    @Test
    fun `time contracts preserve calendar schedule independently from server instants`() {
        val schedule =
            WateringSchedule(
                id = WateringScheduleId("schedule-01"),
                plantId = PersonalPlantId("plant-01"),
                dueDate = LocalDate.of(2026, 8, 12),
                reminderTime = LocalTime.of(9, 30),
                zoneId = ZoneId.of("Asia/Seoul"),
                enabled = true,
                revision = Revision(3),
                updatedAt = Instant.parse("2026-08-11T23:00:00Z"),
            )

        assertEquals(LocalDate.of(2026, 8, 12), schedule.dueDate)
        assertEquals(LocalTime.of(9, 30), schedule.reminderTime)
        assertEquals(ZoneId.of("Asia/Seoul"), schedule.zoneId)
    }

    @Test
    fun `operation ids are stable for a logical mutation and reject malformed input`() {
        val first = OperationId.stable(AccountId("account-a"), "plant-01", "water", "2026-08-12")
        val retried = OperationId.stable(AccountId("account-a"), "plant-01", "water", "2026-08-12")
        val otherAccount =
            OperationId.stable(AccountId("account-b"), "plant-01", "water", "2026-08-12")

        assertEquals(first, retried)
        assertNotEquals(first, otherAccount)
        assertThrows(IllegalArgumentException::class.java) { OperationId("../foreign") }
        assertThrows(IllegalArgumentException::class.java) { Revision(-1) }
    }
}
