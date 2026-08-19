package com.planterior.helper.navigation

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.auth.AuthAccount
import com.planterior.helper.feature.auth.AuthProvider
import com.planterior.helper.feature.auth.AuthUiState
import com.planterior.helper.feature.auth.SyncSummary
import com.planterior.helper.feature.registration.RegistrationAuthOwnership
import org.junit.Assert.assertEquals
import org.junit.Test

class RegistrationAuthOwnershipTest {
    @Test
    fun `transient and unavailable auth map to suspending ownership states`() {
        assertEquals(
            RegistrationAuthOwnership.Restoring,
            registrationAuthOwnership(
                AuthUiState.Restoring,
                coordinatorAvailable = true,
                enforcementEnabled = true,
            ),
        )
        assertEquals(
            RegistrationAuthOwnership.Unknown,
            registrationAuthOwnership(
                null,
                coordinatorAvailable = false,
                enforcementEnabled = true,
            ),
        )
        assertEquals(
            RegistrationAuthOwnership.Unknown,
            registrationAuthOwnership(
                AuthUiState.SigningIn(AuthProvider.GOOGLE),
                coordinatorAvailable = true,
                enforcementEnabled = true,
            ),
        )
    }

    @Test
    fun `authoritative auth maps to signed out or exact account ownership`() {
        assertEquals(
            RegistrationAuthOwnership.SignedOut,
            registrationAuthOwnership(
                AuthUiState.SignedOut(),
                coordinatorAvailable = true,
                enforcementEnabled = true,
            ),
        )
        assertEquals(
            RegistrationAuthOwnership.Authenticated(AccountId("account-a")),
            registrationAuthOwnership(
                AuthUiState.Authenticated(
                    AuthAccount("account-a", null, "A", setOf(AuthProvider.GOOGLE)),
                    SyncSummary.EMPTY,
                ),
                coordinatorAvailable = true,
                enforcementEnabled = true,
            ),
        )
    }

    @Test
    fun `signed out registration login return remains canonical while debug bypass is explicit`() {
        assertEquals(
            RegistrationAuthOwnership.Unmanaged,
            registrationAuthOwnership(
                AuthUiState.Restoring,
                coordinatorAvailable = true,
                enforcementEnabled = false,
            ),
        )
        val login = AuthRouteGuard.destination(PlanteriorRoute.Registration, authenticated = false)
        assertEquals(PlanteriorRoute.Login("planterior://registration"), login)
        assertEquals(
            listOf(PlanteriorRoute.Home, PlanteriorRoute.Registration),
            NotificationTapRouter.resumeAfterLogin((login as PlanteriorRoute.Login).returnRoute),
        )
    }
}
