package com.planterior.helper.feature.collection

import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.feature.watering.WateringLoad
import com.planterior.helper.feature.watering.WateringPlantSnapshot
import com.planterior.helper.feature.watering.WateringPreparationSource

/** Todo 9가 소유권과 공개 상태를 검증한 상세 결과만 물 주기 완료 흐름에 전달한다. */
class CollectionWateringPreparationSource(private val collectionRepository: CollectionRepository) :
    WateringPreparationSource {
    override suspend fun load(plantId: PersonalPlantId): WateringLoad =
        when (val detail = collectionRepository.loadDetail(plantId)) {
            is DetailLoad.Fresh -> detail.detail.wateringLoad()
            is DetailLoad.Partial -> detail.detail.wateringLoad()
            is DetailLoad.Stale -> {
                val zone = detail.accountZone ?: return WateringLoad.Failed
                WateringLoad.Found(
                    WateringPlantSnapshot(
                        detail.plant.accountId,
                        detail.plant.id,
                        detail.plant.displayName,
                        detail.plant.lastWateredDate,
                        publicIntervalDays = null,
                        zone,
                        detail.plant.revision,
                    )
                )
            }
            is DetailLoad.NoStandardContent ->
                WateringLoad.Found(
                    WateringPlantSnapshot(
                        detail.plant.accountId,
                        detail.plant.id,
                        detail.plant.displayName,
                        detail.plant.lastWateredDate,
                        publicIntervalDays = null,
                        detail.accountZone,
                        detail.plant.revision,
                    )
                )
            DetailLoad.Forbidden -> WateringLoad.Forbidden
            DetailLoad.NotFound -> WateringLoad.NotFound
            DetailLoad.Failed -> WateringLoad.Failed
        }
}

private fun PlantDetail.wateringLoad(): WateringLoad =
    WateringLoad.Found(
        WateringPlantSnapshot(
            plant.accountId,
            plant.id,
            plant.displayName,
            plant.lastWateredDate,
            guidance.wateringIntervalDays,
            accountZone,
            plant.revision,
        )
    )
