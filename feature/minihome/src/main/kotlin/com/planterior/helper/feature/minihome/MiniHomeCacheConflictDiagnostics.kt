package com.planterior.helper.feature.minihome

import com.planterior.helper.core.data.AuthoritativeCatalogItem
import com.planterior.helper.core.data.AuthoritativeInventoryAvailability
import com.planterior.helper.core.data.AuthoritativeInventoryCondition
import com.planterior.helper.core.data.AuthoritativeOwnedCatalogSnapshot
import com.planterior.helper.core.data.AuthoritativeOwnedItem
import com.planterior.helper.core.data.authoritativeInventorySnapshotHash
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import java.io.Closeable
import kotlinx.coroutines.CancellationException

enum class MiniHomeCacheDiagnosticStage {
    LAYOUT_APPLY,
    INVENTORY_APPLY,
    CURRENT_SNAPSHOT,
    VERIFIED_INVENTORY_DECODE,
    TERMINAL_CONFLICT,
}

enum class MiniHomeCacheDiagnosticOutcome {
    APPLIED,
    IGNORED,
    CONFLICT,
    VERIFIED,
}

enum class MiniHomeCacheConflictCategory {
    LAYOUT_APPLY,
    INVENTORY_APPLY,
    CURRENT_SNAPSHOT,
    VERIFIED_INVENTORY_DECODE,
}

enum class MiniHomeCacheConflictPredicate {
    LAYOUT_WATERMARK_ACCOUNT,
    LAYOUT_WATERMARK_GENERATION,
    LAYOUT_WATERMARK_KIND,
    LAYOUT_WATERMARK_REVISION,
    LAYOUT_WATERMARK_HOME_ID,
    LAYOUT_WATERMARK_OPERATION_ID,
    LAYOUT_WATERMARK_PAYLOAD_HASH,
    LAYOUT_WATERMARK_TOMBSTONE_ID,
    LAYOUT_WATERMARK_VERIFIED,
    LAYOUT_HOME_CONTENT,
    LAYOUT_PLACEMENT_CONTENT,
    LAYOUT_COHERENCE,
    INVENTORY_SNAPSHOT_HASH,
    INVENTORY_REGISTERED_PLANT_COUNT,
    INVENTORY_PARTIAL,
    INVENTORY_CATALOG_CONTENT,
    INVENTORY_OWNED_CONTENT,
    INVENTORY_COHERENCE,
    CURRENT_SNAPSHOT_MISSING,
    CURRENT_LAYOUT_MISSING,
    CURRENT_INVENTORY_MISSING,
    CURRENT_LAYOUT_UNVERIFIED,
    CURRENT_INVENTORY_UNVERIFIED,
    CURRENT_LAYOUT_TOKEN_MISSING,
    CURRENT_LAYOUT_GENERATION_MISSING,
    CURRENT_TOKEN_MISMATCH,
    CURRENT_GENERATION_MISMATCH,
    VERIFIED_INVENTORY_DECODE_FIELD,
}

internal fun selectLayoutCacheConflictPredicate(
    operands: Map<String, String?>
): MiniHomeCacheConflictPredicate {
    val comparisons =
        listOf(
            MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_ACCOUNT to "accountId",
            MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_GENERATION to "generation",
            MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_KIND to "kind",
            MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_REVISION to "layoutRevision",
            MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_HOME_ID to "miniHomeId",
            MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_OPERATION_ID to "operationId",
            MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_PAYLOAD_HASH to "payloadHash",
            MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_TOMBSTONE_ID to "tombstoneId",
            MiniHomeCacheConflictPredicate.LAYOUT_WATERMARK_VERIFIED to "verified",
        )
    comparisons
        .firstOrNull { (_, field) ->
            operands["watermark.current.$field"] != operands["watermark.candidate.$field"]
        }
        ?.let {
            return it.first
        }
    if (!sameCanonicalFields(operands, "current.home", "candidate.home")) {
        return MiniHomeCacheConflictPredicate.LAYOUT_HOME_CONTENT
    }
    if (!sameCanonicalFields(operands, "current.placements", "candidate.placements")) {
        return MiniHomeCacheConflictPredicate.LAYOUT_PLACEMENT_CONTENT
    }
    return MiniHomeCacheConflictPredicate.LAYOUT_COHERENCE
}

internal fun selectInventoryCacheConflictPredicate(
    operands: Map<String, String?>
): MiniHomeCacheConflictPredicate =
    when {
        operands["inventory.current.snapshotHash"] !=
            operands["inventory.candidate.snapshotHash"] ->
            MiniHomeCacheConflictPredicate.INVENTORY_SNAPSHOT_HASH
        operands["inventory.current.registeredPlantCount"] !=
            operands["inventory.candidate.registeredPlantCount"] ->
            MiniHomeCacheConflictPredicate.INVENTORY_REGISTERED_PLANT_COUNT
        operands["inventory.current.partial"] != operands["inventory.candidate.partial"] ->
            MiniHomeCacheConflictPredicate.INVENTORY_PARTIAL
        !sameCanonicalFields(operands, "current.catalog", "candidate.catalog") ->
            MiniHomeCacheConflictPredicate.INVENTORY_CATALOG_CONTENT
        !sameCanonicalFields(operands, "current.owned", "candidate.owned") ->
            MiniHomeCacheConflictPredicate.INVENTORY_OWNED_CONTENT
        else -> MiniHomeCacheConflictPredicate.INVENTORY_COHERENCE
    }

internal fun selectCurrentSnapshotConflictPredicate(
    operands: Map<String, String?>
): MiniHomeCacheConflictPredicate =
    when {
        operands["snapshot.present"] != "true" ->
            MiniHomeCacheConflictPredicate.CURRENT_SNAPSHOT_MISSING
        operands["layout.present"] != "true" ->
            MiniHomeCacheConflictPredicate.CURRENT_LAYOUT_MISSING
        operands["inventory.present"] != "true" ->
            MiniHomeCacheConflictPredicate.CURRENT_INVENTORY_MISSING
        operands["layout.verified"] != "true" ->
            MiniHomeCacheConflictPredicate.CURRENT_LAYOUT_UNVERIFIED
        operands["inventory.verified"] != "true" ->
            MiniHomeCacheConflictPredicate.CURRENT_INVENTORY_UNVERIFIED
        operands["layout.token"] == null ->
            MiniHomeCacheConflictPredicate.CURRENT_LAYOUT_TOKEN_MISSING
        operands["layout.generation"] == null ->
            MiniHomeCacheConflictPredicate.CURRENT_LAYOUT_GENERATION_MISSING
        operands["layout.token"] != operands["inventory.token"] ->
            MiniHomeCacheConflictPredicate.CURRENT_TOKEN_MISMATCH
        else -> MiniHomeCacheConflictPredicate.CURRENT_GENERATION_MISMATCH
    }

private fun sameCanonicalFields(
    operands: Map<String, String?>,
    currentPrefix: String,
    candidatePrefix: String,
): Boolean {
    fun normalized(prefix: String) =
        operands
            .filterKeys { it == prefix || it.startsWith("$prefix.") }
            .mapKeys { (key, _) -> key.removePrefix(prefix) }
    return normalized(currentPrefix) == normalized(candidatePrefix)
}

data class MiniHomeCacheDiagnosticObservation(
    val stage: MiniHomeCacheDiagnosticStage,
    val accountId: AccountId,
    val operationId: OperationId?,
    val outcome: MiniHomeCacheDiagnosticOutcome,
    val category: MiniHomeCacheConflictCategory? = null,
    val predicate: MiniHomeCacheConflictPredicate? = null,
    val operands: Map<String, String?> = emptyMap(),
)

data class MiniHomeCacheDiagnosticReceipt(
    val accountId: AccountId,
    val operationId: OperationId,
    val observations: List<MiniHomeCacheDiagnosticObservation>,
    val closed: Boolean,
) {
    fun requireComplete(): MiniHomeCacheDiagnosticReceipt {
        require(closed) { "mini-home-cache-capture-unclosed" }
        require(observations.isNotEmpty()) { "mini-home-cache-capture-missing" }
        require(observations.all { it.accountId == accountId }) {
            "mini-home-cache-capture-account-mismatch"
        }
        require(observations.all { it.operationId == operationId }) {
            "mini-home-cache-capture-operation-mismatch"
        }
        observations.forEach(::requireValidCacheObservation)
        val terminals = observations.filter {
            it.stage == MiniHomeCacheDiagnosticStage.TERMINAL_CONFLICT
        }
        require(terminals.size <= 1) { "mini-home-cache-capture-multiple-terminal" }
        if (terminals.isEmpty()) {
            require(
                observations.map { it.stage } ==
                    listOf(
                        MiniHomeCacheDiagnosticStage.LAYOUT_APPLY,
                        MiniHomeCacheDiagnosticStage.INVENTORY_APPLY,
                        MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT,
                        MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                    )
            ) {
                "mini-home-cache-success-stage-order"
            }
            require(
                observations.take(2).all {
                    it.outcome == MiniHomeCacheDiagnosticOutcome.APPLIED ||
                        it.outcome == MiniHomeCacheDiagnosticOutcome.IGNORED
                } &&
                    observations.drop(2).all {
                        it.outcome == MiniHomeCacheDiagnosticOutcome.VERIFIED
                    }
            ) {
                "mini-home-cache-success-outcomes"
            }
            require(observations.all { it.category == null && it.predicate == null }) {
                "mini-home-cache-success-selected-conflict"
            }
            return this
        }
        require(observations.last().stage == MiniHomeCacheDiagnosticStage.TERMINAL_CONFLICT) {
            "mini-home-cache-stage-after-terminal"
        }
        val terminal = terminals.single()
        val category =
            requireNotNull(terminal.category) { "mini-home-cache-terminal-category-missing" }
        val predicate =
            requireNotNull(terminal.predicate) { "mini-home-cache-terminal-predicate-missing" }
        require(terminal.outcome == MiniHomeCacheDiagnosticOutcome.CONFLICT) {
            "mini-home-cache-terminal-outcome"
        }
        val enteredStage = category.stage()
        val entered = observations.dropLast(1).lastOrNull()
        require(entered?.stage == enteredStage) { "mini-home-cache-conflict-stage-missing" }
        require(entered.outcome == MiniHomeCacheDiagnosticOutcome.CONFLICT) {
            "mini-home-cache-conflict-stage-outcome"
        }
        require(entered.category == category && entered.predicate == predicate) {
            "mini-home-cache-conflict-selection-mismatch"
        }
        require(entered.operands == terminal.operands) {
            "mini-home-cache-conflict-operands-mismatch"
        }
        val expectedPrefix =
            listOf(
                    MiniHomeCacheDiagnosticStage.LAYOUT_APPLY,
                    MiniHomeCacheDiagnosticStage.INVENTORY_APPLY,
                    MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT,
                    MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                )
                .takeWhile { it != enteredStage }
        require(observations.dropLast(2).map { it.stage } == expectedPrefix) {
            "mini-home-cache-conflict-stage-order"
        }
        require(
            observations.dropLast(2).none { it.outcome == MiniHomeCacheDiagnosticOutcome.CONFLICT }
        ) {
            "mini-home-cache-multiple-selected"
        }
        return this
    }
}

