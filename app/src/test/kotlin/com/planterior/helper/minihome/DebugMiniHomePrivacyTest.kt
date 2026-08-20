package com.planterior.helper.minihome

import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.minihome.MiniHomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebugMiniHomePrivacyTest {
    @Test
    fun `owner transition loading and unavailable logs contain only incoming owner identity`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val accountB = AccountId("account-b")

        observeDebugMiniHomeState(context, MiniHomeUiState.Loading(accountB))
        val loading = requireNotNull(currentDebugMiniHomeState()).snapshot
        assertEquals("account-b", loading.accountId)
        assertEquals(DebugMiniHomeStateMode.LOADING, loading.mode)
        assertNull(loading.name)
        assertTrue(loading.placements.isEmpty())
        assertFalse(loading.toString().contains("A 비밀"))
        assertFalse(loading.toString().contains("a-private-plant"))

        observeDebugMiniHomeState(context, MiniHomeUiState.Unavailable(accountB))
        val unavailable = requireNotNull(currentDebugMiniHomeState()).snapshot
        assertEquals("account-b", unavailable.accountId)
        assertEquals(DebugMiniHomeStateMode.UNAVAILABLE, unavailable.mode)
        assertNull(unavailable.name)
        assertTrue(unavailable.placements.isEmpty())
        assertFalse(unavailable.toString().contains("A 비밀"))
        assertFalse(unavailable.toString().contains("a-private-plant"))
    }
}
