package com.planterior.helper.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class FirebaseAnalyticsRemoteGatewayTest {
    @Test
    fun `event callable sends one exact ordered batch and parses ordered results`() =
        kotlinx.coroutines.test.runTest {
            var callName = ""
            var payload: Map<String, *> = emptyMap<String, String>()
            val firstId = "11111111-1111-4111-8111-111111111111"
            val secondId = "22222222-2222-4222-8222-222222222222"
            val gateway =
                FirebaseAnalyticsRemoteGateway(
                    AnalyticsCallable { name, data ->
                        callName = name
                        payload = data
                        mapOf(
                            "results" to
                                listOf(
                                    mapOf(
                                        "eventId" to firstId,
                                        "accepted" to true,
                                        "duplicate" to false,
                                    ),
                                    mapOf(
                                        "eventId" to secondId,
                                        "accepted" to true,
                                        "duplicate" to true,
                                    ),
                                )
                        )
                    }
                )

            assertEquals(
                listOf(
                    AnalyticsEventAcknowledgement(firstId, duplicate = false),
                    AnalyticsEventAcknowledgement(secondId, duplicate = true),
                ),
                gateway.recordEvents(
                    AnalyticsEventBatchCommand(
                        ownerUid = "owner",
                        events =
                            listOf(
                                AnalyticsEventCommand(firstId, "APP_SESSION_STARTED", 9),
                                AnalyticsEventCommand(
                                    secondId,
                                    "CARE_INFORMATION_VIEWED",
                                    9,
                                ),
                            ),
                    )
                ),
            )
            assertEquals("recordAnalyticsEvent", callName)
            assertEquals(
                mapOf(
                    "ownerUid" to "owner",
                    "events" to
                        listOf(
                            mapOf(
                                "schemaVersion" to 1,
                                "eventId" to firstId,
                                "eventName" to "APP_SESSION_STARTED",
                                "consentRevision" to 9,
                            ),
                            mapOf(
                                "schemaVersion" to 1,
                                "eventId" to secondId,
                                "eventName" to "CARE_INFORMATION_VIEWED",
                                "consentRevision" to 9,
                            ),
                        ),
                ),
                payload,
            )
        }

    @Test
    fun `consent command carries exact owner next revision operation and desired grant`() =
        kotlinx.coroutines.test.runTest {
            val calls = mutableListOf<Pair<String, Map<String, *>>>()
            val gateway =
                FirebaseAnalyticsRemoteGateway(
                    AnalyticsCallable { name, data ->
                        calls += name to data
                        when (name) {
                            "getAnalyticsConsent" ->
                                mapOf(
                                    "schemaVersion" to 1,
                                    "granted" to false,
                                    "commandGeneration" to 4,
                                    "grantedAtEpochMillis" to null,
                                    "revokedAtEpochMillis" to 1L,
                                )
                            else ->
                                mapOf(
                                    "schemaVersion" to 1,
                                    "granted" to true,
                                    "commandGeneration" to 5,
                                    "replayed" to true,
                                    "purgedEventCount" to 0,
                                )
                        }
                    }
                )

            assertEquals(RemoteAnalyticsConsent(false, 4), gateway.getConsent("owner"))
            assertEquals(
                AnalyticsConsentAcknowledgement(true, 5, true),
                gateway.setConsent(AnalyticsConsentCommand("owner", true, 5, "operation-id")),
            )
            assertEquals(
                listOf(
                    "getAnalyticsConsent" to mapOf("ownerUid" to "owner"),
                    "setAnalyticsConsent" to
                        mapOf(
                            "ownerUid" to "owner",
                            "granted" to true,
                            "commandGeneration" to 5,
                            "operationId" to "operation-id",
                        ),
                ),
                calls,
            )
        }
}