class MiniHomeCacheDiagnosticRecorder(
    private val accountId: AccountId,
    private val operationId: OperationId,
) : MiniHomeCacheDiagnosticSink {
    private val lock = Any()
    private val observations = mutableListOf<MiniHomeCacheDiagnosticObservation>()
    private var closed = false
    private var invalidReason: String? = null

    override fun observe(observation: MiniHomeCacheDiagnosticObservation) {
        synchronized(lock) {
            val rejection =
                when {
                    closed -> "mini-home-cache-recorder-closed"
                    observation.accountId != accountId ->
                        "mini-home-cache-recorder-account-mismatch"
                    observation.operationId != operationId ->
                        "mini-home-cache-recorder-operation-mismatch"
                    observations.any {
                        it.stage == MiniHomeCacheDiagnosticStage.TERMINAL_CONFLICT
                    } -> "mini-home-cache-stage-after-terminal"
                    else -> null
                }
            if (rejection != null) {
                invalidReason = invalidReason ?: rejection
                throw IllegalArgumentException(rejection)
            }
            observations += observation
        }
    }

    fun close(): MiniHomeCacheDiagnosticReceipt =
        synchronized(lock) {
            check(!closed) { "mini-home-cache-recorder-already-closed" }
            closed = true
            require(invalidReason == null) { invalidReason.orEmpty() }
            MiniHomeCacheDiagnosticReceipt(accountId, operationId, observations.toList(), true)
                .requireComplete()
        }
}

private fun MiniHomeCacheConflictCategory.stage(): MiniHomeCacheDiagnosticStage =
    when (this) {
        MiniHomeCacheConflictCategory.LAYOUT_APPLY -> MiniHomeCacheDiagnosticStage.LAYOUT_APPLY
        MiniHomeCacheConflictCategory.INVENTORY_APPLY ->
            MiniHomeCacheDiagnosticStage.INVENTORY_APPLY
        MiniHomeCacheConflictCategory.CURRENT_SNAPSHOT ->
            MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT
        MiniHomeCacheConflictCategory.VERIFIED_INVENTORY_DECODE ->
            MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE
    }

private fun requireValidCacheObservation(observation: MiniHomeCacheDiagnosticObservation) {
    require(observation.operands.isNotEmpty()) { "mini-home-cache-operands-missing" }
    require(observation.operands.keys.all { it.isNotBlank() }) {
        "mini-home-cache-operand-key-malformed"
    }
    observation.operands
        .filterKeys { it.endsWith(".count") }
        .forEach { (countKey, value) ->
            val count = value?.toIntOrNull()
            require(count != null && count >= 0) { "mini-home-cache-canonical-count-malformed" }
            val prefix = countKey.removeSuffix("count")
            val indices =
                observation.operands.keys
                    .mapNotNull { key ->
                        if (!key.startsWith(prefix)) return@mapNotNull null
                        key.removePrefix(prefix).substringBefore('.').toIntOrNull()
                    }
                    .toSet()
            require(indices == (0 until count).toSet()) {
                "mini-home-cache-canonical-content-malformed"
            }
        }
    if (observation.outcome == MiniHomeCacheDiagnosticOutcome.CONFLICT) {
        require(observation.category != null && observation.predicate != null) {
            "mini-home-cache-conflict-selection-missing"
        }
        requireConflictSelection(observation)
    } else {
        require(observation.category == null && observation.predicate == null) {
            "mini-home-cache-nonconflict-selection-present"
        }
        requireSuccessSelection(observation)
    }
}

private fun requireSuccessSelection(observation: MiniHomeCacheDiagnosticObservation) {
    when (observation.stage) {
        MiniHomeCacheDiagnosticStage.LAYOUT_APPLY -> requireLayoutSuccess(observation)
        MiniHomeCacheDiagnosticStage.INVENTORY_APPLY -> requireInventorySuccess(observation)
        MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT -> requireCurrentSnapshotSuccess(observation)
        MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE ->
            requireVerifiedInventoryDecodeSuccess(observation)
        MiniHomeCacheDiagnosticStage.TERMINAL_CONFLICT ->
            throw IllegalArgumentException("mini-home-cache-nonconflict-terminal")
    }
}

private fun requireLayoutSuccess(observation: MiniHomeCacheDiagnosticObservation) {
    val operands = observation.operands
    val expectedKeys = layoutSuccessKeys(operands)
    requireExactKeys(operands, expectedKeys, "layout-success")
    requireLayoutBeforeState(operands)
    requireAuthoritativeLayoutState(operands, "after", allowAbsent = false, allowConverged = true)
    requireAuthoritativeLayoutState(
        operands,
        "candidate",
        allowAbsent = false,
        allowConverged = false,
    )
    require(
        operands["after.accountId"] == observation.accountId.value &&
            operands["candidate.accountId"] == observation.accountId.value &&
            (operands["before.present"] == "false" ||
                operands["before.accountId"] == observation.accountId.value)
    ) {
        "mini-home-cache-layout-success-account"
    }
    val beforePresent = operands["before.present"] == "true"
    val beforeGeneration = operands["before.generation"]?.toLongOrNull()
    val candidateGeneration = requirePositiveLong(operands, "candidate.generation")
    requireGenerationOrdering(
        (beforeGeneration ?: 0).toString(),
        candidateGeneration.toString(),
        operands["candidate.generationOrdering"],
    )
    val beforeVerified = operands["before.verified"] == "true"
    val expectedOutcome =
        when {
            !beforePresent || !beforeVerified -> MiniHomeCacheDiagnosticOutcome.APPLIED
            candidateGeneration < requireNotNull(beforeGeneration) ->
                MiniHomeCacheDiagnosticOutcome.IGNORED
            candidateGeneration == beforeGeneration &&
                operands["before.kind"] == "CONVERGED_ABSENCE" &&
                operands["candidate.kind"] == "DELETED" -> MiniHomeCacheDiagnosticOutcome.IGNORED
            candidateGeneration == beforeGeneration -> {
                require(layoutDomainSame(operands, "before", "candidate")) {
                    "mini-home-cache-layout-success-domain"
                }
                require(layoutContentSame(operands, "before", "candidate")) {
                    "mini-home-cache-layout-success-content"
                }
                coherenceOutcome(operands, "before", "candidate")
            }
            operands["before.kind"] == "PRESENT" &&
                operands["candidate.kind"] == "PRESENT" &&
                requireNotNull(operands["candidate.home.revision"]?.toLongOrNull()) <
                    requireNotNull(operands["before.home.revision"]?.toLongOrNull()) ->
                MiniHomeCacheDiagnosticOutcome.IGNORED
            else -> MiniHomeCacheDiagnosticOutcome.APPLIED
        }
    require(observation.outcome == expectedOutcome) { "mini-home-cache-layout-success-outcome" }
    if (expectedOutcome == MiniHomeCacheDiagnosticOutcome.APPLIED) {
        require(layoutStateSame(operands, "after", "candidate")) {
            "mini-home-cache-layout-applied-current"
        }
    } else {
        require(layoutStateSame(operands, "before", "after")) {
            "mini-home-cache-layout-ignored-current"
        }
    }
}

private fun requireInventorySuccess(observation: MiniHomeCacheDiagnosticObservation) {
    val operands = observation.operands
    val expectedKeys = inventorySuccessKeys(operands)
    requireExactKeys(operands, expectedKeys, "inventory-success")
    requireInventoryBeforeState(operands)
    requireAuthoritativeInventoryState(operands, "after", allowAbsent = false)
    requireAuthoritativeInventoryState(operands, "candidate", allowAbsent = false)
    require(
        operands["after.accountId"] == observation.accountId.value &&
            operands["candidate.accountId"] == observation.accountId.value &&
            (operands["before.present"] == "false" ||
                operands["before.accountId"] == observation.accountId.value)
    ) {
        "mini-home-cache-inventory-success-account"
    }
    val beforePresent = operands["before.present"] == "true"
    val beforeGeneration = operands["before.generation"]?.toLongOrNull()
    val candidateGeneration = requirePositiveLong(operands, "candidate.generation")
    requireGenerationOrdering(
        (beforeGeneration ?: 0).toString(),
        candidateGeneration.toString(),
        operands["candidate.generationOrdering"],
    )
    val beforeVerified = operands["before.verified"] == "true"
    val expectedOutcome =
        when {
            !beforePresent || !beforeVerified -> MiniHomeCacheDiagnosticOutcome.APPLIED
            candidateGeneration < requireNotNull(beforeGeneration) ->
                MiniHomeCacheDiagnosticOutcome.IGNORED
            candidateGeneration == beforeGeneration -> {
                require(inventoryIdentitySame(operands, "before", "candidate")) {
                    "mini-home-cache-inventory-success-identity"
                }
                require(inventoryContentSame(operands, "before", "candidate")) {
                    "mini-home-cache-inventory-success-content"
                }
                coherenceOutcome(operands, "before", "candidate")
            }
            else -> MiniHomeCacheDiagnosticOutcome.APPLIED
        }
    require(observation.outcome == expectedOutcome) { "mini-home-cache-inventory-success-outcome" }
    if (expectedOutcome == MiniHomeCacheDiagnosticOutcome.APPLIED) {
        val sameGenerationCoherenceApply =
            beforePresent && beforeVerified && candidateGeneration == beforeGeneration
        if (sameGenerationCoherenceApply) {
            require(inventoryStateSameExceptCoherence(operands, "before", "after")) {
                "mini-home-cache-inventory-coherence-applied-current"
            }
            require(
                operands["after.snapshotToken"] == operands["candidate.snapshotToken"] &&
                    operands["after.snapshotGeneration"] == operands["candidate.snapshotGeneration"]
            ) {
                "mini-home-cache-inventory-coherence-applied-snapshot"
            }
        } else {
            require(inventoryStateSame(operands, "after", "candidate")) {
                "mini-home-cache-inventory-applied-current"
            }
        }
    } else {
        require(inventoryStateSame(operands, "before", "after")) {
            "mini-home-cache-inventory-ignored-current"
        }
    }
}

