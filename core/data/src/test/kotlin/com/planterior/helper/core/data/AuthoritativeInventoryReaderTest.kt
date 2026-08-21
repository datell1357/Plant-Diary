package com.planterior.helper.core.data

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.Revision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AuthoritativeInventoryReaderTest {
    @Test
    fun `snapshot hash matches the cross-runtime canonical vector`() {
        assertEquals(
            "e833d614de9ea3d590941a196d4704e46c9eab207622311d6a3a1fd1215e0759",
            authoritativeInventorySnapshotHash(
                AccountId("account-a"),
                emptyList(),
                emptyList(),
                0,
                false,
            ),
        )
    }

    @Test
    fun `complete mixed inventory parses public catalog and unavailable placeholders exactly`() =
        runTest {
            val result =
                AuthoritativeInventoryReader(InventoryCallable { completeResponse() })
                    .read(AccountId("account-a"))

            assertEquals(3, result.contractVersion)
            assertEquals(9L, result.generation)
            assertEquals(listOf("public-background"), result.catalog.map { it.itemId.value })
            assertEquals(ItemCategory.BACKGROUND, result.catalog.single().category)
            assertEquals(
                listOf("public-background", "deleted-item", "legacy-item"),
                result.owned.map { it.itemId.value },
            )
            assertEquals(
                AuthoritativeInventoryAvailability.UNAVAILABLE,
                result.owned[1].availability,
            )
            assertEquals("삭제된 장식", result.owned[1].catalogSnapshot?.name)
            assertNull(result.owned[2].catalogSnapshot)
            assertEquals(true, result.partial)
        }

    @Test
    fun `missing extra malformed version and inconsistent availability are rejected`() = runTest {
        val complete = completeResponse()
        val malformed =
            listOf(
                complete - "catalog",
                complete + ("catalogItems" to complete.getValue("catalog")),
                complete + ("contractVersion" to 2),
                complete + ("snapshotHash" to "0".repeat(64)),
                complete + ("registeredPlantCount" to 201),
                complete + ("ownerUid" to "account-b"),
                complete +
                    ("owned" to
                        listOf(
                            owned(
                                "missing-public",
                                "AVAILABLE",
                                snapshot = null,
                            )
                        )),
                complete + ("partial" to false),
                complete +
                    ("catalog" to
                        listOf(
                            catalog("duplicate"),
                            catalog("duplicate"),
                        )),
                complete +
                    ("owned" to
                        listOf(
                            owned(
                                "deleted-item",
                                "UNAVAILABLE",
                                snapshot = mapOf("name" to "partial"),
                            )
                        )),
            )
        malformed.forEach { response ->
            assertSuspendThrows<InconsistentInventoryException> {
                AuthoritativeInventoryReader(InventoryCallable { response })
                    .read(AccountId("account-a"))
            }
        }
    }

    @Test
    fun `nested missing and extra fields are rejected`() = runTest {
        val complete = completeResponse()
        val catalog = catalog("public-background")
        val owned = owned("deleted-item", "UNAVAILABLE", snapshot())
        val malformed =
            listOf(
                complete + ("catalog" to listOf(catalog - "mediaIdentity")),
                complete + ("catalog" to listOf(catalog + ("publicationState" to "PUBLIC"))),
                complete + ("owned" to listOf(owned - "availability")),
                complete + ("owned" to listOf(owned + ("quantity" to 1))),
                complete +
                    ("owned" to
                        listOf(
                            owned + ("catalogSnapshot" to (snapshot() + ("description" to "extra")))
                        )),
            )
        malformed.forEach { response ->
            assertSuspendThrows<InconsistentInventoryException> {
                AuthoritativeInventoryReader(InventoryCallable { response })
                    .read(AccountId("account-a"))
            }
        }
    }

    @Test
    fun `call cancellation propagates for account switch`() = runTest {
        val reader =
            AuthoritativeInventoryReader(
                InventoryCallable { throw CancellationException("account switched") }
            )
        val error =
            assertSuspendThrows<CancellationException> {
                reader.read(AccountId("account-a"))
            }
        assertEquals("account switched", error.message)
    }

    private suspend inline fun <reified T : Throwable> assertSuspendThrows(
        crossinline block: suspend () -> Unit
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        fail("Expected ${T::class.java.simpleName}")
        error("unreachable")
    }

    private fun completeResponse(): Map<String, Any?> {
        val catalog = listOf(catalog("public-background"))
        val owned =
            listOf(
                owned("public-background", "AVAILABLE", snapshot("public-background")),
                owned("deleted-item", "UNAVAILABLE", snapshot()),
                owned("legacy-item", "UNAVAILABLE", null),
            )
        val catalogModels =
            listOf(
                AuthoritativeCatalogItem(
                    ItemId("public-background"),
                    "공개 배경",
                    "공개된 무료 배경",
                    ItemCategory.BACKGROUND,
                    mediaIdentity("public-background"),
                    null,
                    Revision(3),
                    1_787_155_200_000,
                )
            )
        val ownedModels =
            listOf(
                authoritativeOwned(
                    "public-background",
                    AuthoritativeInventoryAvailability.AVAILABLE,
                ),
                authoritativeOwned("deleted-item", AuthoritativeInventoryAvailability.UNAVAILABLE),
                AuthoritativeOwnedItem(
                    ItemId("legacy-item"),
                    1_787_155_200_000,
                    false,
                    Revision(4),
                    AuthoritativeInventoryAvailability.UNAVAILABLE,
                    null,
                ),
            )
        return mapOf(
            "contractVersion" to 3,
            "ownerUid" to "account-a",
            "catalog" to catalog,
            "owned" to owned,
            "registeredPlantCount" to 1,
            "loadedAtEpochMillis" to 1_787_155_200_000,
            "partial" to true,
            "inventoryGeneration" to 9,
            "snapshotHash" to
                authoritativeInventorySnapshotHash(
                    AccountId("account-a"),
                    catalogModels,
                    ownedModels,
                    1,
                    true,
                ),
        )
    }

    private fun authoritativeOwned(
        id: String,
        availability: AuthoritativeInventoryAvailability,
    ) =
        AuthoritativeOwnedItem(
            ItemId(id),
            1_787_155_200_000,
            id == "deleted-item",
            Revision(4),
            availability,
            AuthoritativeOwnedCatalogSnapshot(
                if (id == "deleted-item") "삭제된 장식" else "공개 배경",
                if (id == "deleted-item") ItemCategory.DECORATION else ItemCategory.BACKGROUND,
                mediaIdentity(id),
                Revision(3),
            ),
        )

    private fun catalog(id: String): Map<String, Any?> =
        mapOf(
            "itemId" to id,
            "name" to "공개 배경",
            "description" to "공개된 무료 배경",
            "category" to "BACKGROUND",
            "mediaIdentity" to mediaMap(id),
            "acquisitionCondition" to null,
            "revision" to 3,
            "updatedAtEpochMillis" to 1_787_155_200_000,
        )

    private fun owned(
        id: String,
        availability: String,
        snapshot: Map<String, Any?>?,
    ): Map<String, Any?> =
        mapOf(
            "itemId" to id,
            "acquiredAtEpochMillis" to 1_787_155_200_000,
            "applied" to (id == "deleted-item"),
            "revision" to 4,
            "availability" to availability,
            "catalogSnapshot" to snapshot,
        )

    private fun mediaIdentity(id: String) =
        CatalogMediaIdentity(
            "catalog-assets/$id/${"a".repeat(64)}.webp",
            "a".repeat(64),
            4,
            "image/webp",
            1,
            1,
            Revision(1),
        )

    private fun mediaMap(id: String): Map<String, Any?> =
        mapOf(
            "path" to "catalog-assets/$id/${"a".repeat(64)}.webp",
            "sha256" to "a".repeat(64),
            "byteSize" to 4,
            "mimeType" to "image/webp",
            "width" to 1,
            "height" to 1,
            "mediaRevision" to 1,
        )

    private fun snapshot(id: String = "deleted-item"): Map<String, Any?> =
        mapOf(
            "name" to if (id == "deleted-item") "삭제된 장식" else "공개 배경",
            "category" to if (id == "deleted-item") "DECORATION" else "BACKGROUND",
            "mediaIdentity" to mediaMap(id),
            "catalogRevision" to 3,
        )
}
