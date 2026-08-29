package com.planterior.helper.feature.registration

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RegistrationRouteTest {
    @get:Rule val compose = createComposeRule()

    private val candidate = RegistrationContent(PlantContentId("species-monstera"), "Monstera")

    @Test
    fun `route publishes selected Idle target before a following edit`() {
        // Given
        val observed = mutableListOf<RegistrationUiState>()
        val diagnostics = mutableListOf<RegistrationDiagnosticEvent>()
        lateinit var model: RegistrationViewModel
        compose.setContent {
            val owner = checkNotNull(LocalViewModelStoreOwner.current)
            PlanteriorTheme {
                RegistrationRoute(
                    seed = RegistrationSeed.Manual,
                    repository = Repository(candidate),
                    onOpenExisting = {},
                    onCompleted = {},
                    onCancel = {},
                    onStateObserved = observed::add,
                    diagnosticObserver = { event ->
                        diagnostics += event
                        if (
                            event is RegistrationDiagnosticEvent.SelectContentControllerTransition
                        ) {
                            Handler(Looper.getMainLooper()).post {
                                model.controller.changeName("Following edit")
                            }
                        }
                    },
                )
            }
            model =
                ViewModelProvider(owner)
                    .get(
                        "registration-${RegistrationSeed.Manual.hashCode()}",
                        RegistrationViewModel::class.java,
                    )
        }
        compose.onNodeWithTag(RegistrationTestTags.SEARCH).performTextReplacement("monster")
        compose.onNodeWithTag(RegistrationTestTags.SEARCH_ACTION).performClick()
        compose.onNodeWithTag(RegistrationTestTags.content(candidate.id.value)).assertIsDisplayed()

        // When
        compose.onNodeWithTag(RegistrationTestTags.content(candidate.id.value)).performClick()

        // Then
        compose.runOnIdle {
            val target =
                diagnostics
                    .filterIsInstance<
                        RegistrationDiagnosticEvent.SelectContentControllerTransition
                    >()
                    .single()
                    .after as RegistrationUiState.Editing
            assertEquals(candidate.id, target.draft.selectedContent?.id)
            assertEquals(RegistrationSearchState.Idle, target.search)
            assertNull(model.controller.editing().draft.selectedContent)
            assertEquals(
                "Controller reached selected/Idle but the active route observer skipped it",
                1,
                observed.filterIsInstance<RegistrationUiState.Editing>().count {
                    it.draft.selectedContent?.id == candidate.id
                },
            )
        }
    }

    @Test
    fun `selected result reaches controller Idle state and route callback exactly once`() {
        // Given
        val observed = mutableListOf<RegistrationUiState>()
        val diagnostics = mutableListOf<RegistrationDiagnosticEvent>()
        compose.setContent {
            PlanteriorTheme {
                RegistrationRoute(
                    seed = RegistrationSeed.Manual,
                    repository = Repository(candidate),
                    onOpenExisting = {},
                    onCompleted = {},
                    onCancel = {},
                    onStateObserved = observed::add,
                    diagnosticObserver = diagnostics::add,
                )
            }
        }
        compose.onNodeWithTag(RegistrationTestTags.SEARCH).performTextReplacement("monster")
        compose.onNodeWithTag(RegistrationTestTags.SEARCH_ACTION).performClick()
        compose.onNodeWithTag(RegistrationTestTags.content(candidate.id.value)).assertIsDisplayed()

        // When
        compose.onNodeWithTag(RegistrationTestTags.content(candidate.id.value)).performClick()

        // Then
        compose.onNodeWithTag(RegistrationTestTags.NAME).assertTextContains(candidate.name)
        compose.runOnIdle {
            val transition =
                diagnostics
                    .filterIsInstance<
                        RegistrationDiagnosticEvent.SelectContentControllerTransition
                    >()
                    .single()
            val controllerState = transition.after as RegistrationUiState.Editing
            assertNull((transition.before as RegistrationUiState.Editing).draft.selectedContent)
            assertTrue(transition.before.search is RegistrationSearchState.Results)
            assertEquals(candidate.id, controllerState.draft.selectedContent?.id)
            assertEquals(RegistrationSearchState.Idle, controllerState.search)

            val selectedPublications =
                observed.filterIsInstance<RegistrationUiState.Editing>().filter { state ->
                    state.draft.selectedContent?.id == candidate.id
                }
            assertEquals(
                "Controller reached selected/Idle but the route callback did not publish it once",
                1,
                selectedPublications.size,
            )
            assertEquals(RegistrationSearchState.Idle, selectedPublications.single().search)
        }
    }

    @Test
    fun `real last watered field updates draft with fixed valid date`() {
        // Given
        lateinit var model: RegistrationViewModel
        compose.setContent {
            val owner = checkNotNull(LocalViewModelStoreOwner.current)
            PlanteriorTheme {
                RegistrationRoute(
                    seed = RegistrationSeed.Manual,
                    repository = Repository(candidate),
                    onOpenExisting = {},
                    onCompleted = {},
                    onCancel = {},
                )
            }
            model =
                ViewModelProvider(owner)
                    .get(
                        "registration-${RegistrationSeed.Manual.hashCode()}",
                        RegistrationViewModel::class.java,
                    )
        }

        // When
        compose
            .onNodeWithTag(RegistrationTestTags.LAST_WATERED)
            .performTextReplacement(ENTERED_LAST_WATERED_DATE)

        // Then
        compose.runOnIdle {
            val draftDate = model.controller.editing().draft.lastWateredDate
            assertEquals(ENTERED_LAST_WATERED_DATE, draftDate)
            assertEquals(LocalDate.of(2026, 8, 20), LocalDate.parse(draftDate))
        }
    }

    @Test
    fun `disposing route cancels state observation`() {
        // Given
        val observed = mutableListOf<RegistrationUiState>()
        lateinit var model: RegistrationViewModel
        var routeActive by mutableStateOf(true)
        compose.setContent {
            val owner = checkNotNull(LocalViewModelStoreOwner.current)
            if (routeActive) {
                PlanteriorTheme {
                    RegistrationRoute(
                        seed = RegistrationSeed.Manual,
                        repository = Repository(candidate),
                        onOpenExisting = {},
                        onCompleted = {},
                        onCancel = {},
                        onStateObserved = observed::add,
                    )
                }
            }
            model =
                ViewModelProvider(owner)
                    .get(
                        "registration-${RegistrationSeed.Manual.hashCode()}",
                        RegistrationViewModel::class.java,
                    )
        }
        compose.runOnIdle { assertTrue(observed.isNotEmpty()) }

        // When
        compose.runOnIdle { routeActive = false }
        val countAtDisposal = observed.size
        compose.runOnIdle { model.controller.changeName("After disposal") }

        // Then
        compose.runOnIdle {
            assertEquals("Disposed route callback received state", countAtDisposal, observed.size)
            assertEquals("After disposal", model.controller.editing().draft.name)
        }
    }

    @Test
    fun `replacing observer forwards future state only to replacement`() {
        // Given
        val oldObserver = mutableListOf<RegistrationUiState>()
        val newObserver = mutableListOf<RegistrationUiState>()
        var observer by mutableStateOf<(RegistrationUiState) -> Unit>({ oldObserver += it })
        lateinit var model: RegistrationViewModel
        compose.setContent {
            val owner = checkNotNull(LocalViewModelStoreOwner.current)
            PlanteriorTheme {
                RegistrationRoute(
                    seed = RegistrationSeed.Manual,
                    repository = Repository(candidate),
                    onOpenExisting = {},
                    onCompleted = {},
                    onCancel = {},
                    onStateObserved = observer,
                )
            }
            model =
                ViewModelProvider(owner)
                    .get(
                        "registration-${RegistrationSeed.Manual.hashCode()}",
                        RegistrationViewModel::class.java,
                    )
        }
        compose.runOnIdle { assertTrue(oldObserver.isNotEmpty()) }
        val oldCount = oldObserver.size

        // When
        compose.runOnIdle { observer = { newObserver += it } }
        compose.runOnIdle { model.controller.changeName("Replacement callback") }

        // Then
        compose.runOnIdle {
            assertEquals(oldCount, oldObserver.size)
            assertEquals(1, newObserver.size)
            assertEquals(
                "Replacement callback",
                (newObserver.single() as RegistrationUiState.Editing).draft.name,
            )
        }
    }

    private companion object {
        const val ENTERED_LAST_WATERED_DATE = "2026-08-20"
    }

    private class Repository(private val candidate: RegistrationContent) : RegistrationRepository {
        override suspend fun session() =
            RegistrationSession(AccountId("route-owner"), ZoneId.of("UTC"))

        override suspend fun searchPublicContents(query: String) = listOf(candidate)

        override suspend fun findDuplicates(
            accountId: AccountId,
            contentId: PlantContentId,
            excluding: PersonalPlantId,
        ): List<ExistingPersonalPlant> = emptyList()

        override suspend fun register(
            submission: PendingRegistration,
            checkpoint: RegistrationCheckpoint,
        ): RegistrationAttempt =
            RegistrationAttempt.Failed(RegistrationFailure.DATABASE_UNAVAILABLE, checkpoint)
    }
}
