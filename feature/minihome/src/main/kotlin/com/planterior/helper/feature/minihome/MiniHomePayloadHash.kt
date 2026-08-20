package com.planterior.helper.feature.minihome

import com.planterior.helper.core.model.Revision
import java.security.MessageDigest

/** SHA-256 of the same canonical payload object used by the callable idempotency receipt. */
internal object MiniHomePayloadHash {
    fun create(expectedRevision: Revision, layout: MiniHomeLayout): String =
        create(expectedRevision, layout, layout.name)

    fun create(
        expectedRevision: Revision,
        layout: MiniHomeLayout,
        payloadName: String,
    ): String =
        digest(
            expectedRevision.value,
            layout.id.value,
            payloadName,
            layout.placements.map { placement ->
                val plant = placement.target as? MiniHomePlacementTarget.Plant
                val decoration = placement.target as? MiniHomePlacementTarget.Decoration
                RawPlacementHashFields(
                    placement.id.value,
                    plant?.plantId?.value,
                    decoration?.itemId?.value,
                    placement.position.normalizedX.value,
                    placement.position.normalizedY.value,
                    placement.zIndex.value,
                )
            },
        )

    /** Recomputes the callable hash from exact decoded persisted fields, never from stored hash. */
    fun create(envelope: PersistedMiniHomeEnvelope): String =
        digest(
            envelope.expectedRevision,
            envelope.homeId,
            envelope.rawName,
            envelope.placements.map { placement ->
                RawPlacementHashFields(
                    placement.id,
                    placement.plantId,
                    placement.itemId,
                    (placement.column + 0.5) / MiniHomeGrid.COLUMNS,
                    (placement.row + 0.5) / MiniHomeGrid.ROWS,
                    placement.zIndex,
                )
            },
        )

    fun constantTimeEquals(expected: String?, actual: String?): Boolean {
        if (expected == null || actual == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            actual.toByteArray(Charsets.US_ASCII),
        )
    }

    private fun digest(
        expectedRevision: Long,
        miniHomeId: String,
        name: String,
        placements: List<RawPlacementHashFields>,
    ): String {
        val canonical = buildString {
            append("{\"expectedRevision\":")
            append(expectedRevision)
            append(",\"miniHomeId\":")
            appendQuoted(miniHomeId)
            append(",\"name\":")
            appendQuoted(name)
            append(",\"placements\":[")
            placements.forEachIndexed { index, placement ->
                if (index > 0) append(',')
                append("{\"itemId\":")
                if (placement.itemId == null) append("null") else appendQuoted(placement.itemId)
                append(",\"normalizedX\":")
                appendJsonNumber(placement.normalizedX)
                append(",\"normalizedY\":")
                appendJsonNumber(placement.normalizedY)
                append(",\"placementId\":")
                appendQuoted(placement.id)
                append(",\"plantId\":")
                if (placement.plantId == null) append("null") else appendQuoted(placement.plantId)
                append(",\"zIndex\":")
                append(placement.zIndex)
                append('}')
            }
            append("]}")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private data class RawPlacementHashFields(
        val id: String,
        val plantId: String?,
        val itemId: String?,
        val normalizedX: Double,
        val normalizedY: Double,
        val zIndex: Int,
    )

    private fun StringBuilder.appendJsonNumber(value: Double) {
        require(value.isFinite())
        if (value == 0.0) append('0') else append(value.toString())
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEachIndexed { index, character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    when {
                        character.code < 0x20 -> appendUnicodeEscape(character)
                        character.isHighSurrogate() &&
                            (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) ->
                            appendUnicodeEscape(character)
                        character.isLowSurrogate() &&
                            (index == 0 || !value[index - 1].isHighSurrogate()) ->
                            appendUnicodeEscape(character)
                        else -> append(character)
                    }
            }
        }
        append('"')
    }

    private fun StringBuilder.appendUnicodeEscape(character: Char) {
        append("\\u")
        append(character.code.toString(16).padStart(4, '0'))
    }
}
