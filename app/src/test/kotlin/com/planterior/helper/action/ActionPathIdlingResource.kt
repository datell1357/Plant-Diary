package com.planterior.helper.action

import androidx.compose.ui.test.IdlingResource
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.feature.watering.WateringCompletionRequest
import com.planterior.helper.feature.watering.WateringCompletionResult
import com.planterior.helper.feature.watering.WateringLoad
import com.planterior.helper.feature.watering.WateringPreparationSource
import com.planterior.helper.feature.watering.WateringRepository

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

internal class ActionPathWateringRepository(
    private val delegate: WateringRepository,
    private val onActionReturned: (WateringCompletionResult) -> Unit,
) : WateringRepository {
    override suspend fun load(plantId: PersonalPlantId): WateringLoad = delegate.load(plantId)

    override suspend fun complete(request: WateringCompletionRequest): WateringCompletionResult =
        delegate.complete(request).also(onActionReturned)

    override suspend fun reconcile(request: WateringCompletionRequest): WateringCompletionResult =
        delegate.reconcile(request).also(onActionReturned)
}
