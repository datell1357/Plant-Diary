package com.planterior.helper.identify

import com.planterior.helper.core.data.PrivateMediaReference
import com.planterior.helper.core.model.AccountId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentificationResponseDecoderTest {
    @Test
    fun `release authorizer delegates malformed numeric response to exact shared decoder`() =
        runBlocking {
            var calls = 0
            val authorizer =
                FirebaseIdentificationRequestAuthorizer(
                    FirebaseIdentificationRequestCallable {
                        calls += 1
                        response(acknowledgedAtMillis = 1_000.5)
                    }
                )
            var acknowledgement: IdentificationRequestAcknowledgement? = null

            val failure = runCatching {
                acknowledgement =
                    authorizer.authorize(
                        AccountId("user-a"),
                        "request_12345678",
                        PrivateMediaReference("reservation_12345678", "7"),
                        1,
                    )
            }
                .exceptionOrNull()

            assertEquals(1, calls)
            assertNull(acknowledgement)
            assertTrue(failure is IdentificationHandoffException)
            assertEquals(
                IdentificationHandoffFailure.RequestFailed,
                (failure as IdentificationHandoffException).reason,
            )
        }

    @Test
    fun `release authorizer returns acknowledgement decoded by shared exact seam`() = runBlocking {
        var payload: Map<String, Any>? = null
        val authorizer =
            FirebaseIdentificationRequestAuthorizer(
                FirebaseIdentificationRequestCallable { request ->
                    payload = request
                    response(
                        disclosureVersion = 1.0,
                        acknowledgedAtMillis = 1_000.0,
                        createdAtMillis = 1_000.0,
                        hardExpiresAtMillis = 86_401_000.0,
                    )
                }
            )

        val acknowledgement =
            authorizer.authorize(
                AccountId("user-a"),
                "request_12345678",
                PrivateMediaReference("reservation_12345678", "7"),
                1,
            )

        assertEquals("user-a", payload?.get("expectedOwnerUid"))
        assertEquals("request_12345678", payload?.get("requestId"))
        assertEquals(1, payload?.get("disclosureVersion"))
        assertEquals("request_12345678", acknowledgement.requestId)
        assertEquals(1, acknowledgement.disclosureVersion)
        assertEquals(1_000L, acknowledgement.acknowledgedAtMillis)
        assertEquals(1_000L, acknowledgement.createdAtMillis)
        assertEquals(86_401_000L, acknowledgement.hardExpiresAtMillis)
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
