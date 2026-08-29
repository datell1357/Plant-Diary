package com.planterior.helper

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.planterior.helper.diagnostic.Todo18WaitId
import com.planterior.helper.feature.registration.RegistrationTestTags
import com.planterior.helper.feature.registration.RegistrationUiState
import com.planterior.helper.navigation.PlanteriorRoute
import java.io.File
import org.junit.Assert.assertTrue

/** Reused UI-driving role for the Todo18 MainActivity journey assertions. */
internal class Todo18MainActivityJourneyHarness(
    val runtime: Todo18IntegratedRuntimeRule,
    val compose: Todo18ComposeRule,
) {
    val events = Todo18JourneyEventProbe(runtime, compose)
    val rendered = Todo18RenderedStateProbe(runtime, compose)

    fun registerPlantThroughProductRoute(): String {
        events.navigateTo(PlanteriorRoute.Registration) {
            compose.runOnIdle {
                compose.activity.navigationController.navigate(PlanteriorRoute.Registration)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(RegistrationTestTags.NAME).performTextReplacement("Todo18 Monstera")
        events.awaitBoundary("registration-search") {
            compose.onNodeWithTag(RegistrationTestTags.SEARCH_ACTION).performClick()
        }
        compose.waitForIdle()
        val contentId = "species-monstera"
        Todo18TransitionDiagnosticCapture(
                runtime,
                compose,
                Todo18WaitId.REGISTRATION_SELECT_CONTENT,
            )
            .run(
                wait = { observer ->
                    rendered.awaitRegistration(
                        matches = { event ->
                            (event.state as? RegistrationUiState.Editing)
                                ?.draft
                                ?.selectedContent
                                ?.id
                                ?.value == contentId
                        },
                        trigger = {
                            compose
                                .onNodeWithTag(RegistrationTestTags.content(contentId))
                                .performScrollTo()
                                .performClick()
                        },
                        observer = observer,
                    )
                },
                uiPostcondition = {
                    compose.onNodeWithTag(RegistrationTestTags.NAME).assertTextContains("몬스테라")
                },
            )
        compose
            .onNodeWithTag(RegistrationTestTags.LAST_WATERED)
            .performScrollTo()
            .performTextReplacement("2026-08-20")
        compose.waitForIdle()

        return Todo18RegistrationCommitDiagnosticCapture(runtime, compose).run {
            compose.onNodeWithTag(RegistrationTestTags.SUBMIT).performScrollTo().performClick()
        }
    }

    fun navigateDirectly(route: PlanteriorRoute) {
        events.navigateTo(route) {
            compose.runOnIdle { compose.activity.navigationController.navigate(route) }
        }
    }

    fun captureReceipt(name: String, outcome: String) {
        val directory =
            requireNotNull(compose.activity.getExternalFilesDir("todo18-e2e-journeys")).also {
                check(it.exists() || it.mkdirs())
            }
        val screenshot = File(directory, "$name-api${Build.VERSION.SDK_INT}.png")
        screenshot.outputStream().use { output ->
            assertTrue(
                compose
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output)
            )
        }
        File(directory, "$name-api${Build.VERSION.SDK_INT}.json")
            .writeText(
                """{"scenario":"$name","outcome":"$outcome","api":${Build.VERSION.SDK_INT},"mainActivity":true,"room":true,"screenshot":"${screenshot.name}"}"""
            )
    }
}
