package com.planterior.helper.core.data

import com.planterior.helper.core.model.AccountId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeMiniHomeSnapshotReaderTest {
    private val owner = AccountId("account-a")

    @Test
    fun `combined callable parses one exact owner scoped envelope`() = runTest {
        val response = envelope()
        val reader = AuthoritativeMiniHomeSnapshotReader(MiniHomeSnapshotCallable { response })

        val snapshot = reader.read(owner)

        assertEquals("a".repeat(64), snapshot.token)
        assertEquals(7L, snapshot.generation)
        assertEquals(1234L, snapshot.serverReadTimeEpochMillis)
        assertTrue(snapshot.layout is AuthoritativeMiniHomeLayoutRead.Missing)
        assertEquals(3L, snapshot.inventory.generation)
        assertEquals(emptyList<AuthoritativeMiniHomePlant>(), snapshot.plants)
    }

    @Test
    fun `combined callable rejects extra fields and nested owner mismatch`() = runTest {
        val extra = envelope() + ("legacyLayout" to null)
        val mismatched =
            envelope().toMutableMap().apply {
                this["inventory"] = inventory() + ("ownerUid" to "account-b")
            }

        for (payload in listOf(extra, mismatched)) {
            val reader = AuthoritativeMiniHomeSnapshotReader(MiniHomeSnapshotCallable { payload })
            val failure = runCatching { reader.read(owner) }.exceptionOrNull()
            assertTrue(failure is InconsistentMiniHomeSnapshotException)
        }
    }

    private fun envelope() =
        mapOf(
            "contractVersion" to 1,
            "ownerUid" to owner.value,
            "snapshotToken" to "a".repeat(64),
            "snapshotGeneration" to 7,
            "serverReadTimeEpochMillis" to 1234,
            "layout" to
                mapOf(
                    "kind" to "missing",
                    "ownerUid" to owner.value,
                    "generation" to 2,
                    "tombstoneId" to "initial-missing",
                    "updatedAtEpochMillis" to 1000,
                ),
            "inventory" to inventory(),
            "plants" to emptyList<Map<String, Any?>>(),
        )

    private fun inventory(): Map<String, Any> {
        val hash = authoritativeInventorySnapshotHash(owner, emptyList(), emptyList(), 0, false)
        return mapOf(
            "contractVersion" to INVENTORY_CONTRACT_VERSION,
            "ownerUid" to owner.value,
            "catalog" to emptyList<Map<String, Any>>(),
            "owned" to emptyList<Map<String, Any>>(),
            "registeredPlantCount" to 0,
            "loadedAtEpochMillis" to 1234,
            "partial" to false,
            "inventoryGeneration" to 3,
            "snapshotHash" to hash,
        )
    }
}
