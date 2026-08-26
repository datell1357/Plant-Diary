package com.planterior.helper.feature.settings

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.planterior.helper.core.designsystem.component.PlanteriorBottomBar
import com.planterior.helper.core.designsystem.component.PlanteriorTab
import com.planterior.helper.core.designsystem.icon.PlanteriorIcons
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.DeletionRequestId
import com.planterior.helper.core.model.DeletionStatus
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.feature.auth.AuthAccount
import com.planterior.helper.feature.auth.AuthProvider
import com.planterior.helper.feature.auth.AuthUiState
import com.planterior.helper.feature.auth.SyncSummary
import com.planterior.helper.feature.identify.IdentificationCandidate
import com.planterior.helper.feature.identify.IdentificationScreen
import com.planterior.helper.feature.identify.IdentificationTestTags
import com.planterior.helper.feature.identify.IdentificationUiState
import com.planterior.helper.feature.weather.ApproximateLocation
import com.planterior.helper.feature.weather.LocationPermission
import com.planterior.helper.feature.weather.WeatherConsentMutationResult
import com.planterior.helper.feature.weather.WeatherLoad
import com.planterior.helper.feature.weather.WeatherLocationGateway
import com.planterior.helper.feature.weather.WeatherPermissionCapabilityState
import com.planterior.helper.feature.weather.WeatherPermissionCapabilityStore
import com.planterior.helper.feature.weather.WeatherRegion
import com.planterior.helper.feature.weather.WeatherRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal data class Todo16EvidenceSource(val head: String, val tree: String)

internal object Todo16EvidenceSourceArguments {
    private val gitObjectId = Regex("^[0-9a-f]{40}$")

    fun require(head: String?, tree: String?): Todo16EvidenceSource {
        require(head != null) { "Missing instrumentation argument: todo16SourceHead" }
        require(tree != null) { "Missing instrumentation argument: todo16SourceTree" }
        require(gitObjectId.matches(head)) {
            "Malformed instrumentation argument todo16SourceHead: expected 40 lowercase hex characters"
        }
        require(gitObjectId.matches(tree)) {
            "Malformed instrumentation argument todo16SourceTree: expected 40 lowercase hex characters"
        }
        return Todo16EvidenceSource(head, tree)
    }
}

internal enum class Todo16CaptureContract(
    val file: String,
    val rootTag: String,
    val focusTag: String,
) {
    SETTINGS_ROOT(
        "todo16-api37-settings-root.png",
        "settings.screen",
        "settings.profile",
    ),
    SETTINGS_ROOT_FONT_200(
        "todo16-api37-settings-root-font-200.png",
        "settings.screen",
        "settings.region",
    ),
    ANALYTICS_FONT_200(
        "todo17-api37-analytics-font-200.png",
        "settings.screen",
        "settings.analytics-disclosure",
    ),
    IDENTIFICATION_CANDIDATES(
        "todo17-api37-identification-candidates.png",
        IdentificationTestTags.SCREEN,
        IdentificationTestTags.candidate("species-pothos"),
    ),
    DELETION_PREVIEW_READY(
        "todo16-api37-deletion-preview-ready.png",
        "account-deletion.screen",
        "account-deletion.submit",
    ),
    DELETION_RECEIVED_GRACE(
        "todo16-api37-deletion-received-grace.png",
        "account-deletion.screen",
        "account-deletion.cancel",
    ),
    DELETION_PROCESSING(
        "todo16-api37-deletion-processing.png",
        "account-deletion.screen",
        "account-deletion.status",
    ),
    DELETION_PARTIALLY_FAILED(
        "todo16-api37-deletion-partially-failed.png",
        "account-deletion.screen",
        "account-deletion.retry",
    ),
    DELETION_COMPLETED(
        "todo16-api37-deletion-completed.png",
        "account-deletion.screen",
        "account-deletion.done",
    ),
    LOCATION_CONSENT_REVOKED_MANUAL_REGION(
        "todo16-api37-location-consent-revoked-manual-region.png",
        "settings.screen",
        "settings.location-consent",
    ),
}

/** Todo16 settings and account-deletion API 37 visual evidence host. */
@RunWith(AndroidJUnit4::class)
class Todo16SettingsVisualApi37Test {
    @get:Rule val compose = createComposeRule()

    private val requestedAt = Instant.parse("2026-08-24T04:00:00Z")
    private val scheduledAt = Instant.parse("2026-08-31T04:00:00Z")
    private val scope =
        AccountDeletionScope(
            AccountDeletionScopeHash("a".repeat(64)),
            AccountDeletionCategory.entries,
        )

