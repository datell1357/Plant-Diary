package com.planterior.helper

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.home.SESSION_SIGNED_IN
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Todo18 product-graph journeys driven through the real MainActivity and typed NavHost. */
@RunWith(AndroidJUnit4::class)
class Todo18IntegratedJourneyMainActivityTest {
    @get:Rule(order = 0) val localNetworkPermission = Api37LocalNetworkPermissionRule()

    @get:Rule(order = 1)
    val signedInSession =
        DebugHomeSessionRule(SESSION_SIGNED_IN, Todo18IntegratedRuntimeRule.ACCOUNT_UID)

    @get:Rule(order = 2) val runtime = Todo18IntegratedRuntimeRule()

    @get:Rule(order = 3) val compose = createAndroidComposeRule<MainActivity>()

    private val journey by lazy { Todo18MainActivityJourneyHarness(runtime, compose) }

    @Test
    fun registrationCollectionWateringInventoryMiniHomeShareWeatherAndDeletionPersistInRoom() {
        journey.assertMajorProductJourneyPersistsInRoom()
    }

    @Test
    fun offlineMiniHomeSaveReplaysTheExactPersistedOperationWithoutPolling() {
        journey.assertOfflineMiniHomeReplayUsesPersistedOperation()
    }

    @Test
    fun miniHomeRevisionConflictPreservesDraftAndShowsRecoveryAction() {
        journey.assertMiniHomeConflictPreservesDraft()
    }

    @Test
    fun cameraPermissionDenialAndMalformedUriStayOnProductionCameraFlow() {
        journey.assertCameraPermissionDenial()
    }

    @Test
    fun malformedPickerUriIsRejectedByTheRealPhotoValidator() {
        journey.assertMalformedPickerUriRejected()
    }

    @Test
    fun expiredAndDeletedShareResponsesHaveClosedAccessibleStates() {
        journey.assertExpiredAndDeletedShareStates()
    }
}
