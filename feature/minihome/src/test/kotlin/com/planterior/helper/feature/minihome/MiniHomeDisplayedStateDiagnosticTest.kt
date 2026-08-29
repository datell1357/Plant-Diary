package com.planterior.helper.feature.minihome

import com.planterior.helper.core.model.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MiniHomeDisplayedStateDiagnosticTest {
    @Test
    fun `diagnostic faults preserve the product callback and exact state`() {
        val state = MiniHomeUiState.Loading(AccountId("displayed-diagnostic-owner"))
        val events = mutableListOf<MiniHomeDiagnosticEvent>()
        var published: MiniHomeUiState? = null

        publishMiniHomeDisplayedState(
            state = state,
            diagnostic =
                MiniHomeDisplayedStateDiagnostic(
                    observer = { event ->
                        events += event
                        throw AssertionError("injected displayed diagnostic fault")
                    },
                    binding = binding(),
                ),
            publish = { published = state },
        )

        assertSame(state, published)
        assertEquals(
            listOf(
                MiniHomeDiagnosticEvent.DisplayedCallbackEntry::class,
                MiniHomeDiagnosticEvent.DisplayedCallbackReturn::class,
            ),
            events.map { it::class },
        )
    }

    @Test
    fun `diagnostic return hook preserves primary callback exception identity`() {
        val primary = IllegalStateException("primary displayed callback failure")
        val events = mutableListOf<MiniHomeDiagnosticEvent>()

        val actual =
            try {
                publishMiniHomeDisplayedState(
                    state = MiniHomeUiState.Loading(null),
                    diagnostic = MiniHomeDisplayedStateDiagnostic(events::add, binding()),
                    publish = { throw primary },
                )
                error("primary callback failure did not escape")
            } catch (failure: IllegalStateException) {
                failure
            }

        assertSame(primary, actual)
        assertEquals(2, events.size)
    }

    private fun binding() =
        MiniHomeRuntimeDiagnosticBinding(
            controllerIdentity = MiniHomeControllerIdentity(41),
            controllerEpoch = 1L,
            controllerGeneration = 1L,
            collectorGeneration = 2L,
            callbackGeneration = 3L,
            attachGeneration = 1L,
            disposeGeneration = 0L,
            lifecycleOwnerIdentity = "lifecycle-1",
            lifecycleState = "RESUMED",
            activityIdentity = "activity-1",
            navHostIdentity = "nav-host-1",
            callbackSinkIdentity = "sink-1",
        )
}
