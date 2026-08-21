package com.planterior.helper.feature.shop

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class InventoryPolicyTest {
    @Test
    fun `catalog entries expose free registered plant owned and applied states without currency`() {
        val snapshot =
            InventorySnapshot(
                accountId = AccountId("account-a"),
                catalog =
                    listOf(
                        item(
                            "lamp",
                            ItemCategory.DECORATION,
                            AcquisitionCondition.REGISTERED_PLANT,
                        ),
                        item("wall", ItemCategory.BACKGROUND, null),
                    ),
                owned =
                    listOf(
                        OwnedInventoryItem(
                            ItemId("wall"),
                            Instant.parse("2026-08-12T00:00:00Z"),
                            applied = true,
                            Revision(2),
                        )
                    ),
                registeredPlantCount = 0,
                loadedAt = Instant.parse("2026-08-12T01:00:00Z"),
            )

        val entries = InventoryPolicy.shopEntries(snapshot, null)

        assertEquals(listOf("wall", "lamp"), entries.map { it.id.value })
        assertEquals(AcquisitionEligibility.ALREADY_OWNED, entries[0].eligibility)
        assertEquals(true, entries[0].applied)
        assertEquals(
            AcquisitionEligibility.CONDITION_NOT_MET,
            entries[1].eligibility,
        )
        assertEquals("무료 획득", InventoryPolicy.conditionLabel(entries[0].item!!))
        assertEquals("식물 1개 등록", InventoryPolicy.conditionLabel(entries[1].item!!))
    }

    @Test
    fun `category filter preserves background furniture decoration product order`() {
        val snapshot =
            InventorySnapshot(
                AccountId("account-a"),
                listOf(
                    item("decoration", ItemCategory.DECORATION),
                    item("furniture", ItemCategory.FURNITURE),
                    item("background", ItemCategory.BACKGROUND),
                ),
                emptyList(),
                1,
                Instant.EPOCH,
            )
        assertEquals(
            listOf("furniture"),
            InventoryPolicy.shopEntries(snapshot, ItemCategory.FURNITURE).map { it.id.value },
        )
        assertEquals(
            listOf("background", "furniture", "decoration"),
            InventoryPolicy.shopEntries(snapshot, null).map { it.id.value },
        )
    }

    @Test
    fun `shop never contains unavailable ownership while warehouse keeps a typed searchable section`() {
        val public = item("public", ItemCategory.FURNITURE)
        val deleted =
            OwnedInventoryItem(
                ItemId("deleted"),
                Instant.EPOCH,
                applied = true,
                revision = Revision(2),
                availability = InventoryItemAvailability.UNAVAILABLE,
                catalogSnapshot =
                    OwnedCatalogSnapshot(
                        "삭제된 장식",
                        ItemCategory.DECORATION,
                        "catalog-assets/deleted/preview.webp",
                        Revision(1),
                    ),
            )
        val privateLegacy =
            OwnedInventoryItem(
                ItemId("private-legacy"),
                Instant.EPOCH,
                applied = false,
                revision = Revision(1),
                availability = InventoryItemAvailability.UNAVAILABLE,
            )
        val snapshot =
            InventorySnapshot(
                AccountId("account-a"),
                listOf(public),
                listOf(deleted, privateLegacy),
                0,
                Instant.EPOCH,
                partial = true,
            )

        assertEquals(
            listOf("public"),
            InventoryPolicy.shopEntries(snapshot, null).map { it.id.value },
        )
        assertEquals(
            emptyList<String>(),
            InventoryPolicy.shopEntries(snapshot, ItemCategory.DECORATION).map { it.id.value },
        )
        assertEquals(
            emptyList<String>(),
            InventoryPolicy.shopEntries(snapshot, null, "삭제").map { it.id.value },
        )
        assertEquals(
            listOf("deleted"),
            InventoryPolicy.warehouseEntries(snapshot, ItemCategory.DECORATION).map { it.id.value },
        )
        assertEquals(
            listOf("deleted"),
            InventoryPolicy.warehouseEntries(snapshot, null, "삭제").map { it.id.value },
        )
        assertEquals(true, InventoryPolicy.warehouseEntries(snapshot, null).all { it.unavailable })
    }

    private fun item(
        id: String,
        category: ItemCategory,
        condition: AcquisitionCondition? = null,
    ) =
        InventoryItem(
            ItemId(id),
            id,
            "$id 설명",
            category,
            "catalog-assets/$id/preview.webp",
            condition,
            Revision(1),
            Instant.EPOCH,
        )
}
