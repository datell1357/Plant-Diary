package com.planterior.helper.feature.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthAccountScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `authenticated account exposes missing provider then requires explicit consent`() {
        var requested: Pair<AuthProvider, Boolean>? = null
        compose.setContent {
            AuthAccountScreen(
                state =
                    AuthUiState.Authenticated(
                        AuthAccount("account-a", null, "A", setOf(AuthProvider.GOOGLE)),
                        SyncSummary.EMPTY,
                    ),
                onLink = { provider, consent -> requested = provider to consent },
                onLogout = {},
            )
        }

        compose.onNodeWithTag("link-apple").assertIsDisplayed().performClick()
        assertEquals(AuthProvider.APPLE to false, requested)
    }

    @Test
    fun `collision explains takeover prevention and has no takeover action`() {
        compose.setContent {
            AuthAccountScreen(
                state = AuthUiState.LinkConflict(AuthProvider.APPLE),
                onLink = { _, _ -> error("must not link") },
                onLogout = {},
            )
        }

        compose.onNodeWithTag("link-conflict").assertIsDisplayed()
        compose.onNodeWithTag("link-takeover").assertDoesNotExist()
    }

    @Test
    fun `consent screen confirms before reauthentication`() {
        var requested: Pair<AuthProvider, Boolean>? = null
        compose.setContent {
            AuthAccountScreen(
                state = AuthUiState.LinkConsentRequired(AuthProvider.APPLE),
                onLink = { provider, consent -> requested = provider to consent },
                onLogout = {},
            )
        }

        compose.onNodeWithTag("link-consent-confirm").performClick()
        assertEquals(AuthProvider.APPLE to true, requested)
    }
}
