package com.planterior.helper.notification

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class NotificationEndpointGatewayContractTest {
    @Test
    fun `register and unregister send expected owner generation and capability`() = runTest {
        val callable = RecordingNotificationEndpointCallable()
        val gateway = FirebaseNotificationEndpointGateway(callable)

        callable.response = mapOf("registered" to true)
        gateway.register(
            NotificationEndpointRegistration(
                accountId = "account-a",
                installationId = "installation-a",
                installationSecret = "secret-current",
                nextInstallationSecret = "secret-next",
                generation = 11,
                token = "token-a",
                notificationsEnabled = false,
            )
        )
        callable.response = mapOf("unregistered" to true, "status" to "REVOKED")
        val result =
            gateway.unregister(
                NotificationEndpointUnregistration(
                    "account-a",
                    "installation-a",
                    "secret-next",
                    12,
                )
            )

        assertEquals("account-a", callable.calls[0].second["expectedOwnerUid"])
        assertEquals("secret-current", callable.calls[0].second["installationSecret"])
        assertEquals("secret-next", callable.calls[0].second["nextInstallationSecret"])
        assertEquals(11L, callable.calls[0].second["generation"])
        assertEquals(false, callable.calls[0].second["notificationsEnabled"])
        assertEquals("account-a", callable.calls[1].second["expectedOwnerUid"])
        assertEquals("secret-next", callable.calls[1].second["installationSecret"])
        assertEquals(12L, callable.calls[1].second["generation"])
        assertEquals(NotificationEndpointRevocationResult.REVOKED, result)
    }

    @Test
    fun `transfer replay requires a complete committed registration response`() = runTest {
        val registration =
            NotificationEndpointRegistration(
                accountId = "account-b",
                installationId = "installation-a",
                installationSecret = "secret-current",
                nextInstallationSecret = "secret-next",
                generation = 11,
                token = "token-b",
                notificationsEnabled = true,
            )
        val malformed =
            listOf(
                null,
                emptyMap<String, Any>(),
                mapOf("registered" to false),
                mapOf("registered" to true, "extra" to true),
                mapOf("registered" to "true"),
            )

        malformed.forEach { response ->
            val callable =
                RecordingNotificationEndpointCallable().apply { this.response = response }
            val result = runCatching {
                FirebaseNotificationEndpointGateway(callable).register(registration)
            }
            assertTrue(
                "Malformed registration response was accepted: $response",
                result.exceptionOrNull() is PermanentNotificationEndpointException,
            )
        }
    }

    @Test
    fun `unregister accepts only complete allowlisted response schemas`() = runTest {
        val unregistration =
            NotificationEndpointUnregistration(
                "account-a",
                "installation-a",
                "secret-next",
                12,
            )
        val malformed =
            listOf(
                null,
                emptyMap<String, Any>(),
                mapOf("status" to "REVOKED"),
                mapOf("unregistered" to false, "status" to "REVOKED"),
                mapOf("unregistered" to true, "status" to "UNKNOWN"),
                mapOf("unregistered" to true, "status" to "REVOKED", "extra" to true),
                mapOf("unregistered" to true, "status" to 7),
            )

        malformed.forEach { response ->
            val callable =
                RecordingNotificationEndpointCallable().apply { this.response = response }
            val result = runCatching {
                FirebaseNotificationEndpointGateway(callable).unregister(unregistration)
            }
            assertTrue(
                "Malformed response was accepted: $response",
                result.exceptionOrNull() is PermanentNotificationEndpointException,
            )
        }

        val missing =
            RecordingNotificationEndpointCallable().apply {
                response = mapOf("unregistered" to true, "status" to "ALREADY_ABSENT")
            }
        assertEquals(
            NotificationEndpointRevocationResult.ALREADY_ABSENT,
            FirebaseNotificationEndpointGateway(missing).unregister(unregistration),
        )
    }

    private class RecordingNotificationEndpointCallable : NotificationEndpointCallable {
        val calls = mutableListOf<Pair<String, Map<String, Any>>>()
        var response: Any? = null

        override suspend fun call(name: String, data: Map<String, Any>): Any? {
            calls += name to data
            return response
        }
    }
}