    @Test
    fun tenReviewedStatesUseFreshProductionScreensAndExactApi37Pixels() {
        assertEquals(37, Build.VERSION.SDK_INT)
        val arguments = InstrumentationRegistry.getArguments()
        val evidenceSource =
            Todo16EvidenceSourceArguments.require(
                arguments.getString("todo16SourceHead"),
                arguments.getString("todo16SourceTree"),
            )
        clearFrameDiagnostics()
        var scenario by mutableStateOf("settings-root")
        var fontScale by mutableStateOf(1f)
        var surface by mutableStateOf<VisualSurface>(VisualSurface.Settings(settingsState()))
        var notificationSettingsCallbacks = 0
        var locationRevocationCallbacks = 0
        var reauthenticationCallbacks = 0
        var confirmationCallbacks = 0
        var submitCallbacks = 0
        var cancelCallbacks = 0
        var exitCallbacks = 0

        compose.setContent {
            if (!rememberEvidenceEdgeToEdgeReady()) return@setContent
            val density = LocalDensity.current
            key(scenario) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale)
                ) {
                    PlanteriorTheme {
                        when (val current = surface) {
                            is VisualSurface.Settings ->
                                SettingsScreen(
                                    state = current.state,
                                    actions =
                                        SettingsActions(
                                            onOpenNotificationSettings = {
                                                notificationSettingsCallbacks += 1
                                            },
                                            onLocationConsentChanged = { enabled ->
                                                if (!enabled) locationRevocationCallbacks += 1
                                            },
                                        ),
                                    bottomBar = { SettingsBottomBar() },
                                )
                            is VisualSurface.Identification ->
                                IdentificationScreen(
                                    state = current.state,
                                    onSelect = { candidate ->
                                        val candidates =
                                            (surface as? VisualSurface.Identification)?.state
                                        if (candidates != null) {
                                            surface =
                                                VisualSurface.Identification(
                                                    candidates.copy(
                                                        selectedId = candidate.publicContentId
                                                    )
                                                )
                                        }
                                    },
                                    onConfirm = {},
                                    onFallback = {},
                                    onBack = {},
                                )
                            is VisualSurface.Deletion ->
                                AccountDeletionScreen(
                                    state = current.state,
                                    actions =
                                        AccountDeletionActions(
                                            onReauthenticate = {
                                                reauthenticationCallbacks += 1
                                                val ready =
                                                    (surface as? VisualSurface.Deletion)?.state
                                                        as? AccountDeletionUiState.Ready
                                                if (ready != null) {
                                                    surface =
                                                        VisualSurface.Deletion(
                                                            ready.copy(reauthenticated = true)
                                                        )
                                                }
                                            },
                                            onFinalConfirmationChanged = { confirmed ->
                                                confirmationCallbacks += 1
                                                val ready =
                                                    (surface as? VisualSurface.Deletion)?.state
                                                        as? AccountDeletionUiState.Ready
                                                if (ready != null) {
                                                    surface =
                                                        VisualSurface.Deletion(
                                                            ready.copy(finalConfirmed = confirmed)
                                                        )
                                                }
                                            },
                                            onSubmit = { submitCallbacks += 1 },
                                            onCancel = { cancelCallbacks += 1 },
                                        ),
                                    onBack = { exitCallbacks += 1 },
                                )
                        }
                    }
                }
            }
        }

        fun show(
            name: String,
            next: VisualSurface,
            scale: Float = 1f,
        ) {
            compose.runOnIdle {
                scenario = name
                fontScale = scale
                surface = next
            }
            compose.waitForIdle()
        }

        assertSettingsHierarchy()
        assertHorizontalGutter("settings.profile", 16f)
        compose.onNodeWithTag("settings.watering-switch").assertIsOn()
        compose.onNodeWithTag("settings.weather-switch").assertIsOn()
        compose.onNodeWithContentDescription("설정").assertIsSelected()
        capture(Todo16CaptureContract.SETTINGS_ROOT)
        compose.onNodeWithTag("settings.os-notifications").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, notificationSettingsCallbacks) }
        compose.onNodeWithTag("settings.watering-switch").performScrollTo().assertIsOn()
        compose.onNodeWithTag("settings.weather-switch").performScrollTo().assertIsOn()
        compose.onNodeWithTag("account-delete").performScrollTo().assertIsDisplayed()
        assertMinimumTouchTarget("account-delete")
        compose.onNodeWithTag("settings.location-consent").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, locationRevocationCallbacks) }

        show(
            name = "settings-root-font-200",
            next = VisualSurface.Settings(settingsState()),
            scale = 2f,
        )
        assertSettingsHierarchy()
        capture(Todo16CaptureContract.SETTINGS_ROOT_FONT_200)
        compose.onNodeWithTag("settings.region", useUnmergedTree = true).performScrollTo()
        assertTrailingValueStacked("settings.region")
        compose.onNodeWithTag("account-delete").performScrollTo().assertIsDisplayed()
        assertInsideRoot("account-delete")
        assertNoReplacementCharacters()
        compose.onNodeWithTag("settings.analytics-consent").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithTag("settings.analytics-disclosure", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.waitForIdle()
        compose.onNodeWithTag("settings.analytics-consent").assertIsDisplayed()
        assertMinimumTouchTarget("settings.analytics-consent")
        assertInsideRoot("settings.analytics-consent")
        assertInsideRoot("settings.analytics-disclosure", useUnmergedTree = true)
        capture(Todo16CaptureContract.ANALYTICS_FONT_200)

        show(
            name = "identification-candidates",
            next = VisualSurface.Identification(identificationState()),
        )
        compose.onNodeWithTag(IdentificationTestTags.SCREEN).assertIsDisplayed()
        compose
            .onNodeWithText("식물 후보 확인")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val candidateRadio =
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)
        compose.onAllNodes(candidateRadio, useUnmergedTree = true).assertCountEquals(2)
        val monsteraTag = IdentificationTestTags.candidate("species-monstera")
        val pothosTag = IdentificationTestTags.candidate("species-pothos")
        compose
            .onNodeWithTag(monsteraTag, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose
            .onNodeWithTag(pothosTag, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose
            .onNodeWithTag(monsteraTag, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        compose
            .onNodeWithTag(pothosTag, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        listOf(monsteraTag, pothosTag).forEach { tag ->
            compose
                .onAllNodes(
                    hasAnyAncestor(hasTestTag(tag)) and hasClickAction(),
                    useUnmergedTree = true,
                )
                .assertCountEquals(0)
            assertMinimumTouchTarget(tag)
            assertInsideRoot(tag)
        }
        assertNoReplacementCharacters()
        compose.waitForIdle()
        capture(Todo16CaptureContract.IDENTIFICATION_CANDIDATES)

        show(
            name = "deletion-preview-ready",
            next = VisualSurface.Deletion(deletionState()),
        )
        assertDeletionHierarchy()
        compose.onNodeWithTag("account-deletion.scope").assertIsDisplayed()
        capture(Todo16CaptureContract.DELETION_PREVIEW_READY)
        compose.onNodeWithTag("account-deletion.reauthenticate").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reauthenticationCallbacks) }
        compose
            .onNodeWithTag("account-deletion.final-confirmation")
            .performScrollTo()
            .performClick()
        compose.runOnIdle { assertEquals(1, confirmationCallbacks) }
        compose
            .onNodeWithTag("account-deletion.submit")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(1, submitCallbacks) }
        assertFullWidthAction("account-deletion.submit")

        show(
            name = "deletion-received-grace",
            next = VisualSurface.Deletion(deletionState(DeletionStatus.RECEIVED)),
        )
        assertDeletionHierarchy()
        compose.onNodeWithText("삭제 요청 접수됨 · 7일 유예").assertIsDisplayed()
        compose.onNodeWithTag("account-deletion.cancel").assertIsDisplayed()
        assertMinimumTouchTarget("account-deletion.cancel")
        capture(Todo16CaptureContract.DELETION_RECEIVED_GRACE)
        compose.onNodeWithTag("account-deletion.cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancelCallbacks) }

        show(
            name = "deletion-processing",
            next = VisualSurface.Deletion(deletionState(DeletionStatus.PROCESSING)),
        )
        assertDeletionHierarchy()
        compose.onNodeWithTag("account-deletion.cancel").assertDoesNotExist()
        compose.onNodeWithText("계정 데이터를 삭제하고 있어요.").assertIsDisplayed()
        capture(Todo16CaptureContract.DELETION_PROCESSING)

        show(
            name = "deletion-partially-failed",
            next = VisualSurface.Deletion(deletionState(DeletionStatus.PARTIALLY_FAILED)),
        )
        assertDeletionHierarchy()
        compose.onNodeWithText("로컬 정리 완료").assertDoesNotExist()
        capture(Todo16CaptureContract.DELETION_PARTIALLY_FAILED)
        compose.onNodeWithText("계정은 유지돼요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("account-deletion.retry").assertIsDisplayed()
        assertFullWidthAction("account-deletion.retry")

        show(
            name = "deletion-completed",
            next = VisualSurface.Deletion(deletionState(DeletionStatus.COMPLETED)),
        )
        assertDeletionHierarchy()
        capture(Todo16CaptureContract.DELETION_COMPLETED)
        compose.onNodeWithTag("account-deletion.done").assertIsDisplayed()
        compose.onNodeWithTag("account-deletion.done").performClick()
        compose.runOnIdle { assertEquals(1, exitCallbacks) }

        val locationBoundary = RecordingLocationConsentBoundary()
        val locationController =
            SettingsLocationController(
                initialRegion = SettingsRegion.Manual("서울특별시"),
                accountId = "account-a",
                repository = locationBoundary,
                permissionCapabilities = locationBoundary,
                locationGateway = locationBoundary,
                scope = CoroutineScope(Dispatchers.Unconfined),
                dispatcher = Dispatchers.Unconfined,
            )
        locationController.beginCurrentLocationRequest()
        locationController.revokeCurrentLocationConsent()
        assertEquals(1, locationBoundary.cancelCalls)
        assertEquals(1, locationBoundary.revokeCalls)
        assertEquals(SettingsRegion.Manual("서울특별시"), locationController.state.value.region)
        assertTrue(locationController.state.value.consentRevoked)

        show(
            name = "location-consent-revoked-manual-region",
            next =
                VisualSurface.Settings(
                    settingsState(
                        regionName = "서울특별시",
                        appLocationConsentGranted = false,
                    )
                ),
        )
        assertSettingsHierarchy()
        compose.onNodeWithTag("settings.location-consent").performScrollTo().assertIsOff()
        compose.onNodeWithTag("settings.region.value", useUnmergedTree = true).assertIsDisplayed()
        capture(Todo16CaptureContract.LOCATION_CONSENT_REVOKED_MANUAL_REGION)

        assertTerminalCleanupContract()
        writeManifest(evidenceSource)
    }

    private fun assertSettingsHierarchy() {
        compose.onNodeWithTag("settings.screen").assertIsDisplayed()
        compose
            .onNodeWithText("설정")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).assertCountEquals(1)
        val hierarchy = collectTags(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        val ordered =
            listOf(
                "settings.profile",
                "settings.section.notifications",
                "settings.section.environment",
                "settings.section.data",
                "settings.section.account",
                "settings.section.other",
            )
        val offsets = ordered.map(hierarchy::indexOf)
        assertTrue("Missing settings hierarchy tags: $hierarchy", offsets.all { it >= 0 })
        assertEquals(offsets.sorted(), offsets)
    }

    private fun assertDeletionHierarchy() {
        compose.onNodeWithTag("account-deletion.screen").assertIsDisplayed()
        compose
            .onNodeWithText("계정 삭제")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).assertCountEquals(1)
        assertHorizontalGutter("account-deletion.screen", 16f)
        assertNoReplacementCharacters()
    }

    private fun assertTrailingValueStacked(tag: String) {
        val label =
            compose
                .onNodeWithTag("$tag.label", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val value =
            compose
                .onNodeWithTag("$tag.value", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("$tag trailing value overlaps its label", value.top >= label.bottom)
    }

    private fun assertMinimumTouchTarget(tag: String) {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val density = deviceDensity()
        assertTrue("$tag width was ${bounds.width / density}dp", bounds.width / density >= 48f)
        assertTrue("$tag height was ${bounds.height / density}dp", bounds.height / density >= 48f)
    }

    private fun assertFullWidthAction(tag: String) {
        assertMinimumTouchTarget(tag)
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$tag was only ${bounds.width / deviceDensity()}dp wide",
            bounds.width / deviceDensity() >= 300f,
        )
        assertInsideRoot(tag)
    }

    private fun assertHorizontalGutter(tag: String, expectedDp: Float) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val density = deviceDensity()
        assertEquals(expectedDp, (bounds.left - root.left) / density, 0.6f)
        assertEquals(expectedDp, (root.right - bounds.right) / density, 0.6f)
    }

    private fun assertInsideRoot(tag: String, useUnmergedTree: Boolean = false) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds =
            compose
                .onNodeWithTag(tag, useUnmergedTree = useUnmergedTree)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(
            "$tag was clipped horizontally",
            bounds.left >= root.left && bounds.right <= root.right,
        )
        assertTrue(
            "$tag was clipped vertically",
            bounds.top >= root.top && bounds.bottom <= root.bottom,
        )
    }

    private fun assertNoReplacementCharacters() {
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        fun verify(node: SemanticsNode) {
            if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text].forEach { text ->
                    assertFalse("Tofu replacement character in '$text'", '\uFFFD' in text.text)
                }
            }
            node.children.forEach(::verify)
        }
        verify(root)
    }

    private fun assertTerminalCleanupContract() {
        var partialCleanupCallbacks = 0
        val partial =
            AccountDeletionController(
                dependencies =
                    deletionDependencies(deletionWorkflow(DeletionStatus.PARTIALLY_FAILED)) {
                        partialCleanupCallbacks += 1
                    },
                dispatcher = Dispatchers.Unconfined,
            )
        assertTrue(partial.state.value is AccountDeletionUiState.Ready)
        assertEquals(0, partialCleanupCallbacks)

        var completedCleanupCallbacks = 0
        val completed =
            AccountDeletionController(
                dependencies =
                    deletionDependencies(deletionWorkflow(DeletionStatus.COMPLETED)) {
                        completedCleanupCallbacks += 1
                    },
                dispatcher = Dispatchers.Unconfined,
            )
        assertTrue(completed.state.value is AccountDeletionUiState.Ready)
        assertEquals(1, completedCleanupCallbacks)
        completed.refresh()
        assertEquals(1, completedCleanupCallbacks)
    }

    private fun capture(contract: Todo16CaptureContract) {
        compose.onNodeWithTag(contract.rootTag).assertIsDisplayed()
        compose.waitForIdle()
        val previous = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        var first: Bitmap? = null
        var second: Bitmap? = null
        try {
            first = compose.onRoot().captureToImage().asAndroidBitmap()
            compose.mainClock.advanceTimeByFrame()
            second = compose.onRoot().captureToImage().asAndroidBitmap()

            val firstPixels = first.pixelData()
            val secondPixels = second.pixelData()
            val firstPng = first.pngBytes()
            val secondPng = second.pngBytes()
            val rawPixelsEqual =
                first.width == second.width &&
                    first.height == second.height &&
                    firstPixels.contentEquals(secondPixels)
            val pngBytesEqual = firstPng.contentEquals(secondPng)
            if (!rawPixelsEqual || !pngBytesEqual) {
                persistFrameDiagnostics(
                    contract = contract,
                    first = first,
                    second = second,
                    firstPixels = firstPixels,
                    secondPixels = secondPixels,
                    firstPng = firstPng,
                    secondPng = secondPng,
                    rawPixelsEqual = rawPixelsEqual,
                    pngBytesEqual = pngBytesEqual,
                )
            }
            assertExactFrameEquality(contract, rawPixelsEqual, pngBytesEqual, firstPng, secondPng)

            assertEquals(1080, first.width)
            assertEquals(2400, first.height)
            val file = File(evidenceDirectory(), contract.file)
            file.writeBytes(firstPng)
            copyToDownloads(file, contract.file)
        } finally {
            second?.recycle()
            first?.recycle()
            compose.mainClock.autoAdvance = previous
        }
    }

    private fun Bitmap.pixelData(): IntArray =
        IntArray(width * height).also { pixels ->
            getPixels(pixels, 0, width, 0, 0, width, height)
        }

    private fun Bitmap.pngBytes(): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }

    private fun assertExactFrameEquality(
        contract: Todo16CaptureContract,
        rawPixelsEqual: Boolean,
        pngBytesEqual: Boolean,
        firstPng: ByteArray,
        secondPng: ByteArray,
    ) {
        val rawFailure = runCatching {
            assertTrue(
                "${contract.file} changed across a deterministic raw-pixel frame",
                rawPixelsEqual,
            )
        }
            .exceptionOrNull()
        val pngFailure = runCatching {
            assertArrayEquals(
                "${contract.file} changed across independently compressed PNG frames",
                firstPng,
                secondPng,
            )
        }
            .exceptionOrNull()
        if (rawFailure != null || pngFailure != null) {
            throw AssertionError(
                    "${contract.file} failed strict deterministic-frame equality " +
                        "(rawPixelsEqual=$rawPixelsEqual, pngBytesEqual=$pngBytesEqual)"
                )
                .also { combined ->
                    rawFailure?.let(combined::addSuppressed)
                    pngFailure?.let(combined::addSuppressed)
                }
        }
    }

    private fun clearFrameDiagnostics() {
        Todo16CaptureContract.entries.flatMap(::frameDiagnosticNames).forEach { name ->
            File(evidenceDirectory(), name).delete()
            executeDeviceShell("rm -f /sdcard/Download/$name")
        }
    }

    private fun persistFrameDiagnostics(
        contract: Todo16CaptureContract,
        first: Bitmap,
        second: Bitmap,
        firstPixels: IntArray,
        secondPixels: IntArray,
        firstPng: ByteArray,
        secondPng: ByteArray,
        rawPixelsEqual: Boolean,
        pngBytesEqual: Boolean,
    ) {
        val names = frameDiagnosticNames(contract)
        val frameA = File(evidenceDirectory(), names[0]).apply { writeBytes(firstPng) }
        val frameB = File(evidenceDirectory(), names[1]).apply { writeBytes(secondPng) }
        val differenceIndex =
            firstPixels.indices.firstOrNull { firstPixels[it] != secondPixels[it] }
        val differenceJson =
            if (differenceIndex == null) {
                "null"
            } else {
                val x = differenceIndex % first.width
                val y = differenceIndex / first.width
                """{"x":$x,"y":$y,"argbA":"${argb(firstPixels[differenceIndex])}","argbB":"${argb(secondPixels[differenceIndex])}"}"""
            }
        val metadata =
            File(evidenceDirectory(), names[2]).apply {
                writeText(
                    buildString {
                        appendLine("{")
                        appendLine("  \"contractFile\": \"${contract.file}\",")
                        appendLine("  \"widthA\": ${first.width},")
                        appendLine("  \"heightA\": ${first.height},")
                        appendLine("  \"widthB\": ${second.width},")
                        appendLine("  \"heightB\": ${second.height},")
                        appendLine("  \"rawPixelsEqual\": $rawPixelsEqual,")
                        appendLine("  \"pngBytesEqual\": $pngBytesEqual,")
                        appendLine(
                            "  \"frameA\": {\"file\": \"${names[0]}\", \"sha256\": \"${sha256(firstPng)}\"},"
                        )
                        appendLine(
                            "  \"frameB\": {\"file\": \"${names[1]}\", \"sha256\": \"${sha256(secondPng)}\"},"
                        )
                        appendLine("  \"firstDifferingPixel\": $differenceJson")
                        appendLine("}")
                    },
                    Charsets.UTF_8,
                )
            }
        copyToDownloads(frameA, names[0])
        copyToDownloads(frameB, names[1])
        copyToDownloads(metadata, names[2])
    }

    private fun frameDiagnosticNames(contract: Todo16CaptureContract): List<String> {
        val base = contract.file.removeSuffix(".png")
        return listOf("$base.frame-a.png", "$base.frame-b.png", "$base.frame-diff.json")
    }

    private fun executeDeviceShell(command: String) {
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        descriptor.close()
    }

    private fun argb(pixel: Int): String = "0x%08X".format(pixel)

    private fun writeManifest(source: Todo16EvidenceSource) {
        val files =
            Todo16CaptureContract.entries.associate {
                it.file to sha256(File(evidenceDirectory(), it.file).readBytes())
            }
        val json = buildString {
            appendLine("{")
            appendLine("  \"contractVersion\": 2,")
            appendLine("  \"sourceHead\": \"${source.head}\",")
            appendLine("  \"sourceTree\": \"${source.tree}\",")
            appendLine("  \"apiLevel\": 37,")
            appendLine("  \"deviceProfile\": \"pixel_7\",")
            appendLine("  \"renderer\": \"swiftshader_indirect\",")
            appendLine("  \"dimensions\": \"1080x2400\",")
            appendLine("  \"freshComposeStatePerCapture\": true,")
            appendLine("  \"windowInsetsGate\": \"exact-applied-callback-before-content\",")
            appendLine("  \"files\": {")
            files.entries.forEachIndexed { index, entry ->
                append("    \"").append(entry.key).append("\": \"").append(entry.value).append('"')
                if (index != files.size - 1) append(',')
                appendLine()
            }
            appendLine("  }")
            appendLine("}")
        }
        val file = File(evidenceDirectory(), MANIFEST)
        file.writeText(json, Charsets.UTF_8)
        copyToDownloads(file, MANIFEST)
    }

    private fun evidenceDirectory(): File =
        checkNotNull(
                InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .getExternalFilesDir("todo16-evidence")
            )
            .also { it.mkdirs() }

    private fun copyToDownloads(file: File, name: String) {
        val descriptor =
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand("cp ${file.absolutePath} /sdcard/Download/$name")
        FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        descriptor.close()
    }

    @Composable
    private fun rememberEvidenceEdgeToEdgeReady(): Boolean {
        val activity = LocalContext.current.findComponentActivity()
        val root = activity.findViewById<View>(android.R.id.content)
        var insetsReady by remember(root) { mutableStateOf(false) }
        DisposableEffect(activity, root) {
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                insetsReady = true
                insets
            }
            activity.enableEdgeToEdge()
            ViewCompat.requestApplyInsets(root)
            onDispose { ViewCompat.setOnApplyWindowInsetsListener(root, null) }
        }
        return insetsReady
    }

    private fun Context.findComponentActivity(): ComponentActivity =
        generateSequence(this) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>()
            .first()

    private fun settingsState(
        regionName: String = "서울특별시",
        appLocationConsentGranted: Boolean = true,
    ) =
        SettingsUiState(
            authState =
                AuthUiState.Authenticated(
                    account =
                        AuthAccount(
                            uid = "todo16-api37-owner",
                            email = "gardener@example.com",
                            displayName = "민지",
                            providers = setOf(AuthProvider.GOOGLE),
                        ),
                    sync = SyncSummary.EMPTY,
                ),
            wateringNotificationsEnabled = true,
            weatherNotificationsEnabled = true,
            quietHoursSummary = "오후 10:00~오전 7:00",
            regionName = regionName,
            osLocationPermission = DevicePermissionState.DENIED,
            appLocationConsentGranted = appLocationConsentGranted,
            lastSyncAt = Instant.parse("2026-08-24T04:00:00Z"),
            osNotificationPermission = DevicePermissionState.DENIED,
            appVersion = "v0.1.0",
        )

    private fun identificationState() =
        IdentificationUiState.Candidates(
            candidates =
                listOf(
                    IdentificationCandidate(
                        publicContentId = PlantContentId("species-monstera"),
                        koreanName = "몬스테라",
                        commonName = "Swiss cheese plant",
                        scientificName = "Monstera deliciosa",
                        confidence = 0.93,
                        thumbnailUrl = null,
                    ),
                    IdentificationCandidate(
                        publicContentId = PlantContentId("species-pothos"),
                        koreanName = "스킨답서스",
                        commonName = "Golden pothos",
                        scientificName = "Epipremnum aureum",
                        confidence = 0.87,
                        thumbnailUrl = null,
                    ),
                ),
            selectedId = PlantContentId("species-monstera"),
        )

    private fun deletionState(status: DeletionStatus? = null) =
        AccountDeletionUiState.Ready(
            scope = scope,
            workflow = status?.let(::deletionWorkflow),
        )

    private fun deletionWorkflow(status: DeletionStatus) =
        AccountDeletionWorkflow(
            requestId = DeletionRequestId("todo16-deletion-request"),
            scope = scope,
            requestedAt = requestedAt,
            scheduledAt = scheduledAt,
            status = status,
            completedCategories =
                when (status) {
                    DeletionStatus.COMPLETED -> AccountDeletionCategory.entries.toSet()
                    DeletionStatus.PARTIALLY_FAILED ->
                        AccountDeletionCategory.entries.dropLast(1).toSet()
                    else -> emptySet()
                },
            remainingCategories =
                when (status) {
                    DeletionStatus.COMPLETED -> emptySet()
                    DeletionStatus.PARTIALLY_FAILED -> setOf(AccountDeletionCategory.AUTH_ACCOUNT)
                    else -> AccountDeletionCategory.entries.toSet()
                },
        )

    private fun deletionDependencies(
        workflow: AccountDeletionWorkflow,
        onCompleted: suspend (AccountDeletionCompletion) -> Unit,
    ) =
        AccountDeletionDependencies(
            repository = StaticDeletionRepository(scope, workflow),
            reauthenticator =
                AccountDeletionReauthenticator {
                    AccountDeletionReauthenticationResult.SUCCEEDED
                },
            terminalCallback = AccountDeletionTerminalCallback(onCompleted),
        )

    @Composable
    private fun SettingsBottomBar() {
        PlanteriorBottomBar(
            tabs =
                listOf(
                    PlanteriorTab("홈", PlanteriorIcons.Home),
                    PlanteriorTab("도감", PlanteriorIcons.Collection),
                    PlanteriorTab("창고", PlanteriorIcons.Storage),
                    PlanteriorTab("설정", PlanteriorIcons.Settings),
                ),
            selectedIndex = 3,
            onTabSelected = {},
            cameraContentDescription = "식물 촬영",
            onCameraClick = {},
        )
    }

    private fun deviceDensity(): Float =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    private sealed interface VisualSurface {
        data class Settings(val state: SettingsUiState) : VisualSurface

        data class Identification(val state: IdentificationUiState.Candidates) : VisualSurface

        data class Deletion(val state: AccountDeletionUiState) : VisualSurface
    }

    private class RecordingLocationConsentBoundary :
        WeatherRepository, WeatherPermissionCapabilityStore, WeatherLocationGateway {
        var cancelCalls = 0
        var revokeCalls = 0
        private var capability = WeatherPermissionCapabilityState(true, true, false, 1, true)

        override fun accounts() = flowOf("account-a")

        override suspend fun load(accountId: String) = WeatherLoad.NotConfigured

        override suspend fun recordLocationConsent(
            accountId: String,
            granted: Boolean,
            commandGeneration: Long,
        ): WeatherConsentMutationResult {
            revokeCalls += 1
            return WeatherConsentMutationResult(commandGeneration, granted)
        }

        override suspend fun refresh(accountId: String, location: ApproximateLocation?) =
            WeatherLoad.NotConfigured

        override suspend fun searchRegions(accountId: String, query: String) =
            emptyList<WeatherRegion>()

        override suspend fun selectManualRegion(
            accountId: String,
            region: WeatherRegion,
            expectedRevision: Long,
        ) = WeatherLoad.NotConfigured

        override suspend fun saveAlerts(
            accountId: String,
            globalEnabled: Boolean,
            plants: Map<String, Boolean>,
            expectedRevision: Long,
        ) = WeatherLoad.NotConfigured

        override fun read(accountId: String): WeatherPermissionCapabilityState = capability

        override fun write(accountId: String, state: WeatherPermissionCapabilityState) {
            capability = state
        }

        override fun permission() = LocationPermission.GrantedApproximate

        override suspend fun approximateLocation(): ApproximateLocation? = null

        override fun cancel() {
            cancelCalls += 1
        }
    }

    private class StaticDeletionRepository(
        private val scope: AccountDeletionScope,
        private val workflow: AccountDeletionWorkflow,
    ) : AccountDeletionRepository {
        override suspend fun preview() = scope

        override suspend fun status() = workflow

        override suspend fun request(request: ConfirmedAccountDeletionRequest) = workflow

        override suspend fun cancel(
            requestId: DeletionRequestId
        ): AccountDeletionCancellationResult = error("Not used by the visual fixture")

        override suspend fun retry(
            request: ConfirmedAccountDeletionRetry
        ): AccountDeletionRetryResult = error("Not used by the visual fixture")
    }

    private companion object {
        const val MANIFEST = "todo16-api37-determinism.json"

        fun collectTags(node: SemanticsNode): List<String> = buildList {
            if (node.config.contains(SemanticsProperties.TestTag)) {
                add(node.config[SemanticsProperties.TestTag])
            }
            node.children.forEach { addAll(collectTags(it)) }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}

@RunWith(AndroidJUnit4::class)
class Todo16SettingsVisualSourceContractTest {
    @Test
    fun captureNamesStatesAndStableTagsRemainSourceBound() {
        assertEquals(10, Todo16CaptureContract.entries.size)
        assertEquals(
            listOf(
                "todo16-api37-settings-root.png",
                "todo16-api37-settings-root-font-200.png",
                "todo17-api37-analytics-font-200.png",
                "todo17-api37-identification-candidates.png",
                "todo16-api37-deletion-preview-ready.png",
                "todo16-api37-deletion-received-grace.png",
                "todo16-api37-deletion-processing.png",
                "todo16-api37-deletion-partially-failed.png",
                "todo16-api37-deletion-completed.png",
                "todo16-api37-location-consent-revoked-manual-region.png",
            ),
            Todo16CaptureContract.entries.map(Todo16CaptureContract::file),
        )
        assertEquals(
            setOf(
                "settings.screen",
                "settings.profile",
                "settings.region",
                "settings.analytics-disclosure",
                IdentificationTestTags.SCREEN,
                IdentificationTestTags.candidate("species-pothos"),
                "settings.location-consent",
                "account-deletion.screen",
                "account-deletion.submit",
                "account-deletion.cancel",
                "account-deletion.status",
                "account-deletion.retry",
                "account-deletion.done",
            ),
            Todo16CaptureContract.entries.flatMap { listOf(it.rootTag, it.focusTag) }.toSet(),
        )
    }

    @Test
    fun sourceArgumentsRequireExactLowercaseGitObjectIds() {
        val head = "1".repeat(40)
        val tree = "a".repeat(40)
        assertEquals(
            Todo16EvidenceSource(head, tree),
            Todo16EvidenceSourceArguments.require(head, tree),
        )
        listOf(null, "A".repeat(40), head.dropLast(1)).forEach { malformed ->
            val failure = runCatching {
                Todo16EvidenceSourceArguments.require(malformed, tree)
            }
            assertTrue("Accepted malformed source head: $malformed", failure.isFailure)
        }
        assertTrue(runCatching { Todo16EvidenceSourceArguments.require(head, null) }.isFailure)
    }
}
