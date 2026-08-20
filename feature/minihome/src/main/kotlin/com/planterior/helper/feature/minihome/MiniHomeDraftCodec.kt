package com.planterior.helper.feature.minihome

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal data class RestoredMiniHomeDraft(
    val owner: AccountId,
    val operationId: OperationId,
    val expectedRevision: Revision,
    val layout: MiniHomeLayout,
    val lineageId: OperationId = operationId,
    val supersedesOperationId: OperationId? = null,
)

internal data class PersistedMiniHomePlacementEnvelope(
    val id: String,
    val plantId: String?,
    val itemId: String?,
    val column: Int,
    val row: Int,
    val zIndex: Int,
)

internal data class PersistedMiniHomeEnvelope(
    val rawJson: String,
    val owner: String,
    val operationId: String,
    val expectedRevision: Long,
    val homeId: String,
    val rawName: String,
    val updatedAt: String,
    val placements: List<PersistedMiniHomePlacementEnvelope>,
    val lineageId: String?,
    val supersedesOperationId: String?,
    val payloadHash: String?,
) {
    val requiresCanonicalization: Boolean
        get() = canonicalDraft()?.layout?.name?.let { it != rawName } == true

    fun canonicalDraft(): RestoredMiniHomeDraft? = runCatching {
        val canonicalName = requireNotNull(recoverLegacyMiniHomeName(rawName))
        val expected = Revision(expectedRevision)
        val operation = OperationId(operationId)
        val domainPlacements = placements.map { placement ->
            require((placement.plantId == null) != (placement.itemId == null))
            MiniHomePlacement(
                PlacementId(placement.id),
                placement.plantId?.let {
                    MiniHomePlacementTarget.Plant(PersonalPlantId(it))
                } ?: MiniHomePlacementTarget.Decoration(ItemId(requireNotNull(placement.itemId))),
                GridPosition(placement.column, placement.row),
                MiniHomeZIndex(placement.zIndex),
            )
        }
        RestoredMiniHomeDraft(
            AccountId(owner),
            operation,
            expected,
            MiniHomeLayout(
                MiniHomeId(homeId),
                canonicalName,
                domainPlacements,
                expected,
                Instant.parse(updatedAt),
            ),
            lineageId?.let(::OperationId) ?: operation,
            supersedesOperationId?.let(::OperationId),
        )
    }
        .getOrNull()

    fun exactPayloadHash(): String = MiniHomePayloadHash.create(this)

    fun storedPayloadHashMatches(): Boolean =
        payloadHash == null ||
            MiniHomePayloadHash.constantTimeEquals(exactPayloadHash(), payloadHash)
}

internal sealed interface PersistedMiniHomeEnvelopeDecode {
    data class Decoded(val envelope: PersistedMiniHomeEnvelope) : PersistedMiniHomeEnvelopeDecode

    data class Malformed(val reason: String) : PersistedMiniHomeEnvelopeDecode
}

