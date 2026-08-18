package com.planterior.helper.navigation

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.RegistrationMethod
import com.planterior.helper.feature.collection.CollectionLoad
import com.planterior.helper.feature.collection.CollectionRepository
import com.planterior.helper.feature.collection.DetailLoad
import com.planterior.helper.feature.collection.EditResult
import com.planterior.helper.feature.collection.PersonalPlantDetail
import com.planterior.helper.feature.collection.PlantCareGuidance
import com.planterior.helper.feature.collection.PlantDetail
import com.planterior.helper.feature.collection.PlantDetailTestTags
import com.planterior.helper.feature.collection.PlantEditRequest
import com.planterior.helper.feature.watering.WateringCompletionReceipt
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [ROBOLECTRIC_MAX_SDK],
    qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class WateringNavigationContractTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var navController: NavHostController
    private lateinit var collection: FakeCollectionRepository
    private lateinit var watering: FakeWateringRepository

    @Test
    fun `production nav completion result refreshes exact last and next dates on done`() {
        launch()
        completeWatering()

        composeRule.onNodeWithTag(WateringTestTags.DONE).performClick()
        assertRefreshedDetail()
    }

    @Test
    fun `completed top back publishes refresh before leaving result`() {
        launch()
        completeWatering()

        composeRule.onNodeWithTag(WateringTestTags.BACK).performClick()
        assertRefreshedDetail()
    }

    @Test
    fun `completed system back publishes refresh before the back stack pops`() {
        launch()
        completeWatering()

        composeRule.runOnIdle { navController.popBackStack() }
        assertRefreshedDetail()
    }

    private fun launch() {
        collection = FakeCollectionRepository()
        watering = FakeWateringRepository(collection)
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                navController = rememberNavController()
                PlanteriorNavHost(
                    navController = navController,
                    startRoute = PlanteriorRoute.PlantDetail("plant-a"),
                    collectionRepository = collection,
                    wateringRepository = watering,
                    clock =
                        Clock.fixed(
                            Instant.parse("2026-08-11T00:00:00Z"),
                            ZoneId.of("America/Los_Angeles"),
                        ),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun completeWatering() {
        composeRule.onNodeWithTag(WateringTestTags.RECORD).performClick()
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.WateringConfirmation("plant-a"), currentRoute())

        composeRule.onNodeWithTag(WateringTestTags.CONFIRM).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WateringTestTags.RESULT).assertExists()
        assertEquals(1, watering.requests.size)
    }

    private fun assertRefreshedDetail() {
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.PlantDetail("plant-a"), currentRoute())
        composeRule.onNodeWithTag(PlantDetailTestTags.WATERING_SCHEDULE).assertExists()
        composeRule.onNodeWithText("2026-08-11").assertExists()
        composeRule.onNodeWithText("2026-08-21 예정").assertExists()
        assertEquals(2, collection.detailLoads)
    }

    private fun currentRoute(): PlanteriorRoute? =
        navController.currentBackStackEntry.toPlanteriorRoute()

    private class FakeWateringRepository(private val collection: FakeCollectionRepository) :
        WateringRepository {
        val requests = mutableListOf<WateringCompletionRequest>()

        override suspend fun load(plantId: PersonalPlantId) =
            WateringLoad.Found(
                WateringPlantSnapshot(
                    AccountId("account-a"),
                    PersonalPlantId("plant-a"),
                    "몬스테라",
                    collection.lastWateredDate,
                    10,
                    ZoneId.of("Asia/Seoul"),
                    collection.revision,
                )
            )

        override suspend fun complete(
            request: WateringCompletionRequest
        ): WateringCompletionResult {
            requests += request
            collection.lastWateredDate = request.wateredDate
            collection.revision = 5
            return WateringCompletionResult.Completed(
                WateringCompletionReceipt(
                    request.accountId,
                    request.plantId,
                    request.operationId,
                    request.operationId.value,
                    request.wateredDate,
                    request.wateredDate.plusDays(10),
                    5,
                    3,
                    Instant.parse("2026-08-11T00:00:00Z"),
                    accountZone = ZoneId.of("Asia/Seoul"),
                    requestHash = "a".repeat(64),
                )
            )
        }
    }

    private class FakeCollectionRepository : CollectionRepository {
        var lastWateredDate: LocalDate = LocalDate.of(2026, 8, 1)
        var revision: Long = 4
        var detailLoads = 0

        override suspend fun loadCollection() = CollectionLoad.Fresh(emptyList())

        override suspend fun loadDetail(plantId: PersonalPlantId): DetailLoad {
            detailLoads += 1
            return DetailLoad.Fresh(detail())
        }

        override suspend fun saveEdit(request: PlantEditRequest) = EditResult.NotFound

        override suspend fun reconcileFailedEdit(
            accountId: AccountId,
            plantId: PersonalPlantId,
            operationId: OperationId,
        ) = DetailLoad.NotFound

        private fun detail() =
            PlantDetail(
                plant =
                    PersonalPlantDetail(
                        accountId = AccountId("account-a"),
                        id = PersonalPlantId("plant-a"),
                        displayName = "몬스테라",
                        contentId = PlantContentId("species-a"),
                        registrationMethod = RegistrationMethod.IDENTIFIED,
                        representativePhotoPath = null,
                        location = null,
                        privateNote = null,
                        lastWateredDate = lastWateredDate,
                        revision = revision,
                        updatedAt = Instant.parse("2026-08-11T00:00:00Z"),
                    ),
                accountZone = ZoneId.of("Asia/Seoul"),
                guidance = PlantCareGuidance(10, null, null, null, null, null, emptyList()),
            )
    }
}
