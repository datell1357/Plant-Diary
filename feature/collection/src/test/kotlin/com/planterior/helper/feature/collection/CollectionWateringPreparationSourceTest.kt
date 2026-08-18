package com.planterior.helper.feature.collection

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.RegistrationMethod
import com.planterior.helper.feature.watering.WateringLoad
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionWateringPreparationSourceTest {
    @Test
    fun `only todo nine public guidance supplies a watering interval`() = runTest {
        val repository = FakeRepository(DetailLoad.Fresh(detail()))
        val source = CollectionWateringPreparationSource(repository)

        val public = source.load(PersonalPlantId("plant-a")) as WateringLoad.Found
        assertEquals(10, public.snapshot.publicIntervalDays)

        repository.load = DetailLoad.NoStandardContent(detail().plant, ZoneId.of("Asia/Seoul"))
        val unavailable = source.load(PersonalPlantId("plant-a")) as WateringLoad.Found
        assertEquals(null, unavailable.snapshot.publicIntervalDays)
    }

    @Test
    fun `stale guidance is never reused as a public interval`() = runTest {
        val detail = detail()
        val source =
            CollectionWateringPreparationSource(
                FakeRepository(
                    DetailLoad.Stale(
                        detail.plant,
                        detail.guidance,
                        editingAllowed = true,
                        accountZone = detail.accountZone,
                    )
                )
            )

        val stale = source.load(PersonalPlantId("plant-a")) as WateringLoad.Found

        assertEquals(null, stale.snapshot.publicIntervalDays)
    }

    private fun detail() =
        PlantDetail(
            PersonalPlantDetail(
                AccountId("account-a"),
                PersonalPlantId("plant-a"),
                "몬스테라",
                PlantContentId("species-a"),
                RegistrationMethod.IDENTIFIED,
                null,
                null,
                null,
                LocalDate.of(2026, 8, 1),
                4,
                Instant.parse("2026-08-12T00:00:00Z"),
            ),
            ZoneId.of("Asia/Seoul"),
            PlantCareGuidance(10, null, null, null, null, null, emptyList()),
        )

    private class FakeRepository(var load: DetailLoad) : CollectionRepository {
        override suspend fun loadCollection() = CollectionLoad.Fresh(emptyList())

        override suspend fun loadDetail(plantId: PersonalPlantId) = load

        override suspend fun saveEdit(request: PlantEditRequest) = EditResult.NotFound

        override suspend fun reconcileFailedEdit(
            accountId: AccountId,
            plantId: PersonalPlantId,
            operationId: OperationId,
        ) = DetailLoad.NotFound
    }
}
