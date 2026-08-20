package com.planterior.helper.navigation

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.auth.AuthAccount
import com.planterior.helper.feature.auth.AuthProvider
import com.planterior.helper.feature.auth.AuthUiState
import com.planterior.helper.feature.auth.SyncSummary
import com.planterior.helper.feature.home.HomeMiniHomePreview
import com.planterior.helper.feature.home.HomeSyncState
import com.planterior.helper.feature.home.HomeUiState
import com.planterior.helper.feature.home.HomeWeatherState
import com.planterior.helper.feature.minihome.MiniHomeAuthOwnership
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniHomeAuthOwnershipTest {
    @Test
    fun `restoring and unknown auth defer mini home restoration ownership`() {
        assertEquals(
            MiniHomeAuthOwnership.Restoring,
            miniHomeAuthOwnership(
                AuthUiState.Restoring,
                coordinatorAvailable = true,
                enforcementEnabled = true,
            ),
        )
        assertEquals(
            MiniHomeAuthOwnership.Unknown,
            miniHomeAuthOwnership(
                AuthUiState.SigningIn(AuthProvider.GOOGLE),
                coordinatorAvailable = true,
                enforcementEnabled = true,
            ),
        )
        assertEquals(
            MiniHomeAuthOwnership.Unknown,
            miniHomeAuthOwnership(
                null,
                coordinatorAvailable = false,
                enforcementEnabled = true,
            ),
        )
    }

    @Test
    fun `authoritative auth binds signed out or exact mini home owner`() {
        assertEquals(
            MiniHomeAuthOwnership.SignedOut,
            miniHomeAuthOwnership(
                AuthUiState.SignedOut(),
                coordinatorAvailable = true,
                enforcementEnabled = true,
            ),
        )
        assertEquals(
            MiniHomeAuthOwnership.Authenticated(AccountId("account-a")),
            miniHomeAuthOwnership(
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
    fun `home mini home preview is synchronously hidden for a different or unresolved owner`() {
        val ownerA =
            HomeUiState.Content(
                greetingName = "A private greeting",
                careItems = emptyList(),
                dueTodayCount = 0,
                miniHome = HomeMiniHomePreview("A private preview", 1),
                weather = HomeWeatherState.NotConfigured,
                sync = HomeSyncState.Fresh,
                ownerUid = "account-a",
            )

        assertEquals(
            HomeUiState.Loading,
            ownerA.displayedFor(MiniHomeAuthOwnership.Authenticated(AccountId("account-b"))),
        )
        assertEquals(HomeUiState.Loading, ownerA.displayedFor(MiniHomeAuthOwnership.Restoring))
        assertEquals(HomeUiState.Loading, ownerA.displayedFor(MiniHomeAuthOwnership.Unknown))
        assertEquals(HomeUiState.LoggedOut, ownerA.displayedFor(MiniHomeAuthOwnership.SignedOut))
        assertEquals(
            ownerA,
            ownerA.displayedFor(MiniHomeAuthOwnership.Authenticated(AccountId("account-a"))),
        )
    }

    @Test
    fun `mini home owner enforcement bypass is explicit`() {
        assertEquals(
            MiniHomeAuthOwnership.Unmanaged,
            miniHomeAuthOwnership(
                AuthUiState.Restoring,
                coordinatorAvailable = true,
                enforcementEnabled = false,
            ),
        )
    }
}
