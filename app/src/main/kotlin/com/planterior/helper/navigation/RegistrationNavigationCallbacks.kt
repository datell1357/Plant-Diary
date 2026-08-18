package com.planterior.helper.navigation

import com.planterior.helper.core.model.PersonalPlantId

internal class RegistrationNavigationCallbacks(private val navigate: (PlanteriorRoute) -> Unit) {
    fun openExisting(id: PersonalPlantId) {
        navigate(PlanteriorRoute.PlantDetail(id.value))
    }

    fun registrationCompleted(id: PersonalPlantId) {
        navigate(PlanteriorRoute.PlantDetail(id.value))
    }
}
