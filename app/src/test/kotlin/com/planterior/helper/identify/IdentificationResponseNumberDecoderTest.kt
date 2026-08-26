package com.planterior.helper.identify

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentificationResponseNumberDecoderTest {
    @Test
    fun `rejects fractional non-finite overflowing and broadly coerced disclosure versions`() {
        listOf(
                1.5,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Int.MAX_VALUE.toLong() + 1L,
                Int.MAX_VALUE.toDouble() + 1.0,
                1,
            )
            .forEach { value -> assertRequestFailed(response(disclosureVersion = value)) }
    }

    @Test
    fun `rejects fractional non-finite unsafe negative and impossible timestamps`() {
        listOf(
                1_000.5,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                9_007_199_254_740_993L.toDouble(),
                -1L,
                Long.MAX_VALUE,
                BigInteger("9223372036854775808"),
                253_402_300_800_000L,
                1_000,
            )
            .forEach { value -> assertRequestFailed(response(acknowledgedAtMillis = value)) }
    }

    @Test
    fun `accepts Firebase Long values without coercion`() {
        val decoded = decodeIdentificationRequestAcknowledgement(response())

        assertEquals("request_12345678", decoded.requestId)
        assertEquals(1, decoded.disclosureVersion)
        assertEquals(1_000L, decoded.acknowledgedAtMillis)
        assertEquals(1_000L, decoded.createdAtMillis)
        assertEquals(86_401_000L, decoded.hardExpiresAtMillis)
    }

    @Test
    fun `accepts mathematically integral lossless Double values`() {
        val decoded =
            decodeIdentificationRequestAcknowledgement(
                response(
                    disclosureVersion = 1.0,
                    acknowledgedAtMillis = 1_000.0,
                    createdAtMillis = 1_000.0,
                    hardExpiresAtMillis = 86_401_000.0,
                )
            )

        assertEquals(1, decoded.disclosureVersion)
        assertEquals(1_000L, decoded.acknowledgedAtMillis)
        assertEquals(1_000L, decoded.createdAtMillis)
        assertEquals(86_401_000L, decoded.hardExpiresAtMillis)
    }

    private fun assertRequestFailed(value: Map<String, Any>) {
        val failure = runCatching {
            decodeIdentificationRequestAcknowledgement(value)
        }
            .exceptionOrNull()
        assertTrue(failure is IdentificationHandoffException)
        assertEquals(
            IdentificationHandoffFailure.RequestFailed,
            (failure as IdentificationHandoffException).reason,
        )
    }

    private fun response(
        disclosureVersion: Any = 1L,
        acknowledgedAtMillis: Any = 1_000L,
        createdAtMillis: Any = 1_000L,
        hardExpiresAtMillis: Any = 86_401_000L,
    ): Map<String, Any> =
        mapOf(
            "requestId" to "request_12345678",
            "disclosureVersion" to disclosureVersion,
            "acknowledgedAtMillis" to acknowledgedAtMillis,
            "createdAtMillis" to createdAtMillis,
            "hardExpiresAtMillis" to hardExpiresAtMillis,
        )
}
