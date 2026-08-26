package com.planterior.helper.feature.shop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class InventoryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `shop communicates ownership eligibility acquisition and stale retry accessibly`() {
        var acquired: ItemId? = null
        var retried = false
        val account = AccountId("owner-a")
        val eligible = item("eligible", AcquisitionCondition.REGISTERED_PLANT)
        val owned = item("owned")
        val state =
            InventoryUiState.Content(
                owner = account,
                snapshot =
                    InventorySnapshot(
                        account,
                        listOf(eligible, owned),
                        listOf(
                            OwnedInventoryItem(
                                owned.id,
                                Instant.parse("2026-08-20T00:00:00Z"),
                                applied = true,
                                revision = Revision(1),
                            )
                        ),
                        registeredPlantCount = 0,
                        loadedAt = Instant.parse("2026-08-20T00:00:00Z"),
                    ),
                section = InventorySection.SHOP,
                category = null,
                feedback = InventoryFeedback.CONDITION_NOT_MET,
                feedbackCondition = AcquisitionCondition.REGISTERED_PLANT,
                stale = true,
            )
        compose.setContent {
            PlanteriorTheme {
                InventoryScreen(
                    state,
                    onSelectSection = {},
                    onSelectCategory = {},
                    onAcquire = { acquired = it },
                    onRetry = { retried = true },
                    onOpenMiniHome = {},
                )
            }
        }

        compose.onNodeWithTag(InventoryTestTags.STALE).assertIsDisplayed()
        compose
            .onNodeWithTag(InventoryTestTags.item(eligible.id))
            .assert(hasContentDescription("장식 아이템 eligible name, 미보유, 조건 미충족"))
        compose.onNodeWithTag(InventoryTestTags.acquire(eligible.id)).assertIsNotEnabled()
        compose.onNodeWithText("식물을 1개 등록해 주세요.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("다시 시도").performClick()
        assertEquals(true, retried)
        assertEquals(null, acquired)
    }

    @Test
    fun `warehouse exposes a labelled mini-home application action and acquired live feedback`() {
        val account = AccountId("owner-a")
        val owned = item("owned")
        var opened = false
        val state =
            InventoryUiState.Content(
                owner = account,
                snapshot =
                    InventorySnapshot(
                        account,
                        listOf(owned),
                        listOf(
                            OwnedInventoryItem(
                                owned.id,
                                Instant.parse("2026-08-20T00:00:00Z"),
                                applied = false,
                                revision = Revision(1),
                            )
                        ),
                        registeredPlantCount = 1,
                        loadedAt = Instant.parse("2026-08-20T00:00:00Z"),
                    ),
                section = InventorySection.WAREHOUSE,
                category = null,
                feedback = InventoryFeedback.ACQUIRED,
                stale = false,
            )
        compose.setContent {
            PlanteriorTheme {
                InventoryScreen(
                    state,
                    onSelectSection = {},
                    onSelectCategory = {},
                    onAcquire = {},
                    onRetry = {},
                    onOpenMiniHome = { opened = true },
                )
            }
        }

        compose
            .onNodeWithTag(InventoryTestTags.FEEDBACK)
            .assert(hasText("아이템을 획득했어요. 창고에 바로 추가했어요."))
            .assert(
                SemanticsMatcher("has polite live region") { node ->
                    node.config.contains(SemanticsProperties.LiveRegion)
                }
            )
        compose
            .onNodeWithTag(InventoryTestTags.apply(owned.id))
            .assertHasClickAction()
            .performClick()
        assertEquals(true, opened)
    }

    @Test
    fun `already owned receipt feedback has exact Korean polite announcement`() {
        val account = AccountId("owner-a")
        val owned = item("owned")
        val state =
            InventoryUiState.Content(
                owner = account,
                snapshot =
                    InventorySnapshot(
                        account,
                        listOf(owned),
                        listOf(
                            OwnedInventoryItem(
                                owned.id,
                                Instant.parse("2026-08-20T00:00:00Z"),
                                applied = false,
                                revision = Revision(1),
                            )
                        ),
                        registeredPlantCount = 1,
                        loadedAt = Instant.parse("2026-08-20T00:00:00Z"),
                    ),
                section = InventorySection.WAREHOUSE,
                category = null,
                feedback = InventoryFeedback.ALREADY_OWNED,
            )
        compose.setContent {
            PlanteriorTheme {
                InventoryScreen(
                    state,
                    onSelectSection = {},
                    onSelectCategory = {},
                    onAcquire = {},
                    onRetry = {},
                    onOpenMiniHome = {},
                )
            }
        }

        compose
            .onNodeWithTag(InventoryTestTags.FEEDBACK)
            .assert(hasText("이미 보유한 아이템이에요."))
            .assert(
                SemanticsMatcher("has polite live region") { node ->
                    node.config.contains(SemanticsProperties.LiveRegion)
                }
            )
    }

    @Test
    fun `feedback semantics consumes each stable presentation token once across recomposition`() {
        val account = AccountId("owner-a")
        val owned = item("owned")
        val firstToken =
            InventoryFeedbackPresentationToken(
                InventoryReceiptId("owner-a/operation-stable-token"),
                InventoryReceiptClaimant("presentation-session", 1, 1),
                1,
            )
        var state by
            mutableStateOf(
                InventoryUiState.Content(
                    owner = account,
                    snapshot =
                        InventorySnapshot(
                            account,
                            listOf(owned),
                            emptyList(),
                            registeredPlantCount = 1,
                            loadedAt = Instant.parse("2026-08-20T00:00:00Z"),
                        ),
                    section = InventorySection.SHOP,
                    category = null,
                    feedback = InventoryFeedback.ACQUIRED,
                    feedbackPresentationToken = firstToken,
                )
            )
        val consumed = mutableListOf<InventoryFeedbackPresentationToken>()
        compose.setContent {
            PlanteriorTheme {
                InventoryScreen(
                    state,
                    onSelectSection = {},
                    onSelectCategory = {},
                    onAcquire = {},
                    onRetry = {},
                    onOpenMiniHome = {},
                    onFeedbackConsumed = consumed::add,
                )
            }
        }
        compose.waitForIdle()
        assertEquals(listOf(firstToken), consumed)

        compose.runOnIdle { state = state.copy(searchQuery = "same token") }
        compose.waitForIdle()
        assertEquals(listOf(firstToken), consumed)

        val reboundToken = firstToken.copy(rowVersion = 2)
        compose.runOnIdle { state = state.copy(feedbackPresentationToken = reboundToken) }
        compose.waitForIdle()
        assertEquals(listOf(firstToken, reboundToken), consumed)
    }

    @Test
    fun `mixed partial inventory filters searches recreates and never unwraps unavailable in shop`() {
        val account = AccountId("owner-a")
        val background =
            item("public-background")
                .copy(
                    name = "햇살 배경",
                    category = ItemCategory.BACKGROUND,
                )
        val furniture =
            item("public-furniture")
                .copy(
                    name = "초록 소파",
                    category = ItemCategory.FURNITURE,
                )
        val deleted =
            OwnedInventoryItem(
                ItemId("deleted-decoration"),
                Instant.parse("2026-08-20T00:00:00Z"),
                applied = true,
                revision = Revision(2),
                availability = InventoryItemAvailability.UNAVAILABLE,
                catalogSnapshot =
                    OwnedCatalogSnapshot(
                        "삭제된 장식",
                        ItemCategory.DECORATION,
                        "catalog-assets/deleted-decoration/preview.webp",
                        Revision(1),
                    ),
            )
        val privateWithoutSnapshot =
            OwnedInventoryItem(
                ItemId("private-legacy"),
                Instant.parse("2026-08-20T00:00:00Z"),
                applied = false,
                revision = Revision(1),
                availability = InventoryItemAvailability.UNAVAILABLE,
            )
        val snapshot =
            InventorySnapshot(
                account,
                listOf(background, furniture),
                listOf(deleted, privateWithoutSnapshot),
                1,
                Instant.parse("2026-08-20T00:00:00Z"),
                partial = true,
            )
        var state by
            mutableStateOf(
                InventoryUiState.Content(
                    account,
                    snapshot,
                    InventorySection.SHOP,
                    category = null,
                )
            )
        compose.setContent {
            PlanteriorTheme {
                InventoryScreen(
                    state,
                    onSelectSection = { state = state.copy(section = it) },
                    onSelectCategory = { state = state.copy(category = it) },
                    onSearch = { state = state.copy(searchQuery = it) },
                    onAcquire = {},
                    onRetry = {},
                    onOpenMiniHome = {},
                )
            }
        }

        compose.onAllNodesWithTag(InventoryTestTags.item(background.id)).assertCountEquals(1)
        compose.onAllNodesWithTag(InventoryTestTags.item(deleted.itemId)).assertCountEquals(0)
        compose
            .onAllNodesWithTag(InventoryTestTags.item(privateWithoutSnapshot.itemId))
            .assertCountEquals(0)
        compose.onAllNodesWithTag(InventoryTestTags.UNAVAILABLE_SECTION).assertCountEquals(0)

        compose
            .onNodeWithTag(InventoryTestTags.SEARCH)
            .assert(hasContentDescription("아이템 이름과 설명 검색"))
            .performTextReplacement("소파")
        compose.onAllNodesWithTag(InventoryTestTags.item(background.id)).assertCountEquals(0)
        compose.onAllNodesWithTag(InventoryTestTags.item(furniture.id)).assertCountEquals(1)
        compose.onNodeWithText("가구").performClick()
        compose.onAllNodesWithTag(InventoryTestTags.item(furniture.id)).assertCountEquals(1)
        compose.onNodeWithText("전체").performClick()

        compose.onNodeWithTag(InventoryTestTags.WAREHOUSE).performClick()
        compose.onNodeWithTag(InventoryTestTags.SEARCH).performTextReplacement("삭제")
        compose.onAllNodesWithTag(InventoryTestTags.UNAVAILABLE_SECTION).assertCountEquals(1)
        compose
            .onNodeWithTag(InventoryTestTags.item(deleted.itemId))
            .assert(hasContentDescription("장식 아이템 삭제된 장식, 보유 중, 적용 중"))
        compose
            .onAllNodesWithTag(InventoryTestTags.item(privateWithoutSnapshot.itemId))
            .assertCountEquals(0)

        compose.runOnIdle {
            state = state.copy()
        }
        compose.onNodeWithTag(InventoryTestTags.SEARCH).assert(hasText("삭제"))
        compose.onAllNodesWithTag(InventoryTestTags.item(deleted.itemId)).assertCountEquals(1)
    }

    @Test
    fun `reference phone uses two shop columns and three warehouse columns and cards open details`() {
        val account = AccountId("owner-a")
        val items = (1..6).map { item("item-$it") }
        var opened: ItemId? = null
        var state by
            mutableStateOf(
                InventoryUiState.Content(
                    account,
                    InventorySnapshot(
                        account,
                        items,
                        items.map {
                            OwnedInventoryItem(
                                it.id,
                                Instant.parse("2026-08-20T00:00:00Z"),
                                false,
                                Revision(1),
                            )
                        },
                        1,
                        Instant.parse("2026-08-20T00:00:00Z"),
                    ),
                    InventorySection.SHOP,
                    null,
                )
            )
        compose.setContent {
            PlanteriorTheme {
                InventoryScreen(
                    state,
                    onSelectSection = { state = state.copy(section = it) },
                    onSelectCategory = {},
                    onAcquire = {},
                    onRetry = {},
                    onOpenMiniHome = {},
                    onOpenItem = { opened = it },
                )
            }
        }

        compose.onNodeWithText("아이템 상점").assertIsDisplayed()
        val shopFirstRow =
            items.take(2).map {
                compose.onNodeWithTag(InventoryTestTags.item(it.id)).getUnclippedBoundsInRoot().top
            }
        assertEquals(shopFirstRow[0], shopFirstRow[1])
        val shopThirdTop =
            compose
                .onNodeWithTag(InventoryTestTags.item(items[2].id))
                .getUnclippedBoundsInRoot()
                .top
        assert(shopThirdTop > shopFirstRow[0])

        compose.onNodeWithTag(InventoryTestTags.WAREHOUSE).performClick()
        compose
            .onNodeWithText("나의 창고")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher("is a heading") { node ->
                    node.config.contains(SemanticsProperties.Heading)
                }
            )
        compose.onAllNodesWithText("아이템 창고").assertCountEquals(0)
        val warehouseFirstRow =
            items.take(3).map {
                compose.onNodeWithTag(InventoryTestTags.item(it.id)).getUnclippedBoundsInRoot().top
            }
        assertEquals(1, warehouseFirstRow.distinct().size)
        val warehouseFourthTop =
            compose
                .onNodeWithTag(InventoryTestTags.item(items[3].id))
                .getUnclippedBoundsInRoot()
                .top
        assert(warehouseFourthTop > warehouseFirstRow[0])
        compose.onNodeWithTag(InventoryTestTags.WAREHOUSE).assertIsSelected()
        compose.onNodeWithTag(InventoryTestTags.detailLink(items.first().id)).performClick()
        assertEquals(items.first().id, opened)
    }

    @Test
    fun `section tabs use exact green selected chrome and Figma unselected chrome`() {
        val account = AccountId("owner-a")
        val state =
            InventoryUiState.Content(
                account,
                InventorySnapshot(
                    account,
                    listOf(item("item-1")),
                    emptyList(),
                    1,
                    Instant.parse("2026-08-20T00:00:00Z"),
                ),
                InventorySection.WAREHOUSE,
                null,
            )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                PlanteriorTheme {
                    InventoryScreen(
                        state,
                        onSelectSection = {},
                        onSelectCategory = {},
                        onAcquire = {},
                        onRetry = {},
                        onOpenMiniHome = {},
                    )
                }
            }
        }

        compose
            .onNodeWithTag(InventoryTestTags.WAREHOUSE)
            .assertIsSelected()
            .assert(
                SemanticsMatcher.expectValue(
                    InventorySectionContainerColor,
                    Color(0xFF3D6642).toArgb().toLong(),
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    InventorySectionLabelColor,
                    Color.White.toArgb().toLong(),
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    InventorySectionBorderColor,
                    Color(0xFF3D6642).toArgb().toLong(),
                )
            )
            .assertHeightIsAtLeast(48.dp)
        compose
            .onNodeWithTag(InventoryTestTags.SHOP)
            .assert(
                SemanticsMatcher.expectValue(
                    InventorySectionContainerColor,
                    Color.Transparent.toArgb().toLong(),
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    InventorySectionLabelColor,
                    Color(0xFF6B7280).toArgb().toLong(),
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    InventorySectionBorderColor,
                    Color(0xFFE5E7EB).toArgb().toLong(),
                )
            )
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `category filters use exact Figma chrome semantics and accessible large-text targets`() {
        val account = AccountId("owner-a")
        val state =
            InventoryUiState.Content(
                account,
                InventorySnapshot(
                    account,
                    listOf(item("item-1")),
                    emptyList(),
                    1,
                    Instant.parse("2026-08-20T00:00:00Z"),
                ),
                InventorySection.SHOP,
                ItemCategory.BACKGROUND,
            )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                PlanteriorTheme {
                    InventoryScreen(
                        state,
                        onSelectSection = {},
                        onSelectCategory = {},
                        onAcquire = {},
                        onRetry = {},
                        onOpenMiniHome = {},
                    )
                }
            }
        }

        compose
            .onNodeWithTag(InventoryTestTags.category(ItemCategory.BACKGROUND))
            .assertIsSelected()
            .assertIsEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    InventoryCategoryContainerColor,
                    Color(0xFF3D6642).toArgb().toLong(),
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    InventoryCategoryLabelColor,
                    Color.White.toArgb().toLong(),
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    InventoryCategoryBorderColor,
                    Color(0xFF3D6642).toArgb().toLong(),
                )
            )
            .assertHeightIsAtLeast(48.dp)
        listOf(null, ItemCategory.FURNITURE, ItemCategory.DECORATION).forEach { category ->
            compose
                .onNodeWithTag(InventoryTestTags.category(category))
                .assertIsNotSelected()
                .assertIsEnabled()
                .assert(
                    SemanticsMatcher.expectValue(
                        InventoryCategoryContainerColor,
                        Color.Transparent.toArgb().toLong(),
                    )
                )
                .assert(
                    SemanticsMatcher.expectValue(
                        InventoryCategoryLabelColor,
                        Color(0xFF6B7280).toArgb().toLong(),
                    )
                )
                .assert(
                    SemanticsMatcher.expectValue(
                        InventoryCategoryBorderColor,
                        Color(0xFFE5E7EB).toArgb().toLong(),
                    )
                )
                .assertHeightIsAtLeast(48.dp)
        }
        listOf("전체", "배경", "가구", "장식").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun `disabled category filter preserves Figma palette without Material purple leakage`() {
        compose.setContent {
            PlanteriorTheme {
                CategoryFilterChip(
                    category = ItemCategory.DECORATION,
                    label = "장식",
                    selected = true,
                    enabled = false,
                    onSelected = {},
                )
            }
        }

        compose
            .onNodeWithTag(InventoryTestTags.category(ItemCategory.DECORATION))
            .assertIsSelected()
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    InventoryCategoryContainerColor,
                    Color(0xFF3D6642).toArgb().toLong(),
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    InventoryCategoryLabelColor,
                    Color.White.toArgb().toLong(),
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    InventoryCategoryBorderColor,
                    Color(0xFF3D6642).toArgb().toLong(),
                )
            )
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `detail shows authoritative media status and actions without leaking another owner item`() {
        val ownerA = AccountId("owner-a")
        val ownerB = AccountId("owner-b")
        val available = item("owner-a-item", AcquisitionCondition.REGISTERED_PLANT)
        var state by
            mutableStateOf<InventoryUiState>(
                InventoryUiState.Content(
                    ownerA,
                    InventorySnapshot(
                        ownerA,
                        listOf(available),
                        emptyList(),
                        0,
                        Instant.parse("2026-08-20T00:00:00Z"),
                    ),
                    InventorySection.SHOP,
                    null,
                )
            )
        compose.setContent {
            PlanteriorTheme {
                InventoryItemDetailScreen(
                    state = state,
                    itemId = available.id,
                    onBack = {},
                    onAcquire = {},
                    onOpenMiniHome = {},
                )
            }
        }

        compose.onNodeWithText(available.name).assertIsDisplayed()
        compose
            .onAllNodesWithTag(InventoryTestTags.mediaFallback(available.name))
            .assertCountEquals(1)
        compose.onNodeWithText(available.description).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("식물 1개 등록 필요").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithTag(InventoryTestTags.DETAIL_ACTION)
            .performScrollTo()
            .assertIsNotEnabled()

        compose.runOnIdle {
            state =
                InventoryUiState.Content(
                    ownerB,
                    InventorySnapshot(
                        ownerB,
                        emptyList(),
                        emptyList(),
                        1,
                        Instant.parse("2026-08-20T00:01:00Z"),
                    ),
                    InventorySection.WAREHOUSE,
                    null,
                )
        }
        compose.onAllNodesWithText(available.name).assertCountEquals(0)
        compose.onNodeWithText("아이템을 찾을 수 없어요.").assertIsDisplayed()
    }

    @Test
    fun `locked catalog acquisition detail records one source view across recomposition`() {
        val account = AccountId("owner-a")
        val locked = item("locked-item", AcquisitionCondition.REGISTERED_PLANT)
        var state by
            mutableStateOf(
                InventoryUiState.Content(
                    account,
                    InventorySnapshot(
                        account,
                        listOf(locked),
                        emptyList(),
                        0,
                        Instant.parse("2026-08-20T00:00:00Z"),
                    ),
                    InventorySection.SHOP,
                    null,
                )
            )
        var views = 0
        compose.setContent {
            PlanteriorTheme {
                InventoryItemDetailScreen(
                    state = state,
                    itemId = locked.id,
                    onBack = {},
                    onAcquire = {},
                    onOpenMiniHome = {},
                    onAcquisitionSourceViewed = { views += 1 },
                )
            }
        }
        compose.waitForIdle()
        assertEquals(1, views)

        state = state.copy(searchQuery = "recomposition")
        compose.waitForIdle()
        assertEquals(1, views)
    }

    @Test
    fun `owned catalog detail never records acquisition source view`() {
        val account = AccountId("owner-a")
        val owned = item("owned-item", AcquisitionCondition.REGISTERED_PLANT)
        var views = 0
        compose.setContent {
            PlanteriorTheme {
                InventoryItemDetailScreen(
                    state =
                        InventoryUiState.Content(
                            account,
                            InventorySnapshot(
                                account,
                                listOf(owned),
                                listOf(
                                    OwnedInventoryItem(
                                        owned.id,
                                        Instant.parse("2026-08-20T00:00:00Z"),
                                        false,
                                        Revision(1),
                                    )
                                ),
                                0,
                                Instant.parse("2026-08-20T00:00:00Z"),
                            ),
                            InventorySection.WAREHOUSE,
                            null,
                        ),
                    itemId = owned.id,
                    onBack = {},
                    onAcquire = {},
                    onOpenMiniHome = {},
                    onAcquisitionSourceViewed = { views += 1 },
                )
            }
        }
        compose.waitForIdle()

        assertEquals(0, views)
    }

    @Test
    fun `unavailable detail allows removal only when already applied`() {
        val account = AccountId("owner-a")
        val itemId = ItemId("retired")
        var opened = false
        var state by
            mutableStateOf(
                InventoryUiState.Content(
                    account,
                    InventorySnapshot(
                        account,
                        emptyList(),
                        listOf(
                            OwnedInventoryItem(
                                itemId,
                                Instant.parse("2026-08-20T00:00:00Z"),
                                true,
                                Revision(1),
                                InventoryItemAvailability.UNAVAILABLE,
                                OwnedCatalogSnapshot(
                                    "은퇴한 장식",
                                    ItemCategory.DECORATION,
                                    "catalog-assets/retired/preview.webp",
                                    Revision(1),
                                ),
                            )
                        ),
                        1,
                        Instant.parse("2026-08-20T00:00:00Z"),
                    ),
                    InventorySection.WAREHOUSE,
                    null,
                )
            )
        compose.setContent {
            PlanteriorTheme {
                InventoryItemDetailScreen(
                    state,
                    itemId,
                    onBack = {},
                    onAcquire = {},
                    onOpenMiniHome = { opened = true },
                )
            }
        }

        compose
            .onNodeWithTag(InventoryTestTags.DETAIL_ACTION)
            .performScrollTo()
            .assert(hasText("미니홈피에서 해제"))
            .performClick()
        assertEquals(true, opened)

        compose.runOnIdle {
            val content = state
            state =
                content.copy(
                    snapshot =
                        content.snapshot.copy(
                            owned = content.snapshot.owned.map { it.copy(applied = false) }
                        )
                )
        }
        compose.onAllNodesWithTag(InventoryTestTags.DETAIL_ACTION).assertCountEquals(0)
        compose.onNodeWithText("현재 이용할 수 없어 새로 적용할 수 없어요.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `detail remains usable at two hundred percent text scale with accessible targets`() {
        val account = AccountId("owner-a")
        val owned = item("owned-long-name").copy(name = "아주 긴 이름의 초록 식물원 장식 아이템")
        val state =
            InventoryUiState.Content(
                account,
                InventorySnapshot(
                    account,
                    listOf(owned),
                    listOf(
                        OwnedInventoryItem(
                            owned.id,
                            Instant.parse("2026-08-20T00:00:00Z"),
                            false,
                            Revision(1),
                        )
                    ),
                    1,
                    Instant.parse("2026-08-20T00:00:00Z"),
                ),
                InventorySection.WAREHOUSE,
                null,
            )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                PlanteriorTheme {
                    InventoryItemDetailScreen(
                        state = state,
                        itemId = owned.id,
                        onBack = {},
                        onAcquire = {},
                        onOpenMiniHome = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(InventoryTestTags.DETAIL_BACK).assertHeightIsAtLeast(48.dp)
        compose
            .onNodeWithTag(InventoryTestTags.DETAIL_ACTION)
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("미니홈피에서 적용").assertIsDisplayed()
    }

    private fun item(
        value: String,
        condition: AcquisitionCondition? = null,
    ) =
        InventoryItem(
            ItemId(value),
            "$value name",
            "$value description",
            ItemCategory.DECORATION,
            "items/$value.png",
            condition,
            Revision(1),
            Instant.parse("2026-08-20T00:00:00Z"),
        )
}
