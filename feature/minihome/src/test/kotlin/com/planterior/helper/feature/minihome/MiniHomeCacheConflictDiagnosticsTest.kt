package com.planterior.helper.feature.minihome

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class MiniHomeCacheConflictDiagnosticsTest {
    @After
    fun listenerIsClosed() {
        assertEquals(0, MiniHomeCacheConflictDiagnostics.listenerCount())
    }

    @Test
    fun `runtime and assertion observer faults do not suppress later observations`() {
        val delivered = mutableListOf<MiniHomeCacheDiagnosticStage>()
        var call = 0
        val installation = MiniHomeCacheConflictDiagnostics.install { observation ->
            when (++call) {
                1 -> throw IllegalStateException("observer-runtime")
                2 -> throw AssertionError("observer-assertion")
                else -> delivered += observation.stage
            }
        }

        try {
            MiniHomeCacheConflictDiagnostics.observe(
                observation(MiniHomeCacheDiagnosticStage.LAYOUT_APPLY)
            )
            MiniHomeCacheConflictDiagnostics.observe(
                observation(MiniHomeCacheDiagnosticStage.INVENTORY_APPLY)
            )
            MiniHomeCacheConflictDiagnostics.observe(
                observation(MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT)
            )
            assertEquals(listOf(MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT), delivered)
        } finally {
            installation.close()
        }
    }

    @Test
    fun `observer cancellation is rethrown by exact identity without a false terminal`() {
        val cancellation = CancellationException("observer-cancelled")
        val delivered = mutableListOf<MiniHomeCacheDiagnosticObservation>()
        val installation = MiniHomeCacheConflictDiagnostics.install { observation ->
            delivered += observation
            throw cancellation
        }

        try {
            val thrown =
                assertThrows(CancellationException::class.java) {
                    MiniHomeCacheConflictDiagnostics.observe(
                        observation(MiniHomeCacheDiagnosticStage.LAYOUT_APPLY)
                    )
                }
            assertSame(cancellation, thrown)
            assertEquals(1, delivered.size)
            assertEquals(MiniHomeCacheDiagnosticStage.LAYOUT_APPLY, delivered.single().stage)
        } finally {
            installation.close()
        }
    }

    @Test
    fun `every layout predicate is selected by one reversible canonical operand mutation`() {
        val base = layoutOperands()
        val mutations =
            linkedMapOf(
                MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_ACCOUNT to
                    ("watermark.candidate.accountId" to "other-owner"),
                MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_GENERATION to
                    ("watermark.candidate.generation" to "3"),
                MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_KIND to
                    ("watermark.candidate.kind" to "DELETED"),
                MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_REVISION to
                    ("watermark.candidate.layoutRevision" to "3"),
                MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_HOME_ID to
                    ("watermark.candidate.miniHomeId" to "other-home"),
                MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_OPERATION_ID to
                    ("watermark.candidate.operationId" to "other-operation"),
                MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_PAYLOAD_HASH to
                    ("watermark.candidate.payloadHash" to "b".repeat(64)),
                MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_TOMBSTONE_ID to
                    ("watermark.candidate.tombstoneId" to "tombstone"),
                MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_VERIFIED to
                    ("watermark.candidate.verified" to "false"),
                MiniHomeCacheConflictPredicate.LAYOUT_HOME_CONTENT to
                    ("candidate.home.name" to "mutated"),
                MiniHomeCacheConflictPredicate.LAYOUT_PLACEMENT_CONTENT to
                    ("candidate.placements.0.normalizedX" to "0.75"),
            )
        mutations.forEach { (expected, mutation) ->
            val mutated =
                base +
                    mutation +
                    if (expected == MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_GENERATION) {
                        mapOf("watermark.generationOrdering" to "candidate-higher")
                    } else {
                        emptyMap()
                    }
            assertEquals(expected, selectLayoutCacheConflictPredicate(mutated))
            conflictReceipt(MiniHomeCacheConflictCategory.LAYOUT_APPLY, expected, mutated)
                .requireComplete()
            assertEquals(base, layoutOperands())
        }
        assertEquals(
            MiniHomeCacheConflictPredicate.LAYOUT_COHERENCE,
            selectLayoutCacheConflictPredicate(base),
        )
        conflictReceipt(
                MiniHomeCacheConflictCategory.LAYOUT_APPLY,
                MiniHomeCacheConflictPredicate.LAYOUT_COHERENCE,
                base,
            )
            .requireComplete()
    }

    @Test
    fun `every inventory predicate is selected by one reversible canonical operand mutation`() {
        val base = inventoryOperands()
        val mutations =
            linkedMapOf(
                MiniHomeCacheConflictPredicate.INVENTORY_SNAPSHOT_HASH to
                    ("inventory.candidate.snapshotHash" to "b".repeat(64)),
                MiniHomeCacheConflictPredicate.INVENTORY_REGISTERED_PLANT_COUNT to
                    ("inventory.candidate.registeredPlantCount" to "2"),
                MiniHomeCacheConflictPredicate.INVENTORY_PARTIAL to
                    ("inventory.candidate.partial" to "true"),
                MiniHomeCacheConflictPredicate.INVENTORY_CATALOG_CONTENT to
                    ("candidate.catalog.0.name" to "mutated"),
                MiniHomeCacheConflictPredicate.INVENTORY_OWNED_CONTENT to
                    ("candidate.owned.0.applied" to "true"),
            )
        mutations.forEach { (expected, mutation) ->
            val mutated = base + mutation
            assertEquals(expected, selectInventoryCacheConflictPredicate(mutated))
            conflictReceipt(MiniHomeCacheConflictCategory.INVENTORY_APPLY, expected, mutated)
                .requireComplete()
            assertEquals(base, inventoryOperands())
        }
        assertEquals(
            MiniHomeCacheConflictPredicate.INVENTORY_COHERENCE,
            selectInventoryCacheConflictPredicate(base),
        )
        conflictReceipt(
                MiniHomeCacheConflictCategory.INVENTORY_APPLY,
                MiniHomeCacheConflictPredicate.INVENTORY_COHERENCE,
                base,
            )
            .requireComplete()
    }

    @Test
    fun `every final snapshot predicate is selected by one reversible operand mutation`() {
        val base = snapshotOperands()
        val mutations =
            linkedMapOf(
                MiniHomeCacheConflictPredicate.CURRENT_SNAPSHOT_MISSING to
                    ("snapshot.present" to "false"),
                MiniHomeCacheConflictPredicate.CURRENT_LAYOUT_MISSING to
                    ("layout.present" to "false"),
                MiniHomeCacheConflictPredicate.CURRENT_INVENTORY_MISSING to
                    ("inventory.present" to "false"),
                MiniHomeCacheConflictPredicate.CURRENT_LAYOUT_UNVERIFIED to
                    ("layout.verified" to "false"),
                MiniHomeCacheConflictPredicate.CURRENT_INVENTORY_UNVERIFIED to
                    ("inventory.verified" to "false"),
                MiniHomeCacheConflictPredicate.CURRENT_LAYOUT_TOKEN_MISSING to
                    ("layout.token" to null),
                MiniHomeCacheConflictPredicate.CURRENT_LAYOUT_GENERATION_MISSING to
                    ("layout.generation" to null),
                MiniHomeCacheConflictPredicate.CURRENT_TOKEN_MISMATCH to
                    ("inventory.token" to "other-token"),
            )
        mutations.forEach { (expected, mutation) ->
            val mutated = base + mutation
            assertEquals(expected, selectCurrentSnapshotConflictPredicate(mutated))
            conflictReceipt(MiniHomeCacheConflictCategory.CURRENT_SNAPSHOT, expected, mutated)
                .requireComplete()
            assertEquals(base, snapshotOperands())
        }
        assertEquals(
            MiniHomeCacheConflictPredicate.CURRENT_GENERATION_MISMATCH,
            selectCurrentSnapshotConflictPredicate(base),
        )
        conflictReceipt(
                MiniHomeCacheConflictCategory.CURRENT_SNAPSHOT,
                MiniHomeCacheConflictPredicate.CURRENT_GENERATION_MISMATCH,
                base,
            )
            .requireComplete()
    }

    @Test
    fun `verified inventory decode predicate closes with canonical field operands`() {
        val operands = decodeOperands()
        conflictReceipt(
                MiniHomeCacheConflictCategory.VERIFIED_INVENTORY_DECODE,
                MiniHomeCacheConflictPredicate.VERIFIED_INVENTORY_DECODE_FIELD,
                operands,
            )
            .requireComplete()
    }

    @Test
    fun `receipt rejects cross category false predicate and generic decode claims`() {
        val accountMismatch = layoutOperands() + ("watermark.candidate.accountId" to "other-owner")
        val invalid =
            listOf(
                conflictReceipt(
                    MiniHomeCacheConflictCategory.LAYOUT_APPLY,
                    MiniHomeCacheConflictPredicate.INVENTORY_PARTIAL,
                    accountMismatch,
                ),
                conflictReceipt(
                    MiniHomeCacheConflictCategory.LAYOUT_APPLY,
                    MiniHomeCacheConflictPredicate.LAYOUT_COHERENCE,
                    accountMismatch,
                ),
                conflictReceipt(
                    MiniHomeCacheConflictCategory.VERIFIED_INVENTORY_DECODE,
                    MiniHomeCacheConflictPredicate.VERIFIED_INVENTORY_DECODE_FIELD,
                    decodeOperands() + ("field" to "normalized-content-or-snapshotHash"),
                ),
                conflictReceipt(
                    MiniHomeCacheConflictCategory.VERIFIED_INVENTORY_DECODE,
                    MiniHomeCacheConflictPredicate.VERIFIED_INVENTORY_DECODE_FIELD,
                    decodeOperands() + ("actual.account" to OWNER.value),
                ),
            )
        invalid.forEach { receipt ->
            assertThrows(IllegalArgumentException::class.java) { receipt.requireComplete() }
        }
    }

    @Test
    fun `structurally unreachable Room decoder fields still require exact normalized relations`() {
        val noInventory =
            decodeOperands() +
                mapOf(
                    "field" to "inventory",
                    "actual.account" to null,
                    "verified" to null,
                    "generation" to null,
                    "snapshotHash" to null,
                    "registeredPlantCount" to null,
                    "loadedAtEpochMillis" to null,
                    "partial" to null,
                )
        val unverified =
            decodeOperands() +
                mapOf(
                    "field" to "watermark.verified",
                    "actual.account" to OWNER.value,
                    "verified" to "false",
                )
        val invalidGeneration =
            decodeOperands() +
                mapOf(
                    "field" to "watermark.generation",
                    "actual.account" to OWNER.value,
                    "generation" to "0",
                )
        val duplicateCatalog =
            decodeOperands() +
                mapOf(
                    "field" to "catalog.duplicateItemId",
                    "actual.account" to OWNER.value,
                    "failure.index" to "0",
                    "catalog.count" to "2",
                ) +
                validCatalogOperands(0, "duplicate") +
                validCatalogOperands(1, "duplicate")
        val duplicateOwned =
            decodeOperands() +
                mapOf(
                    "field" to "owned.duplicateItemId",
                    "actual.account" to OWNER.value,
                    "failure.index" to "0",
                    "owned.count" to "2",
                ) +
                validOwnedOperands(0, "duplicate") +
                validOwnedOperands(1, "duplicate")
        listOf(
                noInventory,
                decodeOperands(),
                unverified,
                invalidGeneration,
                duplicateCatalog,
                duplicateOwned,
            )
            .forEach { operands ->
                conflictReceipt(
                        MiniHomeCacheConflictCategory.VERIFIED_INVENTORY_DECODE,
                        MiniHomeCacheConflictPredicate.VERIFIED_INVENTORY_DECODE_FIELD,
                        operands,
                    )
                    .requireComplete()
            }
    }

    @Test
    fun `recorder closes exact success and rejects publication after closure`() {
        val recorder = MiniHomeCacheDiagnosticRecorder(OWNER, OPERATION)
        successObservations().forEach(recorder::observe)
        assertEquals(successObservations(), recorder.close().observations)
        assertThrows(IllegalArgumentException::class.java) {
            recorder.observe(successObservations().first())
        }
    }

    @Test
    fun `success receipt recomputes ignored layout and inventory outcomes`() {
        val ignoredLayout =
            layoutSuccessOperands() +
                layoutState("before", generation = 2) +
                layoutState("after", generation = 2) +
                layoutState("candidate", generation = 1) +
                ("candidate.generationOrdering" to "candidate-lower")
        val ignoredInventory =
            inventorySuccessOperands() +
                inventoryState("before", generation = 2) +
                inventoryState("after", generation = 2) +
                inventoryState("candidate", generation = 1) +
                ("candidate.generationOrdering" to "candidate-lower")
        val observations =
            successObservations().map { observation ->
                when (observation.stage) {
                    MiniHomeCacheDiagnosticStage.LAYOUT_APPLY ->
                        observation.copy(
                            outcome = MiniHomeCacheDiagnosticOutcome.IGNORED,
                            operands = ignoredLayout,
                        )
                    MiniHomeCacheDiagnosticStage.INVENTORY_APPLY ->
                        observation.copy(
                            outcome = MiniHomeCacheDiagnosticOutcome.IGNORED,
                            operands = ignoredInventory,
                        )
                    else -> observation
                }
            }
        MiniHomeCacheDiagnosticReceipt(OWNER, OPERATION, observations, true).requireComplete()
    }

    @Test
    fun `layout success replays coherence stale revision and converged absence branches`() {
        val current = layoutState("before", generation = 2, snapshotToken = "a".repeat(64))
        val coherenceApplied =
            current +
                layoutState(
                    "after",
                    generation = 2,
                    snapshotToken = "b".repeat(64),
                    snapshotGeneration = 3,
                ) +
                layoutState(
                    "candidate",
                    generation = 2,
                    snapshotToken = "b".repeat(64),
                    snapshotGeneration = 3,
                ) +
                ("candidate.generationOrdering" to "equal")
        val coherenceIgnored =
            current +
                layoutState("after", generation = 2, snapshotToken = "a".repeat(64)) +
                layoutState("candidate", generation = 2, snapshotToken = "a".repeat(64)) +
                ("candidate.generationOrdering" to "equal")
        val staleHigher =
            layoutState("before", generation = 2, revision = 5) +
                layoutState("after", generation = 2, revision = 5) +
                layoutState("candidate", generation = 3, revision = 4) +
                ("candidate.generationOrdering" to "candidate-higher")
        val convergedDeletion =
            absenceLayoutState("before", generation = 2, kind = "CONVERGED_ABSENCE") +
                absenceLayoutState("after", generation = 2, kind = "CONVERGED_ABSENCE") +
                absenceLayoutState("candidate", generation = 2, kind = "DELETED") +
                ("candidate.generationOrdering" to "equal")

        listOf(
                MiniHomeCacheDiagnosticOutcome.APPLIED to coherenceApplied,
                MiniHomeCacheDiagnosticOutcome.IGNORED to coherenceIgnored,
                MiniHomeCacheDiagnosticOutcome.IGNORED to staleHigher,
                MiniHomeCacheDiagnosticOutcome.IGNORED to convergedDeletion,
            )
            .forEach { (outcome, operands) ->
                successReceipt(MiniHomeCacheDiagnosticStage.LAYOUT_APPLY, outcome, operands)
                    .requireComplete()
                val contradiction =
                    if (outcome == MiniHomeCacheDiagnosticOutcome.APPLIED) {
                        MiniHomeCacheDiagnosticOutcome.IGNORED
                    } else {
                        MiniHomeCacheDiagnosticOutcome.APPLIED
                    }
                assertThrows(IllegalArgumentException::class.java) {
                    successReceipt(
                            MiniHomeCacheDiagnosticStage.LAYOUT_APPLY,
                            contradiction,
                            operands,
                        )
                        .requireComplete()
                }
            }
    }

    @Test
    fun `inventory success replays same generation coherence apply and ignore`() {
        val coherenceApplied =
            inventoryState("before", generation = 2, snapshotToken = "a".repeat(64)) +
                inventoryState(
                    "after",
                    generation = 2,
                    snapshotToken = "b".repeat(64),
                    snapshotGeneration = 3,
                ) +
                inventoryState(
                    "candidate",
                    generation = 2,
                    snapshotToken = "b".repeat(64),
                    snapshotGeneration = 3,
                ) +
                ("candidate.generationOrdering" to "equal")
        val coherenceIgnored =
            inventoryState("before", generation = 2, snapshotToken = "a".repeat(64)) +
                inventoryState("after", generation = 2, snapshotToken = "a".repeat(64)) +
                inventoryState("candidate", generation = 2, snapshotToken = "a".repeat(64)) +
                ("candidate.generationOrdering" to "equal")

        listOf(
                MiniHomeCacheDiagnosticOutcome.APPLIED to coherenceApplied,
                MiniHomeCacheDiagnosticOutcome.IGNORED to coherenceIgnored,
            )
            .forEach { (outcome, operands) ->
                successReceipt(MiniHomeCacheDiagnosticStage.INVENTORY_APPLY, outcome, operands)
                    .requireComplete()
                val contradiction =
                    if (outcome == MiniHomeCacheDiagnosticOutcome.APPLIED) {
                        MiniHomeCacheDiagnosticOutcome.IGNORED
                    } else {
                        MiniHomeCacheDiagnosticOutcome.APPLIED
                    }
                assertThrows(IllegalArgumentException::class.java) {
                    successReceipt(
                            MiniHomeCacheDiagnosticStage.INVENTORY_APPLY,
                            contradiction,
                            operands,
                        )
                        .requireComplete()
                }
            }
    }

    @Test
    fun `migrated unverified before states apply authoritative candidates`() {
        val migratedLayoutOperands =
            legacyLayoutState("before", generation = 0, revision = 1) +
                layoutState("after", generation = 2) +
                layoutState("candidate", generation = 2) +
                ("candidate.generationOrdering" to "candidate-higher")
        val fallbackLayoutOperands =
            legacyLayoutState(
                "before",
                generation = 1,
                revision = 1,
                snapshotToken = "c".repeat(64),
                snapshotGeneration = 1,
            ) +
                layoutState("after", generation = 2) +
                layoutState("candidate", generation = 2) +
                ("candidate.generationOrdering" to "candidate-higher")
        val inventoryOperands =
            legacyInventoryState("before") +
                inventoryState("after", generation = 2) +
                inventoryState("candidate", generation = 2) +
                ("candidate.generationOrdering" to "candidate-higher")

        listOf(migratedLayoutOperands, fallbackLayoutOperands).forEach { operands ->
            successReceipt(
                    MiniHomeCacheDiagnosticStage.LAYOUT_APPLY,
                    MiniHomeCacheDiagnosticOutcome.APPLIED,
                    operands,
                )
                .requireComplete()
            listOf(
                    MiniHomeCacheDiagnosticOutcome.IGNORED,
                    MiniHomeCacheDiagnosticOutcome.CONFLICT,
                )
                .forEach { contradiction ->
                    assertThrows(IllegalArgumentException::class.java) {
                        successReceipt(
                                MiniHomeCacheDiagnosticStage.LAYOUT_APPLY,
                                contradiction,
                                operands,
                            )
                            .requireComplete()
                    }
                }
        }
        successReceipt(
                MiniHomeCacheDiagnosticStage.INVENTORY_APPLY,
                MiniHomeCacheDiagnosticOutcome.APPLIED,
                inventoryOperands,
            )
            .requireComplete()
        listOf(
                MiniHomeCacheDiagnosticOutcome.IGNORED,
                MiniHomeCacheDiagnosticOutcome.CONFLICT,
            )
            .forEach { contradiction ->
                assertThrows(IllegalArgumentException::class.java) {
                    successReceipt(
                            MiniHomeCacheDiagnosticStage.INVENTORY_APPLY,
                            contradiction,
                            inventoryOperands,
                        )
                        .requireComplete()
                }
            }
    }

    @Test
    fun `migrated unverified before rejects mixed legacy and relaxed authoritative states`() {
        val layoutBase =
            legacyLayoutState("before", generation = 0, revision = 1) +
                layoutState("after", generation = 2) +
                layoutState("candidate", generation = 2) +
                ("candidate.generationOrdering" to "candidate-higher")
        val inventoryBase =
            legacyInventoryState("before") +
                inventoryState("after", generation = 2) +
                inventoryState("candidate", generation = 2) +
                ("candidate.generationOrdering" to "candidate-higher")
        val invalidLayouts =
            listOf(
                layoutBase + ("before.generation" to "-1"),
                layoutBase +
                    ("before.generation" to "2") +
                    ("candidate.generationOrdering" to "equal"),
                layoutBase + ("before.kind" to "DELETED"),
                layoutBase + ("before.operationId" to OPERATION.value),
                layoutBase + ("before.payloadHash" to "a".repeat(64)),
                layoutBase + ("before.tombstoneId" to "legacy-tombstone"),
                layoutBase + ("before.snapshotToken" to "c".repeat(64)),
                layoutBase + ("candidate.verified" to "false"),
                layoutBase +
                    ("candidate.generation" to "0") +
                    ("candidate.generationOrdering" to "equal"),
                layoutBase + ("after.verified" to "false"),
                layoutBase + ("after.home.name" to "Not the candidate"),
            )
        val invalidInventories =
            listOf(
                inventoryBase +
                    ("before.generation" to "1") +
                    ("candidate.generationOrdering" to "candidate-higher"),
                inventoryBase + ("before.snapshotHash" to EMPTY_INVENTORY_HASH),
                inventoryBase + ("before.registeredPlantCount" to "1"),
                inventoryBase + ("before.loadedAtEpochMillis" to "1"),
                inventoryBase + ("before.partial" to "false"),
                inventoryBase + ("before.snapshotGeneration" to "1"),
                inventoryBase + ("before.catalog.0.assetSha256" to "a".repeat(64)),
                inventoryBase + ("before.owned.0.assetSha256Snapshot" to "a".repeat(64)),
                inventoryBase + ("candidate.verified" to "false"),
                inventoryBase +
                    ("candidate.generation" to "0") +
                    ("candidate.generationOrdering" to "equal"),
                inventoryBase + ("after.verified" to "false"),
                inventoryBase + ("after.loadedAtEpochMillis" to "3"),
            )

        invalidLayouts.forEach { operands ->
            assertThrows(IllegalArgumentException::class.java) {
                successReceipt(
                        MiniHomeCacheDiagnosticStage.LAYOUT_APPLY,
                        MiniHomeCacheDiagnosticOutcome.APPLIED,
                        operands,
                    )
                    .requireComplete()
            }
        }
        invalidInventories.forEach { operands ->
            assertThrows(IllegalArgumentException::class.java) {
                successReceipt(
                        MiniHomeCacheDiagnosticStage.INVENTORY_APPLY,
                        MiniHomeCacheDiagnosticOutcome.APPLIED,
                        operands,
                    )
                    .requireComplete()
            }
        }
    }

    @Test
    fun `decode success independently hashes full entities and rejects non boolean applied`() {
        successReceipt(
                MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                MiniHomeCacheDiagnosticOutcome.VERIFIED,
                fullDecodeSuccessOperands(),
            )
            .requireComplete()
        val nonBoolean = fullDecodeSuccessOperands() + ("owned.0.applied" to "yes")
        assertThrows(IllegalArgumentException::class.java) {
            successReceipt(
                    MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                    MiniHomeCacheDiagnosticOutcome.VERIFIED,
                    nonBoolean,
                )
                .requireComplete()
        }
        fullDecodeSuccessOperands().keys.forEach { missingKey ->
            assertThrows(IllegalArgumentException::class.java) {
                successReceipt(
                        MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                        MiniHomeCacheDiagnosticOutcome.VERIFIED,
                        fullDecodeSuccessOperands() - missingKey,
                    )
                    .requireComplete()
            }
        }
    }

    @Test
    fun `success receipt rejects generic stage placeholders`() {
        val generic =
            successObservations().map { observation ->
                observation.copy(operands = mapOf("stage" to observation.stage.name))
            }
        assertThrows(IllegalArgumentException::class.java) {
            MiniHomeCacheDiagnosticReceipt(OWNER, OPERATION, generic, true).requireComplete()
        }
    }

    @Test
    fun `success receipt rejects every required field omission and unknown field`() {
        val success = successObservations()
        success.forEachIndexed { observationIndex, observation ->
            observation.operands.keys.forEach { missingKey ->
                val malformed = success.mapIndexed { index, item ->
                    if (index == observationIndex) {
                        item.copy(operands = item.operands - missingKey)
                    } else {
                        item
                    }
                }
                assertThrows(IllegalArgumentException::class.java) {
                    MiniHomeCacheDiagnosticReceipt(OWNER, OPERATION, malformed, true)
                        .requireComplete()
                }
            }
            val unknown = success.mapIndexed { index, item ->
                if (index == observationIndex) {
                    item.copy(operands = item.operands + ("unknown" to "value"))
                } else {
                    item
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                MiniHomeCacheDiagnosticReceipt(OWNER, OPERATION, unknown, true).requireComplete()
            }
        }
    }

    @Test
    fun `success receipt rejects malformed and contradictory stage facts`() {
        val success = successObservations()
        val malformed =
            listOf(
                success.mapIndexed { index, observation ->
                    if (index == 0) {
                        observation.copy(outcome = MiniHomeCacheDiagnosticOutcome.IGNORED)
                    } else {
                        observation
                    }
                },
                replaceSuccess(
                    success,
                    MiniHomeCacheDiagnosticStage.LAYOUT_APPLY,
                    layoutSuccessOperands() + ("candidate.generationOrdering" to "candidate-lower"),
                ),
                replaceSuccess(
                    success,
                    MiniHomeCacheDiagnosticStage.LAYOUT_APPLY,
                    layoutSuccessOperands() + ("after.kind" to "UNKNOWN"),
                ),
                replaceSuccess(
                    success,
                    MiniHomeCacheDiagnosticStage.LAYOUT_APPLY,
                    layoutSuccessOperands() + ("candidate.snapshotToken" to "d".repeat(64)),
                ),
                replaceSuccess(
                    success,
                    MiniHomeCacheDiagnosticStage.INVENTORY_APPLY,
                    inventorySuccessOperands() + ("after.snapshotHash" to "bad"),
                ),
                replaceSuccess(
                    success,
                    MiniHomeCacheDiagnosticStage.INVENTORY_APPLY,
                    inventorySuccessOperands() + ("candidate.partial" to "true"),
                ),
                replaceSuccess(
                    success,
                    MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT,
                    currentSuccessOperands() + ("inventory.accountId" to "wrong-owner"),
                ),
                replaceSuccess(
                    success,
                    MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT,
                    currentSuccessOperands() + ("inventory.token" to "d".repeat(64)),
                ),
                replaceSuccess(
                    success,
                    MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT,
                    currentSuccessOperands() + ("inventory.generation" to "3"),
                ),
                replaceSuccess(
                    success,
                    MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT,
                    currentSuccessOperands() + ("layout.placementCount" to "malformed"),
                ),
                replaceSuccess(
                    success,
                    MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                    decodeSuccessOperands() + ("field" to "watermark.snapshotHash.content"),
                ),
                replaceSuccess(
                    success,
                    MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                    decodeSuccessOperands() + ("failure.index" to "0"),
                ),
                replaceSuccess(
                    success,
                    MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                    decodeSuccessOperands() + ("content.expectedSnapshotHash" to "d".repeat(64)),
                ),
                replaceSuccess(
                    success,
                    MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                    decodeSuccessOperands() + ("catalog.count" to "1"),
                ),
            )
        malformed.forEach { observations ->
            assertThrows(IllegalArgumentException::class.java) {
                MiniHomeCacheDiagnosticReceipt(OWNER, OPERATION, observations, true)
                    .requireComplete()
            }
        }
    }

    @Test
    fun `recorder permanently rejects wrong identity and stages after terminal`() {
        listOf(
                successObservations().first().copy(accountId = AccountId("wrong-owner")),
                successObservations().first().copy(operationId = OperationId("wrong-operation")),
            )
            .forEach { invalid ->
                val recorder = MiniHomeCacheDiagnosticRecorder(OWNER, OPERATION)
                assertThrows(IllegalArgumentException::class.java) { recorder.observe(invalid) }
                assertThrows(IllegalArgumentException::class.java) { recorder.close() }
            }

        val conflict =
            conflictReceipt(
                    MiniHomeCacheConflictCategory.LAYOUT_APPLY,
                    MiniHomeCacheConflictPredicate.LAYOUT_COHERENCE,
                    layoutOperands(),
                )
                .observations
        val recorder = MiniHomeCacheDiagnosticRecorder(OWNER, OPERATION)
        conflict.forEach(recorder::observe)
        assertThrows(IllegalArgumentException::class.java) {
            recorder.observe(successObservations().last())
        }
        assertThrows(IllegalArgumentException::class.java) { recorder.close() }
    }

    @Test
    fun `first failing predicate remains selected while all later mismatches remain visible`() {
        val operands =
            layoutOperands() +
                mapOf(
                    "watermark.candidate.accountId" to "later-account-mismatch",
                    "candidate.home.revision" to "99",
                )
        assertEquals(
            MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_ACCOUNT,
            selectLayoutCacheConflictPredicate(operands),
        )
        assertEquals("99", operands["candidate.home.revision"])
    }

    @Test
    fun `receipt rejects every malformed lifecycle class`() {
        val success = successObservations()
        val conflict =
            conflictReceipt(
                    MiniHomeCacheConflictCategory.LAYOUT_APPLY,
                    MiniHomeCacheConflictPredicate.LAYOUT_COHERENCE,
                    layoutOperands(),
                )
                .observations
        val malformed =
            listOf(
                MiniHomeCacheDiagnosticReceipt(OWNER, OPERATION, emptyList(), true),
                MiniHomeCacheDiagnosticReceipt(OWNER, OPERATION, success + success.last(), true),
                MiniHomeCacheDiagnosticReceipt(
                    OWNER,
                    OPERATION,
                    success.toMutableList().apply { add(0, removeAt(1)) },
                    true,
                ),
                MiniHomeCacheDiagnosticReceipt(OWNER, OPERATION, conflict + success.last(), true),
                MiniHomeCacheDiagnosticReceipt(OWNER, OPERATION, conflict + conflict.last(), true),
                MiniHomeCacheDiagnosticReceipt(
                    OWNER,
                    OPERATION,
                    success.mapIndexed { index, item ->
                        if (index == 0) item.copy(accountId = AccountId("wrong")) else item
                    },
                    true,
                ),
                MiniHomeCacheDiagnosticReceipt(
                    OWNER,
                    OPERATION,
                    success.mapIndexed { index, item ->
                        if (index == 0) {
                            item.copy(operationId = OperationId("wrong-operation"))
                        } else item
                    },
                    true,
                ),
                MiniHomeCacheDiagnosticReceipt(
                    OWNER,
                    OPERATION,
                    success.mapIndexed { index, item ->
                        if (index == 0) item.copy(operands = emptyMap()) else item
                    },
                    true,
                ),
                MiniHomeCacheDiagnosticReceipt(
                    OWNER,
                    OPERATION,
                    success.mapIndexed { index, item ->
                        if (index == 0) {
                            item.copy(operands = mapOf("catalog.count" to "1"))
                        } else item
                    },
                    true,
                ),
                MiniHomeCacheDiagnosticReceipt(OWNER, OPERATION, success, false),
                MiniHomeCacheDiagnosticReceipt(
                    OWNER,
                    OPERATION,
                    listOf(
                        conflict.first().copy(outcome = MiniHomeCacheDiagnosticOutcome.CONFLICT)
                    ) + conflict,
                    true,
                ),
            )
        malformed.forEach { receipt ->
            assertThrows(IllegalArgumentException::class.java) { receipt.requireComplete() }
        }
    }

    @Test
    fun `decode success rejects two jointly forged snapshot hashes`() {
        val forged =
            decodeSuccessOperands() +
                mapOf(
                    "snapshotHash" to "d".repeat(64),
                    "content.expectedSnapshotHash" to "d".repeat(64),
                )
        val receipt =
            MiniHomeCacheDiagnosticReceipt(
                OWNER,
                OPERATION,
                replaceSuccess(
                    successObservations(),
                    MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                    forged,
                ),
                true,
            )

        assertThrows(IllegalArgumentException::class.java) { receipt.requireComplete() }
    }

    private fun observation(stage: MiniHomeCacheDiagnosticStage) =
        MiniHomeCacheDiagnosticObservation(
            stage = stage,
            accountId = AccountId("cache-diagnostic-owner"),
            operationId = OperationId("cache-diagnostic-operation"),
            outcome = MiniHomeCacheDiagnosticOutcome.VERIFIED,
        )

    private fun successObservations() =
        listOf(
            success(
                MiniHomeCacheDiagnosticStage.LAYOUT_APPLY,
                MiniHomeCacheDiagnosticOutcome.APPLIED,
            ),
            success(
                MiniHomeCacheDiagnosticStage.INVENTORY_APPLY,
                MiniHomeCacheDiagnosticOutcome.APPLIED,
            ),
            success(
                MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT,
                MiniHomeCacheDiagnosticOutcome.VERIFIED,
            ),
            success(
                MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                MiniHomeCacheDiagnosticOutcome.VERIFIED,
            ),
        )

    private fun success(
        stage: MiniHomeCacheDiagnosticStage,
        outcome: MiniHomeCacheDiagnosticOutcome,
    ) =
        MiniHomeCacheDiagnosticObservation(
            stage,
            OWNER,
            OPERATION,
            outcome,
            operands =
                when (stage) {
                    MiniHomeCacheDiagnosticStage.LAYOUT_APPLY -> layoutSuccessOperands()
                    MiniHomeCacheDiagnosticStage.INVENTORY_APPLY -> inventorySuccessOperands()
                    MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT -> currentSuccessOperands()
                    MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE ->
                        decodeSuccessOperands()
                    MiniHomeCacheDiagnosticStage.TERMINAL_CONFLICT -> error("terminal is conflict")
                },
        )

    private fun replaceSuccess(
        observations: List<MiniHomeCacheDiagnosticObservation>,
        stage: MiniHomeCacheDiagnosticStage,
        operands: Map<String, String?>,
    ) = observations.map { observation ->
        if (observation.stage == stage) observation.copy(operands = operands) else observation
    }

    private fun successReceipt(
        stage: MiniHomeCacheDiagnosticStage,
        outcome: MiniHomeCacheDiagnosticOutcome,
        operands: Map<String, String?>,
    ) =
        MiniHomeCacheDiagnosticReceipt(
            OWNER,
            OPERATION,
            successObservations().map { observation ->
                if (observation.stage == stage) {
                    observation.copy(outcome = outcome, operands = operands)
                } else {
                    observation
                }
            },
            true,
        )

    private fun layoutSuccessOperands() =
        absentLayoutState("before") +
            layoutState("after", generation = 2) +
            layoutState("candidate", generation = 2) +
            ("candidate.generationOrdering" to "candidate-higher")

    private fun inventorySuccessOperands() =
        absentInventoryState("before") +
            inventoryState("after", generation = 2) +
            inventoryState("candidate", generation = 2) +
            ("candidate.generationOrdering" to "candidate-higher")

    private fun absentLayoutState(prefix: String) =
        mapOf(
            "$prefix.present" to "false",
            "$prefix.accountId" to null,
            "$prefix.generation" to null,
            "$prefix.kind" to null,
            "$prefix.layoutRevision" to null,
            "$prefix.miniHomeId" to null,
            "$prefix.operationId" to null,
            "$prefix.payloadHash" to null,
            "$prefix.tombstoneId" to null,
            "$prefix.authoritativeAtEpochMillis" to null,
            "$prefix.verified" to null,
            "$prefix.snapshotToken" to null,
            "$prefix.snapshotGeneration" to null,
            "$prefix.home.present" to "false",
            "$prefix.home.accountId" to null,
            "$prefix.home.miniHomeId" to null,
            "$prefix.home.name" to null,
            "$prefix.home.placedPlantCount" to null,
            "$prefix.home.revision" to null,
            "$prefix.home.updatedAtEpochMillis" to null,
            "$prefix.placements.count" to "0",
            "$prefix.placements.order" to "",
        )

    private fun layoutState(
        prefix: String,
        generation: Long,
        snapshotToken: String? = "b".repeat(64),
        snapshotGeneration: Long? = generation,
        verified: Boolean = true,
        revision: Long = generation,
    ) =
        mapOf(
            "$prefix.present" to "true",
            "$prefix.accountId" to OWNER.value,
            "$prefix.generation" to generation.toString(),
            "$prefix.kind" to "PRESENT",
            "$prefix.layoutRevision" to revision.toString(),
            "$prefix.miniHomeId" to "home",
            "$prefix.operationId" to OPERATION.value,
            "$prefix.payloadHash" to "a".repeat(64),
            "$prefix.tombstoneId" to null,
            "$prefix.authoritativeAtEpochMillis" to "20",
            "$prefix.verified" to verified.toString(),
            "$prefix.snapshotToken" to snapshotToken,
            "$prefix.snapshotGeneration" to snapshotGeneration?.toString(),
            "$prefix.home.present" to "true",
            "$prefix.home.accountId" to OWNER.value,
            "$prefix.home.miniHomeId" to "home",
            "$prefix.home.name" to "Home",
            "$prefix.home.placedPlantCount" to "1",
            "$prefix.home.revision" to revision.toString(),
            "$prefix.home.updatedAtEpochMillis" to "20",
            "$prefix.placements.count" to "1",
            "$prefix.placements.order" to "placement",
            "$prefix.placements.0.accountId" to OWNER.value,
            "$prefix.placements.0.placementId" to "placement",
            "$prefix.placements.0.miniHomeId" to "home",
            "$prefix.placements.0.plantId" to "plant",
            "$prefix.placements.0.itemId" to null,
            "$prefix.placements.0.normalizedX" to "0.5",
            "$prefix.placements.0.normalizedY" to "0.5",
            "$prefix.placements.0.zIndex" to "0",
            "$prefix.placements.0.layoutRevision" to revision.toString(),
        )

    private fun legacyLayoutState(
        prefix: String,
        generation: Long,
        revision: Long,
        snapshotToken: String? = null,
        snapshotGeneration: Long? = null,
    ) =
        layoutState(
            prefix = prefix,
            generation = generation,
            snapshotToken = snapshotToken,
            snapshotGeneration = snapshotGeneration,
            verified = false,
            revision = revision,
        ) +
            mapOf(
                "$prefix.operationId" to null,
                "$prefix.payloadHash" to null,
            )

    private fun absenceLayoutState(prefix: String, generation: Long, kind: String) =
        absentLayoutState(prefix) +
            mapOf(
                "$prefix.present" to "true",
                "$prefix.accountId" to OWNER.value,
                "$prefix.generation" to generation.toString(),
                "$prefix.kind" to kind,
                "$prefix.tombstoneId" to if (kind == "DELETED") "tombstone-id" else null,
                "$prefix.authoritativeAtEpochMillis" to "20",
                "$prefix.verified" to "true",
                "$prefix.snapshotToken" to "b".repeat(64),
                "$prefix.snapshotGeneration" to generation.toString(),
            )

    private fun absentInventoryState(prefix: String) =
        mapOf(
            "$prefix.present" to "false",
            "$prefix.accountId" to null,
            "$prefix.generation" to null,
            "$prefix.snapshotHash" to null,
            "$prefix.registeredPlantCount" to null,
            "$prefix.loadedAtEpochMillis" to null,
            "$prefix.partial" to null,
            "$prefix.verified" to null,
            "$prefix.snapshotToken" to null,
            "$prefix.snapshotGeneration" to null,
            "$prefix.catalog.count" to "0",
            "$prefix.owned.count" to "0",
        )

    private fun inventoryState(
        prefix: String,
        generation: Long,
        snapshotToken: String? = "b".repeat(64),
        snapshotGeneration: Long? = generation,
        verified: Boolean = true,
    ) =
        mapOf(
            "$prefix.present" to "true",
            "$prefix.accountId" to OWNER.value,
            "$prefix.generation" to generation.toString(),
            "$prefix.snapshotHash" to EMPTY_INVENTORY_HASH,
            "$prefix.registeredPlantCount" to "0",
            "$prefix.loadedAtEpochMillis" to "2",
            "$prefix.partial" to "false",
            "$prefix.verified" to verified.toString(),
            "$prefix.snapshotToken" to snapshotToken,
            "$prefix.snapshotGeneration" to snapshotGeneration?.toString(),
            "$prefix.catalog.count" to "0",
            "$prefix.owned.count" to "0",
        )

    private fun legacyInventoryState(prefix: String) =
        inventoryState(
            prefix = prefix,
            generation = 0,
            snapshotToken = null,
            snapshotGeneration = null,
            verified = false,
        ) +
            mapOf(
                "$prefix.snapshotHash" to MIGRATION_ZERO_HASH,
                "$prefix.loadedAtEpochMillis" to "0",
                "$prefix.partial" to "true",
                "$prefix.catalog.count" to "1",
                "$prefix.catalog.0.accountId" to OWNER.value,
                "$prefix.catalog.0.itemId" to "legacy-item",
                "$prefix.catalog.0.name" to "Legacy item",
                "$prefix.catalog.0.description" to "Legacy description",
                "$prefix.catalog.0.category" to "DECORATION",
                "$prefix.catalog.0.assetPath" to "catalog-assets/legacy-item/preview.webp",
                "$prefix.catalog.0.acquisitionCondition" to null,
                "$prefix.catalog.0.revision" to "1",
                "$prefix.catalog.0.updatedAtEpochMillis" to "1",
                "$prefix.catalog.0.assetSha256" to "",
                "$prefix.catalog.0.assetByteSize" to "0",
                "$prefix.catalog.0.assetMimeType" to "",
                "$prefix.catalog.0.assetWidth" to "0",
                "$prefix.catalog.0.assetHeight" to "0",
                "$prefix.catalog.0.assetMediaRevision" to "0",
                "$prefix.owned.count" to "1",
                "$prefix.owned.0.accountId" to OWNER.value,
                "$prefix.owned.0.itemId" to "legacy-item",
                "$prefix.owned.0.acquiredAtEpochMillis" to "1",
                "$prefix.owned.0.applied" to "false",
                "$prefix.owned.0.revision" to "1",
                "$prefix.owned.0.availability" to "AVAILABLE",
                "$prefix.owned.0.nameSnapshot" to "Legacy item",
                "$prefix.owned.0.categorySnapshot" to "DECORATION",
                "$prefix.owned.0.assetPathSnapshot" to "catalog-assets/legacy-item/preview.webp",
                "$prefix.owned.0.catalogRevisionSnapshot" to "1",
                "$prefix.owned.0.assetSha256Snapshot" to null,
                "$prefix.owned.0.assetByteSizeSnapshot" to null,
                "$prefix.owned.0.assetMimeTypeSnapshot" to null,
                "$prefix.owned.0.assetWidthSnapshot" to null,
                "$prefix.owned.0.assetHeightSnapshot" to null,
                "$prefix.owned.0.assetMediaRevisionSnapshot" to null,
            )

    private fun currentSuccessOperands() =
        mapOf(
            "snapshot.present" to "true",
            "layout.present" to "true",
            "inventory.present" to "true",
            "layout.accountId" to OWNER.value,
            "inventory.accountId" to OWNER.value,
            "layout.verified" to "true",
            "inventory.verified" to "true",
            "layout.token" to "b".repeat(64),
            "inventory.token" to "b".repeat(64),
            "layout.generation" to "2",
            "inventory.generation" to "2",
            "layout.homePresent" to "true",
            "layout.placementCount" to "1",
            "inventory.catalogCount" to "0",
            "inventory.ownedCount" to "0",
        )

    private fun decodeSuccessOperands() =
        mapOf(
            "field" to null,
            "failure.index" to null,
            "content.expectedSnapshotHash" to EMPTY_INVENTORY_HASH,
            "expected.account" to OWNER.value,
            "actual.account" to OWNER.value,
            "verified" to "true",
            "generation" to "2",
            "snapshotHash" to EMPTY_INVENTORY_HASH,
            "registeredPlantCount" to "0",
            "loadedAtEpochMillis" to "2",
            "partial" to "false",
            "snapshotToken" to "b".repeat(64),
            "snapshotGeneration" to "2",
            "catalog.count" to "0",
            "owned.count" to "0",
        )

    private fun fullDecodeSuccessOperands() =
        decodeSuccessOperands() +
            mapOf(
                "content.expectedSnapshotHash" to FULL_INVENTORY_HASH,
                "snapshotHash" to FULL_INVENTORY_HASH,
                "catalog.count" to "1",
                "owned.count" to "1",
            ) +
            validCatalogOperands(0, "item") +
            validOwnedOperands(0, "item")

    private fun conflictReceipt(
        category: MiniHomeCacheConflictCategory,
        predicate: MiniHomeCacheConflictPredicate,
        operands: Map<String, String?>,
    ): MiniHomeCacheDiagnosticReceipt {
        val entered =
            when (category) {
                MiniHomeCacheConflictCategory.LAYOUT_APPLY ->
                    MiniHomeCacheDiagnosticStage.LAYOUT_APPLY
                MiniHomeCacheConflictCategory.INVENTORY_APPLY ->
                    MiniHomeCacheDiagnosticStage.INVENTORY_APPLY
                MiniHomeCacheConflictCategory.CURRENT_SNAPSHOT ->
                    MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT
                MiniHomeCacheConflictCategory.VERIFIED_INVENTORY_DECODE ->
                    MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE
            }
        val stages =
            listOf(
                MiniHomeCacheDiagnosticStage.LAYOUT_APPLY,
                MiniHomeCacheDiagnosticStage.INVENTORY_APPLY,
                MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT,
                MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
            )
        val observations =
            stages
                .takeWhile { it != entered }
                .map { stage ->
                    success(
                        stage,
                        if (
                            stage == MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT ||
                                stage == MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE
                        )
                            MiniHomeCacheDiagnosticOutcome.VERIFIED
                        else MiniHomeCacheDiagnosticOutcome.APPLIED,
                    )
                } +
                MiniHomeCacheDiagnosticObservation(
                    entered,
                    OWNER,
                    OPERATION,
                    MiniHomeCacheDiagnosticOutcome.CONFLICT,
                    category,
                    predicate,
                    operands,
                ) +
                MiniHomeCacheDiagnosticObservation(
                    MiniHomeCacheDiagnosticStage.TERMINAL_CONFLICT,
                    OWNER,
                    OPERATION,
                    MiniHomeCacheDiagnosticOutcome.CONFLICT,
                    category,
                    predicate,
                    operands,
                )
        return MiniHomeCacheDiagnosticReceipt(OWNER, OPERATION, observations, true)
    }

    private fun layoutOperands() =
        buildMap<String, String?> {
            listOf(
                    "accountId" to OWNER.value,
                    "generation" to "2",
                    "kind" to "PRESENT",
                    "layoutRevision" to "2",
                    "miniHomeId" to "home",
                    "operationId" to OPERATION.value,
                    "payloadHash" to "a".repeat(64),
                    "tombstoneId" to null,
                    "verified" to "true",
                )
                .forEach { (field, value) ->
                    put("watermark.current.$field", value)
                    put("watermark.candidate.$field", value)
                }
            put("watermark.generationOrdering", "equal")
            listOf(
                    "present" to "true",
                    "accountId" to OWNER.value,
                    "miniHomeId" to "home",
                    "name" to "Home",
                    "placedPlantCount" to "1",
                    "revision" to "2",
                    "updatedAtEpochMillis" to "20",
                )
                .forEach { (field, value) ->
                    put("current.home.$field", value)
                    put("candidate.home.$field", value)
                }
            listOf(
                    "count" to "1",
                    "order" to "placement",
                    "0.accountId" to OWNER.value,
                    "0.placementId" to "placement",
                    "0.miniHomeId" to "home",
                    "0.plantId" to "plant",
                    "0.itemId" to null,
                    "0.normalizedX" to "0.5",
                    "0.normalizedY" to "0.5",
                    "0.zIndex" to "0",
                    "0.layoutRevision" to "2",
                )
                .forEach { (field, value) ->
                    put("current.placements.$field", value)
                    put("candidate.placements.$field", value)
                }
            put("coherence.current.token", "a".repeat(64))
            put("coherence.current.generation", "2")
            put("coherence.candidate.token", "b".repeat(64))
            put("coherence.candidate.generation", "2")
        }

    private fun inventoryOperands() =
        buildMap<String, String?> {
            listOf(
                    "accountId" to OWNER.value,
                    "generation" to "2",
                    "snapshotHash" to "a".repeat(64),
                    "registeredPlantCount" to "1",
                    "partial" to "false",
                )
                .forEach { (field, value) ->
                    put("inventory.current.$field", value)
                    put("inventory.candidate.$field", value)
                }
            put("inventory.generationOrdering", "equal")
            put("inventory.current.snapshotToken", "a".repeat(64))
            put("inventory.candidate.snapshotToken", "b".repeat(64))
            put("inventory.current.snapshotGeneration", "2")
            put("inventory.candidate.snapshotGeneration", "2")
            listOf("count" to "1", "0.itemId" to "item", "0.name" to "Item").forEach {
                (field, value) ->
                put("current.catalog.$field", value)
                put("candidate.catalog.$field", value)
            }
            listOf("count" to "1", "0.itemId" to "item", "0.applied" to "false").forEach {
                (field, value) ->
                put("current.owned.$field", value)
                put("candidate.owned.$field", value)
            }
        }

    private fun snapshotOperands() =
        mapOf(
            "snapshot.present" to "true",
            "layout.present" to "true",
            "inventory.present" to "true",
            "layout.accountId" to OWNER.value,
            "inventory.accountId" to OWNER.value,
            "layout.verified" to "true",
            "inventory.verified" to "true",
            "layout.token" to "token",
            "inventory.token" to "token",
            "layout.generation" to "2",
            "inventory.generation" to "3",
            "layout.homePresent" to "true",
            "layout.placementCount" to "1",
            "inventory.catalogCount" to "1",
            "inventory.ownedCount" to "1",
        )

    private fun decodeOperands() =
        mapOf(
            "field" to "watermark.accountId",
            "failure.index" to null,
            "content.expectedSnapshotHash" to null,
            "expected.account" to OWNER.value,
            "actual.account" to "wrong-owner",
            "verified" to "true",
            "generation" to "2",
            "snapshotHash" to "a".repeat(64),
            "registeredPlantCount" to "1",
            "loadedAtEpochMillis" to "1",
            "partial" to "false",
            "snapshotToken" to "token",
            "snapshotGeneration" to "2",
            "catalog.count" to "0",
            "owned.count" to "0",
        )

    private fun validCatalogOperands(index: Int, itemId: String): Map<String, String?> {
        val sha = "a".repeat(64)
        val key = "catalog.$index"
        return mapOf(
            "$key.accountId" to OWNER.value,
            "$key.itemId" to itemId,
            "$key.name" to "Item",
            "$key.description" to "Description",
            "$key.category" to "DECORATION",
            "$key.assetPath" to "catalog-assets/$itemId/$sha.png",
            "$key.acquisitionCondition" to null,
            "$key.revision" to "1",
            "$key.updatedAtEpochMillis" to "1",
            "$key.assetSha256" to sha,
            "$key.assetByteSize" to "1",
            "$key.assetMimeType" to "image/png",
            "$key.assetWidth" to "1",
            "$key.assetHeight" to "1",
            "$key.assetMediaRevision" to "1",
        )
    }

    private fun validOwnedOperands(index: Int, itemId: String): Map<String, String?> {
        val key = "owned.$index"
        return mapOf(
            "$key.accountId" to OWNER.value,
            "$key.itemId" to itemId,
            "$key.acquiredAtEpochMillis" to "1",
            "$key.applied" to "false",
            "$key.revision" to "1",
            "$key.availability" to "AVAILABLE",
            "$key.nameSnapshot" to null,
            "$key.categorySnapshot" to null,
            "$key.assetPathSnapshot" to null,
            "$key.catalogRevisionSnapshot" to null,
            "$key.assetSha256Snapshot" to null,
            "$key.assetByteSizeSnapshot" to null,
            "$key.assetMimeTypeSnapshot" to null,
            "$key.assetWidthSnapshot" to null,
            "$key.assetHeightSnapshot" to null,
            "$key.assetMediaRevisionSnapshot" to null,
        )
    }

    private companion object {
        val OWNER = AccountId("cache-diagnostic-owner")
        val OPERATION = OperationId("cache-diagnostic-operation")
        const val EMPTY_INVENTORY_HASH =
            "812f532758b78689978b52a77a219b5066f4f75a00fc73864955a6530626e415"
        const val FULL_INVENTORY_HASH =
            "5f495fb04ed175816879bde4006b74e6addefd91a4366843ef48a869479380dc"
        const val MIGRATION_ZERO_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
