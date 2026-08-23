package com.planterior.helper.feature.share

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.GridPosition
import com.planterior.helper.feature.minihome.MiniHomeAuthOwnership
import com.planterior.helper.feature.minihome.MiniHomeDecorationChoice
import com.planterior.helper.feature.minihome.MiniHomeLayout
import com.planterior.helper.feature.minihome.MiniHomePlacement
import com.planterior.helper.feature.minihome.MiniHomePlacementPolicy
import com.planterior.helper.feature.minihome.MiniHomePlacementTarget
import com.planterior.helper.feature.minihome.MiniHomePlantChoice
import com.planterior.helper.feature.minihome.MiniHomeZIndex
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Todo 15 API 37 수동 QA 호스트이다.
 *
 * 별도 모형 화면을 만들지 않고 제품 [MiniHomeShareScreen]과 [MiniHomeShareRoute]를 그대로 사용한다. 시각 상태만 결정적 fixture로
 * 공급하며, 모든 전이는 제품 semantics 노드를 실제 클릭해서 실행한다. PNG 승격과 독립 wipe 비교는 호스트 스크립트가 담당하므로 실패한
 * instrumentation 실행이 기준 이미지를 바꿀 수 없다.
 */
internal data class Todo15EvidenceSource(val head: String, val tree: String)

internal object Todo15EvidenceSourceArguments {
    private val gitObjectId = Regex("^[0-9a-f]{40}$")

    fun require(head: String?, tree: String?): Todo15EvidenceSource {
        require(head != null) { "Missing instrumentation argument: todo15SourceHead" }
        require(tree != null) { "Missing instrumentation argument: todo15SourceTree" }
        require(gitObjectId.matches(head)) {
            "Malformed instrumentation argument todo15SourceHead: expected 40 lowercase hex characters"
        }
        require(gitObjectId.matches(tree)) {
            "Malformed instrumentation argument todo15SourceTree: expected 40 lowercase hex characters"
        }
        return Todo15EvidenceSource(head, tree)
    }
}

@RunWith(AndroidJUnit4::class)
class MiniHomeShareVisualApi37Test {
    @get:Rule val compose = createComposeRule()

    private lateinit var evidenceSource: Todo15EvidenceSource
    private val owner = AccountId("todo15-api37-owner")
    private val otherOwner = AccountId("todo15-api37-owner-b")
    private val createdAt = Instant.parse("2026-08-23T00:00:00.000Z")
    private val expiresAt = Instant.parse("2026-09-22T00:00:00.000Z")
    private val url = "https://share.planterior.app/m?token=${"t".repeat(43)}"
    private val link =
        MiniHomeShareLink(
            MiniHomeShareId("s".repeat(43)),
            url,
            Revision(12),
            createdAt,
            expiresAt,
        )

    @Before
    fun requireEvidenceSourceArguments() {
        val arguments = InstrumentationRegistry.getArguments()
        evidenceSource =
            Todo15EvidenceSourceArguments.require(
                arguments.getString("todo15SourceHead"),
                arguments.getString("todo15SourceTree"),
            )
    }