private fun layoutSuccessKeys(operands: Map<String, String?>): Set<String> = buildSet {
    add("candidate.generationOrdering")
    CACHE_STATE_PREFIXES.forEach { prefix ->
        val count = operands["$prefix.placements.count"]?.toIntOrNull()
        require(count != null && count >= 0) { "mini-home-cache-layout-success-placement-count" }
        addAll(LAYOUT_STATE_FIELDS.map { "$prefix.$it" })
        addAll(HOME_FIELDS.map { "$prefix.home.$it" })
        add("$prefix.placements.count")
        add("$prefix.placements.order")
        addAll(canonicalKeys("$prefix.placements", count, PLACEMENT_FIELDS))
    }
}

private fun inventorySuccessKeys(operands: Map<String, String?>): Set<String> = buildSet {
    add("candidate.generationOrdering")
    CACHE_STATE_PREFIXES.forEach { prefix ->
        val catalogCount = operands["$prefix.catalog.count"]?.toIntOrNull()
        val ownedCount = operands["$prefix.owned.count"]?.toIntOrNull()
        require(catalogCount != null && catalogCount in 0..200) {
            "mini-home-cache-inventory-success-catalog-count"
        }
        require(ownedCount != null && ownedCount in 0..200) {
            "mini-home-cache-inventory-success-owned-count"
        }
        addAll(INVENTORY_STATE_FIELDS.map { "$prefix.$it" })
        add("$prefix.catalog.count")
        add("$prefix.owned.count")
        addAll(canonicalKeys("$prefix.catalog", catalogCount, CATALOG_FIELDS))
        addAll(canonicalKeys("$prefix.owned", ownedCount, OWNED_FIELDS))
    }
}

private fun requireLayoutBeforeState(operands: Map<String, String?>) {
    when {
        operands["before.present"] == "false" ->
            requireAuthoritativeLayoutState(
                operands,
                "before",
                allowAbsent = true,
                allowConverged = true,
            )
        operands["before.verified"] == "true" ->
            requireAuthoritativeLayoutState(
                operands,
                "before",
                allowAbsent = false,
                allowConverged = true,
            )
        operands["before.verified"] == "false" -> requireLegacyLayoutBeforeState(operands)
        else -> throw IllegalArgumentException("mini-home-cache-layout-before-verification")
    }
}

private fun requireAuthoritativeLayoutState(
    operands: Map<String, String?>,
    prefix: String,
    allowAbsent: Boolean,
    allowConverged: Boolean,
) {
    val present = operands["$prefix.present"]
    require(present in setOf("true", "false")) { "mini-home-cache-layout-state-presence" }
    val placementCount = requireNonnegativeInt(operands, "$prefix.placements.count")
    if (present == "false") {
        require(allowAbsent) { "mini-home-cache-layout-state-required" }
        require(
            (LAYOUT_STATE_FIELDS - "present").all { operands["$prefix.$it"] == null } &&
                operands["$prefix.home.present"] == "false" &&
                (HOME_FIELDS - "present").all { operands["$prefix.home.$it"] == null } &&
                placementCount == 0 &&
                operands["$prefix.placements.order"].isNullOrEmpty()
        ) {
            "mini-home-cache-layout-state-absent"
        }
        return
    }
    require(!operands["$prefix.accountId"].isNullOrBlank()) {
        "mini-home-cache-layout-state-account"
    }
    requirePositiveLong(operands, "$prefix.generation")
    require(
        operands["$prefix.authoritativeAtEpochMillis"]?.toLongOrNull()?.let { it >= 0 } == true
    ) {
        "mini-home-cache-layout-state-authoritative-at"
    }
    require(operands["$prefix.verified"] == "true") {
        "mini-home-cache-layout-state-verified"
    }
    requirePairedSnapshotIdentity(operands, prefix)
    when (operands["$prefix.kind"]) {
        "PRESENT" -> {
            require(
                requireNotNull(operands["$prefix.layoutRevision"]?.toLongOrNull()) >= 1 &&
                    !operands["$prefix.miniHomeId"].isNullOrBlank() &&
                    !operands["$prefix.operationId"].isNullOrBlank() &&
                    operands["$prefix.payloadHash"].isSha256() &&
                    operands["$prefix.tombstoneId"] == null &&
                    operands["$prefix.home.present"] == "true"
            ) {
                "mini-home-cache-layout-state-present"
            }
            require(
                operands["$prefix.home.accountId"] == operands["$prefix.accountId"] &&
                    operands["$prefix.home.miniHomeId"] == operands["$prefix.miniHomeId"] &&
                    operands["$prefix.home.revision"] == operands["$prefix.layoutRevision"] &&
                    !operands["$prefix.home.name"].isNullOrBlank() &&
                    operands["$prefix.home.placedPlantCount"]?.toIntOrNull()?.let { it >= 0 } ==
                        true &&
                    operands["$prefix.home.updatedAtEpochMillis"]?.toLongOrNull()?.let {
                        it >= 0
                    } == true
            ) {
                "mini-home-cache-layout-state-home"
            }
            requireLayoutPlacements(operands, prefix, placementCount)
        }
        "DELETED" ->
            requireEmptyLayoutContent(
                operands,
                prefix,
                placementCount,
                tombstoneRequired = true,
            )
        "CONVERGED_ABSENCE" -> {
            require(allowConverged) { "mini-home-cache-layout-state-converged-candidate" }
            requireEmptyLayoutContent(
                operands,
                prefix,
                placementCount,
                tombstoneRequired = false,
            )
        }
        else -> throw IllegalArgumentException("mini-home-cache-layout-state-kind")
    }
}

private fun requireLegacyLayoutBeforeState(operands: Map<String, String?>) {
    val prefix = "before"
    val placementCount = requireNonnegativeInt(operands, "$prefix.placements.count")
    require(operands["$prefix.present"] == "true") {
        "mini-home-cache-layout-legacy-presence"
    }
    require(!operands["$prefix.accountId"].isNullOrBlank()) {
        "mini-home-cache-layout-legacy-account"
    }
    val generation =
        requireNotNull(operands["$prefix.generation"]?.toLongOrNull()).also {
            require(it >= 0) { "mini-home-cache-layout-legacy-generation" }
        }
    require(operands["$prefix.kind"] == "PRESENT") {
        "mini-home-cache-layout-legacy-kind"
    }
    val revision =
        requireNotNull(operands["$prefix.layoutRevision"]?.toLongOrNull()).also {
            require(it >= 1) { "mini-home-cache-layout-legacy-revision" }
        }
    require(generation == revision || generation == maxOf(0, revision - 1)) {
        "mini-home-cache-layout-legacy-generation-relation"
    }
    require(
        !operands["$prefix.miniHomeId"].isNullOrBlank() &&
            operands["$prefix.operationId"] == null &&
            operands["$prefix.payloadHash"] == null &&
            operands["$prefix.tombstoneId"] == null &&
            operands["$prefix.authoritativeAtEpochMillis"]?.toLongOrNull()?.let { it >= 0 } ==
                true &&
            operands["$prefix.verified"] == "false" &&
            operands["$prefix.home.present"] == "true"
    ) {
        "mini-home-cache-layout-legacy-watermark"
    }
    requirePairedSnapshotIdentity(operands, prefix)
    require(
        operands["$prefix.home.accountId"] == operands["$prefix.accountId"] &&
            operands["$prefix.home.miniHomeId"] == operands["$prefix.miniHomeId"] &&
            operands["$prefix.home.revision"] == operands["$prefix.layoutRevision"] &&
            !operands["$prefix.home.name"].isNullOrBlank() &&
            operands["$prefix.home.placedPlantCount"]?.toIntOrNull()?.let { it >= 0 } == true &&
            operands["$prefix.home.updatedAtEpochMillis"]?.toLongOrNull()?.let { it >= 0 } == true
    ) {
        "mini-home-cache-layout-legacy-home"
    }
    requireLayoutPlacements(operands, prefix, placementCount)
}

private fun requireEmptyLayoutContent(
    operands: Map<String, String?>,
    prefix: String,
    placementCount: Int,
    tombstoneRequired: Boolean,
) {
    require(
        operands["$prefix.layoutRevision"] == null &&
            operands["$prefix.miniHomeId"] == null &&
            operands["$prefix.operationId"] == null &&
            operands["$prefix.payloadHash"] == null &&
            if (tombstoneRequired) {
                operands["$prefix.tombstoneId"]?.matches(Regex("^[A-Za-z0-9_-]{8,128}$")) == true
            } else {
                operands["$prefix.tombstoneId"] == null
            } &&
            operands["$prefix.home.present"] == "false" &&
            (HOME_FIELDS - "present").all { operands["$prefix.home.$it"] == null } &&
            placementCount == 0 &&
            operands["$prefix.placements.order"].isNullOrEmpty()
    ) {
        "mini-home-cache-layout-state-absence-content"
    }
}

