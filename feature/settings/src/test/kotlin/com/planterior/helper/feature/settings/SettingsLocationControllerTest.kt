package com.planterior.helper.feature.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsLocationControllerTest {
    @Test
    fun `revoke cancels once immediately preserves manual region and rejects late location`() =
        runTest {
            val revokeFinished = CompletableDeferred<Unit>()
            var cancellations = 0
            val controller =
                SettingsLocationController(
                    initialRegion = SettingsRegion.Manual("서울특별시"),
                    boundary =
                        object : CurrentLocationConsentBoundary {
                            override fun cancelInFlightLocation() {
                                cancellations += 1
                            }

                            override suspend fun revokeConsent() {
                                revokeFinished.await()
                            }
                        },
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val request = controller.beginCurrentLocationRequest()

            controller.revokeCurrentLocationConsent()

            assertEquals(1, cancellations)
            assertEquals(SettingsRegion.Manual("서울특별시"), controller.state.value.region)
            assertFalse(controller.acceptCurrentLocation(request, "현재 위치"))
            revokeFinished.complete(Unit)
            advanceUntilIdle()
            assertTrue(controller.state.value.consentRevoked)
        }
}
