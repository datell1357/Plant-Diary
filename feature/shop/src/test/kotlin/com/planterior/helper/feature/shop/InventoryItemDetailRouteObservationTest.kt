package com.planterior.helper.feature.shop

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class InventoryItemDetailRouteObservationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `real detail route observes authoritative acquired feedback before consumption`() {
        val eligibleObserved = java.util.concurrent.atomic.AtomicBoolean()
        val repository = RouteRepository { eligibleObserved.get() }
        val ownership = InventoryAuthOwnership.Authenticated(OWNER)
        val observed = CopyOnWriteArrayList<InventoryUiState.Content>()

        compose.setContent {
            PlanteriorTheme {
                InventoryItemDetailRoute(
                    repository = repository,
                    authOwnership = ownership,
                    itemId = ITEM.id,
                    onBack = {},
                    onOpenMiniHome = {},
                    onStateObserved = { state ->
                        val content =
                            state as? InventoryUiState.Content ?: return@InventoryItemDetailRoute
                        observed += content
                        if (
                            content.feedback == InventoryFeedback.ACQUIRED &&
                                !content.stale &&
                                content.owner == OWNER &&
                                content.snapshot.owned.any { it.itemId == ITEM.id }
                        ) {
                            eligibleObserved.set(true)
                        }
                    },
                )
            }
        }

        compose
            .onNodeWithTag(InventoryTestTags.DETAIL_ACTION)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()

        val operation = requireNotNull(repository.operationId)
        val eligible = observed.single {
            it.feedback == InventoryFeedback.ACQUIRED &&
                it.snapshot.owned.any { owned -> owned.itemId == ITEM.id }
        }
        assertEquals(OWNER, eligible.owner)
        assertEquals(OWNER, eligible.snapshot.accountId)
        assertTrue(!eligible.stale)
        assertEquals("${OWNER.value}/${operation.value}", eligible.feedbackReceiptId?.value)
        assertEquals(listOf(true), repository.eligibleObservedBeforeAcknowledgement)
        assertEquals(1, repository.acknowledgements)
    }

    private class RouteRepository(private val wasEligibleObserved: () -> Boolean) :
        InventoryRepository {
        val eligibleObservedBeforeAcknowledgement = mutableListOf<Boolean>()
        var acknowledgements = 0
            private set

        var operationId: com.planterior.helper.core.model.OperationId? = null
            private set

        private var acquired = false
        private var claim: InventoryReceiptClaim? = null

        override suspend fun load(): InventoryLoadResult =
            InventoryLoadResult.Ready(
                SNAPSHOT.copy(
                    owned =
                        if (acquired) {
                            listOf(
                                OwnedInventoryItem(
                                    ITEM.id,
                                    NOW,
                                    applied = false,
                                    revision = Revision(1),
                                )
                            )
                        } else {
                            emptyList()
                        }
                ),
                stale = false,
                receiptCandidates = emptyList(),
            )

        override suspend fun acquire(request: InventoryAcquireRequest): InventoryAcquireResult {
            assertEquals(OWNER, request.accountId)
            assertEquals(ITEM.id, request.itemId)
            acquired = true
            operationId = request.operationId
            return InventoryAcquireResult.Success(
                InventoryOwnershipReceipt(
                    OWNER,
                    ITEM.id,
                    ITEM.revision,
                    Revision(1),
                    NOW,
                )
            )
        }

        override suspend fun claimForPresentation(
            receiptId: InventoryReceiptId,
            expected: InventoryReceiptPresentationExpectation?,
            claimant: InventoryReceiptClaimant,
        ): InventoryReceiptClaimResult =
            InventoryReceiptClaimResult.Claimed(
                    InventoryReceiptClaim(
                        requireNotNull(expected).receipt,
                        claimant,
                        rowVersion = 1,
                        leaseExpiresAtEpochMillis = Long.MAX_VALUE,
                    )
                )
                .also { claim = it.claim }

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult =
            InventoryReceiptPresentationResult.Presented(
                    claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
                )
                .also { this.claim = it.claim }

        override suspend fun acknowledgeReceipt(
            claim: InventoryReceiptClaim
        ): InventoryReceiptAcknowledgement {
            assertEquals(this.claim, claim)
            eligibleObservedBeforeAcknowledgement += wasEligibleObserved()
            acknowledgements += 1
            return InventoryReceiptAcknowledgement.ACKNOWLEDGED
        }
    }

    private companion object {
        val OWNER = AccountId("route-observation-owner")
        val NOW: Instant = Instant.parse("2026-08-30T00:00:00Z")
        val ITEM =
            InventoryItem(
                ItemId("route-observation-item"),
                "Observed item",
                "Route observation fixture",
                ItemCategory.DECORATION,
                "items/route-observation-item.png",
                null,
                Revision(1),
                NOW,
            )
        val SNAPSHOT =
            InventorySnapshot(
                OWNER,
                listOf(ITEM),
                emptyList(),
                registeredPlantCount = 1,
                loadedAt = NOW,
            )
    }
}
