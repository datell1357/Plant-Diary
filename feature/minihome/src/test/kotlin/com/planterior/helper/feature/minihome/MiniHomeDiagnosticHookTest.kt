package com.planterior.helper.feature.minihome

import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniHomeDiagnosticHookTest {
    @Test
    fun `begin-edit screen callback and Viewing-to-Editing controller transition occur once`() =
        runTest {
            val controller =
                MiniHomeController(
                    repository = ViewingRepository,
                    savedStateHandle = SavedStateHandle(),
                    operationIdFactory = { OperationId("diagnostic-edit") },
                )
            controller.start()
            val events = mutableListOf<MiniHomeDiagnosticEvent>()

            performMiniHomeBeginEdit(controller, events::add)

            assertEquals(1, events.filterIsInstance<MiniHomeDiagnosticEvent.BeginEditScreen>().size)
            val transition =
                events
                    .filterIsInstance<MiniHomeDiagnosticEvent.BeginEditControllerTransition>()
                    .single()
            assertTrue(transition.before is MiniHomeUiState.Viewing)
            assertTrue(transition.after is MiniHomeUiState.Editing)
            assertEquals(controller.diagnosticIdentity, transition.controllerIdentity)
            assertTrue(controller.state.value is MiniHomeUiState.Editing)
        }

    @Test
    fun `diagnostic callback failures do not alter action or route publication`() = runTest {
        val controller =
            MiniHomeController(
                ViewingRepository,
                SavedStateHandle(),
                operationIdFactory = { OperationId("fault-isolated-edit") },
            )
        controller.start()
        var publications = 0

        performMiniHomeBeginEdit(controller) { throw AssertionError("diagnostic") }
        publishMiniHomeRouteState(
            controller.diagnosticIdentity,
            controller.state.value,
            { throw IllegalStateException("diagnostic") },
        ) {
            publications += 1
        }

        assertTrue(controller.state.value is MiniHomeUiState.Editing)
        assertEquals(1, publications)
    }

    @Test
    fun `route audit occurs before Task1 publication with the same controller identity`() {
        val state = viewing()
        val order = mutableListOf<String>()
        val events = mutableListOf<MiniHomeDiagnosticEvent>()

        publishMiniHomeRouteState(
            controllerIdentity = MiniHomeControllerIdentity(91),
            state = state,
            diagnosticObserver = { event ->
                events += event
                order += "route"
            },
            publish = { order += "publish" },
        )

        assertEquals(listOf("route", "publish"), order)
        val audit = events.single() as MiniHomeDiagnosticEvent.RouteStateAudit
        assertEquals(MiniHomeControllerIdentity(91), audit.controllerIdentity)
        assertTrue(audit.state is MiniHomeUiState.Viewing)
    }

    private object ViewingRepository : MiniHomeRepository {
        override suspend fun load(): MiniHomeLoadResult =
            MiniHomeLoadResult.Ready(
                accountId = OWNER,
                committed = viewing().committed,
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
        val OWNER = AccountId("diagnostic-owner")

        fun viewing() =
            MiniHomeUiState.Viewing(
                committed =
                    MiniHomeLayout(
                        id = MiniHomeId("diagnostic-home"),
                        name = "Diagnostic home",
                        placements = emptyList(),
                        revision = Revision(1),
                        updatedAt = Instant.EPOCH,
                    ),
                plants = emptyList(),
                decorations = emptyList(),
                stale = false,
                owner = OWNER,
            )
    }
}
