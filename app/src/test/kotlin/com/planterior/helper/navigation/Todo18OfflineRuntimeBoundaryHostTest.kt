package com.planterior.helper.navigation

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.Todo18RenderedStateSink
import com.planterior.helper.auth.AuthRuntimeDependencyOverrides
import com.planterior.helper.auth.Todo18DebugRuntimeDependencies
import com.planterior.helper.auth.runtimeDiagnosticIdentity
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.diagnostic.Todo18WaitId
import com.planterior.helper.feature.minihome.MiniHomeAuthOwnership
import com.planterior.helper.feature.minihome.MiniHomeDiscardHandle
import com.planterior.helper.feature.minihome.MiniHomeDiscardResult
import com.planterior.helper.feature.minihome.MiniHomeLayout
import com.planterior.helper.feature.minihome.MiniHomeLoadResult
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomeSaveRequest
import com.planterior.helper.feature.minihome.MiniHomeSaveResult
import com.planterior.helper.feature.minihome.MiniHomeTestTags
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_MAX_SDK])
class Todo18OfflineRuntimeBoundaryHostTest {
    @get:Rule val compose = createComposeRule()

    @After
    fun clearInstalledRuntime() {
        Todo18DebugRuntimeDependencies.clear()
    }

    @Test
    fun `real host records every displayed Editing callback and sink boundary in order`() {
        // Given
        val sink = Todo18RenderedStateSink()
        val runtime =
            AuthRuntimeDependencyOverrides(
                miniHomeRepository = BoundaryRepository,
                renderedStateSink = sink,
            )
        Todo18DebugRuntimeDependencies.install(runtime)
        val capture = sink.startDiagnosticCapture(Todo18WaitId.OFFLINE_BEGIN_EDIT)
        compose.setContent {
            PlanteriorTheme {
                PlanteriorNavHost(
                    navController = rememberNavController(),
                    startRoute = PlanteriorRoute.MiniHome,
                    authRouteGuardEnabled = false,
                    miniHomeRepository = BoundaryRepository,
                    miniHomeAuthOwnershipOverride = MiniHomeAuthOwnership.Authenticated(OWNER),
                    renderedStateSink = runtime.renderedStateSink,
                )
            }
        }
        compose.waitForIdle()

        // When
        compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().performClick()
        compose.waitForIdle()

        // Then
        val snapshot = capture.snapshot()
        val observed = snapshot.pipeline.map { it.kind.name }
        capture.close()
        assertEquals(
            listOf(
                "ROUTE_STATE_OBSERVED",
                "DISPLAYED_CALLBACK_ENTRY",
                "DISPLAYED_SINK_ENTRY",
                "TASK1_PUBLICATION",
                "PRIMARY_DISPATCH_BEGIN",
                "PRIMARY_DISPATCH_RETURN",
                "DISPLAYED_SINK_RETURN",
                "DISPLAYED_CALLBACK_RETURN",
            ),
            observed.filter(EXPECTED_BOUNDARIES::contains),
        )
        val bindings = snapshot.pipeline.mapNotNull { it.runtimeBinding }
        assertEquals(5, bindings.size)
        assertEquals(1, bindings.distinct().size)
        val binding = bindings.distinct().single()
        assertTrue(binding.disposeGeneration < binding.attachGeneration)
        assertTrue(binding.attachGeneration < binding.collectorGeneration)
        assertTrue(binding.attachGeneration < binding.callbackGeneration)
        assertEquals("RESUMED", binding.lifecycleState)
        assertEquals(runtimeDiagnosticIdentity(sink), binding.callbackSinkIdentity)
        assertTrue(binding.activityIdentity.contains("@"))
        assertTrue(binding.navHostIdentity.contains("@"))
    }

    private object BoundaryRepository : MiniHomeRepository {
        override suspend fun load(): MiniHomeLoadResult =
            MiniHomeLoadResult.Ready(
                accountId = OWNER,
                committed =
                    MiniHomeLayout(
                        MiniHomeId("offline-runtime-boundary"),
                        "Offline runtime boundary",
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

    private companion object {
        val OWNER = AccountId("offline-runtime-owner")
        val EXPECTED_BOUNDARIES =
            setOf(
                "ROUTE_STATE_OBSERVED",
                "DISPLAYED_CALLBACK_ENTRY",
                "DISPLAYED_SINK_ENTRY",
                "TASK1_PUBLICATION",
                "PRIMARY_DISPATCH_BEGIN",
                "PRIMARY_DISPATCH_RETURN",
                "DISPLAYED_SINK_RETURN",
                "DISPLAYED_CALLBACK_RETURN",
            )
    }
}
