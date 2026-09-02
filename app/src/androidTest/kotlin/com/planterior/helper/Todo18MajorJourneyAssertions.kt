package com.planterior.helper

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.feature.collection.PlantDetailTestTags
import com.planterior.helper.feature.minihome.MiniHomeTestTags
import com.planterior.helper.feature.share.MiniHomeShareTestTags
import com.planterior.helper.feature.shop.InventoryFeedback
import com.planterior.helper.feature.shop.InventoryTestTags
import com.planterior.helper.feature.watering.WateringTestTags
import com.planterior.helper.feature.weather.WeatherTestTags
import com.planterior.helper.navigation.PlanteriorRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals

/** Assertions for the complete persisted product journey. */
internal fun Todo18MainActivityJourneyHarness.assertMajorProductJourneyPersistsInRoom() {
    val plantId = registerPlantThroughProductRoute()

    navigateDirectly(PlanteriorRoute.PlantDetail(plantId))
    compose.onNodeWithTag(PlantDetailTestTags.SCREEN).assertIsDisplayed()

    events.navigateTo(PlanteriorRoute.WateringConfirmation(plantId)) {
        compose.onNodeWithTag(WateringTestTags.RECORD).performScrollTo().performClick()
    }
    val fixtureWateredDate = runtime.boundary.now().atZone(runtime.boundary.zone).toLocalDate()
    compose
        .onNodeWithTag(WateringTestTags.DATE_INPUT)
        .performTextReplacement(fixtureWateredDate.toString())
    Todo18IntegratedActionDiagnosticCapture(
            runtime,
            "registration-watering-confirm",
            Todo18IntegratedActionKind.WATERING_CONFIRM,
        )
        .run { action ->
            events
                .awaitBoundary("watering-receipt") {
                    compose.onNodeWithTag(WateringTestTags.CONFIRM).performScrollTo()
                    assertWateringConfirmActionNode(PersonalPlantId(plantId))
                    compose.onNodeWithTag(WateringTestTags.CONFIRM).performClick()
                }
                .also { action.recordBoundaryDelivery() }
            compose.waitForIdle()
            compose.onNodeWithTag(WateringTestTags.RESULT).assertIsDisplayed()
        }
    assertEquals(
        fixtureWateredDate.toString(),
        runBlocking {
            runtime.database
                .cacheDao()
                .plant(Todo18IntegratedRuntimeRule.ACCOUNT_UID, plantId)
                ?.lastWateredDate
        },
    )

    events.navigateAndAwaitBoundary(
        route = PlanteriorRoute.Storage,
        boundaryKind = "inventory-loaded",
    )
    compose.onNodeWithTag(InventoryTestTags.SHOP).performClick()
    compose
        .onNodeWithTag(InventoryTestTags.item(ItemId("todo18-planter")))
        .performScrollTo()
        .assertIsDisplayed()
    Todo18InventorySettlementDiagnosticCapture(runtime, compose).capture {
        lateinit var acquired: Todo18BoundaryEvent
        lateinit var settled: Todo18BoundaryEvent
        val feedback =
            rendered.awaitInventoryFeedback(
                matches = {
                    it.feedback == InventoryFeedback.ACQUIRED &&
                        it.settlement.accountId.value == Todo18IntegratedRuntimeRule.ACCOUNT_UID &&
                        it.settlement.itemId == ItemId("todo18-planter")
                },
                trigger = {
                    settled =
                        events.awaitBoundary("inventory-cache-settled") {
                            acquired =
                                events.awaitBoundary("inventory-acquired") {
                                    compose
                                        .onNodeWithTag(
                                            InventoryTestTags.acquire(ItemId("todo18-planter"))
                                        )
                                        .performClick()
                                }
                        }
                },
            )
        assertEquals(acquired.identity, settled.identity)
        assertEquals(acquired.identity, feedback.settlement.operationId.value)
        assertEquals(
            1,
            runBlocking {
                runtime.database.cacheDao().ownedItems(Todo18IntegratedRuntimeRule.ACCOUNT_UID).size
            },
        )
    }

    Todo18MiniHomeInitialLoadDiagnosticCapture(runtime, compose).captureInitialLoad(
        "registration-mini-home-initial-load"
    ) {
        val miniHomeBarrier = events.navigateAndAwaitMiniHomeLoaded()
        rendered.awaitMiniHomeViewingAfterLoad(miniHomeBarrier)
    }
    compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().performClick()
    compose
        .onNodeWithTag(MiniHomeTestTags.plant(PersonalPlantId(plantId)))
        .performScrollTo()
        .performClick()
    events.awaitBoundary("mini-home-committed") {
        compose.onNodeWithTag(MiniHomeTestTags.SAVE).performScrollTo().performClick()
    }
    compose.waitForIdle()
    compose.onNodeWithText("저장했어요").assertIsDisplayed()
    assertEquals(
        1,
        runBlocking {
            val dao = runtime.database.cacheDao()
            val home = requireNotNull(dao.miniHome(Todo18IntegratedRuntimeRule.ACCOUNT_UID))
            dao.miniHomePlacements(
                    Todo18IntegratedRuntimeRule.ACCOUNT_UID,
                    home.miniHomeId,
                    home.revision,
                )
                .size
        },
    )

    val floor = rendered.currentMiniHomeShareState()?.sequence ?: 0L
    events.navigateAndAwaitBoundary(
        route = PlanteriorRoute.MiniHomeShare,
        boundaryKind = "mini-home-loaded",
    )
    rendered.awaitMiniHomeShareReady(floor)
    events.awaitBoundary("share-create") {
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_CREATE).performScrollTo().performClick()
    }
    compose.waitForIdle()
    compose.onNodeWithTag(MiniHomeShareTestTags.LINK_URL).assertIsDisplayed()
    compose.onNodeWithTag(MiniHomeShareTestTags.LINK_EXPIRY).performScrollTo().assertIsDisplayed()

    events.navigateAndAwaitBoundary(
        route = PlanteriorRoute.Weather,
        boundaryKind = "weather-loaded",
    )
    compose.onNodeWithTag(WeatherTestTags.STALE).assertIsDisplayed()

    events.navigateAndAwaitBoundary(
        route = PlanteriorRoute.AccountDeletion,
        boundaryKind = "account-deletion-partial",
    )
    compose.onNodeWithTag("account-deletion.account-retained").performScrollTo().assertIsDisplayed()
    compose
        .onNodeWithTag("account-deletion.remaining.AUTH_ACCOUNT")
        .performScrollTo()
        .assertIsDisplayed()

    captureReceipt(
        "integrated-major-journeys",
        "registration,collection,watering,inventory,minihome,share,weather,partial-deletion",
    )
}