private fun requireLayoutPlacements(
    operands: Map<String, String?>,
    prefix: String,
    count: Int,
) {
    val order =
        operands["$prefix.placements.order"]?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
    require(order.size == count && order.distinct().size == count) {
        "mini-home-cache-layout-state-placement-order"
    }
    require(
        (0 until count).all { index ->
            val key = "$prefix.placements.$index"
            operands["$key.accountId"] == operands["$prefix.accountId"] &&
                !operands["$key.placementId"].isNullOrBlank() &&
                operands["$key.miniHomeId"] == operands["$prefix.miniHomeId"] &&
                ((operands["$key.plantId"] == null) xor (operands["$key.itemId"] == null)) &&
                operands["$key.normalizedX"]?.toDoubleOrNull()?.let { it.isFinite() } == true &&
                operands["$key.normalizedY"]?.toDoubleOrNull()?.let { it.isFinite() } == true &&
                operands["$key.zIndex"]?.toIntOrNull()?.let { it >= 0 } == true &&
                operands["$key.layoutRevision"] == operands["$prefix.layoutRevision"]
        }
    ) {
        "mini-home-cache-layout-state-placement"
    }
}

private fun requireInventoryBeforeState(operands: Map<String, String?>) {
    when {
        operands["before.present"] == "false" ->
            requireAuthoritativeInventoryState(operands, "before", allowAbsent = true)
        operands["before.verified"] == "true" ->
            requireAuthoritativeInventoryState(operands, "before", allowAbsent = false)
        operands["before.verified"] == "false" -> requireLegacyInventoryBeforeState(operands)
        else -> throw IllegalArgumentException("mini-home-cache-inventory-before-verification")
    }
}

private fun requireAuthoritativeInventoryState(
    operands: Map<String, String?>,
    prefix: String,
    allowAbsent: Boolean,
) {
    val present = operands["$prefix.present"]
    require(present in setOf("true", "false")) { "mini-home-cache-inventory-state-presence" }
    val catalogCount = requireNonnegativeInt(operands, "$prefix.catalog.count")
    val ownedCount = requireNonnegativeInt(operands, "$prefix.owned.count")
    if (present == "false") {
        require(allowAbsent) { "mini-home-cache-inventory-state-required" }
        require(
            (INVENTORY_STATE_FIELDS - "present").all { operands["$prefix.$it"] == null } &&
                catalogCount == 0 &&
                ownedCount == 0
        ) {
            "mini-home-cache-inventory-state-absent"
        }
        return
    }
    val account = operands["$prefix.accountId"]
    require(account?.matches(Regex("^[A-Za-z0-9_-]{1,128}$")) == true) {
        "mini-home-cache-inventory-state-account"
    }
    requirePositiveLong(operands, "$prefix.generation")
    require(operands["$prefix.snapshotHash"].isSha256()) {
        "mini-home-cache-inventory-state-hash"
    }
    requireNonnegativeInt(operands, "$prefix.registeredPlantCount").also {
        require(it <= 200) { "mini-home-cache-inventory-state-registered-count" }
    }
    require(operands["$prefix.loadedAtEpochMillis"]?.toLongOrNull()?.let { it >= 0 } == true) {
        "mini-home-cache-inventory-state-loaded-at"
    }
    require(operands["$prefix.partial"] in setOf("true", "false")) {
        "mini-home-cache-inventory-state-partial"
    }
    require(operands["$prefix.verified"] == "true") {
        "mini-home-cache-inventory-state-verified"
    }
    requirePairedSnapshotIdentity(operands, prefix)
    val normalized = inventoryEntityOperands(operands, prefix, catalogCount, ownedCount)
    require((0 until catalogCount).none { catalogEntityMalformed(normalized, it) }) {
        "mini-home-cache-inventory-state-catalog"
    }
    require((0 until ownedCount).none { ownedEntityMalformed(normalized, it) }) {
        "mini-home-cache-inventory-state-owned"
    }
    require(
        (0 until catalogCount).map { normalized["catalog.$it.itemId"] }.distinct().size ==
            catalogCount
    ) {
        "mini-home-cache-inventory-state-catalog-duplicate"
    }
    require(
        (0 until ownedCount).map { normalized["owned.$it.itemId"] }.distinct().size == ownedCount
    ) {
        "mini-home-cache-inventory-state-owned-duplicate"
    }
}

private fun requireLegacyInventoryBeforeState(operands: Map<String, String?>) {
    val prefix = "before"
    val catalogCount = requireNonnegativeInt(operands, "$prefix.catalog.count")
    val ownedCount = requireNonnegativeInt(operands, "$prefix.owned.count")
    val account = operands["$prefix.accountId"]
    require(
        operands["$prefix.present"] == "true" &&
            account?.matches(Regex("^[A-Za-z0-9_-]{1,128}$")) == true &&
            operands["$prefix.generation"] == "0" &&
            operands["$prefix.snapshotHash"] == MIGRATION_INVENTORY_ZERO_HASH &&
            operands["$prefix.registeredPlantCount"] == "0" &&
            operands["$prefix.loadedAtEpochMillis"] == "0" &&
            operands["$prefix.partial"] == "true" &&
            operands["$prefix.verified"] == "false"
    ) {
        "mini-home-cache-inventory-legacy-watermark"
    }
    requirePairedSnapshotIdentity(operands, prefix)
    val normalized = inventoryEntityOperands(operands, prefix, catalogCount, ownedCount)
    require((0 until catalogCount).none { legacyCatalogEntityMalformed(normalized, it) }) {
        "mini-home-cache-inventory-legacy-catalog"
    }
    require((0 until ownedCount).none { legacyOwnedEntityMalformed(normalized, it) }) {
        "mini-home-cache-inventory-legacy-owned"
    }
    require(
        (0 until catalogCount).map { normalized["catalog.$it.itemId"] }.distinct().size ==
            catalogCount &&
            (0 until ownedCount).map { normalized["owned.$it.itemId"] }.distinct().size ==
                ownedCount
    ) {
        "mini-home-cache-inventory-legacy-duplicate"
    }
}

private fun legacyCatalogEntityMalformed(
    operands: Map<String, String?>,
    index: Int,
): Boolean {
    val key = "catalog.$index"
    val itemId = operands["$key.itemId"]
    val name = operands["$key.name"]
    val description = operands["$key.description"]
    return operands["$key.accountId"] != operands["expected.account"] ||
        itemId?.matches(Regex("^[A-Za-z0-9_-]{1,128}$")) != true ||
        name == null ||
        name.codePointCount(0, name.length) !in 1..100 ||
        description == null ||
        description.codePointCount(0, description.length) !in 1..500 ||
        operands["$key.category"] !in setOf("BACKGROUND", "FURNITURE", "DECORATION") ||
        operands["$key.assetPath"]?.startsWith("catalog-assets/$itemId/") != true ||
        operands["$key.acquisitionCondition"] !in setOf(null, "registered-plant") ||
        (operands["$key.revision"]?.toLongOrNull() ?: 0) < 1 ||
        operands["$key.updatedAtEpochMillis"]?.toLongOrNull() !in 0..9_007_199_254_740_991L ||
        operands["$key.assetSha256"] != "" ||
        operands["$key.assetByteSize"] != "0" ||
        operands["$key.assetMimeType"] != "" ||
        operands["$key.assetWidth"] != "0" ||
        operands["$key.assetHeight"] != "0" ||
        operands["$key.assetMediaRevision"] != "0"
}

private fun legacyOwnedEntityMalformed(
    operands: Map<String, String?>,
    index: Int,
): Boolean {
    val key = "owned.$index"
    val itemId = operands["$key.itemId"]
    val legacySnapshot =
        listOf(
            operands["$key.nameSnapshot"],
            operands["$key.categorySnapshot"],
            operands["$key.assetPathSnapshot"],
            operands["$key.catalogRevisionSnapshot"],
        )
    val migratedMediaDefaults =
        listOf(
            operands["$key.assetSha256Snapshot"],
            operands["$key.assetByteSizeSnapshot"],
            operands["$key.assetMimeTypeSnapshot"],
            operands["$key.assetWidthSnapshot"],
            operands["$key.assetHeightSnapshot"],
            operands["$key.assetMediaRevisionSnapshot"],
        )
    val snapshotValid =
        legacySnapshot.all { it == null } ||
            (legacySnapshot.none { it == null } &&
                operands["$key.nameSnapshot"].let {
                    it != null && it.codePointCount(0, it.length) in 1..100
                } &&
                operands["$key.categorySnapshot"] in
                    setOf("BACKGROUND", "FURNITURE", "DECORATION") &&
                operands["$key.assetPathSnapshot"]?.startsWith("catalog-assets/$itemId/") == true &&
                (operands["$key.catalogRevisionSnapshot"]?.toLongOrNull() ?: 0) >= 1)
    return operands["$key.accountId"] != operands["expected.account"] ||
        itemId?.matches(Regex("^[A-Za-z0-9_-]{1,128}$")) != true ||
        operands["$key.acquiredAtEpochMillis"]?.toLongOrNull() !in 0..9_007_199_254_740_991L ||
        operands["$key.applied"] !in setOf("true", "false") ||
        (operands["$key.revision"]?.toLongOrNull() ?: 0) < 1 ||
        operands["$key.availability"] !in setOf("AVAILABLE", "UNAVAILABLE") ||
        !snapshotValid ||
        migratedMediaDefaults.any { it != null }
}

private fun inventoryEntityOperands(
    operands: Map<String, String?>,
    prefix: String,
    catalogCount: Int,
    ownedCount: Int,
): Map<String, String?> = buildMap {
    put("expected.account", operands["$prefix.accountId"])
    put("catalog.count", catalogCount.toString())
    put("owned.count", ownedCount.toString())
    (0 until catalogCount).forEach { index ->
        CATALOG_FIELDS.forEach { field ->
            put("catalog.$index.$field", operands["$prefix.catalog.$index.$field"])
        }
    }
    (0 until ownedCount).forEach { index ->
        OWNED_FIELDS.forEach { field ->
            put("owned.$index.$field", operands["$prefix.owned.$index.$field"])
        }
    }
}

private fun requirePairedSnapshotIdentity(operands: Map<String, String?>, prefix: String) {
    val token = operands["$prefix.snapshotToken"]
    val generation = operands["$prefix.snapshotGeneration"]
    require((token == null) == (generation == null)) { "mini-home-cache-snapshot-identity-pair" }
    token?.let {
        require(it.isSha256() && requireNotNull(generation?.toLongOrNull()) >= 1) {
            "mini-home-cache-snapshot-identity-value"
        }
    }
}

