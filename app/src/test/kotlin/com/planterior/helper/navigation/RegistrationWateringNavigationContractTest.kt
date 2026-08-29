package com.planterior.helper.navigation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlant
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.feature.collection.CollectionLoad
import com.planterior.helper.feature.collection.CollectionPlant
import com.planterior.helper.feature.collection.CollectionRepository
import com.planterior.helper.feature.collection.DetailLoad
import com.planterior.helper.feature.collection.EditResult
import com.planterior.helper.feature.collection.PersonalPlantDetail
import com.planterior.helper.feature.collection.PlantCareGuidance
import com.planterior.helper.feature.collection.PlantDetail
import com.planterior.helper.feature.collection.PlantDetailTestTags
import com.planterior.helper.feature.collection.PlantEditRequest
import com.planterior.helper.feature.registration.ExistingPersonalPlant
import com.planterior.helper.feature.registration.PendingRegistration
import com.planterior.helper.feature.registration.RegistrationAttempt
import com.planterior.helper.feature.registration.RegistrationCheckpoint
import com.planterior.helper.feature.registration.RegistrationContent
import com.planterior.helper.feature.registration.RegistrationRepository
import com.planterior.helper.feature.registration.RegistrationSession
import com.planterior.helper.feature.registration.RegistrationTestTags
import com.planterior.helper.feature.watering.WateringCompletionRequest
import com.planterior.helper.feature.watering.WateringCompletionResult
import com.planterior.helper.feature.watering.WateringLoad
import com.planterior.helper.feature.watering.WateringPlantSnapshot
import com.planterior.helper.feature.watering.WateringRepository
import com.planterior.helper.feature.watering.WateringTestTags
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [ROBOLECTRIC_MAX_SDK],
    qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class RegistrationWateringNavigationContractTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var navController: NavHostController

    @Test
    fun `fixed last watered registration commits exact plant into PlantDetail with watering record`() {
        // Given
        val repository = JourneyRepository()
        launch(repository)

        // When
        submitRegistration(ENTERED_LAST_WATERED_DATE)

        // Then
        val submission = repository.submissions.single()
        val committed = requireNotNull(repository.committedPlant)
        assertEquals(LocalDate.of(2026, 8, 20), submission.lastWateredDate)
        assertEquals(submission.plantId, committed.id)
        assertEquals(submission.lastWateredDate, committed.lastWateredDate)
        assertSame(committed, repository.detailSourcePlant)
        assertEquals(listOf(committed.id), repository.detailPlantIds.distinct())
        assertEquals(PlanteriorRoute.PlantDetail(committed.id.value), currentRoute())
        compose.onNodeWithText(ENTERED_LAST_WATERED_DATE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(WateringTestTags.RECORD).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `null last watered registration reaches committed PlantDetail without watering record`() {
        // Given
        val repository = JourneyRepository()
        launch(repository)

        // When
        submitRegistration(lastWateredDate = null)

        // Then
        val submission = repository.submissions.single()
        val committed = requireNotNull(repository.committedPlant)
        assertNull(submission.lastWateredDate)
        assertEquals(submission.plantId, committed.id)
        assertNull(committed.lastWateredDate)
        assertSame(committed, repository.detailSourcePlant)
        assertEquals(listOf(committed.id), repository.detailPlantIds.distinct())
        assertEquals(PlanteriorRoute.PlantDetail(committed.id.value), currentRoute())
        compose
            .onNodeWithTag(PlantDetailTestTags.PRIVATE_NOTE)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onAllNodesWithTag(WateringTestTags.RECORD).assertCountEquals(0)
    }

    private fun launch(repository: JourneyRepository) {
        compose.setContent {
            PlanteriorTheme {
                navController = rememberNavController()
                PlanteriorNavHost(
                    navController = navController,
                    startRoute = PlanteriorRoute.Registration,
                    authRouteGuardEnabled = false,
                    registrationRepository = repository,
                    collectionRepository = repository,
                    wateringRepository = repository,
                    clock =
                        Clock.fixed(
                            Instant.parse("2026-08-26T03:00:00Z"),
                            ACCOUNT_ZONE,
                        ),
                )
            }
        }
    }

    private fun submitRegistration(lastWateredDate: String?) {
        compose.onNodeWithTag(RegistrationTestTags.NAME).performTextReplacement("Todo18 Monstera")
        if (lastWateredDate != null) {
            compose
                .onNodeWithTag(RegistrationTestTags.LAST_WATERED)
                .performScrollTo()
                .performTextReplacement(lastWateredDate)
        }
        compose.onNodeWithTag(RegistrationTestTags.SUBMIT).performScrollTo().performClick()
        compose
            .onNodeWithTag(PlantDetailTestTags.PRIVATE_NOTE)
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun currentRoute(): PlanteriorRoute? =
        navController.currentBackStackEntry.toPlanteriorRoute()

    private class JourneyRepository :
        RegistrationRepository, CollectionRepository, WateringRepository {
        val submissions = mutableListOf<PendingRegistration>()
        val detailPlantIds = mutableListOf<PersonalPlantId>()
        var committedPlant: PersonalPlant? = null
            private set

        var detailSourcePlant: PersonalPlant? = null
            private set

        override suspend fun session() = RegistrationSession(ACCOUNT_ID, ACCOUNT_ZONE)

        override suspend fun searchPublicContents(query: String) = emptyList<RegistrationContent>()

        override suspend fun findDuplicates(
            accountId: AccountId,
            contentId: PlantContentId,
            excluding: PersonalPlantId,
        ) = emptyList<ExistingPersonalPlant>()

        override suspend fun register(
            submission: PendingRegistration,
            checkpoint: RegistrationCheckpoint,
        ): RegistrationAttempt {
            submissions += submission
            val committed = submission.toPersonalPlant(1, COMMITTED_AT)
            committedPlant = committed
            return RegistrationAttempt.Completed(committed)
        }

        override suspend fun loadCollection(): CollectionLoad {
            val plant = committedPlant ?: return CollectionLoad.Fresh(emptyList())
            return CollectionLoad.Fresh(
                listOf(CollectionPlant(plant.id, plant.displayName, plant.representativePhotoPath))
            )
        }

        override suspend fun loadDetail(plantId: PersonalPlantId): DetailLoad {
            detailPlantIds += plantId
            val plant = committedPlant?.takeIf { it.id == plantId } ?: return DetailLoad.NotFound
            detailSourcePlant = plant
            return DetailLoad.Fresh(
                PlantDetail(
                    plant = plant.detail(),
                    accountZone = ACCOUNT_ZONE,
                    guidance =
                        PlantCareGuidance(
                            wateringIntervalDays = 10,
                            lightGuidance = null,
                            minimumTemperatureCelsius = null,
                            maximumTemperatureCelsius = null,
                            minimumHumidityPercent = null,
                            maximumHumidityPercent = null,
                            symptoms = emptyList(),
                        ),
                )
            )
        }

        override suspend fun saveEdit(request: PlantEditRequest): EditResult = EditResult.NotFound

        override suspend fun reconcileFailedEdit(
            accountId: AccountId,
            plantId: PersonalPlantId,
            operationId: OperationId,
        ): DetailLoad = DetailLoad.NotFound

        override suspend fun load(plantId: PersonalPlantId): WateringLoad {
            val plant = committedPlant?.takeIf { it.id == plantId } ?: return WateringLoad.NotFound
            return WateringLoad.Found(
                WateringPlantSnapshot(
                    accountId = ACCOUNT_ID,
                    plantId = plant.id,
                    displayName = plant.displayName,
                    lastWateredDate = plant.lastWateredDate,
                    publicIntervalDays = 10,
                    accountZone = ACCOUNT_ZONE,
                    revision = plant.revision.value,
                )
            )
        }

        override suspend fun complete(
            request: WateringCompletionRequest
        ): WateringCompletionResult = error("Watering completion is not part of this contract")

        private fun PersonalPlant.detail() =
            PersonalPlantDetail(
                accountId = ACCOUNT_ID,
                id = id,
                displayName = displayName,
                contentId = contentId,
                registrationMethod = registrationMethod,
                representativePhotoPath = representativePhotoPath,
                location = location,
                privateNote = note,
                lastWateredDate = lastWateredDate,
                revision = revision.value,
                updatedAt = updatedAt,
            )
    }

    private companion object {
        val ACCOUNT_ID = AccountId("account-a")
        val ACCOUNT_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        val COMMITTED_AT: Instant = Instant.parse("2026-08-26T03:00:00Z")
        const val ENTERED_LAST_WATERED_DATE = "2026-08-20"
    }
}
