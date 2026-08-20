package com.planterior.helper.core.data

import com.planterior.helper.core.model.AccountId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AuthoritativeMiniHomeLayoutReaderTest {
    @Test
    fun `schema complete revision two layout parses exactly`() = runTest {
        val reader =
            AuthoritativeMiniHomeLayoutReader(
                MiniHomeLayoutCallable {
                    mapOf(
                        "kind" to "present",
                        "ownerUid" to "account-a",
                        "generation" to 2,
                        "miniHomeId" to "home-a",
                        "name" to "revision two",
                        "placedPlantCount" to 1,
                        "placementCount" to 2,
                        "revision" to 2,
                        "expectedRevision" to 1,
                        "idempotencyKey" to "operation-revision-two",
                        "requestHash" to "a".repeat(64),
                        "updatedAtEpochMillis" to 2000,
                        "placements" to
                            listOf(
                                placement("plant-placement", 0, plantId = "plant-a"),
                                placement("decor-placement", 1, itemId = "decor-a"),
                            ),
                    )
                }
            )

        val result = reader.read(AccountId("account-a"))

        val present = result as AuthoritativeMiniHomeLayoutRead.Present
        assertEquals(2, present.layout.revision)
        assertEquals(
            listOf("plant-placement", "decor-placement"),
            present.layout.placements.map { it.id },
        )
        assertEquals(1, present.layout.placedPlantCount)
    }

    @Test
    fun `missing and zero placement layouts remain distinct authoritative results`() = runTest {
        val missing =
            AuthoritativeMiniHomeLayoutReader(
                    MiniHomeLayoutCallable {
                        mapOf(
                            "kind" to "missing",
                            "ownerUid" to "account-a",
                            "generation" to 4,
                            "tombstoneId" to "deletion-generation-four",
                            "updatedAtEpochMillis" to 4000,
                        )
                    }
                )
                .read(AccountId("account-a"))
        assertEquals(
            AuthoritativeMiniHomeLayoutRead.Missing(
                AccountId("account-a"),
                4,
                "deletion-generation-four",
                4000,
            ),
            missing,
        )

        val zero =
            AuthoritativeMiniHomeLayoutReader(
                    MiniHomeLayoutCallable {
                        mapOf(
                            "kind" to "present",
                            "ownerUid" to "account-a",
                            "generation" to 3,
                            "miniHomeId" to "home-a",
                            "name" to "empty room",
                            "placedPlantCount" to 0,
                            "placementCount" to 0,
                            "revision" to 3,
                            "expectedRevision" to 2,
                            "idempotencyKey" to "operation-empty-room",
                            "requestHash" to "b".repeat(64),
                            "updatedAtEpochMillis" to 3000,
                            "placements" to emptyList<Map<String, Any?>>(),
                        )
                    }
                )
                .read(AccountId("account-a")) as AuthoritativeMiniHomeLayoutRead.Present
        assertEquals(emptyList<Any>(), zero.layout.placements)
        assertEquals(0, zero.layout.placedPlantCount)
    }

    @Test
    fun `partial mismatched and cross-owner responses fail before becoming cacheable`() = runTest {
        val malformed =
            listOf(
                mapOf(
                    "kind" to "missing",
                    "ownerUid" to "account-a",
                    "generation" to 0,
                    "tombstoneId" to "initial-missing",
                    "updatedAtEpochMillis" to 0,
                ),
                completeResponse(
                    placementCount = 2,
                    placements = listOf(placement("only-row", 0, plantId = "plant-a")),
                ),
                completeResponse(
                    placementCount = 1,
                    placements =
                        listOf(placement("wrong-revision", 0, plantId = "plant-a", revision = 1)),
                ),
                completeResponse(ownerUid = "account-b"),
                completeResponse(
                    placementCount = 1,
                    placements = listOf(placement("missing-last-row", 1, plantId = "plant-a")),
                ),
            )
        malformed.forEach { response ->
            assertSuspendThrows<InconsistentMiniHomeLayoutException> {
                AuthoritativeMiniHomeLayoutReader(MiniHomeLayoutCallable { response })
                    .read(AccountId("account-a"))
            }
        }
    }

    @Test
    fun `call cancellation propagates without conversion`() = runTest {
        val reader =
            AuthoritativeMiniHomeLayoutReader(
                MiniHomeLayoutCallable { throw CancellationException("owner switched") }
            )
        val error =
            assertSuspendThrows<CancellationException> { reader.read(AccountId("account-a")) }
        assertEquals("owner switched", error.message)
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

    private fun completeResponse(
        ownerUid: String = "account-a",
        placementCount: Int = 1,
        placements: List<Map<String, Any?>> =
            listOf(placement("placement-a", 0, plantId = "plant-a")),
    ): Map<String, Any?> =
        mapOf(
            "kind" to "present",
            "ownerUid" to ownerUid,
            "generation" to 2,
            "miniHomeId" to "home-a",
            "name" to "room",
            "placedPlantCount" to 1,
            "placementCount" to placementCount,
            "revision" to 2,
            "expectedRevision" to 1,
            "idempotencyKey" to "operation-revision-two",
            "requestHash" to "c".repeat(64),
            "updatedAtEpochMillis" to 2000,
            "placements" to placements,
        )

    private companion object {
        fun placement(
            id: String,
            zIndex: Int,
            plantId: String? = null,
            itemId: String? = null,
            revision: Int = 2,
        ): Map<String, Any?> =
            mapOf(
                "placementId" to id,
                "ownerUid" to "account-a",
                "miniHomeId" to "home-a",
                "layoutRevision" to revision,
                "plantId" to plantId,
                "itemId" to itemId,
                "normalizedX" to if (zIndex == 0) 0.1 else 0.3,
                "normalizedY" to 0.125,
                "zIndex" to zIndex,
                "revision" to revision,
                "expectedRevision" to revision - 1,
                "idempotencyKey" to "operation-revision-two",
                "updatedAtEpochMillis" to 2000,
            )
    }
}
