package com.planterior.helper.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.feature.auth.AuthAccount
import com.planterior.helper.feature.auth.AuthProvider
import com.planterior.helper.feature.auth.AuthUiState
import com.planterior.helper.feature.auth.SyncSummary
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `settings exposes account deletion entry`() {
        compose.setContent { PlanteriorTheme { SettingsScreen(readyState(), SettingsActions()) } }

        compose.onNodeWithTag("account-delete").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `logout exposes legacy and settings tags with one callback`() {
        var logouts = 0
        compose.setContent {
            PlanteriorTheme {
                SettingsScreen(
                    readyState(),
                    SettingsActions(onLogout = { logouts += 1 }),
                )
            }
        }

        compose
            .onNodeWithTag("account-logout")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
        compose.onNodeWithTag("settings.logout").assertExists().assertHasClickAction()
        compose.onNodeWithTag("account-delete").performScrollTo().assertExists()

        compose.onNodeWithTag("account-logout").performScrollTo().performClick()

        assertEquals(1, logouts)
    }

    @Test
    fun `account deletion entry invokes its navigation callback once`() {
        var opens = 0
        compose.setContent {
            PlanteriorTheme {
                SettingsScreen(
                    readyState(),
                    SettingsActions(onOpenAccountDeletion = { opens += 1 }),
                )
            }
        }

        compose.onNodeWithTag("account-delete").performScrollTo().performClick()

        assertEquals(1, opens)
    }

    @Test
    fun `root owns one vertical scroll and keeps audited section hierarchy`() {
        compose.setContent { PlanteriorTheme { SettingsScreen(readyState(), SettingsActions()) } }

        compose.onNodeWithTag("settings.screen").assertIsDisplayed()
        compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).assertCountEquals(1)
        val hierarchy = collectTags(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        val sectionOffsets =
            listOf(
                    "settings.profile",
                    "settings.section.notifications",
                    "settings.section.environment",
                    "settings.section.data",
                    "settings.section.account",
                    "settings.section.other",
                )
                .map(hierarchy::indexOf)
        assertTrue(sectionOffsets.all { it >= 0 })
        assertEquals(sectionOffsets.sorted(), sectionOffsets)
    }

    @Test
    fun `two hundred percent text stacks trailing values and keeps merged switches`() {
        compose.setContent {
            PlanteriorTheme {
                val current = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(current.density, fontScale = 2f)
                ) {
                    SettingsScreen(readyState(), SettingsActions())
                }
            }
        }

        compose.onNodeWithTag("settings.region", useUnmergedTree = true).performScrollTo()
        val label =
            compose
                .onNodeWithTag("settings.region.label", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val value =
            compose
                .onNodeWithTag("settings.region.value", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(value.top >= label.bottom)
        compose
            .onNodeWithTag("settings.watering-switch")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsOn()
        compose
            .onNodeWithTag("settings.weather-switch")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsOn()
    }

    private fun collectTags(node: SemanticsNode): List<String> = buildList {
        if (node.config.contains(SemanticsProperties.TestTag)) {
            add(node.config[SemanticsProperties.TestTag])
        }
        node.children.forEach { addAll(collectTags(it)) }
    }

    private fun readyState() =
        SettingsUiState(
            authState =
                AuthUiState.Authenticated(
                    AuthAccount(
                        uid = "account-a",
                        email = "gardener@example.com",
                        displayName = "민지",
                        providers = setOf(AuthProvider.GOOGLE),
                    ),
                    SyncSummary.EMPTY,
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
}
