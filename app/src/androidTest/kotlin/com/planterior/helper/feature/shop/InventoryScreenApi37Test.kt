package com.planterior.helper.feature.shop

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.planterior.helper.Api37LocalNetworkPermissionRule
import com.planterior.helper.ExactEventRegistration
import com.planterior.helper.ExactEventSubscription
import com.planterior.helper.LeasedExactEventRegistration
import com.planterior.helper.core.designsystem.component.PlanteriorBottomBar
import com.planterior.helper.core.designsystem.component.PlanteriorTab
import com.planterior.helper.core.designsystem.component.TabBarTestTag
import com.planterior.helper.core.designsystem.icon.PlanteriorIcons
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.GridPosition
import com.planterior.helper.feature.minihome.MiniHomeControllerSessionToken
import com.planterior.helper.feature.minihome.MiniHomeDecorationChoice
import com.planterior.helper.feature.minihome.MiniHomeDiscardResult
import com.planterior.helper.feature.minihome.MiniHomeLayout
import com.planterior.helper.feature.minihome.MiniHomePhotoLoader
import com.planterior.helper.feature.minihome.MiniHomePhotoRequest
import com.planterior.helper.feature.minihome.MiniHomePlacement
import com.planterior.helper.feature.minihome.MiniHomePlacementTarget
import com.planterior.helper.feature.minihome.MiniHomeSaveState
import com.planterior.helper.feature.minihome.MiniHomeScreen
import com.planterior.helper.feature.minihome.MiniHomeTestTags
import com.planterior.helper.feature.minihome.MiniHomeUiState
import com.planterior.helper.feature.minihome.MiniHomeZIndex
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InventoryScreenApi37Test {
    @get:Rule(order = 0) val localNetworkPermission = Api37LocalNetworkPermissionRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val fixtureContract by lazy(::readFixtureContract)
    private val evidenceDrift = mutableListOf<String>()

    @Test
    fun acquisitionCommittedBackgroundAndEvidenceAreDeterministicOnApi37() {
        evidenceDrift.clear()
        assertEquals(37, Build.VERSION.SDK_INT)
        val account = AccountId("api37-owner")
        val item =
            InventoryItem(
                ItemId(fixtureContract.itemId),
                "초록 정원",
                "미니 식물원 방 전체를 바꾸는 무료 배경",
                ItemCategory.BACKGROUND,
                fixtureMediaIdentity(),
                null,
                Revision(1),
                Instant.parse("2026-08-20T00:00:00Z"),
            )
        val unavailable =
            OwnedInventoryItem(
                ItemId("api37-retired-decoration"),
                Instant.parse("2026-08-19T00:00:00Z"),
                applied = false,
                revision = Revision(2),
                availability = InventoryItemAvailability.UNAVAILABLE,
                catalogSnapshot =
                    OwnedCatalogSnapshot(
                        "은퇴한 장식",
                        ItemCategory.DECORATION,
                        testMediaIdentity("api37-retired-decoration", "d", 1),
                        Revision(1),
                    ),
            )
        val events = VisualEventSource()
        val mediaLoader = VerifiedStorageCatalogMediaLoader(events)
        val photoLoader = MiniHomePhotoLoader { request ->
            check(request is MiniHomePhotoRequest.Catalog)
            when (val result = mediaLoader.load(request.identity)) {
                is CatalogMediaLoadResult.Loaded -> result.bitmap
                is CatalogMediaLoadResult.Fallback -> error("Catalog media: ${result.reason}")
            }
        }
        var acquired: ItemId? = null
        var openedMiniHome by mutableStateOf(false)
        var openedDetail by mutableStateOf(false)
        var showInventory by mutableStateOf(false)
        var state by
            mutableStateOf(
                InventoryUiState.Content(
                    account,
                    InventorySnapshot(
                        account,
                        listOf(item),
                        listOf(unavailable),
                        1,
                        Instant.parse("2026-08-20T00:00:00Z"),
                        partial = true,
                    ),
                    InventorySection.SHOP,
                    null,
                )
            )
        val miniLayout =
            MiniHomeLayout(
                MiniHomeId("api37-home"),
                "API 37 미니 식물원",
                emptyList(),
                Revision(1),
                Instant.parse("2026-08-20T00:00:00Z"),
            )
        val backgroundChoice =
            MiniHomeDecorationChoice(
                item.id,
                item.name,
                ItemCategory.BACKGROUND,
                item.mediaIdentity,
            )
        var miniState by
            mutableStateOf<MiniHomeUiState>(
                MiniHomeUiState.Editing(
                    miniLayout,
                    miniLayout,
                    emptyList(),
                    listOf(backgroundChoice),
                    null,
                    OperationId("api37-background-operation"),
                    MiniHomeSaveState.Idle,
                )
            )
        compose.setContent {
            if (!rememberEvidenceEdgeToEdgeReady()) return@setContent
            PlanteriorTheme {
                if (!openedMiniHome && !openedDetail && showInventory) {
                    InventoryScreen(
                        state,
                        onSelectSection = { section -> state = state.copy(section = section) },
                        onSelectCategory = { category ->
                            state = state.copy(category = category)
                        },
                        onAcquire = {
                            acquired = it
                            events.publish(VisualEvent.Acquired(it))
                        },
                        onRetry = {},
                        onOpenMiniHome = {
                            events.publish(VisualEvent.MiniHomeOpened)
                            openedMiniHome = true
                        },
                        onOpenItem = {
                            events.publish(VisualEvent.DetailOpened(it))
                            openedDetail = true
                        },
                        bottomBar = { EvidenceBottomBar() },
                        mediaLoader = mediaLoader,
                    )
                } else if (openedDetail) {
                    InventoryItemDetailScreen(
                        state = state,
                        itemId = item.id,
                        onBack = { openedDetail = false },
                        onAcquire = {},
                        onOpenMiniHome = {},
                        bottomBar = { EvidenceBottomBar() },
                        mediaLoader = mediaLoader,
                    )
                } else if (openedMiniHome) {
                    MiniHomeScreen(
                        state = miniState,
                        session = MiniHomeControllerSessionToken(1, 1, account),
                        onBack = {},
                        onRetryLoad = {},
                        onBeginEditing = {},
                        onRename = {},
                        onAddPlant = {},
                        onAddDecoration = { itemId ->
                            val editing = miniState as MiniHomeUiState.Editing
                            val placement =
                                MiniHomePlacement(
                                    BACKGROUND_PLACEMENT_ID,
                                    MiniHomePlacementTarget.Decoration(itemId),
                                    GridPosition(0, 0),
                                    MiniHomeZIndex(0),
                                )
                            miniState =
                                editing.copy(
                                    draft = editing.draft.copy(placements = listOf(placement)),
                                    selectedPlacementId = placement.id,
                                )
                            events.publish(
                                VisualEvent.BackgroundDraftApplied(
                                    editing.draft.revision,
                                    placement.id,
                                )
                            )
                        },
                        onSelect = {},
                        onMove = {},
                        onMoveBy = { _, _ -> },
                        onRemove = {},
                        onSave = {
                            val editing = miniState as MiniHomeUiState.Editing
                            val committed =
                                editing.draft.copy(
                                    revision = Revision(2),
                                    updatedAt = Instant.parse("2026-08-20T00:02:00Z"),
                                )
                            miniState =
                                MiniHomeUiState.Viewing(
                                    committed,
                                    editing.plants,
                                    editing.decorations,
                                    stale = false,
                                    saved = true,
                                    owner = account,
                                )
                            events.publish(
                                VisualEvent.BackgroundCommitted(
                                    committed.revision,
                                    BACKGROUND_PLACEMENT_ID,
                                )
                            )
                        },
                        onDiscard = { MiniHomeDiscardResult.Consumed },
                        onAdoptConflict = {},
                        onOpenCollection = {},
                        photoLoader = photoLoader,
                    )
                }
            }
        }

        val catalogAssetSubscription =
            exactSubscription(events) {
                it is VisualEvent.AssetLoaded || it is VisualEvent.AssetFailed
            }
        try {
            catalogAssetSubscription.arm()
            runBlocking { mediaLoader.load(fixtureMediaIdentity()) }
            val terminal =
                catalogAssetSubscription.await(
                    EVENT_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                    "Firebase Storage emulator catalog asset",
                )
            check(terminal is VisualEvent.AssetLoaded) { "catalog asset failed: $terminal" }
            assertVerifiedAsset(terminal)
            compose.runOnIdle { showInventory = true }
            compose
                .onNodeWithTag(InventoryTestTags.item(item.id))
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithTag(InventoryTestTags.media(item.id.value)).assertIsDisplayed()
            compose.onNodeWithText("아이템 상점").assertIsDisplayed()
            compose.onNodeWithTag(InventoryTestTags.SHOP).assertIsSelected()
        } finally {
            catalogAssetSubscription.close()
        }
        compose.onNodeWithText("무료 획득").assertIsDisplayed()
        compose.onAllNodesWithTag(InventoryTestTags.item(unavailable.itemId)).assertCountEquals(0)
        compose.onAllNodesWithTag(InventoryTestTags.UNAVAILABLE_SECTION).assertCountEquals(0)
        val shopHash = captureStableEvidence("todo14-api37-shop.png")

        awaitExact(events, { it == VisualEvent.Acquired(item.id) }, "inventory acquisition") {
            compose
                .onNodeWithTag(InventoryTestTags.acquire(item.id))
                .assertIsEnabled()
                .performClick()
        }
        assertEquals(item.id, acquired)

        compose.runOnIdle {
            state =
                state.copy(
                    snapshot =
                        state.snapshot.copy(
                            owned =
                                listOf(
                                    OwnedInventoryItem(
                                        item.id,
                                        Instant.parse("2026-08-20T00:01:00Z"),
                                        false,
                                        Revision(1),
                                    ),
                                    unavailable,
                                )
                        ),
                    section = InventorySection.WAREHOUSE,
                    searchQuery = "은퇴",
                    feedback = InventoryFeedback.ACQUIRED,
                )
        }
        compose.onNodeWithText("아이템을 획득했어요.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("나의 창고").assertIsDisplayed()
        compose.onNodeWithTag(InventoryTestTags.WAREHOUSE).assertIsSelected()
        compose.onNodeWithTag(InventoryTestTags.UNAVAILABLE_SECTION).assertIsDisplayed()
        compose.onNodeWithTag(InventoryTestTags.item(unavailable.itemId)).assertIsDisplayed()
        val warehouseHash = captureStableEvidence("todo14-api37-warehouse.png")

        val detailOpened = exactSubscription(events) { it == VisualEvent.DetailOpened(item.id) }
        val detailAssetInvocation = mediaLoader.nextInvocation()
        val detailAsset =
            exactSubscription(events) {
                it is VisualEvent.AssetLoaded && it.invocation == detailAssetInvocation
            }
        try {
            detailOpened.arm()
            detailAsset.arm()
            compose.runOnIdle {
                events.publish(VisualEvent.DetailOpened(item.id))
                openedDetail = true
            }
            detailOpened.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS, "inventory detail open")
            runBlocking { mediaLoader.load(fixtureMediaIdentity()) }
            val loaded =
                detailAsset.await(
                    EVENT_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                    "inventory detail Storage asset",
                ) as VisualEvent.AssetLoaded
            assertVerifiedAsset(loaded)
        } finally {
            detailOpened.close()
            detailAsset.close()
        }
        compose.onNodeWithTag(InventoryTestTags.DETAIL).assertIsDisplayed()
        compose.onAllNodesWithTag(TabBarTestTag).assertCountEquals(1)
        compose.onNodeWithContentDescription("창고").assertIsSelected()
        compose.onNodeWithTag(InventoryTestTags.DETAIL_ACTION).performScrollTo().assertIsDisplayed()
        val detailHash = captureStableEvidence("todo14-api37-item-detail.png")
        compose.onNodeWithTag(InventoryTestTags.DETAIL_BACK).performClick()
        compose.onNodeWithTag(InventoryTestTags.SCREEN).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(searchQuery = "") }

        val opened = exactSubscription(events) { it == VisualEvent.MiniHomeOpened }
        val initialAssetInvocation = mediaLoader.nextInvocation()
        val initialAsset =
            exactSubscription(events) {
                it is VisualEvent.AssetLoaded && it.invocation == initialAssetInvocation
            }
        try {
            opened.arm()
            initialAsset.arm()
            compose.runOnIdle {
                events.publish(VisualEvent.MiniHomeOpened)
                openedMiniHome = true
            }
            opened.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS, "mini-home open")
            runBlocking { mediaLoader.load(fixtureMediaIdentity()) }
            val loaded =
                initialAsset.await(
                    EVENT_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                    "initial deterministic picker asset load",
                ) as VisualEvent.AssetLoaded
            assertVerifiedAsset(loaded)
        } finally {
            opened.close()
            initialAsset.close()
        }
        assertTrue(openedMiniHome)

        val draftApplied =
            exactSubscription(events) {
                it == VisualEvent.BackgroundDraftApplied(Revision(1), BACKGROUND_PLACEMENT_ID)
            }
        val backgroundAssetInvocation = mediaLoader.nextInvocation()
        val backgroundAsset =
            exactSubscription(events) {
                it is VisualEvent.AssetLoaded && it.invocation == backgroundAssetInvocation
            }
        try {
            draftApplied.arm()
            backgroundAsset.arm()
            compose.onNodeWithText("초록 정원 배경 적용").performScrollTo().assertIsEnabled().performClick()
            compose.waitForIdle()
            draftApplied.await(
                EVENT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
                "background draft application",
            )
            val loaded =
                backgroundAsset.await(
                    EVENT_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                    "background asset terminal load",
                ) as VisualEvent.AssetLoaded
            assertVerifiedAsset(loaded)
        } finally {
            draftApplied.close()
            backgroundAsset.close()
        }
        compose.waitForIdle()
        compose
            .onNodeWithTag("mini-home:background-media:${item.id.value}", true)
            .assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeTestTags.BACKGROUND).assertIsDisplayed()
        compose.onNodeWithText("선택한 배경").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag(MiniHomeTestTags.MOVE_LEFT).assertCountEquals(0)
        compose
            .onAllNodesWithTag(MiniHomeTestTags.placement(BACKGROUND_PLACEMENT_ID))
            .assertCountEquals(0)

        compose.onNodeWithTag(MiniHomeTestTags.SAVE).performScrollTo().assertIsEnabled()
        val committedRevision = Revision(2)
        val committed =
            exactSubscription(events) {
                it ==
                    VisualEvent.BackgroundCommitted(
                        committedRevision,
                        BACKGROUND_PLACEMENT_ID,
                    )
            }
        val viewingAssetInvocation = mediaLoader.nextInvocation()
        val viewingAsset =
            exactSubscription(events) {
                it is VisualEvent.AssetLoaded && it.invocation == viewingAssetInvocation
            }
        try {
            committed.arm()
            viewingAsset.arm()
            compose.onNodeWithTag(MiniHomeTestTags.SAVE).performClick()
            compose.waitForIdle()
            committed.await(
                EVENT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
                "committed mini-home revision",
            )
            val loaded =
                viewingAsset.await(
                    EVENT_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                    "committed viewing asset terminal load",
                ) as VisualEvent.AssetLoaded
            assertVerifiedAsset(loaded)
        } finally {
            committed.close()
            viewingAsset.close()
        }
        compose
            .onNodeWithTag("mini-home:background-media:${item.id.value}", true)
            .assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeTestTags.CANVAS).performScrollTo().assertIsDisplayed()
        val backgroundHash = captureStableEvidence("todo14-api37-background.png")

        writeDeterminismMetadata(
            events.timeline(),
            shopHash,
            warehouseHash,
            detailHash,
            backgroundHash,
            committedRevision,
        )
        assertTrue(
            "Canonical visual evidence drifted:\n${evidenceDrift.joinToString("\n")}",
            evidenceDrift.isEmpty(),
        )
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

    @Composable
    private fun EvidenceBottomBar() {
        PlanteriorBottomBar(
            tabs =
                listOf(
                    PlanteriorTab("홈", PlanteriorIcons.Home),
                    PlanteriorTab("도감", PlanteriorIcons.Collection),
                    PlanteriorTab("창고", PlanteriorIcons.Storage),
                    PlanteriorTab("설정", PlanteriorIcons.Settings),
                ),
            selectedIndex = 2,
            onTabSelected = {},
            cameraContentDescription = "식물 촬영",
            onCameraClick = {},
        )
    }

    private fun exactSubscription(
        source: VisualEventSource,
        matches: (VisualEvent) -> Boolean,
    ): ExactEventSubscription<VisualEvent> =
        ExactEventSubscription(matches = matches, subscribe = source::subscribe)

    private fun awaitExact(
        source: VisualEventSource,
        matches: (VisualEvent) -> Boolean,
        description: String,
        trigger: () -> Unit,
    ): VisualEvent {
        val subscription = exactSubscription(source, matches)
        return try {
            subscription.arm()
            trigger()
            subscription.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS, description)
        } finally {
            subscription.close()
        }
    }

    private fun assertVerifiedAsset(event: VisualEvent.AssetLoaded) {
        assertEquals(fixtureContract.storagePath, event.path)
        assertEquals(fixtureContract.sha256, event.sha256)
        assertEquals(fixtureContract.width, event.width)
        assertEquals(fixtureContract.height, event.height)
    }

    private fun readFixtureContract(): Api37CatalogFixtureContract {
        val manifest =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open(FIXTURE_MANIFEST)
                .bufferedReader()
                .use { Json.parseToJsonElement(it.readText()).jsonObject }
        val storageMetadata = manifest.getValue("storageMetadata").jsonObject
        val visualEvidence = manifest.getValue("visualEvidence").jsonObject
        val visualFiles =
            visualEvidence.getValue("files").jsonObject.mapValues { (_, value) ->
                val entry = value.jsonObject
                Api37VisualEvidence(entry.string("file"), entry.string("sha256"))
            }
        return Api37CatalogFixtureContract(
            contractVersion = manifest.getValue("contractVersion").jsonPrimitive.int,
            itemId = manifest.string("itemId"),
            file = manifest.string("file"),
            storagePath = manifest.string("storagePath"),
            contentType = manifest.string("contentType"),
            byteSize = manifest.getValue("byteSize").jsonPrimitive.long,
            sha256 = manifest.string("sha256"),
            width = manifest.getValue("width").jsonPrimitive.int,
            height = manifest.getValue("height").jsonPrimitive.int,
            storageWidth = storageMetadata.string("width"),
            storageHeight = storageMetadata.string("height"),
            mediaRevision = manifest.getValue("mediaRevision").jsonPrimitive.long,
            visualContractVersion = visualEvidence.getValue("contractVersion").jsonPrimitive.int,
            visualDeviceProfile = visualEvidence.string("deviceProfile"),
            visualRenderer = visualEvidence.string("renderer"),
            visualWidth = visualEvidence.getValue("width").jsonPrimitive.int,
            visualHeight = visualEvidence.getValue("height").jsonPrimitive.int,
            requiredIndependentWipedRuns =
                visualEvidence.getValue("requiredIndependentWipedRuns").jsonPrimitive.int,
            visualMetadataFile = visualEvidence.string("metadataFile"),
            visualFiles = visualFiles,
        )
    }

    private fun fixtureMediaIdentity() =
        CatalogMediaIdentity(
            fixtureContract.storagePath,
            fixtureContract.sha256,
            fixtureContract.byteSize,
            fixtureContract.contentType,
            fixtureContract.width,
            fixtureContract.height,
            Revision(fixtureContract.mediaRevision),
        )

    private fun testMediaIdentity(itemId: String, digestCharacter: String, mediaRevision: Long) =
        CatalogMediaIdentity(
            "catalog-assets/$itemId/${digestCharacter.repeat(64)}.webp",
            digestCharacter.repeat(64),
            4,
            "image/webp",
            1,
            1,
            Revision(mediaRevision),
        )

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun captureStableEvidence(name: String): String {
        compose.waitForIdle()
        val previousAutoAdvance = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        val first: Bitmap
        try {
            first = compose.onRoot().captureToImage().asAndroidBitmap()
            compose.mainClock.advanceTimeByFrame()
            val next = compose.onRoot().captureToImage().asAndroidBitmap()
            assertTrue("$name changed across a deterministic Compose frame", first.sameAs(next))
        } finally {
            compose.mainClock.autoAdvance = previousAutoAdvance
        }
        val bytes =
            ByteArrayOutputStream().use { output ->
                check(first.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        val expected = checkNotNull(fixtureContract.visualFiles[name])
        val expectedBytes =
            InstrumentationRegistry.getInstrumentation().context.assets.open(expected.file).use {
                it.readBytes()
            }
        assertEquals(expected.sha256, sha256(expectedBytes))
        val actualHash = sha256(bytes)
        if (!bytes.contentEquals(expectedBytes)) {
            evidenceDrift += "$name expected=${expected.sha256} actual=$actualHash"
        }
        val evidence = File(evidenceDirectory(), name)
        evidence.writeBytes(bytes)
        copyToDownloads(evidence, name)
        return actualHash
    }

    private fun writeDeterminismMetadata(
        timeline: List<String>,
        shopHash: String,
        warehouseHash: String,
        detailHash: String,
        backgroundHash: String,
        committedRevision: Revision,
    ) {
        val metadata = buildString {
            appendLine("{")
            appendLine("  \"contractVersion\": 6,")
            appendLine("  \"apiLevel\": 37,")
            appendLine("  \"deviceProfile\": \"${fixtureContract.visualDeviceProfile}\",")
            appendLine("  \"renderer\": \"${fixtureContract.visualRenderer}\",")
            appendLine(
                "  \"independentlyWipedAvdRuns\": ${fixtureContract.requiredIndependentWipedRuns},"
            )
            appendLine(
                "  \"evidenceDimensions\": \"${fixtureContract.visualWidth}x${fixtureContract.visualHeight}\","
            )
            appendLine(
                "  \"visualEvidenceContractVersion\": ${fixtureContract.visualContractVersion},"
            )
            appendLine("  \"shell\": \"production-scaffold-and-bottom-navigation\",")
            appendLine("  \"theme\": \"figma-light-only\",")
            appendLine("  \"windowInsetsContract\": \"activity-enable-edge-to-edge\",")
            appendLine("  \"warehouseTitle\": \"나의 창고\",")
            appendLine("  \"shopTitle\": \"아이템 상점\",")
            appendLine("  \"selectedSectionContainerArgb\": \"#FF3D6642\",")
            appendLine("  \"selectedSectionContentArgb\": \"#FFFFFFFF\",")
            appendLine("  \"unselectedSectionContainer\": \"transparent\",")
            appendLine("  \"unselectedSectionContentArgb\": \"#FF6B7280\",")
            appendLine("  \"selectedCategoryContainerArgb\": \"#FF3D6642\",")
            appendLine("  \"selectedCategoryContentArgb\": \"#FFFFFFFF\",")
            appendLine("  \"unselectedCategoryContainer\": \"transparent\",")
            appendLine("  \"unselectedCategoryContentArgb\": \"#FF6B7280\",")
            appendLine("  \"categoryBorderArgb\": \"#FFE5E7EB\",")
            appendLine("  \"categoryStateLayer\": \"current-content-color\",")
            appendLine("  \"detailBottomNavigation\": \"storage-selected\",")
            appendLine("  \"catalogMediaSource\": \"firebase-storage-emulator\",")
            appendLine("  \"fixtureManifestVersion\": ${fixtureContract.contractVersion},")
            appendLine("  \"fixtureFile\": \"${fixtureContract.file}\",")
            appendLine("  \"catalogAssetPath\": \"${fixtureContract.storagePath}\",")
            appendLine("  \"fixtureContentType\": \"${fixtureContract.contentType}\",")
            appendLine("  \"fixtureByteSize\": ${fixtureContract.byteSize},")
            appendLine("  \"fixtureSha256\": \"${fixtureContract.sha256}\",")
            appendLine(
                "  \"fixtureDimensions\": \"${fixtureContract.width}x${fixtureContract.height}\","
            )
            appendLine("  \"fixtureStorageWidth\": \"${fixtureContract.storageWidth}\",")
            appendLine("  \"fixtureStorageHeight\": \"${fixtureContract.storageHeight}\",")
            appendLine("  \"committedMiniHomeRevision\": ${committedRevision.value},")
            appendLine("  \"timeline\": [")
            timeline.forEachIndexed { index, event ->
                append("    \"").append(event).append('"')
                if (index != timeline.lastIndex) append(',')
                appendLine()
            }
            appendLine("  ],")
            appendLine("  \"evidence\": {")
            appendLine("    \"shop\": \"$shopHash\",")
            appendLine("    \"warehouse\": \"$warehouseHash\",")
            appendLine("    \"itemDetail\": \"$detailHash\",")
            appendLine("    \"background\": \"$backgroundHash\"")
            appendLine("  }")
            appendLine("}")
        }
        val expectedMetadata =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open(fixtureContract.visualMetadataFile)
                .bufferedReader()
                .use { it.readText() }
        if (expectedMetadata != metadata) {
            evidenceDrift +=
                "$DETERMINISM_METADATA expected=${sha256(expectedMetadata.encodeToByteArray())} " +
                    "actual=${sha256(metadata.encodeToByteArray())}"
        }
        val file = File(evidenceDirectory(), DETERMINISM_METADATA)
        file.writeText(metadata, Charsets.UTF_8)
        copyToDownloads(file, DETERMINISM_METADATA)
    }

    private fun evidenceDirectory(): File =
        checkNotNull(
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .getExternalFilesDir("todo14-evidence")
        )

    private fun copyToDownloads(file: File, name: String) {
        val descriptor =
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand("cp ${file.absolutePath} /sdcard/Download/$name")
        FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        descriptor.close()
    }

    private fun readHttp(url: String): ByteArray {
        val connection =
            (java.net.URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
            }
        return try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Storage emulator HTTP ${connection.responseCode}"
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private inner class VerifiedStorageCatalogMediaLoader(private val events: VisualEventSource) :
        CatalogMediaLoader {
        private val delegate =
            BoundedCatalogMediaLoader(
                CatalogMediaVerifiedSource { path, maximumBytes ->
                    check(path == fixtureContract.storagePath)
                    val encoded = URLEncoder.encode(path, Charsets.UTF_8.name())
                    val objectUrl =
                        "http://10.0.2.2:9199/v0/b/demo-planterior.appspot.com/o/$encoded"
                    val remoteMetadata =
                        withContext(Dispatchers.IO) { readHttp(objectUrl) }
                            .decodeToString()
                            .let(Json::parseToJsonElement)
                            .jsonObject
                    val customMetadata = remoteMetadata.getValue("metadata").jsonObject
                    check(remoteMetadata.string("contentType") == fixtureContract.contentType)
                    check(remoteMetadata.string("size").toLong() == fixtureContract.byteSize)
                    check(customMetadata.string("width") == fixtureContract.storageWidth)
                    check(customMetadata.string("height") == fixtureContract.storageHeight)
                    check(customMetadata.string("sha256") == fixtureContract.sha256)
                    check(
                        customMetadata.string("mediaRevision").toLong() ==
                            fixtureContract.mediaRevision
                    )
                    val bytes = withContext(Dispatchers.IO) { readHttp("$objectUrl?alt=media") }
                    val canonicalBytes =
                        InstrumentationRegistry.getInstrumentation()
                            .context
                            .assets
                            .open(fixtureContract.file)
                            .use { it.readBytes() }
                    check(bytes.size in 1..maximumBytes.toInt())
                    check(bytes.size.toLong() == fixtureContract.byteSize)
                    check(bytes.contentEquals(canonicalBytes))
                    check(sha256(bytes) == fixtureContract.sha256) {
                        "Storage emulator fixture bytes do not match the reviewed artifact"
                    }
                    CatalogMediaPayload(
                        bytes,
                        CatalogMediaObjectMetadata(
                            contentType = remoteMetadata.string("contentType"),
                            sizeBytes = remoteMetadata.string("size").toLong(),
                            width = customMetadata.string("width").toInt(),
                            height = customMetadata.string("height").toInt(),
                            sha256 = customMetadata.string("sha256"),
                            mediaRevision = customMetadata.string("mediaRevision").toLong(),
                        ),
                    )
                }
            )
        private var invocation = 0

        fun nextInvocation(): Int = synchronized(this) { invocation + 1 }

        override suspend fun load(identity: CatalogMediaIdentity): CatalogMediaLoadResult =
            try {
                when (val result = delegate.load(identity)) {
                    is CatalogMediaLoadResult.Loaded -> {
                        val path = identity.path
                        val currentInvocation = synchronized(this) { ++invocation }
                        events.publish(
                            VisualEvent.AssetLoaded(
                                path,
                                currentInvocation,
                                fixtureContract.sha256,
                                result.bitmap.width,
                                result.bitmap.height,
                            )
                        )
                        result
                    }
                    is CatalogMediaLoadResult.Fallback -> {
                        events.publish(
                            VisualEvent.AssetFailed(
                                identity.path,
                                result.reason.name,
                                "typed fallback",
                            )
                        )
                        result
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                events.publish(
                    VisualEvent.AssetFailed(
                        identity.path,
                        error::class.java.simpleName,
                        error.message.orEmpty(),
                    )
                )
                throw error
            }
    }

    private class VisualEventSource {
        private val lock = Any()
        private val listeners = mutableSetOf<(VisualEvent) -> Unit>()
        private val events = mutableListOf<String>()

        fun subscribe(receiver: (VisualEvent) -> Unit): ExactEventRegistration =
            LeasedExactEventRegistration(
                receiver = receiver,
                register = { callback -> synchronized(lock) { listeners += callback } },
                unregister = { callback -> synchronized(lock) { listeners -= callback } },
            )

        fun publish(event: VisualEvent) {
            val callbacks =
                synchronized(lock) {
                    events += event.timelineLabel()
                    listeners.toList()
                }
            callbacks.forEach { it(event) }
        }

        fun timeline(): List<String> = synchronized(lock) { events.toList() }
    }

    private sealed interface VisualEvent {
        data class Acquired(val itemId: ItemId) : VisualEvent

        data object MiniHomeOpened : VisualEvent

        data class DetailOpened(val itemId: ItemId) : VisualEvent

        data class AssetFailed(
            val path: String,
            val type: String,
            val message: String,
        ) : VisualEvent

        data class AssetLoaded(
            val path: String,
            val invocation: Int,
            val sha256: String,
            val width: Int,
            val height: Int,
        ) : VisualEvent

        data class BackgroundDraftApplied(
            val revision: Revision,
            val placementId: PlacementId,
        ) : VisualEvent

        data class BackgroundCommitted(
            val revision: Revision,
            val placementId: PlacementId,
        ) : VisualEvent

        fun timelineLabel(): String =
            when (this) {
                is Acquired -> "acquired:${itemId.value}"
                MiniHomeOpened -> "mini-home-opened"
                is DetailOpened -> "detail-opened:${itemId.value}"
                is AssetLoaded -> "asset-loaded:$invocation:$path:$sha256:${width}x$height"
                is AssetFailed -> "asset-failed:$path:$type:$message"
                is BackgroundDraftApplied ->
                    "background-draft:r${revision.value}:${placementId.value}"
                is BackgroundCommitted ->
                    "background-committed:r${revision.value}:${placementId.value}"
            }
    }

    private data class Api37VisualEvidence(val file: String, val sha256: String)

    private data class Api37CatalogFixtureContract(
        val contractVersion: Int,
        val itemId: String,
        val file: String,
        val storagePath: String,
        val contentType: String,
        val byteSize: Long,
        val sha256: String,
        val width: Int,
        val height: Int,
        val storageWidth: String,
        val storageHeight: String,
        val mediaRevision: Long,
        val visualContractVersion: Int,
        val visualDeviceProfile: String,
        val visualRenderer: String,
        val visualWidth: Int,
        val visualHeight: Int,
        val requiredIndependentWipedRuns: Int,
        val visualMetadataFile: String,
        val visualFiles: Map<String, Api37VisualEvidence>,
    )

    private companion object {
        const val EVENT_TIMEOUT_SECONDS = 10L
        const val FIXTURE_MANIFEST = "todo14/catalog-media-fixture.json"
        const val DETERMINISM_METADATA = "todo14-api37-determinism.json"
        val BACKGROUND_PLACEMENT_ID = PlacementId("api37-background-placement")

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}