internal object MiniHomeDraftCodec {
    fun encode(value: RestoredMiniHomeDraft): String =
        JsonObject(
                mapOf(
                    "owner" to JsonPrimitive(value.owner.value),
                    "operationId" to JsonPrimitive(value.operationId.value),
                    "lineageId" to JsonPrimitive(value.lineageId.value),
                    "supersedesOperationId" to JsonPrimitive(value.supersedesOperationId?.value),
                    "expectedRevision" to JsonPrimitive(value.expectedRevision.value),
                    "homeId" to JsonPrimitive(value.layout.id.value),
                    "name" to JsonPrimitive(value.layout.name),
                    "updatedAt" to JsonPrimitive(value.layout.updatedAt.toString()),
                    "placements" to
                        JsonArray(
                            value.layout.placements.map { placement ->
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(placement.id.value),
                                        "plantId" to
                                            JsonPrimitive(
                                                (placement.target as? MiniHomePlacementTarget.Plant)
                                                    ?.plantId
                                                    ?.value
                                            ),
                                        "itemId" to
                                            JsonPrimitive(
                                                (placement.target
                                                        as? MiniHomePlacementTarget.Decoration)
                                                    ?.itemId
                                                    ?.value
                                            ),
                                        "column" to JsonPrimitive(placement.position.column),
                                        "row" to JsonPrimitive(placement.position.row),
                                        "zIndex" to JsonPrimitive(placement.zIndex.value),
                                    )
                                )
                            }
                        ),
                )
            )
            .toString()

    fun decode(raw: String?): RestoredMiniHomeDraft? =
        (decodePersisted(raw, null) as? PersistedMiniHomeEnvelopeDecode.Decoded)
            ?.envelope
            ?.canonicalDraft()

    fun decodePersisted(
        raw: String?,
        payloadHash: String?,
    ): PersistedMiniHomeEnvelopeDecode {
        if (raw == null || raw.toByteArray(Charsets.UTF_8).size > MAX_RAW_JSON_BYTES) {
            return PersistedMiniHomeEnvelopeDecode.Malformed("missing or oversized JSON")
        }
        return try {
            val root = Json.parseToJsonElement(raw).jsonObject
            val rawName = root.requiredBoundedString("name", MAX_RAW_NAME_CODE_POINTS)
            val placementItems = root.getValue("placements").jsonArray
            require(placementItems.size <= MiniHomeGrid.MAX_PLACEMENTS)
            val placements = placementItems.map { item ->
                val placement = item.jsonObject
                PersistedMiniHomePlacementEnvelope(
                    placement.requiredBoundedString("id", MAX_RAW_ID_CODE_POINTS),
                    placement.optionalBoundedString("plantId", MAX_RAW_ID_CODE_POINTS),
                    placement.optionalBoundedString("itemId", MAX_RAW_ID_CODE_POINTS),
                    placement.requiredBoundedInt("column"),
                    placement.requiredBoundedInt("row"),
                    placement.requiredBoundedInt("zIndex"),
                )
            }
            val operationId =
                root.requiredBoundedString("operationId", MAX_RAW_OPERATION_ID_CODE_POINTS)
            PersistedMiniHomeEnvelopeDecode.Decoded(
                PersistedMiniHomeEnvelope(
                    raw,
                    root.requiredBoundedString("owner", MAX_RAW_ID_CODE_POINTS),
                    operationId,
                    root.requiredBoundedLong("expectedRevision"),
                    root.requiredBoundedString("homeId", MAX_RAW_ID_CODE_POINTS),
                    rawName,
                    root.requiredBoundedString("updatedAt", MAX_RAW_TIMESTAMP_CODE_POINTS),
                    placements,
                    root.optionalBoundedString("lineageId", MAX_RAW_OPERATION_ID_CODE_POINTS),
                    root.optionalBoundedString(
                        "supersedesOperationId",
                        MAX_RAW_OPERATION_ID_CODE_POINTS,
                    ),
                    payloadHash,
                )
            )
        } catch (_: Exception) {
            PersistedMiniHomeEnvelopeDecode.Malformed("invalid persisted envelope")
        }
    }

    private const val MAX_RAW_JSON_BYTES = 131_072
    private const val MAX_RAW_NAME_CODE_POINTS = 1_000
    private const val MAX_RAW_ID_CODE_POINTS = 128
    private const val MAX_RAW_OPERATION_ID_CODE_POINTS = 128
    private const val MAX_RAW_TIMESTAMP_CODE_POINTS = 64
}

private fun JsonObject.requiredBoundedString(name: String, maximumCodePoints: Int): String =
    requireNotNull(getValue(name).jsonPrimitive.contentOrNull).also {
        require(it.isScalarSafeAndBounded(maximumCodePoints))
    }

private fun JsonObject.optionalBoundedString(name: String, maximumCodePoints: Int): String? =
    get(name)?.jsonPrimitive?.contentOrNull?.also {
        require(it.isScalarSafeAndBounded(maximumCodePoints))
    }

private fun JsonObject.requiredBoundedLong(name: String): Long =
    getValue(name).jsonPrimitive.long.also {
        require(it in 0..MiniHomeRequestContract.MAX_SAFE_REVISION)
    }

private fun JsonObject.requiredBoundedInt(name: String): Int =
    getValue(name).jsonPrimitive.int.also {
        require(it in -1_000_000..1_000_000)
    }

private fun String.isScalarSafeAndBounded(maximumCodePoints: Int): Boolean {
    if (isEmpty() || codePointCount(0, length) > maximumCodePoints) return false
    return indices.none { index ->
        when {
            this[index].isHighSurrogate() ->
                index + 1 >= length || !this[index + 1].isLowSurrogate()
            this[index].isLowSurrogate() -> index == 0 || !this[index - 1].isHighSurrogate()
            else -> false
        }
    }
}
