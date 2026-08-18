package com.planterior.helper.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.feature.registration.ExistingPersonalPlant
import com.planterior.helper.feature.registration.PendingRegistration
import com.planterior.helper.feature.registration.RegistrationAttempt
import com.planterior.helper.feature.registration.RegistrationCheckpoint
import com.planterior.helper.feature.registration.RegistrationContent
import com.planterior.helper.feature.registration.RegistrationRepository
import com.planterior.helper.feature.registration.RegistrationSession
import com.planterior.helper.feature.registration.RegistrationTestTags
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_MAX_SDK])
class RegistrationNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `registration navigation callbacks open existing and newly-created stable ids`() {
        val destinations = mutableListOf<PlanteriorRoute>()
        val callbacks = RegistrationNavigationCallbacks(destinations::add)

        callbacks.openExisting(PersonalPlantId("existing-plant"))
        callbacks.registrationCompleted(PersonalPlantId("new-plant"))

        assertEquals(
            listOf(
                PlanteriorRoute.PlantDetail("existing-plant"),
                PlanteriorRoute.PlantDetail("new-plant"),
            ),
            destinations,
        )
    }

    @Test
    fun `completed registration is removed from the real back stack`() {
        lateinit var navController: NavHostController
        compose.setContent {
            PlanteriorTheme {
                navController = rememberNavController()
                PlanteriorNavHost(
                    navController = navController,
                    startRoute = PlanteriorRoute.Camera,
                    registrationRepository = CompletingRegistrationRepository,
                )
            }
        }

        compose.onNodeWithText("식물 이름을 직접 등록").performClick()
        compose.onNodeWithTag(RegistrationTestTags.NAME).performTextInput("고무나무")
        compose.onNodeWithTag(RegistrationTestTags.SUBMIT).performClick()
        compose.onNodeWithText("식물 상세").assertIsDisplayed()

        compose.runOnIdle { navController.popBackStack() }
        compose.onNodeWithText("식물 촬영").assertIsDisplayed()
    }

    @Test
    fun `camera direct registration reaches the production manual registration surface`() {
        compose.setContent {
            PlanteriorTheme {
                PlanteriorNavHost(
                    navController = rememberNavController(),
                    startRoute = PlanteriorRoute.Camera,
                    registrationRepository = NavigationRegistrationRepository,
                )
            }
        }

        compose.onNodeWithText("식물 이름을 직접 등록").performClick()
        compose.onNodeWithTag(RegistrationTestTags.NAME).assertIsDisplayed()
        compose.onNodeWithTag(RegistrationTestTags.SUBMIT).assertIsDisplayed()
    }
}

private object CompletingRegistrationRepository : RegistrationRepository {
    override suspend fun session() =
        RegistrationSession(AccountId("account-a"), ZoneId.of("Asia/Seoul"))

    override suspend fun searchPublicContents(query: String) = emptyList<RegistrationContent>()

    override suspend fun findDuplicates(
        accountId: AccountId,
        contentId: PlantContentId,
        excluding: PersonalPlantId,
    ) = emptyList<ExistingPersonalPlant>()

    override suspend fun register(
        submission: PendingRegistration,
        checkpoint: RegistrationCheckpoint,
    ) =
        RegistrationAttempt.Completed(
            submission.toPersonalPlant(1, java.time.Instant.parse("2026-08-18T03:00:00Z"))
        )
}

private object NavigationRegistrationRepository : RegistrationRepository {
    override suspend fun session() =
        RegistrationSession(AccountId("account-a"), ZoneId.of("Asia/Seoul"))

    override suspend fun searchPublicContents(query: String) = emptyList<RegistrationContent>()

    override suspend fun findDuplicates(
        accountId: AccountId,
        contentId: PlantContentId,
        excluding: PersonalPlantId,
    ) = emptyList<ExistingPersonalPlant>()

    override suspend fun register(
        submission: PendingRegistration,
        checkpoint: RegistrationCheckpoint,
    ): RegistrationAttempt = error("No save expected")
}
