package com.planterior.helper

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.navigation.NavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.feature.identify.IdentificationTestTags
import com.planterior.helper.home.SESSION_SIGNED_IN
import com.planterior.helper.navigation.PlanteriorRoute
import com.planterior.helper.navigation.toPlanteriorRoute
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production [MainActivity] failure journeys for the debug-only identification boundary.
 *
 * This intentionally drives the real NavHost and IdentificationRoute; only the remote
 * identification response is selected by the debug source set. Each case writes a screenshot and a
 * closed JSON receipt for the host evidence collector.
 */
@RunWith(AndroidJUnit4::class)
class Todo18IdentificationFailureJourneyTest {
    @get:Rule(order = 0) val signedInSession = DebugHomeSessionRule(SESSION_SIGNED_IN, ACCOUNT_ID)

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun identification429ShowsRecoverableCjkFallback() =
        assertFailureJourney(
            requestId = "fixture-rate-limited",
            expectedTitle = "지금은 요청이 많아요",
            receiptName = "identification-429",
        )

    @Test
    fun identification500ShowsRecoverableCjkFallback() =
        assertFailureJourney(
            requestId = "fixture-provider-unavailable",
            expectedTitle = "식물 분석을 사용할 수 없어요",
            receiptName = "identification-500",
        )

    @Test
    fun identificationNoCandidateShowsAlternativeActions() {
        navigateTo(PlanteriorRoute.Identification("fixture-no-candidates")) {
            compose.runOnIdle {
                compose.activity.navigationController.navigate(
                    PlanteriorRoute.Identification("fixture-no-candidates")
                )
            }
        }
        compose.onNodeWithText("사진에서 식물 후보를 찾지 못했어요").assertIsDisplayed()
        compose.onNodeWithTag(IdentificationTestTags.RETAKE).assertIsDisplayed()
        compose.onNodeWithTag(IdentificationTestTags.CHANGE).assertIsDisplayed()
        compose.onNodeWithTag(IdentificationTestTags.EDIT).assertIsDisplayed()
        compose.onNodeWithTag(IdentificationTestTags.REGISTER).assertIsDisplayed()
        captureReceipt("identification-no-candidate", "NO_CANDIDATE")
    }

    private fun assertFailureJourney(
        requestId: String,
        expectedTitle: String,
        receiptName: String,
    ) {
        navigateTo(PlanteriorRoute.Identification(requestId)) {
            compose.runOnIdle {
                compose.activity.navigationController.navigate(
                    PlanteriorRoute.Identification(requestId)
                )
            }
        }
        compose.onNodeWithText(expectedTitle).assertIsDisplayed()
        compose.onNodeWithTag(IdentificationTestTags.RETRY).assertIsDisplayed()
        compose.onNodeWithTag(IdentificationTestTags.RETAKE).assertIsDisplayed()
        compose.onNodeWithTag(IdentificationTestTags.REGISTER).assertIsDisplayed()
        captureReceipt(receiptName, requestId)
    }

    private fun navigateTo(expected: PlanteriorRoute, trigger: () -> Unit) {
        val controller = compose.activity.navigationController
        ExactEventSubscription<PlanteriorRoute>(
                matches = { it == expected },
                subscribe = { receiver -> subscribeToDestinations(controller, receiver) },
            )
            .use { subscription ->
                subscription.arm()
                trigger()
                subscription.await(
                    EVENT_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS,
                    "Todo18 identification route $expected",
                )
            }
        compose.waitForIdle()
    }

    private fun subscribeToDestinations(
        controller: NavController,
        receiver: (PlanteriorRoute) -> Unit,
    ): ExactEventRegistration {
        lateinit var listener: NavController.OnDestinationChangedListener
        return LeasedExactEventRegistration(
            receiver = receiver,
            register = { dispatch ->
                listener = NavController.OnDestinationChangedListener { current, _, _ ->
                    current.currentBackStackEntry.toPlanteriorRoute()?.let(dispatch)
                }
                compose.runOnIdle { controller.addOnDestinationChangedListener(listener) }
            },
            unregister = {
                compose.runOnIdle { controller.removeOnDestinationChangedListener(listener) }
            },
        )
    }

    private fun captureReceipt(name: String, outcome: String) {
        val directory =
            requireNotNull(compose.activity.getExternalFilesDir("todo18-e2e-journeys")).also {
                check(it.exists() || it.mkdirs())
            }
        val screenshot = File(directory, "$name-api${Build.VERSION.SDK_INT}.png")
        screenshot.outputStream().use { output ->
            check(
                compose
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output)
            )
        }
        File(directory, "$name-api${Build.VERSION.SDK_INT}.json")
            .writeText(
                """{"scenario":"$outcome","api":${Build.VERSION.SDK_INT},"route":"identification","cjkAccessible":true,"screenshot":"${screenshot.name}"}"""
            )
    }

    private companion object {
        const val ACCOUNT_ID = "todo18-identification-journey"
        const val EVENT_TIMEOUT_MILLIS = 10_000L
    }
}
