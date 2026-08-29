package com.planterior.helper.action

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.Todo18BoundaryEvent
import com.planterior.helper.Todo18PlantRepositoryFixture
import com.planterior.helper.Todo18Scenario
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.collection.CollectionWateringPreparationSource
import com.planterior.helper.feature.collection.FirebaseCollectionRepository
import com.planterior.helper.feature.watering.OutboxWateringRepository
import com.planterior.helper.feature.watering.WateringConfirmActionDecision
import com.planterior.helper.feature.watering.WateringConfirmActionDiagnostics
import com.planterior.helper.feature.watering.WateringConfirmActionObservation
import com.planterior.helper.feature.watering.WateringConfirmActionStage
import com.planterior.helper.feature.watering.WateringConfirmationController
import com.planterior.helper.feature.watering.WateringConfirmationRoute
import com.planterior.helper.feature.watering.WateringConfirmationUiState
import com.planterior.helper.feature.watering.WateringTestTags
import java.io.Closeable
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [ROBOLECTRIC_MAX_SDK],
    qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class WateringConfirmActionEntryTraceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PlanteriorDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    PlanteriorDatabase::class.java,
                )
                .build()
    }

    @After
    fun tearDown() {
        check(WateringConfirmActionDiagnostics.listenerCount() == 0) {
            "Watering action diagnostic listener leaked"
        }
        database.close()
    }

    @Test
    fun `invalid date records rejected validation with exact identities and never enters outbox`() {
        val scenario = Todo18Scenario(AccountId("watering-validation-owner"))
        val plantId = scenario.seedPlant()
        val plants = Todo18PlantRepositoryFixture(scenario)
        val repository =
            OutboxWateringRepository(
                database,
                CollectionWateringPreparationSource(
                    FirebaseCollectionRepository(database, plants, plants, scenario::now)
                ),
                plants,
                plants,
                scenario::now,
            )
        val controller =
            WateringConfirmationController(
                plantId,
                repository,
                Clock.fixed(scenario.now(), scenario.zone),
                SavedStateHandle(),
            )
        runBlocking { controller.start() }
        controller.changeWateredDate("")
        val ready = controller.state.value as WateringConfirmationUiState.Ready
        val observations = mutableListOf<WateringConfirmActionObservation>()

        WateringConfirmActionDiagnostics.install(observations::add).use {
            runBlocking { controller.confirm() }
        }

        assertEquals(
            listOf(
                WateringConfirmActionStage.CONTROLLER_ENTRY,
                WateringConfirmActionStage.VALIDATION_DECISION,
            ),
            observations.map(WateringConfirmActionObservation::stage),
        )
        assertEquals(
            listOf(WateringConfirmActionDecision.REJECTED),
            observations.mapNotNull(WateringConfirmActionObservation::decision),
        )
        assertEquals(setOf(plantId), observations.mapNotNull { it.plantId }.toSet())
        assertEquals(
            setOf(ready.draft.operationId),
            observations.mapNotNull { it.operationId }.toSet(),
        )
        assertNotNull((controller.state.value as WateringConfirmationUiState.Ready).validationError)
        runBlocking {
            assertTrue(database.syncDao().pending(scenario.accountId.value).isEmpty())
        }
        assertEquals(0, WateringConfirmActionDiagnostics.listenerCount())
    }

    @Test
    fun `CONFIRM traces semantics through real outbox receipt and exact fixture listener in order`() {
        val scenario = Todo18Scenario(AccountId("watering-action-owner"))
        val plantId = scenario.seedPlant()
        val plants = Todo18PlantRepositoryFixture(scenario)
        val collection = FirebaseCollectionRepository(database, plants, plants, scenario::now)
        val routeReady = AtomicBoolean(false)
        val repository =
            OutboxWateringRepository(
                database,
                ActionPathWateringPreparationSource(
                    CollectionWateringPreparationSource(collection)
                ) {
                    routeReady.set(true)
                },
                plants,
                plants,
                scenario::now,
            )
        val observations = mutableListOf<WateringConfirmActionObservation>()
        val trace = OrderedActionStageTrace(WateringConfirmActionStage.entries)
        val boundaryEvents = mutableListOf<Todo18BoundaryEvent>()
        val diagnostic = WateringConfirmActionDiagnostics.install { observation ->
            observations += observation
            trace.record(observation.stage)
        }
        val boundary: Closeable = scenario.subscribe { event ->
            if (event.kind == "watering-receipt") boundaryEvents += event
        }
        val completed =
            mutableListOf<com.planterior.helper.feature.watering.WateringCompletionReceipt>()
        val routeIdlingResource = ActionPathIdlingResource(routeReady::get)
        val completedIdlingResource = ActionPathIdlingResource(completed::isNotEmpty)
        var routeIdlingRegistered = false
        var completionIdlingRegistered = false
        assertEquals(1, scenario.listenerCount())
        try {
            compose.setContent {
                PlanteriorTheme {
                    WateringConfirmationRoute(
                        plantId = plantId,
                        repository = repository,
                        onBack = {},
                        onDone = {},
                        clock = Clock.fixed(scenario.now(), scenario.zone),
                        onCompleted = completed::add,
                    )
                }
            }
            compose.registerIdlingResource(routeIdlingResource)
            routeIdlingRegistered = true
            compose.waitForIdle()

            recordConfirmSemantics(trace)
            compose.onNodeWithTag(WateringTestTags.CONFIRM).performClick()
            assertTrue(
                "real CONFIRM click did not reach the route coroutine",
                observations.any { it.stage == WateringConfirmActionStage.COROUTINE_ENTRY },
            )
            compose.registerIdlingResource(completedIdlingResource)
            completionIdlingRegistered = true
            compose.waitForIdle()
            compose.onNodeWithTag(WateringTestTags.RESULT).assertIsDisplayed()

            trace.requireComplete()
            val boundaryEvent = boundaryEvents.single()
            val operationIds =
                observations.mapNotNull(WateringConfirmActionObservation::operationId).toSet()
            assertEquals(setOf(boundaryEvent.identity), operationIds.map { it.value }.toSet())
            assertEquals(
                setOf(plantId),
                observations.mapNotNull(WateringConfirmActionObservation::plantId).toSet(),
            )
            assertEquals(
                listOf(WateringConfirmActionDecision.ACCEPTED),
                observations.mapNotNull(WateringConfirmActionObservation::decision),
            )
            assertEquals(boundaryEvent.identity, completed.last().operationId.value)
            runBlocking {
                assertEquals(
                    LocalDate.of(2026, 8, 26),
                    database
                        .cacheDao()
                        .plant(scenario.accountId.value, plantId.value)
                        ?.lastWateredDate
                        ?.let(LocalDate::parse),
                )
                assertTrue(database.syncDao().pending(scenario.accountId.value).isEmpty())
            }
        } finally {
            if (completionIdlingRegistered) {
                compose.unregisterIdlingResource(completedIdlingResource)
            }
            if (routeIdlingRegistered) compose.unregisterIdlingResource(routeIdlingResource)
            boundary.close()
            diagnostic.close()
        }
        assertEquals(0, scenario.listenerCount())
        assertEquals(0, WateringConfirmActionDiagnostics.listenerCount())
    }

    private fun recordConfirmSemantics(trace: OrderedActionStageTrace<WateringConfirmActionStage>) {
        val nodes = compose.onAllNodesWithTag(WateringTestTags.CONFIRM).fetchSemanticsNodes()
        assertEquals(1, nodes.size)
        trace.record(WateringConfirmActionStage.CONFIRM_NODE_COUNT)
        compose.onNodeWithTag(WateringTestTags.CONFIRM).assertIsDisplayed()
        trace.record(WateringConfirmActionStage.CONFIRM_NODE_DISPLAYED)
        compose.onNodeWithTag(WateringTestTags.CONFIRM).assertIsEnabled()
        trace.record(WateringConfirmActionStage.CONFIRM_NODE_ENABLED)
        assertTrue(nodes.single().config.contains(SemanticsActions.OnClick))
        trace.record(WateringConfirmActionStage.CONFIRM_NODE_ON_CLICK)
    }
}
