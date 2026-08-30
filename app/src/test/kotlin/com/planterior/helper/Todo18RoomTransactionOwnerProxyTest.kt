package com.planterior.helper

import com.planterior.helper.core.database.RoomTransactionOwner
import com.planterior.helper.core.database.RoomTransactionOwnerObservation
import com.planterior.helper.diagnostic.attachTodo18RoomTransactionOwnerListener
import com.planterior.helper.diagnostic.roomTransactionOwnerDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Test

class Todo18RoomTransactionOwnerProxyTest {
    @Test
    fun `runtime created before listener observes only the attached fixed schedule`() {
        val diagnostics = roomTransactionOwnerDiagnostics()
        val observations = mutableListOf<RoomTransactionOwnerObservation>()

        diagnostics.observeImmediate(RoomTransactionOwner.ANALYTICS_ENQUEUE) { "off-before" }
        val attachment = attachTodo18RoomTransactionOwnerListener(observations::add)
        assertEquals(
            "on",
            diagnostics.observeImmediate(RoomTransactionOwner.ANALYTICS_ENQUEUE) { "on" },
        )
        attachment.close()
        diagnostics.observeImmediate(RoomTransactionOwner.ANALYTICS_ENQUEUE) { "off-after" }

        assertEquals(2, observations.size)
        assertEquals(observations[0].token, observations[1].token)
        assertEquals(RoomTransactionOwner.ANALYTICS_ENQUEUE, observations[0].owner)
        assertEquals(true, observations[0] is RoomTransactionOwnerObservation.Began)
        assertEquals(true, observations[1] is RoomTransactionOwnerObservation.Returned)
    }
}
