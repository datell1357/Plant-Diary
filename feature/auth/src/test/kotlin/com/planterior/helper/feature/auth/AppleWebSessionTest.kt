package com.planterior.helper.feature.auth

import java.net.URI
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleWebSessionTest {
    @Test
    fun `session uses independent state nonce and PKCE S256 values`() {
        var seed = 0
        val session = AppleWebSession.create { ByteArray(32) { (++seed).toByte() } }

        assertNotEquals(session.state, session.rawNonce)
        assertNotEquals(session.rawNonce, session.codeVerifier)
        assertEquals(43, session.nonceHash.length)
        assertEquals(43, session.codeChallenge.length)
        assertFalse(session.nonceHash.contains("="))
    }

    @Test
    fun `only exact allowlisted callback is accepted`() {
        var seed = 0
        val session = AppleWebSession.create { ByteArray(32) { (++seed).toByte() } }
        val valid = URI("planterior://auth/apple?sessionId=s_12345678&state=${session.state}")
        assertEquals("s_12345678", session.validateCallback(valid, Instant.now()).sessionId)

        listOf(
                "https://evil.example/auth/apple?sessionId=s_12345678&state=${session.state}",
                "planterior://evil/apple?sessionId=s_12345678&state=${session.state}",
                "planterior://auth/apple?sessionId=../../secret&state=${session.state}",
                "planterior://auth/apple?sessionId=s_12345678&state=wrong",
                "planterior://auth/apple?sessionId=s_12345678&state=${session.state}&token=leak",
            )
            .forEach { input ->
                assertTrue(
                    runCatching { session.validateCallback(URI(input), Instant.now()) }.isFailure
                )
            }
    }

    @Test
    fun `expired and consumed callback cannot be replayed`() {
        val now = Instant.parse("2026-08-12T12:00:00Z")
        val session = AppleWebSession.create(now = now, randomBytes = { ByteArray(32) { 7 } })
        val callback = URI("planterior://auth/apple?sessionId=s_12345678&state=${session.state}")
        session.validateCallback(callback, now.plusSeconds(1))

        assertEquals(
            AuthFailure.ReplayedCallback,
            (runCatching { session.validateCallback(callback, now.plusSeconds(2)) }
                    .exceptionOrNull() as AuthGatewayException)
                .failure,
        )
        val expired = AppleWebSession.create(now = now, randomBytes = { ByteArray(32) { 8 } })
        val error =
            runCatching {
                expired.validateCallback(
                    URI("planterior://auth/apple?sessionId=s_12345678&state=${expired.state}"),
                    now.plusSeconds(601),
                )
            }
                .exceptionOrNull() as AuthGatewayException
        assertEquals(AuthFailure.InvalidOrExpiredToken, error.failure)
    }
}
