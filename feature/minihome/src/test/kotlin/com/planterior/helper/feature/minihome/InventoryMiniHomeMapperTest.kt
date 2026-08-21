package com.planterior.helper.feature.minihome

import com.planterior.helper.core.data.AuthoritativeCatalogItem
import com.planterior.helper.core.data.AuthoritativeInventory
import com.planterior.helper.core.data.AuthoritativeInventoryAvailability
import com.planterior.helper.core.data.AuthoritativeOwnedCatalogSnapshot
import com.planterior.helper.core.data.AuthoritativeOwnedItem
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.Revision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InventoryMiniHomeMapperTest {
    @Test
    fun `fresh authoritative ownership restores available background and typed unavailable placeholders`() {
        val background =
            AuthoritativeCatalogItem(
                ItemId("fresh-background"),
                "새 배경",
                "새로 획득한 배경",
                ItemCategory.BACKGROUND,
                media("fresh-background", "a", 2),
                null,
                Revision(2),
                20,
            )
        val inventory =
            AuthoritativeInventory(
                contractVersion = 3,
                accountId = AccountId("account-a"),
                catalog = listOf(background),
                owned =
                    listOf(
                        AuthoritativeOwnedItem(
                            background.itemId,
                            21,
                            applied = false,
                            revision = Revision(1),
                            availability = AuthoritativeInventoryAvailability.AVAILABLE,
                            catalogSnapshot =
                                AuthoritativeOwnedCatalogSnapshot(
                                    background.name,
                                    background.category,
                                    background.mediaIdentity,
                                    background.revision,
                                ),
                        ),
                        AuthoritativeOwnedItem(
                            ItemId("deleted-decoration"),
                            10,
                            applied = true,
                            revision = Revision(4),
                            availability = AuthoritativeInventoryAvailability.UNAVAILABLE,
                            catalogSnapshot =
                                AuthoritativeOwnedCatalogSnapshot(
                                    "삭제된 장식",
                                    ItemCategory.DECORATION,
                                    media("deleted-decoration", "b", 3),
                                    Revision(3),
                                ),
                        ),
                        AuthoritativeOwnedItem(
                            ItemId("legacy-private"),
                            5,
                            applied = false,
                            revision = Revision(1),
                            availability = AuthoritativeInventoryAvailability.UNAVAILABLE,
                            catalogSnapshot = null,
                        ),
                    ),
                registeredPlantCount = 1,
                loadedAtEpochMillis = 22,
                partial = true,
                generation = 1,
                snapshotHash = "a".repeat(64),
            )

        val choices = inventory.miniHomeDecorationChoices()

        val restored = choices[0]
        assertEquals(ItemCategory.BACKGROUND, restored.category)
        assertEquals(background.mediaIdentity, restored.mediaIdentity)
        assertTrue(restored.availableForApplication)
        assertEquals("삭제된 장식", choices[1].displayName)
        assertFalse(choices[1].availableForApplication)
        assertEquals("사용할 수 없는 아이템", choices[2].displayName)
        assertNull(choices[2].category)
        assertFalse(choices[2].availableForApplication)
    }

    private fun media(itemId: String, digestCharacter: String, revision: Long) =
        CatalogMediaIdentity(
            path = "catalog-assets/$itemId/${digestCharacter.repeat(64)}.webp",
            sha256 = digestCharacter.repeat(64),
            byteSize = 4,
            mimeType = "image/webp",
            width = 1,
            height = 1,
            mediaRevision = Revision(revision),
        )
}
