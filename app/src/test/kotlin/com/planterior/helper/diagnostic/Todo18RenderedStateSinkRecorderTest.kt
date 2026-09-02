package com.planterior.helper.diagnostic

import com.planterior.helper.Todo18MiniHomeShareStateEvent
import com.planterior.helper.Todo18RenderedStateSink
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.MiniHomeLayout
import com.planterior.helper.feature.minihome.MiniHomeUiState
import com.planterior.helper.feature.registration.RegistrationContent
import com.planterior.helper.feature.registration.RegistrationDraft
import com.planterior.helper.feature.registration.RegistrationUiState
import com.planterior.helper.feature.share.MiniHomeShareTarget
import com.planterior.helper.feature.share.MiniHomeShareUiState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18RenderedStateSinkRecorderTest {
    @Test
    fun `fresh sink starts with null currents sequence zero and no listeners`() {
        val sink = Todo18RenderedStateSink()

        assertEquals(0L, sink.sequenceValue())
        assertEquals(0, sink.primaryListenerCount())
        assertNull(sink.currentRawMiniHomeState())
        assertNull(sink.currentDisplayedMiniHomeState())
        assertNull(sink.currentRegistrationState())
        assertNull(sink.currentMiniHomeShareState())
        assertTrue(sink.isFresh())
    }

    @Test
    fun `typed share states publish monotonically and settle on the current Ready state`() {
        val sink = Todo18RenderedStateSink()
        val events = mutableListOf<Todo18MiniHomeShareStateEvent>()
        val subscription = sink.subscribeToMiniHomeShareStates(events::add)
        val loading = MiniHomeShareUiState.Loading(AccountId("share-owner"))
        val ready =
            MiniHomeShareUiState.Ready(
                MiniHomeShareTarget(
                    owner = AccountId("share-owner"),
                    committed =
                        MiniHomeLayout(
                            MiniHomeId("share-home"),
                            "공유 미니홈",
                            emptyList(),
                            Revision(1),
                            Instant.EPOCH,
                        ),
                    plants = emptyList(),
                    decorations = emptyList(),
                )
            )

        sink.onMiniHomeShareState(loading)
        sink.onMiniHomeShareState(ready)

        assertEquals(listOf(1L, 2L), events.map(Todo18MiniHomeShareStateEvent::sequence))
        assertSame(ready, sink.currentMiniHomeShareState()?.state)
        assertTrue(sink.currentMiniHomeShareState()?.state is MiniHomeShareUiState.Ready)
        subscription.close()
        assertEquals(0, sink.primaryListenerCount())
    }

    @Test
    fun `one and two primary subscribers receive once and close independently`() {
        val sink = Todo18RenderedStateSink()
        var firstCount = 0
        var secondCount = 0
        val first = sink.subscribeToRawMiniHomeStates { firstCount += 1 }
        val second = sink.subscribeToRawMiniHomeStates { secondCount += 1 }

        sink.onMiniHomeRawState(MiniHomeUiState.Loading(AccountId("owner")))
        first.close()
        sink.onMiniHomeRawState(MiniHomeUiState.Loading(AccountId("owner")))
        second.close()

        assertEquals(1, firstCount)
        assertEquals(2, secondCount)
        assertEquals(0, sink.primaryListenerCount())
    }

    @Test
    fun `primary dispatch records current before after state identity and selected content`() {
        val sink = Todo18RenderedStateSink()
        val capture = sink.startDiagnosticCapture(Todo18WaitId.REGISTRATION_SELECT_CONTENT)
        val selected = PlantContentId("species-monstera")
        val state =
            RegistrationUiState.Editing(
                RegistrationDraft(
                    plantId = PersonalPlantId("plant"),
                    operationId = null,
                    name = "Monstera",
                    selectedContent = RegistrationContent(selected, "Monstera"),
                    photo = null,
                    lastWateredDate = null,
                )
            )
        val subscription = sink.subscribeToRegistrationStates {}

        sink.onRegistrationState(state)
        subscription.close()
        val snapshot = capture.snapshot()
        capture.close()

        assertEquals(
            listOf(Todo18StateDispatchPhase.BEGIN, Todo18StateDispatchPhase.RETURN),
            snapshot.stateDispatches.map(Todo18StateDispatchRecord::phase),
        )
        assertNull(snapshot.stateDispatches.first().currentBefore)
        assertEquals(
            Todo18StateKind.REGISTRATION_EDITING,
            snapshot.stateDispatches.last().currentAfter?.state,
        )
        assertEquals(selected, snapshot.stateDispatches.last().selectedContentId)
        assertEquals(1, snapshot.stateDispatches.first().primaryListenerCount)
        assertTrue(snapshot.stateDispatches.all(Todo18StateDispatchRecord::freshForWait))
        assertTrue(snapshot.stateDispatches.all(Todo18StateDispatchRecord::isolatedInstance))
    }

    @Test
    fun `second sink inherits no current listener sequence or active wait`() {
        val first = Todo18RenderedStateSink()
        val capture = first.startDiagnosticCapture(Todo18WaitId.CONFLICT_BEGIN_EDIT)
        val listener = first.subscribeToDisplayedMiniHomeStates {}
        first.onMiniHomeDisplayedState(MiniHomeUiState.Loading(AccountId("first-owner")))
        val second = Todo18RenderedStateSink()

        assertEquals(0L, second.sequenceValue())
        assertEquals(0, second.primaryListenerCount())
        assertNull(second.currentDisplayedMiniHomeState())
        assertFalse(second.hasActiveDiagnosticCapture())

        listener.close()
        capture.close()
    }
}