    @Test
    fun eightReviewedStatesUseProductionScreenClicksSemanticsAndExactApi37Pixels() {
        assertEquals(37, Build.VERSION.SDK_INT)
        var scenario by mutableStateOf("rendering")
        var state by mutableStateOf(ready(render = MiniHomeShareRenderState.Rendering))
        var fontScale by mutableStateOf(1f)
        var backClicks = 0
        compose.setContent {
            EvidenceEdgeToEdge()
            val density = LocalDensity.current
            key(scenario) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale)
                ) {
                    PlanteriorTheme {
                        MiniHomeShareScreen(
                            state = state,
                            onBack = { backClicks += 1 },
                            onCreateLink = {
                                state =
                                    if (scenario == "offline-failure") {
                                        ready(
                                            render = MiniHomeShareRenderState.Ready,
                                            linkState =
                                                MiniHomeShareLinkState.Failed(
                                                    MiniHomeShareFailure.OFFLINE
                                                ),
                                        )
                                    } else {
                                        ready(
                                            render = MiniHomeShareRenderState.Ready,
                                            linkState = MiniHomeShareLinkState.Active(link),
                                        )
                                    }
                            },
                            onCopyLink = {
                                state = state.copy(feedback = MiniHomeShareFeedback.LINK_COPIED)
                            },
                            onShareImage = {
                                state = state.copy(feedback = MiniHomeShareFeedback.SHEET_CANCELLED)
                            },
                            onShareLink = {
                                state = state.copy(feedback = MiniHomeShareFeedback.SHEET_CANCELLED)
                            },
                            onRevokeLink = {
                                state =
                                    state.copy(
                                        link = MiniHomeShareLinkState.Revoked,
                                        feedback = null,
                                    )
                            },
                            onRetryRender = {
                                state = state.copy(render = MiniHomeShareRenderState.Rendering)
                            },
                            onRetryLoad = {},
                            zone = ZoneId.of("UTC"),
                        )
                    }
                }
            }
        }

        fun moveTo(name: String, scale: Float = 1f, next: MiniHomeShareUiState.Ready) {
            compose.runOnIdle {
                scenario = name
                fontScale = scale
                state = next
            }
            compose.waitForIdle()
        }

        assertReadyChrome()
        capture("todo15-api37-rendering.png")

        moveTo("ready", next = ready(render = MiniHomeShareRenderState.Ready))
        assertReadyChrome()
        assertMinimumTouchTarget(MiniHomeShareTestTags.IMAGE_SHARE)
        compose
            .onNodeWithTag(MiniHomeShareTestTags.IMAGE_SHARE)
            .performScrollTo()
            .assertIsDisplayed()
        capture("todo15-api37-ready.png")

        moveTo("render-failure", next = ready(render = MiniHomeShareRenderState.Failed))
        assertReadyChrome()
        assertErrorSemantics(MiniHomeShareTestTags.RENDER_FAILURE)
        capture("todo15-api37-render-failure.png")
        compose.onNodeWithTag(MiniHomeShareTestTags.RENDER_RETRY).performScrollTo().performClick()
        compose.onNodeWithTag(MiniHomeShareTestTags.RENDER_PROGRESS).assertIsDisplayed()

        moveTo("chooser-cancelled", next = ready(render = MiniHomeShareRenderState.Ready))
        compose.onNodeWithTag(MiniHomeShareTestTags.IMAGE_SHARE).performScrollTo().performClick()
        compose.onNodeWithTag(MiniHomeShareTestTags.FEEDBACK).performScrollTo().assertIsDisplayed()
        assertNoErrorSemantics(MiniHomeShareTestTags.FEEDBACK)
        moveTo("chooser-cancelled-capture", next = state)
        compose.onNodeWithTag(MiniHomeShareTestTags.FEEDBACK).performScrollTo().assertIsDisplayed()
        capture("todo15-api37-chooser-cancelled.png")

        moveTo("active-link", next = ready(render = MiniHomeShareRenderState.Ready))
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_CREATE).performScrollTo().performClick()
        assertActiveLinkActions()
        moveTo("active-link-capture", next = state)
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_URL).performScrollTo().assertIsDisplayed()
        capture("todo15-api37-active-link.png")
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_COPY).performScrollTo().performClick()
        compose.onNodeWithTag(MiniHomeShareTestTags.FEEDBACK).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_SHARE).performScrollTo().performClick()
        assertNoErrorSemantics(MiniHomeShareTestTags.FEEDBACK)

        moveTo("offline-failure", next = ready(render = MiniHomeShareRenderState.Ready))
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_CREATE).performScrollTo().performClick()
        assertErrorSemantics(MiniHomeShareTestTags.LINK_FAILURE)
        compose.onNodeWithText("연결 상태를 확인해 주세요").performScrollTo().assertIsDisplayed()
        moveTo("offline-failure-capture", next = state)
        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_FAILURE)
            .performScrollTo()
            .assertIsDisplayed()
        capture("todo15-api37-offline-failure.png")

        moveTo(
            "revoked",
            next =
                ready(
                    render = MiniHomeShareRenderState.Ready,
                    linkState = MiniHomeShareLinkState.Active(link),
                ),
        )
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_REVOKE).performScrollTo().performClick()
        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_REVOKED)
            .performScrollTo()
            .assertIsDisplayed()
        moveTo("revoked-capture", next = state)
        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_REVOKED)
            .performScrollTo()
            .assertIsDisplayed()
        capture("todo15-api37-revoked.png")

        moveTo(
            "active-link-font-200",
            scale = 2f,
            next =
                ready(
                    render = MiniHomeShareRenderState.Ready,
                    linkState = MiniHomeShareLinkState.Active(link),
                ),
        )
        assertReadyChrome()
        assertActiveLinkActions()
        capture("todo15-api37-active-link-font-200.png")

        compose.onNodeWithTag(MiniHomeShareTestTags.BACK).performClick()
        compose.runOnIdle { assertEquals(1, backClicks) }
        writeManifest()
    }

    @Test
    fun productionRouteLoadsEmptyCommittedRevisionAndOwnerSwitchClearsBearerState() {
        val repository = RouteRepository()
        var ownership by
            mutableStateOf<MiniHomeAuthOwnership>(MiniHomeAuthOwnership.Authenticated(owner))
        compose.setContent {
            PlanteriorTheme {
                MiniHomeShareRoute(
                    repository = repository,
                    onBack = {},
                    authOwnership = ownership,
                )
            }
        }
        compose.onNodeWithTag(MiniHomeShareTestTags.REVISION).assertIsDisplayed()
        compose.onNodeWithText("저장 12번째 구성이에요. 편집 중인 내용은 담기지 않아요.").assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeShareTestTags.PREVIEW).assertIsDisplayed()
        assertTrue(repository.lastLoadedTarget.committed.placements.isEmpty())

        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_CREATE).performScrollTo().performClick()
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_URL).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            ownership = MiniHomeAuthOwnership.Authenticated(otherOwner)
            repository.nextOwner = otherOwner
        }
        compose.onNodeWithTag(MiniHomeShareTestTags.REVISION).assertIsDisplayed()
        compose.onAllNodesWithText(url).assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, repository.clearCalls) }
    }

    @Test
    fun loggedOutGuardAndUnsavedRevisionZeroFailClosedWhileEmptySavedRevisionShares() {
        assertEquals(
            com.planterior.helper.navigation.PlanteriorRoute.Login("planterior://minihome/share"),
            com.planterior.helper.navigation.AuthRouteGuard.destination(
                com.planterior.helper.navigation.PlanteriorRoute.MiniHomeShare,
                authenticated = false,
            ),
        )
        val repository = BoundaryRepository()
        val controller = MiniHomeShareController(repository)
        runBlocking {
            controller.start(MiniHomeAuthOwnership.Authenticated(owner))
            assertTrue(controller.state.value is MiniHomeShareUiState.NoTarget)
            repository.target = target(owner, revision = 12, placements = emptyList())
            controller.retryLoad(MiniHomeAuthOwnership.Authenticated(owner))
            assertTrue(controller.state.value is MiniHomeShareUiState.Ready)
            controller.createLink()
        }
        assertEquals(Revision(12), repository.createRevision)
    }

    private fun assertReadyChrome() {
        compose.onNodeWithTag(MiniHomeShareTestTags.PRIVACY_NOTICE).assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeShareTestTags.REVISION).assertIsDisplayed()
        compose.onNodeWithText("저장 12번째 구성이에요. 편집 중인 내용은 담기지 않아요.").assertIsDisplayed()
    }

    private fun assertActiveLinkActions() {
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_URL).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(url).assertIsDisplayed()
        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_EXPIRY)
            .performScrollTo()
            .assertIsDisplayed()
        listOf(
                MiniHomeShareTestTags.LINK_COPY,
                MiniHomeShareTestTags.LINK_SHARE,
                MiniHomeShareTestTags.LINK_REVOKE,
            )
            .forEach { tag ->
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                assertMinimumTouchTarget(tag)
            }
    }

    private fun assertMinimumTouchTarget(tag: String) {
        val bounds: Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val density =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .resources
                .displayMetrics
                .density
        assertTrue("$tag width was ${bounds.width / density}dp", bounds.width / density >= 48f)
        assertTrue("$tag height was ${bounds.height / density}dp", bounds.height / density >= 48f)
    }

    private fun assertErrorSemantics(tag: String) {
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    private fun assertNoErrorSemantics(tag: String) {
        val config = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        assertFalse(config.contains(SemanticsProperties.Error))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val previous = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        val first: Bitmap
        try {
            first = compose.onRoot().captureToImage().asAndroidBitmap()
            compose.mainClock.advanceTimeByFrame()
            val second = compose.onRoot().captureToImage().asAndroidBitmap()
            assertTrue("$name changed across a deterministic frame", first.sameAs(second))
            second.recycle()
        } finally {
            compose.mainClock.autoAdvance = previous
        }
        assertEquals(1080, first.width)
        assertEquals(2400, first.height)
        val bytes =
            ByteArrayOutputStream().use { output ->
                check(first.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        first.recycle()
        val file = File(evidenceDirectory(), name)
        file.writeBytes(bytes)
        copyToDownloads(file, name)
    }

    private fun writeManifest() {
        val files = EVIDENCE_FILES.associateWith { name ->
            sha256(File(evidenceDirectory(), name).readBytes())
        }
        val json = buildString {
            appendLine("{")
            appendLine("  \"contractVersion\": 1,")
            appendLine("  \"sourceHead\": \"${evidenceSource.head}\",")
            appendLine("  \"sourceTree\": \"${evidenceSource.tree}\",")
            appendLine("  \"apiLevel\": 37,")
            appendLine("  \"deviceProfile\": \"pixel_7\",")
            appendLine("  \"renderer\": \"swiftshader_indirect\",")
            appendLine("  \"dimensions\": \"1080x2400\",")
            appendLine("  \"requiredIndependentWipedRuns\": 3,")
            appendLine("  \"referencePolicy\": \"first-reviewed-green-run-only\",")
            appendLine(
                "  \"platformSeparation\": \"Android production HTTPS codec is tested independently from emulator HTTP transport\","
            )
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
                    .getExternalFilesDir("todo15-evidence")
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

    @androidx.compose.runtime.Composable
    private fun EvidenceEdgeToEdge() {
        val activity = LocalContext.current.findComponentActivity()
        SideEffect { activity.enableEdgeToEdge() }
    }

    private fun Context.findComponentActivity(): ComponentActivity =
        generateSequence(this) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>()
            .first()

    private fun ready(
        render: MiniHomeShareRenderState,
        linkState: MiniHomeShareLinkState = MiniHomeShareLinkState.Idle,
    ) = MiniHomeShareUiState.Ready(target(owner, 12), render, linkState)

    private fun target(
        account: AccountId,
        revision: Long,
        placements: List<MiniHomePlacement> =
            listOf(
                MiniHomePlacement(
                    PlacementId("todo15-plant-placement"),
                    MiniHomePlacementTarget.Plant(PersonalPlantId("todo15-plant")),
                    GridPosition(1, 1),
                    MiniHomeZIndex(0),
                ),
                MiniHomePlacement(
                    PlacementId("todo15-decoration-placement"),
                    MiniHomePlacementTarget.Decoration(ItemId("todo15-decoration")),
                    GridPosition(3, 2),
                    MiniHomeZIndex(1),
                ),
            ),
    ) =
        MiniHomeShareTarget(
            account,
            MiniHomeLayout(
                MiniHomeId("todo15-home-${account.value}"),
                "1.2 미리보기",
                MiniHomePlacementPolicy.layer(placements),
                Revision(revision),
                Instant.parse("2026-08-23T00:00:00Z"),
            ),
            if (placements.any { it.target is MiniHomePlacementTarget.Plant }) {
                listOf(MiniHomePlantChoice(PersonalPlantId("todo15-plant"), "몬스테라", null))
            } else {
                emptyList()
            },
            if (placements.any { it.target is MiniHomePlacementTarget.Decoration }) {
                listOf(MiniHomeDecorationChoice(ItemId("todo15-decoration"), "원목 선반"))
            } else {
                emptyList()
            },
        )

    private inner class RouteRepository : MiniHomeShareRepository {
        var nextOwner = owner
        var clearCalls = 0
        lateinit var lastLoadedTarget: MiniHomeShareTarget

        override suspend fun loadCommitted(): MiniHomeShareLoadResult {
            lastLoadedTarget = target(nextOwner, 12, emptyList())
            return MiniHomeShareLoadResult.Ready(lastLoadedTarget)
        }

        override suspend fun createLink(request: MiniHomeShareLinkRequest) =
            MiniHomeShareCreateResult.Created(
                MiniHomeShareLink(
                    link.shareId,
                    link.url,
                    request.expectedRevision,
                    createdAt,
                    expiresAt,
                )
            )

        override suspend fun revokeLink(shareId: MiniHomeShareId) =
            MiniHomeShareRevokeResult.Revoked(createdAt)

        override suspend fun clearOwnerArtifacts() {
            clearCalls += 1
        }
    }

    private inner class BoundaryRepository : MiniHomeShareRepository {
        var target = target(owner, 0, emptyList())
        var createRevision: Revision? = null

        override suspend fun loadCommitted() = MiniHomeShareLoadResult.Ready(target)

        override suspend fun createLink(
            request: MiniHomeShareLinkRequest
        ): MiniHomeShareCreateResult {
            createRevision = request.expectedRevision
            return MiniHomeShareCreateResult.Created(link)
        }

        override suspend fun revokeLink(shareId: MiniHomeShareId) =
            MiniHomeShareRevokeResult.Revoked(createdAt)
    }

    private companion object {
        const val MANIFEST = "todo15-api37-determinism.json"
        val EVIDENCE_FILES =
            listOf(
                "todo15-api37-rendering.png",
                "todo15-api37-ready.png",
                "todo15-api37-render-failure.png",
                "todo15-api37-chooser-cancelled.png",
                "todo15-api37-active-link.png",
                "todo15-api37-offline-failure.png",
                "todo15-api37-revoked.png",
                "todo15-api37-active-link-font-200.png",
            )

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}