private fun coherenceOutcome(
    operands: Map<String, String?>,
    current: String,
    candidate: String,
): MiniHomeCacheDiagnosticOutcome {
    val currentToken = operands["$current.snapshotToken"]
    val currentGeneration = operands["$current.snapshotGeneration"]?.toLongOrNull()
    val candidateToken = operands["$candidate.snapshotToken"]
    val candidateGeneration = operands["$candidate.snapshotGeneration"]?.toLongOrNull()
    return when {
        candidateToken == null -> MiniHomeCacheDiagnosticOutcome.IGNORED
        currentToken == null -> MiniHomeCacheDiagnosticOutcome.APPLIED
        requireNotNull(candidateGeneration) > requireNotNull(currentGeneration) ->
            MiniHomeCacheDiagnosticOutcome.APPLIED
        candidateGeneration < currentGeneration -> MiniHomeCacheDiagnosticOutcome.IGNORED
        candidateToken == currentToken -> MiniHomeCacheDiagnosticOutcome.IGNORED
        else -> throw IllegalArgumentException("mini-home-cache-success-coherence-conflict")
    }
}

private fun layoutDomainSame(operands: Map<String, String?>, left: String, right: String): Boolean =
    LAYOUT_DOMAIN_FIELDS.all {
        operands["$left.$it"] == operands["$right.$it"]
    }

private fun layoutContentSame(
    operands: Map<String, String?>,
    left: String,
    right: String,
): Boolean =
    HOME_FIELDS.all { operands["$left.home.$it"] == operands["$right.home.$it"] } &&
        canonicalContentSame(operands, "$left.placements", "$right.placements", PLACEMENT_FIELDS)

private fun layoutStateSame(operands: Map<String, String?>, left: String, right: String): Boolean =
    LAYOUT_STATE_FIELDS.all { operands["$left.$it"] == operands["$right.$it"] } &&
        layoutContentSame(operands, left, right)

private fun inventoryIdentitySame(
    operands: Map<String, String?>,
    left: String,
    right: String,
): Boolean = INVENTORY_IDENTITY_FIELDS.all { operands["$left.$it"] == operands["$right.$it"] }

private fun inventoryContentSame(
    operands: Map<String, String?>,
    left: String,
    right: String,
): Boolean =
    canonicalContentSame(operands, "$left.catalog", "$right.catalog", CATALOG_FIELDS) &&
        canonicalContentSame(operands, "$left.owned", "$right.owned", OWNED_FIELDS)

private fun inventoryStateSame(
    operands: Map<String, String?>,
    left: String,
    right: String,
): Boolean =
    INVENTORY_STATE_FIELDS.all { operands["$left.$it"] == operands["$right.$it"] } &&
        inventoryContentSame(operands, left, right)

private fun inventoryStateSameExceptCoherence(
    operands: Map<String, String?>,
    left: String,
    right: String,
): Boolean =
    (INVENTORY_STATE_FIELDS - setOf("snapshotToken", "snapshotGeneration")).all {
        operands["$left.$it"] == operands["$right.$it"]
    } && inventoryContentSame(operands, left, right)

private fun canonicalContentSame(
    operands: Map<String, String?>,
    left: String,
    right: String,
    fields: Set<String>,
): Boolean {
    val leftCount = operands["$left.count"]?.toIntOrNull() ?: return false
    val rightCount = operands["$right.count"]?.toIntOrNull() ?: return false
    if (leftCount != rightCount) return false
    if (operands["$left.order"] != operands["$right.order"] && fields == PLACEMENT_FIELDS) {
        return false
    }
    return (0 until leftCount).all { index ->
        fields.all { field -> operands["$left.$index.$field"] == operands["$right.$index.$field"] }
    }
}

private fun requireCurrentSnapshotSuccess(observation: MiniHomeCacheDiagnosticObservation) {
    val operands = observation.operands
    requireExactKeys(operands, CURRENT_SNAPSHOT_KEYS, "current-success")
    require(observation.outcome == MiniHomeCacheDiagnosticOutcome.VERIFIED) {
        "mini-home-cache-current-success-outcome"
    }
    require(
        operands["snapshot.present"] == "true" &&
            operands["layout.present"] == "true" &&
            operands["inventory.present"] == "true" &&
            operands["layout.verified"] == "true" &&
            operands["inventory.verified"] == "true"
    ) {
        "mini-home-cache-current-success-presence"
    }
    require(
        operands["layout.accountId"] == observation.accountId.value &&
            operands["inventory.accountId"] == observation.accountId.value
    ) {
        "mini-home-cache-current-success-account"
    }
    require(
        operands["layout.token"].isSnapshotToken() &&
            operands["layout.token"] == operands["inventory.token"]
    ) {
        "mini-home-cache-current-success-token"
    }
    val layoutGeneration = requirePositiveLong(operands, "layout.generation")
    val inventoryGeneration = requirePositiveLong(operands, "inventory.generation")
    require(layoutGeneration == inventoryGeneration) {
        "mini-home-cache-current-success-generation"
    }
    require(operands["layout.homePresent"] in setOf("true", "false")) {
        "mini-home-cache-current-success-home"
    }
    val placementCount = requireNonnegativeInt(operands, "layout.placementCount")
    requireNonnegativeInt(operands, "inventory.catalogCount")
    requireNonnegativeInt(operands, "inventory.ownedCount")
    require(operands["layout.homePresent"] == "true" || placementCount == 0) {
        "mini-home-cache-current-success-layout-content"
    }
}

private fun requireVerifiedInventoryDecodeSuccess(observation: MiniHomeCacheDiagnosticObservation) {
    val operands = observation.operands
    val catalogCount = operands["catalog.count"]?.toIntOrNull()
    val ownedCount = operands["owned.count"]?.toIntOrNull()
    require(catalogCount != null && catalogCount in 0..200) {
        "mini-home-cache-decode-success-catalog-count"
    }
    require(ownedCount != null && ownedCount in 0..200) {
        "mini-home-cache-decode-success-owned-count"
    }
    val expectedKeys =
        DECODE_SUCCESS_KEYS +
            canonicalKeys("catalog", catalogCount, CATALOG_FIELDS) +
            canonicalKeys("owned", ownedCount, OWNED_FIELDS)
    requireExactKeys(operands, expectedKeys, "decode-success")
    require(observation.outcome == MiniHomeCacheDiagnosticOutcome.VERIFIED) {
        "mini-home-cache-decode-success-outcome"
    }
    require(operands.containsKey("field") && operands["field"] == null) {
        "mini-home-cache-decode-success-field"
    }
    require(operands["failure.index"] == null) { "mini-home-cache-decode-success-index" }
    require(
        operands["expected.account"] == observation.accountId.value &&
            operands["actual.account"] == observation.accountId.value &&
            operands["verified"] == "true"
    ) {
        "mini-home-cache-decode-success-owner"
    }
    requirePositiveLong(operands, "generation")
    require(operands["snapshotHash"].isSha256()) { "mini-home-cache-decode-success-hash" }
    val registeredPlantCount =
        requireNonnegativeInt(operands, "registeredPlantCount").also {
            require(it <= 200) { "mini-home-cache-decode-success-registered-count" }
        }
    val loadedAt = operands["loadedAtEpochMillis"]?.toLongOrNull()
    require(loadedAt in 0..9_007_199_254_740_991L) {
        "mini-home-cache-decode-success-loaded-at"
    }
    require(operands["partial"] in setOf("true", "false")) {
        "mini-home-cache-decode-success-partial"
    }
    require(operands["snapshotToken"].isSnapshotToken()) {
        "mini-home-cache-decode-success-token"
    }
    requirePositiveLong(operands, "snapshotGeneration")
    require((0 until catalogCount).none { catalogEntityMalformed(operands, it) }) {
        "mini-home-cache-decode-success-catalog"
    }
    require((0 until ownedCount).none { ownedEntityMalformed(operands, it) }) {
        "mini-home-cache-decode-success-owned"
    }
    require(
        (0 until catalogCount).map { operands["catalog.$it.itemId"] }.distinct().size ==
            catalogCount
    ) {
        "mini-home-cache-decode-success-catalog-duplicate"
    }
    require(
        (0 until ownedCount).map { operands["owned.$it.itemId"] }.distinct().size == ownedCount
    ) {
        "mini-home-cache-decode-success-owned-duplicate"
    }
    val catalogIds = (0 until catalogCount).mapTo(mutableSetOf()) { operands["catalog.$it.itemId"] }
    require(
        (0 until ownedCount).all { index ->
            (operands["owned.$index.availability"] == "AVAILABLE") ==
                (operands["owned.$index.itemId"] in catalogIds)
        }
    ) {
        "mini-home-cache-decode-success-availability"
    }
    require(
        operands["partial"] == "true" ||
            (0 until ownedCount).none {
                operands["owned.$it.availability"] == "UNAVAILABLE"
            }
    ) {
        "mini-home-cache-decode-success-partial-contract"
    }
    val decodedCatalog =
        (0 until catalogCount).map { index -> decodeCatalogOperand(operands, index) }
    val decodedOwned = (0 until ownedCount).map { index -> decodeOwnedOperand(operands, index) }
    val recomputedHash =
        authoritativeInventorySnapshotHash(
            observation.accountId,
            decodedCatalog,
            decodedOwned,
            registeredPlantCount,
            operands["partial"] == "true",
        )
    require(
        operands["snapshotHash"] == recomputedHash &&
            operands["content.expectedSnapshotHash"] == recomputedHash
    ) {
        "mini-home-cache-decode-success-content-hash"
    }
}

