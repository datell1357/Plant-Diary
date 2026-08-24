package com.planterior.helper.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.feature.auth.AuthAccount
import com.planterior.helper.feature.auth.AuthProvider
import com.planterior.helper.feature.auth.AuthUiState
import com.planterior.helper.feature.auth.SyncSummary
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsProseTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `chunks round trip spaces punctuation and explicit newlines without invisible controls`() {
        val text = "동의를 철회하면  진행 중인 위치 요청을 즉시 취소해요.\n\n직접 선택한 지역은 계속 사용할 수 있어요."

        val lines = settingsProseLines(text)
        val reconstructed = lines.joinToString("\n") { it.joinToString("") }

        assertEquals(text, reconstructed)
        assertEquals(3, lines.size)
        assertTrue(lines[1].isEmpty())
        val forbidden = setOf('\u00a0', '\u200b', '\u200c', '\u200d', '\u2060', '\ufeff')
        assertFalse(reconstructed.any(forbidden::contains))
    }

    @Test
    fun `ordinary Korean words and approved auxiliary phrases are indivisible chunks`() {
        val chunks =
            settingsProseLines("직접 선택한 지역은 계속 사용할 수 있어요. 서버 상태를 다시 확인해 주세요.")
                .single()
                .map(String::trimEnd)

        assertTrue("선택한" in chunks)
        assertTrue("사용할 수 있어요." in chunks)
        assertTrue("확인해 주세요." in chunks)
        assertFalse("선택" in chunks)
        assertFalse("한" in chunks)
    }

    @Test
    fun `renderer exposes original prose once and clears visual chunk semantics`() {
        val text = SETTINGS_PHOTO_DISCLOSURE
        compose.setContent {
            PlanteriorTheme {
                SettingsProse(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("prose"),
                )
            }
        }

        compose.onNodeWithTag("prose", useUnmergedTree = true).assertTextEquals(text)
        compose.onAllNodes(hasTextExactly(text), useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("도감", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `settings location and photo paths expose exact full prose once`() {
        compose.setContent {
            PlanteriorTheme { SettingsScreen(settingsState(), SettingsActions()) }
        }

        listOf(
                "settings.location-disclosure" to SETTINGS_LOCATION_DISCLOSURE,
                "settings.photo-disclosure" to SETTINGS_PHOTO_DISCLOSURE,
            )
            .forEach { (tag, text) ->
                compose
                    .onNodeWithTag(tag, useUnmergedTree = true)
                    .performScrollTo()
                    .assertTextEquals(text)
                compose
                    .onAllNodes(hasTextExactly(text), useUnmergedTree = true)
                    .assertCountEquals(1)
            }
        assertTrue(
            settingsProseLines(SETTINGS_LOCATION_DISCLOSURE)
                .flatten()
                .map(String::trimEnd)
                .contains("선택한")
        )
        assertTrue(
            settingsProseLines(SETTINGS_PHOTO_DISCLOSURE)
                .flatten()
                .map(String::trimEnd)
                .contains("도감")
        )
    }

    @Test
    fun `account deletion photo path exposes exact full prose once`() {
        compose.setContent {
            PlanteriorTheme {
                AccountDeletionScreen(deletionState(), AccountDeletionActions(), {})
            }
        }

        compose
            .onNodeWithTag("account-deletion.photo-disclosure", useUnmergedTree = true)
            .performScrollTo()
            .assertTextEquals(SETTINGS_PHOTO_DISCLOSURE)
        compose
            .onAllNodes(hasTextExactly(SETTINGS_PHOTO_DISCLOSURE), useUnmergedTree = true)
            .assertCountEquals(1)
    }

    private fun settingsState() =
        SettingsUiState(
            authState =
                AuthUiState.Authenticated(
                    account =
                        AuthAccount(
                            uid = "account-a",
                            email = "gardener@example.com",
                            displayName = "민지",
                            providers = setOf(AuthProvider.GOOGLE),
                        ),
                    sync = SyncSummary.EMPTY,
                ),
            wateringNotificationsEnabled = true,
            weatherNotificationsEnabled = true,
            quietHoursSummary = "없음",
            regionName = "서울특별시",
            osLocationPermission = DevicePermissionState.ALLOWED,
            appLocationConsentGranted = true,
            lastSyncAt = Instant.parse("2026-08-24T04:00:00Z"),
            osNotificationPermission = DevicePermissionState.DENIED,
            appVersion = "v0.1.0",
        )

    private fun deletionState(): AccountDeletionUiState.Ready {
        val scope =
            AccountDeletionScope(
                AccountDeletionScopeHash("a".repeat(64)),
                AccountDeletionCategory.entries,
            )
        return AccountDeletionUiState.Ready(
            scope = scope,
            workflow = null,
        )
    }
}
