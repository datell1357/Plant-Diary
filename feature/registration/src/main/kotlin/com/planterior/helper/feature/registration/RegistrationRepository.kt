package com.planterior.helper.feature.registration

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId

interface RegistrationRepository {
    suspend fun session(): RegistrationSession

    suspend fun searchPublicContents(query: String): List<RegistrationContent>

    suspend fun findDuplicates(
        accountId: AccountId,
        contentId: PlantContentId,
        excluding: PersonalPlantId,
    ): List<ExistingPersonalPlant>

    suspend fun reconcileCheckpoint(
        submission: PendingRegistration,
        checkpoint: RegistrationCheckpoint,
    ): RegistrationCheckpoint = checkpoint

    suspend fun register(
        submission: PendingRegistration,
        checkpoint: RegistrationCheckpoint,
    ): RegistrationAttempt
}