private fun decodeCatalogOperand(
    operands: Map<String, String?>,
    index: Int,
): AuthoritativeCatalogItem {
    val key = "catalog.$index"
    return requireNotNull(
        runCatching {
            val itemId = ItemId(requireNotNull(operands["$key.itemId"]))
            AuthoritativeCatalogItem(
                itemId = itemId,
                name = requireNotNull(operands["$key.name"]),
                description = requireNotNull(operands["$key.description"]),
                category = ItemCategory.valueOf(requireNotNull(operands["$key.category"])),
                mediaIdentity = mediaIdentity(operands, key, "", itemId),
                acquisitionCondition =
                    operands["$key.acquisitionCondition"]?.let { wireValue ->
                        AuthoritativeInventoryCondition.entries.single {
                            it.wireValue == wireValue
                        }
                    },
                revision = Revision(requireNotNull(operands["$key.revision"]?.toLongOrNull())),
                updatedAtEpochMillis =
                    requireNotNull(operands["$key.updatedAtEpochMillis"]?.toLongOrNull()),
            )
        }
            .getOrNull()
    ) {
        "mini-home-cache-decode-success-catalog-semantic"
    }
}

private fun decodeOwnedOperand(
    operands: Map<String, String?>,
    index: Int,
): AuthoritativeOwnedItem {
    val key = "owned.$index"
    return requireNotNull(
        runCatching {
            val itemId = ItemId(requireNotNull(operands["$key.itemId"]))
            val applied =
                when (operands["$key.applied"]) {
                    "true" -> true
                    "false" -> false
                    else -> error("owned applied is not boolean")
                }
            val snapshotName = operands["$key.nameSnapshot"]
            val snapshot =
                if (snapshotName == null) {
                    null
                } else {
                    AuthoritativeOwnedCatalogSnapshot(
                        name = snapshotName,
                        category =
                            ItemCategory.valueOf(requireNotNull(operands["$key.categorySnapshot"])),
                        mediaIdentity = mediaIdentity(operands, key, "Snapshot", itemId),
                        catalogRevision =
                            Revision(
                                requireNotNull(
                                    operands["$key.catalogRevisionSnapshot"]?.toLongOrNull()
                                )
                            ),
                    )
                }
            AuthoritativeOwnedItem(
                itemId = itemId,
                acquiredAtEpochMillis =
                    requireNotNull(operands["$key.acquiredAtEpochMillis"]?.toLongOrNull()),
                applied = applied,
                revision = Revision(requireNotNull(operands["$key.revision"]?.toLongOrNull())),
                availability =
                    AuthoritativeInventoryAvailability.valueOf(
                        requireNotNull(operands["$key.availability"])
                    ),
                catalogSnapshot = snapshot,
            )
        }
            .getOrNull()
    ) {
        "mini-home-cache-decode-success-owned-semantic"
    }
}

private fun mediaIdentity(
    operands: Map<String, String?>,
    key: String,
    suffix: String,
    itemId: ItemId,
): CatalogMediaIdentity =
    CatalogMediaIdentity(
            path = requireNotNull(operands["$key.assetPath$suffix"]),
            sha256 = requireNotNull(operands["$key.assetSha256$suffix"]),
            byteSize = requireNotNull(operands["$key.assetByteSize$suffix"]?.toLongOrNull()),
            mimeType = requireNotNull(operands["$key.assetMimeType$suffix"]),
            width = requireNotNull(operands["$key.assetWidth$suffix"]?.toIntOrNull()),
            height = requireNotNull(operands["$key.assetHeight$suffix"]?.toIntOrNull()),
            mediaRevision =
                Revision(
                    requireNotNull(operands["$key.assetMediaRevision$suffix"]?.toLongOrNull())
                ),
        )
        .also { require(it.path.startsWith("catalog-assets/${itemId.value}/")) }

private fun requireNullableLayoutWatermark(
    operands: Map<String, String?>,
    prefix: String,
    generation: Long?,
) {
    if (generation == null) {
        require(
            setOf(
                    "generation",
                    "kind",
                    "revision",
                    "operationId",
                    "payloadHash",
                    "snapshotToken",
                    "snapshotGeneration",
                )
                .all { operands["$prefix.$it"] == null }
        ) {
            "mini-home-cache-layout-empty-before"
        }
    } else {
        require(generation >= 1) { "mini-home-cache-layout-before-generation" }
        requireLayoutWatermark(operands, prefix)
    }
}

private fun requireLayoutWatermark(operands: Map<String, String?>, prefix: String) {
    requirePositiveLong(operands, "$prefix.generation")
    when (operands["$prefix.kind"]) {
        "PRESENT" ->
            require(
                operands["$prefix.revision"]?.toLongOrNull()?.let { it >= 1 } == true &&
                    !operands["$prefix.operationId"].isNullOrBlank() &&
                    operands["$prefix.payloadHash"].isSha256()
            ) {
                "mini-home-cache-layout-present"
            }
        "DELETED" ->
            require(
                operands["$prefix.revision"] == null &&
                    operands["$prefix.operationId"] == null &&
                    operands["$prefix.payloadHash"] == null
            ) {
                "mini-home-cache-layout-deleted"
            }
        else -> throw IllegalArgumentException("mini-home-cache-layout-kind")
    }
    requireSnapshotIdentity(operands, prefix, required = true)
}

private fun requireNullableInventoryWatermark(
    operands: Map<String, String?>,
    prefix: String,
    generation: Long?,
) {
    if (generation == null) {
        require(
            setOf(
                    "generation",
                    "snapshotHash",
                    "registeredPlantCount",
                    "partial",
                    "snapshotToken",
                    "snapshotGeneration",
                )
                .all { operands["$prefix.$it"] == null }
        ) {
            "mini-home-cache-inventory-empty-before"
        }
    } else {
        require(generation >= 1) { "mini-home-cache-inventory-before-generation" }
        requireInventoryWatermark(operands, prefix)
    }
}

private fun requireInventoryWatermark(operands: Map<String, String?>, prefix: String) {
    requirePositiveLong(operands, "$prefix.generation")
    require(operands["$prefix.snapshotHash"].isSha256()) {
        "mini-home-cache-inventory-hash"
    }
    requireNonnegativeInt(operands, "$prefix.registeredPlantCount").also {
        require(it <= 200) { "mini-home-cache-inventory-registered-count" }
    }
    require(operands["$prefix.partial"] in setOf("true", "false")) {
        "mini-home-cache-inventory-partial"
    }
    requireSnapshotIdentity(operands, prefix, required = true)
}

private fun requireSnapshotIdentity(
    operands: Map<String, String?>,
    prefix: String,
    required: Boolean,
) {
    val token = operands["$prefix.snapshotToken"]
    val generation = operands["$prefix.snapshotGeneration"]
    if (!required && token == null && generation == null) return
    require(token.isSnapshotToken() && generation?.toLongOrNull()?.let { it >= 1 } == true) {
        "mini-home-cache-snapshot-identity"
    }
}

private fun requireExactKeys(
    operands: Map<String, String?>,
    expected: Set<String>,
    label: String,
) {
    require(operands.keys == expected) { "mini-home-cache-$label-keys" }
}

private fun requireSameFields(
    operands: Map<String, String?>,
    leftPrefix: String,
    rightPrefix: String,
    fields: Set<String>,
    message: String,
) {
    require(fields.all { operands["$leftPrefix.$it"] == operands["$rightPrefix.$it"] }) {
        message
    }
}

private fun requirePositiveLong(operands: Map<String, String?>, key: String): Long {
    val value = operands[key]?.toLongOrNull()
    require(value != null && value >= 1) { "mini-home-cache-positive-long-$key" }
    return value
}

private fun requireNonnegativeInt(operands: Map<String, String?>, key: String): Int {
    val value = operands[key]?.toIntOrNull()
    require(value != null && value >= 0) { "mini-home-cache-nonnegative-int-$key" }
    return value
}

private fun String?.isSha256(): Boolean = this?.matches(Regex("^[a-f0-9]{64}$")) == true

private fun String?.isSnapshotToken(): Boolean = isSha256()

private fun canonicalKeys(prefix: String, count: Int, fields: Set<String>): Set<String> = buildSet {
    (0 until count).forEach { index -> fields.forEach { add("$prefix.$index.$it") } }
}

private val CACHE_STATE_PREFIXES = setOf("before", "after", "candidate")

private const val MIGRATION_INVENTORY_ZERO_HASH =
    "0000000000000000000000000000000000000000000000000000000000000000"

private val LAYOUT_STATE_FIELDS =
    setOf(
        "present",
        "accountId",
        "generation",
        "kind",
        "layoutRevision",
        "miniHomeId",
        "operationId",
        "payloadHash",
        "tombstoneId",
        "authoritativeAtEpochMillis",
        "verified",
        "snapshotToken",
        "snapshotGeneration",
    )

private val LAYOUT_DOMAIN_FIELDS =
    setOf(
        "accountId",
        "generation",
        "kind",
        "layoutRevision",
        "miniHomeId",
        "operationId",
        "payloadHash",
        "tombstoneId",
        "verified",
    )

private val HOME_FIELDS =
    setOf(
        "present",
        "accountId",
        "miniHomeId",
        "name",
        "placedPlantCount",
        "revision",
        "updatedAtEpochMillis",
    )

private val PLACEMENT_FIELDS =
    setOf(
        "accountId",
        "placementId",
        "miniHomeId",
        "plantId",
        "itemId",
        "normalizedX",
        "normalizedY",
        "zIndex",
        "layoutRevision",
    )

private val INVENTORY_STATE_FIELDS =
    setOf(
        "present",
        "accountId",
        "generation",
        "snapshotHash",
        "registeredPlantCount",
        "loadedAtEpochMillis",
        "partial",
        "verified",
        "snapshotToken",
        "snapshotGeneration",
    )

private val INVENTORY_IDENTITY_FIELDS =
    setOf("accountId", "generation", "snapshotHash", "registeredPlantCount", "partial")

private val CURRENT_SNAPSHOT_KEYS =
    setOf(
        "snapshot.present",
        "layout.present",
        "inventory.present",
        "layout.accountId",
        "inventory.accountId",
        "layout.verified",
        "inventory.verified",
        "layout.token",
        "inventory.token",
        "layout.generation",
        "inventory.generation",
        "layout.homePresent",
        "layout.placementCount",
        "inventory.catalogCount",
        "inventory.ownedCount",
    )

