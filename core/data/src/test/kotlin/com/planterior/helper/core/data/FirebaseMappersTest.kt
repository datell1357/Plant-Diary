package com.planterior.helper.core.data

import com.planterior.helper.core.model.WateringScheduleId
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class FirebaseMappersTest {
    @Test
    fun `schedule DTO preserves LocalDate without timezone conversion`() {
        val dto =
            WateringScheduleDto(
                "account-a",
                "plant-a",
                "2026-08-12",
                "09:00",
                "Asia/Seoul",
                true,
                2,
                1,
                "operation-0001",
                "2026-08-11T23:00:00Z",
            )
        val domain = dto.toDomain(WateringScheduleId("schedule-a"))
        assertEquals(LocalDate.of(2026, 8, 12), domain.dueDate)
        assertEquals("Asia/Seoul", domain.zoneId.id)
    }
}
