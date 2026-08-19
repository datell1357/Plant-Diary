package com.planterior.helper.navigation

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.feature.auth.AccountProfileStore
import com.planterior.helper.feature.auth.AccountSessionCache
import com.planterior.helper.feature.auth.AccountSynchronizer
import com.planterior.helper.feature.auth.AuthAccount
import com.planterior.helper.feature.auth.AuthCoordinator
import com.planterior.helper.feature.auth.FirebaseIdentityGateway
import com.planterior.helper.feature.auth.ProviderProof
import com.planterior.helper.feature.auth.SyncSummary
import com.planterior.helper.feature.watering.GlobalWateringReminder
import com.planterior.helper.feature.watering.WateringNotificationSettings
import com.planterior.helper.feature.watering.WateringNotificationSettingsRepository
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_MAX_SDK])
class NotificationSettingsEntryNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `settings notification entry opens production notifications route`() {
        lateinit var navController: NavHostController
        compose.setContent {
            navController = rememberNavController()
            PlanteriorNavHost(
                navController = navController,
                startRoute = PlanteriorRoute.Settings,
                authCoordinator = coordinator(),
                wateringNotificationSettingsRepository = NotificationSettingsRepository,
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("account-notification-settings").performClick()
        compose.waitForIdle()

        assertEquals(
            PlanteriorRoute.Notifications,
            navController.currentBackStackEntry.toPlanteriorRoute(),
        )
    }

    private fun coordinator() =
        AuthCoordinator(
            emptyMap(),
            object : FirebaseIdentityGateway {
                override fun current(): AuthAccount? = null

                override suspend fun signIn(proof: ProviderProof): AuthAccount = error("unused")

                override suspend fun reauthenticate(proof: ProviderProof): AuthAccount =
                    error("unused")

                override suspend fun link(proof: ProviderProof): AuthAccount = error("unused")

                override suspend fun signOut() = Unit
            },
            AccountProfileStore {},
            object : AccountSessionCache {
                override suspend fun clearVisible(accountUid: String?) = Unit

                override fun activate(accountUid: String?) = Unit
            },
            AccountSynchronizer { SyncSummary.EMPTY },
        )

    private object NotificationSettingsRepository : WateringNotificationSettingsRepository {
        override suspend fun load() =
            WateringNotificationSettings(
                GlobalWateringReminder(
                    enabled = true,
                    defaultTime = LocalTime.of(9, 0),
                    zoneId = ZoneId.of("Asia/Seoul"),
                ),
                emptyList(),
            )

        override suspend fun save(settings: WateringNotificationSettings) =
            com.planterior.helper.feature.watering.WateringSettingsSaveResult.Saved(settings)
    }
}