private val DECODE_SUCCESS_KEYS =
    setOf(
        "field",
        "failure.index",
        "content.expectedSnapshotHash",
        "expected.account",
        "actual.account",
        "verified",
        "generation",
        "snapshotHash",
        "registeredPlantCount",
        "loadedAtEpochMillis",
        "partial",
        "snapshotToken",
        "snapshotGeneration",
        "catalog.count",
        "owned.count",
    )

private val CATALOG_FIELDS =
    setOf(
        "accountId",
        "itemId",
        "name",
        "description",
        "category",
        "assetPath",
        "acquisitionCondition",
        "revision",
        "updatedAtEpochMillis",
        "assetSha256",
        "assetByteSize",
        "assetMimeType",
        "assetWidth",
        "assetHeight",
        "assetMediaRevision",
    )

private val OWNED_FIELDS =
    setOf(
        "accountId",
        "itemId",
        "acquiredAtEpochMillis",
        "applied",
        "revision",
        "availability",
        "nameSnapshot",
        "categorySnapshot",
        "assetPathSnapshot",
        "catalogRevisionSnapshot",
        "assetSha256Snapshot",
        "assetByteSizeSnapshot",
        "assetMimeTypeSnapshot",
        "assetWidthSnapshot",
        "assetHeightSnapshot",
        "assetMediaRevisionSnapshot",
    )

private fun requireConflictSelection(observation: MiniHomeCacheDiagnosticObservation) {
    val category = requireNotNull(observation.category)
    val predicate = requireNotNull(observation.predicate)
    val selected =
        when (category) {
            MiniHomeCacheConflictCategory.LAYOUT_APPLY -> {
                require(
                    predicate in
                        MiniHomeCacheConflictPredicate
                            .LAYOUT_WATERMARK_ACCOUNT..MiniHomeCacheConflictPredicate
                                .LAYOUT_COHERENCE
                ) {
                    "mini-home-cache-layout-predicate-incompatible"
                }
                requireLayoutConflictOperands(observation.operands)
                selectLayoutCacheConflictPredicate(observation.operands)
            }
            MiniHomeCacheConflictCategory.INVENTORY_APPLY -> {
                require(
                    predicate in
                        MiniHomeCacheConflictPredicate
                            .INVENTORY_SNAPSHOT_HASH..MiniHomeCacheConflictPredicate
                                .INVENTORY_COHERENCE
                ) {
                    "mini-home-cache-inventory-predicate-incompatible"
                }
                requireInventoryConflictOperands(observation.operands)
                selectInventoryCacheConflictPredicate(observation.operands)
            }
            MiniHomeCacheConflictCategory.CURRENT_SNAPSHOT -> {
                require(
                    predicate in
                        MiniHomeCacheConflictPredicate
                            .CURRENT_SNAPSHOT_MISSING..MiniHomeCacheConflictPredicate
                                .CURRENT_GENERATION_MISMATCH
                ) {
                    "mini-home-cache-current-predicate-incompatible"
                }
                requireCurrentSnapshotOperands(observation.operands)
                selectCurrentSnapshotConflictPredicate(observation.operands)
            }
            MiniHomeCacheConflictCategory.VERIFIED_INVENTORY_DECODE -> {
                require(
                    predicate == MiniHomeCacheConflictPredicate.VERIFIED_INVENTORY_DECODE_FIELD
                ) {
                    "mini-home-cache-decode-predicate-incompatible"
                }
                requireDecodeConflictOperands(observation.operands)
                predicate
            }
        }
    require(predicate == selected) { "mini-home-cache-selected-predicate-mismatch" }
}

private fun requireLayoutConflictOperands(operands: Map<String, String?>) {
    val watermarkFields =
        setOf(
            "accountId",
            "generation",
            "kind",
            "layoutRevision",
            "miniHomeId",
            "operationId",
            "payloadHash",
            "tombstoneId",
            "verified",
        )
    require(
        watermarkFields.all { field ->
            operands.containsKey("watermark.current.$field") &&
                operands.containsKey("watermark.candidate.$field")
        } &&
            operands.containsKey("watermark.generationOrdering") &&
            operands.containsKey("current.home.present") &&
            operands.containsKey("candidate.home.present") &&
            operands.containsKey("current.placements.count") &&
            operands.containsKey("candidate.placements.count") &&
            operands.containsKey("coherence.current.token") &&
            operands.containsKey("coherence.current.generation") &&
            operands.containsKey("coherence.candidate.token") &&
            operands.containsKey("coherence.candidate.generation")
    ) {
        "mini-home-cache-layout-operands-incomplete"
    }
    requireGenerationOrdering(
        operands["watermark.current.generation"],
        operands["watermark.candidate.generation"],
        operands["watermark.generationOrdering"],
    )
}

private fun requireInventoryConflictOperands(operands: Map<String, String?>) {
    val fields =
        setOf(
            "accountId",
            "generation",
            "snapshotHash",
            "registeredPlantCount",
            "partial",
            "snapshotToken",
            "snapshotGeneration",
        )
    require(
        fields.all { field ->
            operands.containsKey("inventory.current.$field") &&
                operands.containsKey("inventory.candidate.$field")
        } &&
            operands.containsKey("inventory.generationOrdering") &&
            operands.containsKey("current.catalog.count") &&
            operands.containsKey("candidate.catalog.count") &&
            operands.containsKey("current.owned.count") &&
            operands.containsKey("candidate.owned.count")
    ) {
        "mini-home-cache-inventory-operands-incomplete"
    }
    requireGenerationOrdering(
        operands["inventory.current.generation"],
        operands["inventory.candidate.generation"],
        operands["inventory.generationOrdering"],
    )
}

private fun requireCurrentSnapshotOperands(operands: Map<String, String?>) {
    val required =
        setOf(
            "snapshot.present",
            "layout.present",
            "inventory.present",
            "layout.accountId",
            "inventory.accountId",
            "layout.verified",
            "inventory.verified",
            "layout.token",
            "inventory.token",
            "layout.generation",
            "inventory.generation",
            "layout.homePresent",
            "layout.placementCount",
            "inventory.catalogCount",
            "inventory.ownedCount",
        )
    require(required.all(operands::containsKey)) {
        "mini-home-cache-current-operands-incomplete"
    }
}

private fun requireDecodeConflictOperands(operands: Map<String, String?>) {
    val required =
        setOf(
            "field",
            "expected.account",
            "actual.account",
            "verified",
            "generation",
            "snapshotHash",
            "registeredPlantCount",
            "loadedAtEpochMillis",
            "partial",
            "snapshotToken",
            "snapshotGeneration",
            "catalog.count",
            "owned.count",
        )
    require(required.all(operands::containsKey)) { "mini-home-cache-decode-operands-incomplete" }
    val field =
        VerifiedInventoryFailureField.entries.singleOrNull { it.wireValue == operands["field"] }
    require(field != null) { "mini-home-cache-decode-field-unknown" }
    val selected =
        VerifiedInventoryFailureField.entries.firstOrNull {
            decodeFailureRelation(it, operands)
        }
    require(field == selected) {
        "mini-home-cache-decode-field-relation-mismatch"
    }
}

private fun requireGenerationOrdering(current: String?, candidate: String?, ordering: String?) {
    val currentGeneration = current?.toLongOrNull()
    val candidateGeneration = candidate?.toLongOrNull()
    require(currentGeneration != null && candidateGeneration != null) {
        "mini-home-cache-generation-malformed"
    }
    val expected =
        when {
            candidateGeneration < currentGeneration -> "candidate-lower"
            candidateGeneration == currentGeneration -> "equal"
            else -> "candidate-higher"
        }
    require(ordering == expected) { "mini-home-cache-generation-ordering-mismatch" }
}

private enum class VerifiedInventoryFailureField(val wireValue: String) {
    INVENTORY("inventory"),
    WATERMARK_ACCOUNT("watermark.accountId"),
    WATERMARK_VERIFIED("watermark.verified"),
    WATERMARK_GENERATION("watermark.generation"),
    WATERMARK_SNAPSHOT_HASH_FORMAT("watermark.snapshotHash.format"),
    WATERMARK_REGISTERED_PLANT_COUNT("watermark.registeredPlantCount"),
    WATERMARK_LOADED_AT("watermark.loadedAtEpochMillis"),
    CATALOG_COUNT("catalog.count"),
    OWNED_COUNT("owned.count"),
    CATALOG_ENTITY("catalog.entity"),
    OWNED_ENTITY("owned.entity"),
    CATALOG_DUPLICATE_ID("catalog.duplicateItemId"),
    OWNED_DUPLICATE_ID("owned.duplicateItemId"),
    OWNED_CATALOG_AVAILABILITY("owned.catalogAvailability"),
    PARTIAL_UNAVAILABLE_OWNED("partial.unavailableOwned"),
    SNAPSHOT_HASH_CONTENT("watermark.snapshotHash.content"),
}

