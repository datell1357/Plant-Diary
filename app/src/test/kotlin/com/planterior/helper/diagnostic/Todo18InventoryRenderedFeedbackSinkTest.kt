package com.planterior.helper.diagnostic

import com.planterior.helper.Todo18InventoryFeedbackEvent
import com.planterior.helper.Todo18RenderedStateSink
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.shop.InventoryFeedback
import com.planterior.helper.feature.shop.InventoryFeedbackPresentationToken
import com.planterior.helper.feature.shop.InventoryItemAvailability
import com.planterior.helper.feature.shop.InventoryReceiptClaimant
import com.planterior.helper.feature.shop.InventoryReceiptId
import com.planterior.helper.feature.shop.InventorySection
import com.planterior.helper.feature.shop.InventorySnapshot
import com.planterior.helper.feature.shop.InventoryUiState
import com.planterior.helper.feature.shop.OwnedInventoryItem
import com.planterior.helper.inventory.Todo18InventoryCacheSettlement
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18InventoryRenderedFeedbackSinkTest {
    @Test
    fun `rendered diagnostic retains exact non stale feedback semantic facts`() {
        val sink = Todo18RenderedStateSink()
        val observations =
            mutableListOf<com.planterior.helper.inventory.Todo18InventorySettlementObservation>()
        sink.observeInventoryDiagnostics(observations::add)
        sink.armInventoryFeedback(SETTLEMENT)

        sink.onInventoryState(content(ITEM, stale = false))

        val observation = observations.single()
        assertEquals(
            com.planterior.helper.inventory.Todo18InventorySettlementStage.RENDERED_FEEDBACK,
            observation.stage,
        )
        assertEquals(InventoryFeedback.ACQUIRED, observation.feedback)
        assertEquals(false, observation.stale)
        assertTrue(ITEM in observation.ownedItemIds.orEmpty())
        assertEquals(SETTLEMENT, observation.settlement)
    }

    @Test
    fun `exact acquired route state emits one rendered feedback receipt and closes listener`() {
        val sink = Todo18RenderedStateSink()
        val observed = mutableListOf<Todo18InventoryFeedbackEvent>()
        val listener = sink.subscribeToInventoryFeedback(observed::add)
        sink.armInventoryFeedback(SETTLEMENT)

        sink.onInventoryState(content(ITEM))
        sink.onInventoryState(content(ITEM))
        listener.close()

        assertEquals(listOf(SETTLEMENT), observed.map { it.settlement })
        assertEquals(listOf(InventoryFeedback.ACQUIRED), observed.map { it.feedback })
        assertEquals(0, sink.primaryListenerCount())
    }

    @Test
    fun `wrong item cannot publish the armed rendered feedback receipt`() {
        val sink = Todo18RenderedStateSink()
        sink.armInventoryFeedback(SETTLEMENT)

        sink.onInventoryState(content(ItemId("wrong-item")))

        assertNull(sink.currentInventoryFeedback())
    }

    @Test
    fun `stale acquired route state cannot publish the armed rendered feedback receipt`() {
        val sink = Todo18RenderedStateSink()
        sink.armInventoryFeedback(SETTLEMENT)

        sink.onInventoryState(content(ITEM, stale = true))

        assertNull(sink.currentInventoryFeedback())
    }

    private fun content(ownedItem: ItemId, stale: Boolean = false) =
        InventoryUiState.Content(
            owner = OWNER,
            snapshot =
                InventorySnapshot(
                    accountId = OWNER,
                    catalog = emptyList(),
                    owned =
                        listOf(
                            OwnedInventoryItem(
                                itemId = ownedItem,
                                acquiredAt = Instant.EPOCH,
                                applied = false,
                                revision = Revision(1),
                                availability = InventoryItemAvailability.UNAVAILABLE,
                            )
                        ),
                    registeredPlantCount = 1,
                    loadedAt = Instant.EPOCH,
                    snapshotHash = "0".repeat(64),
                ),
            section = InventorySection.SHOP,
            category = null,
            feedback = InventoryFeedback.ACQUIRED,
            feedbackPresentationToken =
                InventoryFeedbackPresentationToken(
                    InventoryReceiptId("${OWNER.value}/${OPERATION.value}"),
                    InventoryReceiptClaimant("presentation", 1, 1),
                    1,
                ),
            stale = stale,
        )

    private companion object {
        val OWNER = AccountId("account")
        val ITEM = ItemId("item")
        val OPERATION = OperationId("operation")
        val SETTLEMENT = Todo18InventoryCacheSettlement(OWNER, ITEM, OPERATION)
    }
}
