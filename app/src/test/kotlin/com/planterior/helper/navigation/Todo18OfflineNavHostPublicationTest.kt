package com.planterior.helper.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.Todo18MiniHomeStateEvent
import com.planterior.helper.Todo18RenderedStateSink
import com.planterior.helper.auth.AuthRuntimeDependencyOverrides
import com.planterior.helper.auth.RenderedStateSink
import com.planterior.helper.auth.Todo18DebugRuntimeDependencies
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.diagnostic.Todo18PipelineEventKind
import com.planterior.helper.diagnostic.Todo18StateChannel
import com.planterior.helper.diagnostic.Todo18StateKind
import com.planterior.helper.diagnostic.Todo18WaitId
import com.planterior.helper.feature.minihome.MiniHomeAuthOwnership
import com.planterior.helper.feature.minihome.MiniHomeDiscardHandle
import com.planterior.helper.feature.minihome.MiniHomeDiscardResult
import com.planterior.helper.feature.minihome.MiniHomeLayout
import com.planterior.helper.feature.minihome.MiniHomeLoadResult
import com.planterior.helper.feature.minihome.MiniHomePlantChoice
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomeSaveActionDiagnostics
import com.planterior.helper.feature.minihome.MiniHomeSaveActionObservation
import com.planterior.helper.feature.minihome.MiniHomeSaveActionStage
import com.planterior.helper.feature.minihome.MiniHomeSaveFailure
import com.planterior.helper.feature.minihome.MiniHomeSaveRequest
import com.planterior.helper.feature.minihome.MiniHomeSaveResult
import com.planterior.helper.feature.minihome.MiniHomeTestTags
import com.planterior.helper.feature.minihome.MiniHomeUiState
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_MAX_SDK])
class Todo18OfflineNavHostPublicationTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var navController: NavHostController

    @After
    fun clearInstalledRuntime() {
        Todo18DebugRuntimeDependencies.clear()
    }

    @Test
    fun `installed sink receives audited raw and displayed Editing exactly once`() {
        // Given
        val sink = Todo18RenderedStateSink()
        val runtime = installRuntime(sink)
        val raw = mutableListOf<Todo18MiniHomeStateEvent>()
        val displayed = mutableListOf<Todo18MiniHomeStateEvent>()
        val capture = sink.startDiagnosticCapture(Todo18WaitId.OFFLINE_BEGIN_EDIT)
        val rawSubscription = sink.subscribeToRawMiniHomeStates(raw::add)
        val displayedSubscription = sink.subscribeToDisplayedMiniHomeStates(displayed::add)
        assertSame(runtime, Todo18DebugRuntimeDependencies.current())
        assertSame(sink, runtime.renderedStateSink)

        try {
            mount(runtime, OWNER_A) { runtime.renderedStateSink }
            assertEquals(1, raw.countState<MiniHomeUiState.Viewing>())
            assertEquals(1, displayed.countState<MiniHomeUiState.Viewing>())

            // When
            compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().performClick()
            compose.waitForIdle()

            // Then
            val snapshot = capture.snapshot()
            assertEquals(
                "raw=${raw.map { it.state::class.simpleName }}, " +
                    "displayed=${displayed.map { it.state::class.simpleName }}, " +
                    "pipeline=${snapshot.pipeline.map { it.kind }}",
                1,
                raw.countState<MiniHomeUiState.Editing>(),
            )
            assertTrue(
                snapshot.pipeline.any {
                    it.kind == Todo18PipelineEventKind.ROUTE_STATE_OBSERVED
                }
            )
            assertEquals(
                Todo18StateKind.MINI_HOME_EDITING,
                snapshot.stateDispatches
                    .last { it.channel == Todo18StateChannel.MINI_HOME_RAW }
                    .state,
            )
            assertEquals(
                "PlanteriorNavHost must publish displayed Editing after Route audit/raw Editing",
                1,
                displayed.countState<MiniHomeUiState.Editing>(),
            )
            assertTrue(
                raw.last { it.state is MiniHomeUiState.Editing }.sequence <
                    displayed.last { it.state is MiniHomeUiState.Editing }.sequence
            )
            val pipelineKinds = snapshot.pipeline.map { it.kind }
            assertTrue(
                pipelineKinds.indexOf(Todo18PipelineEventKind.ROUTE_STATE_OBSERVED) <
                    pipelineKinds.indexOf(Todo18PipelineEventKind.TASK1_PUBLICATION)
            )
        } finally {
            displayedSubscription.close()
            rawSubscription.close()
            capture.close()
        }
    }

    @Test
    fun `offline Retry Saved revision 2 publishes controller raw route displayed and sink`() {
        val sink = Todo18RenderedStateSink()
        val repository = HostOfflineRetryRepository()
        val runtime = installRuntime(sink, repository)
        val raw = mutableListOf<Todo18MiniHomeStateEvent>()
        val route = mutableListOf<Todo18MiniHomeStateEvent>()
        val displayed = mutableListOf<Todo18MiniHomeStateEvent>()
        val observations = mutableListOf<MiniHomeSaveActionObservation>()
        val rawSubscription = sink.subscribeToRawMiniHomeStates(raw::add)
        val routeSubscription = sink.subscribeToRouteMiniHomeStates(route::add)
        val displayedSubscription = sink.subscribeToDisplayedMiniHomeStates(displayed::add)
        val actionDiagnostic = MiniHomeSaveActionDiagnostics.install { observation ->
            observations += observation
        }

        try {
            mount(runtime, OWNER_A) { sink }
            compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().performClick()
            compose.waitForIdle()
            compose
                .onNodeWithTag(MiniHomeTestTags.plant(OFFLINE_PLANT))
                .performScrollTo()
                .performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(MiniHomeTestTags.SAVE).performScrollTo().performClick()
            compose.waitForIdle()

            val retry = compose.onNodeWithTag(MiniHomeTestTags.RETRY)
            retry.performScrollTo()
            retry.assertIsDisplayed()
            retry.performClick()
            compose.waitForIdle()

            val finalRaw = raw.filter { it.state.isCommittedRevision(Revision(2)) }
            val finalRoute = route.filter { it.state.isCommittedRevision(Revision(2)) }
            val finalDisplayed = displayed.filter { it.state.isCommittedRevision(Revision(2)) }
            assertEquals(
                "repository must fail once then return persisted revision 2",
                2,
                repository.saveCalls,
            )
            assertEquals(2, repository.saveRequests.size)
            val firstRequest = repository.saveRequests.first()
            val retryRequest = repository.saveRequests.last()
            assertEquals(firstRequest.operationId, retryRequest.operationId)
            assertEquals(firstRequest.lineageId, retryRequest.lineageId)
            assertEquals(1, finalRaw.size)
            assertEquals(1, finalRoute.size)
            assertEquals(1, finalDisplayed.size)
            assertTrue(finalRaw.single().sequence < finalRoute.single().sequence)
            assertTrue(finalRoute.single().sequence < finalDisplayed.single().sequence)
            listOf(finalRaw, finalRoute, finalDisplayed).forEach { events ->
                val outcome = (events.single().state as MiniHomeUiState.Viewing).exitOutcome
                assertEquals(firstRequest.operationId, outcome?.operationId)
                assertEquals(firstRequest.lineageId, outcome?.lineageId)
                assertEquals(OWNER_A, outcome?.owner)
            }
            val stages = observations.map { it.stage }
            assertEquals(1, stages.count { it == MiniHomeSaveActionStage.SCREEN_CALLBACK })
            assertEquals(1, stages.count { it == MiniHomeSaveActionStage.COROUTINE_ENTRY })
            assertEquals(2, stages.count { it == MiniHomeSaveActionStage.CONTROLLER_ENTRY })
            assertEquals(2, stages.count { it == MiniHomeSaveActionStage.SAVING_PUBLICATION })
        } finally {
            actionDiagnostic.close()
            displayedSubscription.close()
            routeSubscription.close()
            rawSubscription.close()
        }
    }

    @Test
    fun `callback replacement forwards Editing with a newer callback generation`() {
        // Given
        val installedSink = Todo18RenderedStateSink()
        val replacementSink = Todo18RenderedStateSink()
        val runtime = installRuntime(installedSink)
        var activeSink by mutableStateOf<RenderedStateSink>(installedSink)
        mount(runtime, OWNER_A) { activeSink }
        assertTrue(installedSink.currentDisplayedMiniHomeState()?.state is MiniHomeUiState.Viewing)
        val capture = replacementSink.startDiagnosticCapture(Todo18WaitId.OFFLINE_BEGIN_EDIT)
        val editingDisplayed = CompletableDeferred<Unit>()
        val subscription = replacementSink.subscribeToDisplayedMiniHomeStates {
            if (it.state is MiniHomeUiState.Editing) editingDisplayed.complete(Unit)
        }

        try {
            // When
            compose.runOnIdle { activeSink = replacementSink }
            compose.waitForIdle()
            compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().performClick()
            compose.waitForIdle()
            assertTrue(editingDisplayed.isCompleted)

            // Then
            assertTrue(installedSink.currentRawMiniHomeState()?.state !is MiniHomeUiState.Editing)
            assertTrue(
                installedSink.currentDisplayedMiniHomeState()?.state !is MiniHomeUiState.Editing
            )
            assertTrue(replacementSink.currentRawMiniHomeState()?.state is MiniHomeUiState.Editing)
            assertTrue(
                replacementSink.currentDisplayedMiniHomeState()?.state is MiniHomeUiState.Editing
            )
            val binding =
                capture.snapshot().pipeline.mapNotNull { it.runtimeBinding }.distinct().single()
            assertTrue(binding.callbackGeneration > binding.collectorGeneration + 1L)
        } finally {
            subscription.close()
            capture.close()
        }
    }

    @Test
    fun `owner mismatch publishes gated Loading instead of private Editing`() {
        // Given
        val sink = Todo18RenderedStateSink()
        val runtime = installRuntime(sink)
        val raw = mutableListOf<Todo18MiniHomeStateEvent>()
        val rawSubscription = sink.subscribeToRawMiniHomeStates(raw::add)

        try {
            // When
            mount(runtime, OWNER_B) { sink }

            // Then
            assertTrue(raw.none { it.state.owner == OWNER_A })
            assertEquals(
                MiniHomeUiState.Unavailable(OWNER_B),
                sink.currentDisplayedMiniHomeState()?.state,
            )
            compose.onNodeWithTag(MiniHomeTestTags.EDIT).assertDoesNotExist()
        } finally {
            rawSubscription.close()
        }
    }

    @Test
    fun `navigation disposal resumes with the replacement sink and newer generations`() {
        // Given
        val installedSink = Todo18RenderedStateSink()
        val replacementSink = Todo18RenderedStateSink()
        val runtime = installRuntime(installedSink)
        var activeSink by mutableStateOf<RenderedStateSink>(installedSink)
        mount(runtime, OWNER_A) { activeSink }
        compose.runOnIdle { navController.navigate(PlanteriorRoute.Home) }
        compose.waitForIdle()
        val capture = replacementSink.startDiagnosticCapture(Todo18WaitId.OFFLINE_BEGIN_EDIT)
        val editingDisplayed = CompletableDeferred<Unit>()
        val subscription = replacementSink.subscribeToDisplayedMiniHomeStates {
            if (it.state is MiniHomeUiState.Editing) editingDisplayed.complete(Unit)
        }

        try {
            // When
            compose.runOnIdle {
                activeSink = replacementSink
                navController.popBackStack()
            }
            compose.waitForIdle()
            compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().performClick()
            compose.waitForIdle()
            assertTrue(editingDisplayed.isCompleted)

            // Then
            val snapshot = capture.snapshot()
            assertTrue(
                installedSink.currentDisplayedMiniHomeState()?.state !is MiniHomeUiState.Editing
            )
            assertTrue(replacementSink.currentRawMiniHomeState()?.state is MiniHomeUiState.Editing)
            assertTrue(
                replacementSink.currentDisplayedMiniHomeState()?.state is MiniHomeUiState.Editing
            )
            assertEquals(
                1,
                snapshot.pipeline.mapNotNull { it.controllerIdentity }.distinct().size,
            )
            val binding = snapshot.pipeline.mapNotNull { it.runtimeBinding }.distinct().single()
            assertTrue(binding.disposeGeneration > 0L)
            assertTrue(binding.disposeGeneration < binding.attachGeneration)
            assertTrue(binding.attachGeneration < binding.collectorGeneration)
            assertTrue(binding.attachGeneration < binding.callbackGeneration)
        } finally {
            subscription.close()
            capture.close()
        }
    }

    private fun installRuntime(
        sink: Todo18RenderedStateSink,
        repository: MiniHomeRepository = HostMiniHomeRepository(),
    ) =
        AuthRuntimeDependencyOverrides(
                miniHomeRepository = repository,
                renderedStateSink = sink,
            )
            .also(Todo18DebugRuntimeDependencies::install)

    private fun mount(
        runtime: AuthRuntimeDependencyOverrides,
        owner: AccountId,
        sink: () -> RenderedStateSink?,
    ) {
        compose.setContent {
            PlanteriorTheme {
                navController = rememberNavController()
                PlanteriorNavHost(
                    navController = navController,
                    startRoute = PlanteriorRoute.MiniHome,
                    authRouteGuardEnabled = false,
                    miniHomeRepository = requireNotNull(runtime.miniHomeRepository),
                    miniHomeAuthOwnershipOverride = MiniHomeAuthOwnership.Authenticated(owner),
                    renderedStateSink = sink(),
                )
            }
        }
        compose.waitForIdle()
    }

    private inline fun <reified T : MiniHomeUiState> List<Todo18MiniHomeStateEvent>.countState() =
        count {
            it.state is T
        }

    private class HostMiniHomeRepository : MiniHomeRepository {
        override suspend fun load(): MiniHomeLoadResult =
            MiniHomeLoadResult.Ready(
                accountId = OWNER_A,
                committed =
                    MiniHomeLayout(
                        MiniHomeId("offline-navhost-home"),
                        "Offline host",
                        emptyList(),
                        Revision(1),
                        Instant.EPOCH,
                    ),
                plants = emptyList(),
                decorations = emptyList(),
                stale = false,
                pending = null,
            )

        override suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult =
            MiniHomeSaveResult.Forbidden

        override suspend fun abandon(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult =
            MiniHomeDiscardResult.Rejected
    }

    private class HostOfflineRetryRepository : MiniHomeRepository {
        var saveCalls = 0
            private set

        val saveRequests = mutableListOf<MiniHomeSaveRequest>()

        override suspend fun load(): MiniHomeLoadResult =
            MiniHomeLoadResult.Ready(
                accountId = OWNER_A,
                committed =
                    MiniHomeLayout(
                        MiniHomeId("offline-retry-home"),
                        "Offline retry host",
                        emptyList(),
                        Revision(1),
                        Instant.EPOCH,
                    ),
                plants = listOf(MiniHomePlantChoice(OFFLINE_PLANT, "Offline plant", null)),
                decorations = emptyList(),
                stale = false,
                pending = null,
            )

        override suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult {
            saveCalls += 1
            saveRequests += request
            return if (saveCalls == 1) {
                MiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK)
            } else {
                MiniHomeSaveResult.Saved(request.layout.copy(revision = Revision(2)))
            }
        }

        override suspend fun abandon(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult =
            MiniHomeDiscardResult.Rejected
    }

    private fun MiniHomeUiState.isCommittedRevision(revision: Revision): Boolean =
        this is MiniHomeUiState.Viewing && committed.revision == revision

    private companion object {
        val OWNER_A = AccountId("offline-owner-a")
        val OWNER_B = AccountId("offline-owner-b")
        val OFFLINE_PLANT = PersonalPlantId("offline-retry-plant")
    }
}