private fun decodeFailureRelation(
    field: VerifiedInventoryFailureField,
    operands: Map<String, String?>,
): Boolean {
    val catalogCount = operands["catalog.count"]?.toIntOrNull() ?: return false
    val ownedCount = operands["owned.count"]?.toIntOrNull() ?: return false
    val failureIndex = operands["failure.index"]?.toIntOrNull()
    return when (field) {
        VerifiedInventoryFailureField.INVENTORY ->
            operands["actual.account"] == null &&
                operands["verified"] == null &&
                catalogCount == 0 &&
                ownedCount == 0
        VerifiedInventoryFailureField.WATERMARK_ACCOUNT ->
            operands["actual.account"] != operands["expected.account"]
        VerifiedInventoryFailureField.WATERMARK_VERIFIED -> operands["verified"] != "true"
        VerifiedInventoryFailureField.WATERMARK_GENERATION ->
            (operands["generation"]?.toLongOrNull() ?: Long.MIN_VALUE) < 1
        VerifiedInventoryFailureField.WATERMARK_SNAPSHOT_HASH_FORMAT ->
            operands["snapshotHash"]?.matches(Regex("^[a-f0-9]{64}$")) != true
        VerifiedInventoryFailureField.WATERMARK_REGISTERED_PLANT_COUNT ->
            operands["registeredPlantCount"]?.toIntOrNull() !in 0..200
        VerifiedInventoryFailureField.WATERMARK_LOADED_AT ->
            operands["loadedAtEpochMillis"]?.toLongOrNull() !in 0..9_007_199_254_740_991L
        VerifiedInventoryFailureField.CATALOG_COUNT -> catalogCount > 200
        VerifiedInventoryFailureField.OWNED_COUNT -> ownedCount > 200
        VerifiedInventoryFailureField.CATALOG_ENTITY ->
            (0 until catalogCount)
                .firstOrNull { catalogEntityMalformed(operands, it) }
                .let {
                    it != null && failureIndex == it
                }
        VerifiedInventoryFailureField.OWNED_ENTITY ->
            (0 until ownedCount)
                .firstOrNull { ownedEntityMalformed(operands, it) }
                .let {
                    it != null && failureIndex == it
                }
        VerifiedInventoryFailureField.CATALOG_DUPLICATE_ID ->
            (0 until catalogCount)
                .firstOrNull {
                    duplicateItemId(operands, "catalog", catalogCount, it)
                }
                .let { it != null && failureIndex == it }
        VerifiedInventoryFailureField.OWNED_DUPLICATE_ID ->
            (0 until ownedCount)
                .firstOrNull {
                    duplicateItemId(operands, "owned", ownedCount, it)
                }
                .let { it != null && failureIndex == it }
        VerifiedInventoryFailureField.OWNED_CATALOG_AVAILABILITY ->
            (0 until ownedCount)
                .firstOrNull { index ->
                    (operands["owned.$index.availability"] == "AVAILABLE") !=
                        (operands["owned.$index.itemId"] in
                            (0 until catalogCount).map { operands["catalog.$it.itemId"] })
                }
                .let { it != null && failureIndex == it }
        VerifiedInventoryFailureField.PARTIAL_UNAVAILABLE_OWNED ->
            operands["partial"] == "false" &&
                (0 until ownedCount)
                    .firstOrNull {
                        operands["owned.$it.availability"] == "UNAVAILABLE"
                    }
                    .let { it != null && failureIndex == it }
        VerifiedInventoryFailureField.SNAPSHOT_HASH_CONTENT ->
            operands["content.expectedSnapshotHash"]?.matches(Regex("^[a-f0-9]{64}$")) == true &&
                operands["content.expectedSnapshotHash"] != operands["snapshotHash"]
    }
}

private fun duplicateItemId(
    operands: Map<String, String?>,
    prefix: String,
    count: Int,
    index: Int,
): Boolean =
    index in 0 until count &&
        (0 until count).count {
            operands["$prefix.$it.itemId"] == operands["$prefix.$index.itemId"]
        } > 1

private fun catalogEntityMalformed(operands: Map<String, String?>, index: Int): Boolean {
    val key = "catalog.$index"
    val itemId = operands["$key.itemId"]
    val name = operands["$key.name"]
    val description = operands["$key.description"]
    return operands["$key.accountId"] != operands["expected.account"] ||
        itemId?.matches(Regex("^[A-Za-z0-9_-]{1,128}$")) != true ||
        name == null ||
        name.codePointCount(0, name.length) !in 1..100 ||
        description == null ||
        description.codePointCount(0, description.length) !in 1..500 ||
        operands["$key.category"] !in setOf("BACKGROUND", "FURNITURE", "DECORATION") ||
        operands["$key.acquisitionCondition"] !in setOf(null, "registered-plant") ||
        mediaMalformed(operands, key, "", itemId) ||
        (operands["$key.revision"]?.toLongOrNull() ?: 0) < 1 ||
        operands["$key.updatedAtEpochMillis"]?.toLongOrNull() !in 0..9_007_199_254_740_991L
}

private fun ownedEntityMalformed(operands: Map<String, String?>, index: Int): Boolean {
    val key = "owned.$index"
    val itemId = operands["$key.itemId"]
    val snapshots =
        listOf(
                "nameSnapshot",
                "categorySnapshot",
                "assetPathSnapshot",
                "catalogRevisionSnapshot",
                "assetSha256Snapshot",
                "assetByteSizeSnapshot",
                "assetMimeTypeSnapshot",
                "assetWidthSnapshot",
                "assetHeightSnapshot",
                "assetMediaRevisionSnapshot",
            )
            .map { operands["$key.$it"] }
    val snapshotShapeValid = snapshots.all { it == null } || snapshots.none { it == null }
    return operands["$key.accountId"] != operands["expected.account"] ||
        itemId?.matches(Regex("^[A-Za-z0-9_-]{1,128}$")) != true ||
        operands["$key.acquiredAtEpochMillis"]?.toLongOrNull() !in 0..9_007_199_254_740_991L ||
        operands["$key.applied"] !in setOf("true", "false") ||
        (operands["$key.revision"]?.toLongOrNull() ?: 0) < 1 ||
        operands["$key.availability"] !in setOf("AVAILABLE", "UNAVAILABLE") ||
        !snapshotShapeValid ||
        (snapshots.none { it == null } &&
            (operands["$key.nameSnapshot"].let {
                it == null || it.codePointCount(0, it.length) !in 1..100
            } ||
                operands["$key.categorySnapshot"] !in
                    setOf("BACKGROUND", "FURNITURE", "DECORATION") ||
                mediaMalformed(operands, key, "Snapshot", itemId) ||
                (operands["$key.catalogRevisionSnapshot"]?.toLongOrNull() ?: 0) < 1))
}

private fun mediaMalformed(
    operands: Map<String, String?>,
    key: String,
    suffix: String,
    itemId: String?,
): Boolean {
    val path = operands["$key.assetPath$suffix"]
    val sha256 = operands["$key.assetSha256$suffix"]
    val byteSize = operands["$key.assetByteSize$suffix"]?.toLongOrNull()
    val mimeType = operands["$key.assetMimeType$suffix"]
    val width = operands["$key.assetWidth$suffix"]?.toIntOrNull()
    val height = operands["$key.assetHeight$suffix"]?.toIntOrNull()
    val revision = operands["$key.assetMediaRevision$suffix"]?.toLongOrNull()
    val match = path?.let {
        Regex("^catalog-assets/[A-Za-z0-9_-]{1,128}/([a-f0-9]{64})\\.(png|jpg|jpeg|webp)$")
            .matchEntire(it)
    }
    if (
        itemId == null ||
            sha256?.matches(Regex("^[a-f0-9]{64}$")) != true ||
            match == null ||
            !path.startsWith("catalog-assets/$itemId/") ||
            match.groupValues[1] != sha256 ||
            byteSize !in 1..8L * 1024L * 1024L ||
            width !in 1..32_768 ||
            height !in 1..32_768 ||
            revision == null ||
            revision < 1
    ) {
        return true
    }
    val validWidth = requireNotNull(width)
    val validHeight = requireNotNull(height)
    val pixels = validWidth.toLong() * validHeight
    if (
        pixels > 64L * 1024L * 1024L ||
            pixels * 4L > 256L * 1024L * 1024L ||
            maxOf(validWidth, validHeight).toLong() > minOf(validWidth, validHeight).toLong() * 32L
    ) {
        return true
    }
    val extension = match.groupValues[2]
    return !((extension == "png" && mimeType == "image/png") ||
        (extension in setOf("jpg", "jpeg") && mimeType == "image/jpeg") ||
        (extension == "webp" && mimeType == "image/webp"))
}

fun interface MiniHomeCacheDiagnosticSink {
    fun observe(observation: MiniHomeCacheDiagnosticObservation)
}

enum class MiniHomeCacheTransactionDiagnosticStage(val receiptStage: String) {
    TRANSACTION_CALL_ENTERED("cache-transaction-call-entered"),
    TRANSACTION_BODY_ENTERED("cache-transaction-body-entered"),
    LAYOUT_APPLY("cache-layout-apply"),
    INVENTORY_APPLY("cache-inventory-apply"),
    CURRENT_SNAPSHOT("cache-current-snapshot"),
    VERIFIED_INVENTORY_DECODE("cache-verified-inventory-decode"),
    TRANSACTION_BODY_RETURNED("cache-transaction-body-returned"),
    TRANSACTION_SCOPE_RETURNED("cache-transaction-scope-returned"),
    TERMINAL_CONFLICT("cache-terminal-conflict"),
    TRANSACTION_RETURNED("cache-transaction-returned"),
    TRANSACTION_THREW("cache-transaction-threw"),
    TRANSACTION_CANCELLED("cache-transaction-cancelled"),
}

enum class MiniHomeCacheTransactionResult {
    CURRENT,
    CONFLICT,
}

data class MiniHomeCacheTransactionDiagnosticObservation(
    val stage: MiniHomeCacheTransactionDiagnosticStage,
    val accountId: AccountId,
    val operationId: OperationId?,
    val result: MiniHomeCacheTransactionResult? = null,
    val failure: Throwable? = null,
    val cacheObservation: MiniHomeCacheDiagnosticObservation? = null,
)

sealed interface MiniHomePublicationReadTerminalOutcome {
    data object Returned : MiniHomePublicationReadTerminalOutcome

    data class Threw(val failure: Throwable) : MiniHomePublicationReadTerminalOutcome

    data class Cancelled(val failure: CancellationException) :
        MiniHomePublicationReadTerminalOutcome
}

object MiniHomeCacheConflictDiagnostics {
    private data class Installation(val token: Any, val sink: MiniHomeCacheDiagnosticSink)

    private val lock = Any()

    @Volatile private var installation: Installation? = null

    fun install(sink: MiniHomeCacheDiagnosticSink): Closeable {
        val installed = Installation(Any(), sink)
        synchronized(lock) {
            check(installation == null) { "MiniHome cache diagnostics already installed" }
            installation = installed
        }
        return Closeable {
            synchronized(lock) {
                if (installation?.token === installed.token) installation = null
            }
        }
    }

    internal fun observe(observation: MiniHomeCacheDiagnosticObservation) {
        val sink = installation?.sink ?: return
        try {
            sink.observe(observation)
        } catch (error: CancellationException) {
            throw error
        } catch (_: AssertionError) {} catch (_: RuntimeException) {}
    }

    fun listenerCount(): Int = if (installation == null) 0 else 1
}
