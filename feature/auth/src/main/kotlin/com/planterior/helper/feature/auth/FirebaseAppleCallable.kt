package com.planterior.helper.feature.auth

import com.google.firebase.functions.FirebaseFunctions

class FirebaseAppleCallable(private val functions: FirebaseFunctions) : AppleAuthCallable {
    override suspend fun begin(
        nonceHash: String,
        codeChallenge: String,
        state: String,
    ): AppleAuthorizationStart {
        val result =
            functions
                .getHttpsCallable("beginAppleSignIn")
                .call(
                    mapOf(
                        "nonceHash" to nonceHash,
                        "codeChallenge" to codeChallenge,
                        "state" to state,
                    )
                )
                .await()
                .getData()
                .asStringMap()
        return AppleAuthorizationStart(
            result.requiredString("sessionId"),
            result.requiredString("authorizationUrl"),
        )
    }

    override suspend fun complete(sessionId: String, state: String, codeVerifier: String): String {
        val result =
            functions
                .getHttpsCallable("completeAppleSignIn")
                .call(
                    mapOf(
                        "sessionId" to sessionId,
                        "state" to state,
                        "codeVerifier" to codeVerifier,
                    )
                )
                .await()
                .getData()
                .asStringMap()
        return result.requiredString("idToken")
    }

    private fun Any?.asStringMap(): Map<*, *> =
        this as? Map<*, *> ?: throw AuthGatewayException(AuthFailure.InvalidCredential)

    private fun Map<*, *>.requiredString(key: String): String =
        this[key] as? String ?: throw AuthGatewayException(AuthFailure.InvalidCredential)
}
