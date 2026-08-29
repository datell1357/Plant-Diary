package com.planterior.helper.action

import androidx.compose.ui.test.IdlingResource
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.feature.watering.WateringLoad
import com.planterior.helper.feature.watering.WateringPreparationSource

internal class ActionPathIdlingResource(private val idle: () -> Boolean) : IdlingResource {
    override val isIdleNow: Boolean
        get() = idle()
}

internal class ActionPathWateringPreparationSource(
    private val delegate: WateringPreparationSource,
    private val onLoaded: () -> Unit,
) : WateringPreparationSource {
    override suspend fun load(plantId: PersonalPlantId): WateringLoad =
        delegate.load(plantId).also { onLoaded() }
}
