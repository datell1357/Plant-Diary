package com.planterior.helper.feature.auth

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

class AppleWebSession
private constructor(
    val state: String,
    val rawNonce: String,
    val codeVerifier: String,
    val nonceHash: String,
    val codeChallenge: String,
    private val createdAt: Instant,
) {
    private var consumed = false

    fun validateCallback(uri: URI, now: Instant): AppleCallback {
        if (consumed) throw AuthGatewayException(AuthFailure.ReplayedCallback)
        if (now.isAfter(createdAt.plusSeconds(600)))
            throw AuthGatewayException(AuthFailure.InvalidOrExpiredToken)
        if (
            uri.scheme != CALLBACK_SCHEME || uri.host != CALLBACK_HOST || uri.path != CALLBACK_PATH
        ) {
            throw AuthGatewayException(AuthFailure.InvalidCredential)
        }
        val values =
            uri.rawQuery?.split('&')?.map {
                val pair = it.split('=', limit = 2)
                if (pair.size != 2) throw AuthGatewayException(AuthFailure.InvalidCredential)
                pair[0] to pair[1]
            } ?: throw AuthGatewayException(AuthFailure.InvalidCredential)
        if (values.map { it.first }.toSet() != setOf("sessionId", "state") || values.size != 2) {
            throw AuthGatewayException(AuthFailure.InvalidCredential)
        }
        val callbackState = values.first { it.first == "state" }.second
        if (!MessageDigest.isEqual(state.toByteArray(), callbackState.toByteArray())) {
            throw AuthGatewayException(AuthFailure.InvalidCredential)
        }
        val sessionId = values.first { it.first == "sessionId" }.second
        if (!SESSION_ID.matches(sessionId))
            throw AuthGatewayException(AuthFailure.InvalidCredential)
        consumed = true
        return AppleCallback(sessionId, codeVerifier, rawNonce)
    }

    companion object {
        private const val CALLBACK_SCHEME = "planterior"
        private const val CALLBACK_HOST = "auth"
        private const val CALLBACK_PATH = "/apple"
        private val SESSION_ID = Regex("^[A-Za-z0-9_-]{8,128}$")

        fun create(
            now: Instant = Instant.now(),
            randomBytes: () -> ByteArray = { ByteArray(32).also(SecureRandom()::nextBytes) },
        ): AppleWebSession {
            val state = encode(randomBytes())
            val nonce = encode(randomBytes())
            val verifier = encode(randomBytes())
            return AppleWebSession(state, nonce, verifier, hash(nonce), hash(verifier), now)
        }

        private fun hash(value: String): String =
            encode(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

        private fun encode(value: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}

data class AppleCallback(val sessionId: String, val codeVerifier: String, val rawNonce: String)
