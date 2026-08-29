package com.planterior.helper.feature.minihome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MiniHomeRouteStateObserverTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `observer publishes Loading to Viewing target exactly once`() {
        val controller = controller()
        val observed = mutableListOf<MiniHomeUiState>()
        val diagnostics = mutableListOf<MiniHomeDiagnosticEvent>()
        compose.setContent {
            MiniHomeRouteStateObserver(controller, observed::add, diagnostics::add)
        }
        assertEquals(1, observed.count { it is MiniHomeUiState.Loading })

        compose.runOnIdle { runBlocking { controller.start() } }
        compose.waitForIdle()

        assertTrue(controller.state.value is MiniHomeUiState.Viewing)
        assertEquals(1, observed.count { it is MiniHomeUiState.Viewing })
        assertEquals(
            listOf("Loading", "Viewing"),
            diagnostics.filterIsInstance<MiniHomeDiagnosticEvent.RouteStateAudit>().map {
                it.state::class.simpleName
            },
        )
    }

    @Test
    fun `observer publishes controller begin-edit target exactly once`() {
        val controller = controller()
        runBlocking { controller.start() }
        val observed = mutableListOf<MiniHomeUiState>()
        val diagnostics = mutableListOf<MiniHomeDiagnosticEvent>()
        compose.setContent {
            MiniHomeRouteStateObserver(controller, observed::add, diagnostics::add)
        }
        assertEquals(1, observed.count { it is MiniHomeUiState.Viewing })

        compose.runOnIdle { controller.beginEditing(diagnostics::add) }
        compose.waitForIdle()

        val transition =
            diagnostics
                .filterIsInstance<MiniHomeDiagnosticEvent.BeginEditControllerTransition>()
                .single()
        assertTrue(transition.after is MiniHomeUiState.Editing)
        assertEquals(1, observed.count { it is MiniHomeUiState.Editing })
        assertEquals(
            listOf("Viewing", "Editing"),
            diagnostics.filterIsInstance<MiniHomeDiagnosticEvent.RouteStateAudit>().map {
                it.state::class.simpleName
            },
        )
    }

    @Test
    fun `observer disposal cancels publication before the next controller transition`() {
        val controller = controller()
        runBlocking { controller.start() }
        val observed = mutableListOf<MiniHomeUiState>()
        var mounted by mutableStateOf(true)
        compose.setContent {
            if (mounted) {
                MiniHomeRouteStateObserver(controller, observed::add, null)
            }
        }
        assertEquals(1, observed.count { it is MiniHomeUiState.Viewing })

        mounted = false
        compose.waitForIdle()
        compose.runOnIdle { controller.beginEditing() }

        assertTrue(controller.state.value is MiniHomeUiState.Editing)
        assertEquals(0, observed.count { it is MiniHomeUiState.Editing })
    }

    @Test
    fun `observer replacement forwards the next state only to the current callback`() {
        val controller = controller()
        runBlocking { controller.start() }
        val first = mutableListOf<MiniHomeUiState>()
        val replacement = mutableListOf<MiniHomeUiState>()
        val firstCallback: (MiniHomeUiState) -> Unit = { observed -> first += observed }
        val replacementCallback: (MiniHomeUiState) -> Unit = { observed -> replacement += observed }
        var replaced by mutableStateOf(false)
        compose.setContent {
            MiniHomeRouteStateObserver(
                controller = controller,
                onRawStateObserved = if (replaced) replacementCallback else firstCallback,
                diagnosticObserver = null,
            )
        }
        assertEquals(1, first.count { it is MiniHomeUiState.Viewing })

        replaced = true
        compose.waitForIdle()
        compose.runOnIdle { controller.beginEditing() }
        compose.waitForIdle()

        assertEquals(0, first.count { it is MiniHomeUiState.Editing })
        assertEquals(1, replacement.count { it is MiniHomeUiState.Editing })
    }

    private fun controller() =
        MiniHomeController(
            repository = RouteTestRepository(),
            savedStateHandle = SavedStateHandle(),
        )
}

internal class RouteTestRepository : MiniHomeRepository {
    var loadCompleted = false
        private set

    override suspend fun load(): MiniHomeLoadResult =
        MiniHomeLoadResult.Ready(
                accountId = ROUTE_OWNER,
                committed =
                    MiniHomeLayout(
                        id = MiniHomeId("route-observer-home"),
                        name = "Route observer home",
                        placements = emptyList(),
                        revision = Revision(1),
                        updatedAt = Instant.EPOCH,
                    ),
                plants = emptyList(),
                decorations = emptyList(),
                stale = false,
                pending = null,
            )
            .also { loadCompleted = true }

    override suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult =
        MiniHomeSaveResult.Forbidden

    override suspend fun abandon(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult =
        MiniHomeDiscardResult.Rejected

    private companion object {
        val ROUTE_OWNER = AccountId("route-observer-owner")
    }
}
