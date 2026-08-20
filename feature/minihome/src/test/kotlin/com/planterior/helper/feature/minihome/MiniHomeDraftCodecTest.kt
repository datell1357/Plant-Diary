package com.planterior.helper.feature.minihome

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniHomeDraftCodecTest {
    @Test
    fun `raw 200 code point legacy name decodes exactly and canonicalizes to 100 NFC`() {
        val rawName = "e\u0301".repeat(100)
        val payload = legacyPayload(rawName)
        val storedHash = "a".repeat(64)

        val decoded =
            MiniHomeDraftCodec.decodePersisted(payload, storedHash)
                as PersistedMiniHomeEnvelopeDecode.Decoded

        assertEquals(payload, decoded.envelope.rawJson)
        assertEquals(rawName, decoded.envelope.rawName)
        assertEquals(200, rawName.codePointCount(0, rawName.length))
        assertEquals(storedHash, decoded.envelope.payloadHash)
        assertTrue(decoded.envelope.exactPayloadHash() != storedHash)
        assertTrue(!decoded.envelope.storedPayloadHashMatches())
        assertEquals("é".repeat(100), decoded.envelope.canonicalDraft()?.layout?.name)
        assertEquals(100, decoded.envelope.canonicalDraft()?.layout?.name?.codePointCount(0, 100))
        assertTrue(decoded.envelope.requiresCanonicalization)
    }

    @Test
    fun `exact payload hash is recomputed from raw envelope fields after payload mutation`() {
        val original = legacyPayload("raw-name")
        val originalEnvelope =
            (MiniHomeDraftCodec.decodePersisted(original, null)
                    as PersistedMiniHomeEnvelopeDecode.Decoded)
                .envelope
        val originalHash = requireNotNull(originalEnvelope.exactPayloadHash())
        val mutated = original.replace("raw-name", "raw-tame")
        val mutatedEnvelope =
            (MiniHomeDraftCodec.decodePersisted(mutated, originalHash)
                    as PersistedMiniHomeEnvelopeDecode.Decoded)
                .envelope

        assertEquals(originalHash, originalEnvelope.exactPayloadHash())
        assertTrue(originalEnvelope.rawJson.toByteArray().contentEquals(original.toByteArray()))
        assertTrue(mutatedEnvelope.exactPayloadHash() != originalHash)
        assertTrue(!mutatedEnvelope.storedPayloadHashMatches())
        assertEquals(mutated, mutatedEnvelope.rawJson)
    }

    @Test
    fun `over raw canonical valid and canonical invalid envelopes stay distinguishable`() {
        val recoverable =
            (MiniHomeDraftCodec.decodePersisted(legacyPayload("e\u0301".repeat(100)), null)
                    as PersistedMiniHomeEnvelopeDecode.Decoded)
                .envelope
        val overCanonicalLimit =
            (MiniHomeDraftCodec.decodePersisted(legacyPayload("e\u0301".repeat(101)), null)
                    as PersistedMiniHomeEnvelopeDecode.Decoded)
                .envelope
        val unsafe =
            listOf("A\u0000B", "A\u202EB", "x".repeat(401)).map { name ->
                (MiniHomeDraftCodec.decodePersisted(legacyPayload(name), null)
                        as PersistedMiniHomeEnvelopeDecode.Decoded)
                    .envelope
            }

        assertNotNull(recoverable.canonicalDraft())
        assertNull(overCanonicalLimit.canonicalDraft())
        unsafe.forEach { assertNull(it.canonicalDraft()) }
    }

    @Test
    fun `malformed surrogate and malformed JSON are typed instead of null`() {
        val malformedSurrogate = legacyPayload("safe").replace("safe", "\\uD800")

        assertTrue(
            MiniHomeDraftCodec.decodePersisted(malformedSurrogate, null)
                is PersistedMiniHomeEnvelopeDecode.Malformed
        )
        assertTrue(
            MiniHomeDraftCodec.decodePersisted("{not-json", null)
                is PersistedMiniHomeEnvelopeDecode.Malformed
        )
        assertTrue(
            MiniHomeDraftCodec.decodePersisted(null, null)
                is PersistedMiniHomeEnvelopeDecode.Malformed
        )
    }

    private fun legacyPayload(rawName: String): String {
        val canonical =
            RestoredMiniHomeDraft(
                AccountId("account-a"),
                OperationId("operation-envelope-0001"),
                Revision(3),
                MiniHomeLayout(
                    MiniHomeId("home-a"),
                    "safe",
                    emptyList(),
                    Revision(3),
                    Instant.ofEpochMilli(3),
                ),
            )
        val root = Json.parseToJsonElement(MiniHomeDraftCodec.encode(canonical)).jsonObject
        return JsonObject(root + ("name" to JsonPrimitive(rawName))).toString()
    }
}
