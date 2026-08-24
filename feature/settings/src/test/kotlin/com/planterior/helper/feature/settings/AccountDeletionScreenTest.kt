package com.planterior.helper.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.DeletionRequestId
import com.planterior.helper.core.model.DeletionStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountDeletionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `received exposes cancel while processing does not`() {
        var status by mutableStateOf(DeletionStatus.RECEIVED)
        compose.setContent {
            PlanteriorTheme { AccountDeletionScreen(ready(status), AccountDeletionActions(), {}) }
        }
        compose.onNodeWithTag("account-deletion.cancel").assertIsDisplayed()

        status = DeletionStatus.PROCESSING
        compose.onNodeWithTag("account-deletion.cancel").assertDoesNotExist()
    }

    @Test
    fun `partial failure states account retained and offers retry without cleanup claim`() {
        compose.setContent {
            PlanteriorTheme {
                AccountDeletionScreen(
                    ready(DeletionStatus.PARTIALLY_FAILED),
                    AccountDeletionActions(),
                    {},
                )
            }
        }

        compose.onNodeWithText("계정은 유지돼요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("account-deletion.retry").assertIsDisplayed()
        compose.onNodeWithText("로컬 정리 완료").assertDoesNotExist()
    }

    @Test
    fun `partial failure lists every remaining category including skipped dependencies`() {
        val completed = setOf(AccountDeletionCategory.PUBLIC_SHARES)
        compose.setContent {
            PlanteriorTheme {
                AccountDeletionScreen(
                    ready(
                        status = DeletionStatus.PARTIALLY_FAILED,
                        completedCategories = completed,
                        remainingCategories = AccountDeletionCategory.entries.toSet() - completed,
                    ),
                    AccountDeletionActions(),
                    {},
                )
            }
        }

        listOf(
                "삭제되지 않은 범위 · 식물과 관리 기록",
                "삭제되지 않은 범위 · 알림 연결",
                "삭제되지 않은 범위 · 사진 분석 원본",
                "삭제되지 않은 범위 · 대표 사진과 공유 이미지",
                "삭제되지 않은 범위 · 비공개 미디어 업로드 예약",
                "삭제되지 않은 범위 · 로그인 계정",
            )
            .forEach { text -> compose.onNodeWithText(text).performScrollTo().assertIsDisplayed() }
    }

    @Test
    @Config(
        sdk = [36],
        qualifiers = "w411dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav",
    )
    fun `normal API 37 partial uses two columns and fully shows confirmation and footer`() {
        setApi37Partial(fontScale = 1f)

        val rows = scopeRows(assertDisplayed = true)
        assertTwoColumnScope(rows)
        assertNormalPartialBounds(rows)
    }

    @Test
    @Config(
        sdk = [36],
        qualifiers = "w411dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav",
    )
    fun `200 percent partial falls back to one readable scope column`() {
        setApi37Partial(fontScale = 2f)

        assertOneColumnScope(scopeRows(assertDisplayed = false))
        assertFixedFooterBounds()
    }

    @Test
    @Config(
        sdk = [36],
        qualifiers = "w320dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav",
    )
    fun `narrow partial falls back to one scope column at normal font scale`() {
        setApi37Partial(fontScale = 1f)

        assertOneColumnScope(scopeRows(assertDisplayed = false))
    }

    @Test
    fun `each ready state exposes exactly one primary action with its original callback`() {
        var state by mutableStateOf(ready(null).confirmed())
        var submitCallbacks = 0
        var cancelCallbacks = 0
        var doneCallbacks = 0
        compose.setContent {
            PlanteriorTheme {
                AccountDeletionScreen(
                    state = state,
                    actions =
                        AccountDeletionActions(
                            onSubmit = { submitCallbacks += 1 },
                            onCancel = { cancelCallbacks += 1 },
                        ),
                    onBack = { doneCallbacks += 1 },
                )
            }
        }

        fun show(status: DeletionStatus?) {
            compose.runOnIdle { state = ready(status).confirmed() }
        }

        fun assertOnlyAction(tag: String?) {
            PRIMARY_ACTION_TAGS.forEach { candidate ->
                compose
                    .onAllNodesWithTag(candidate)
                    .assertCountEquals(if (candidate == tag) 1 else 0)
            }
            compose
                .onAllNodesWithTag("account-deletion.footer")
                .assertCountEquals(if (tag == null) 0 else 1)
            if (tag != null) {
                val screen = compose.onNodeWithTag("account-deletion.screen").fetchSemanticsNode()
                val action =
                    compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp).fetchSemanticsNode()
                assertEquals(screen.size.width, action.size.width)
            }
        }

        assertOnlyAction("account-deletion.submit")
        compose.onNodeWithTag("account-deletion.submit").performClick()
        compose.runOnIdle { assertEquals(1, submitCallbacks) }

        show(DeletionStatus.CANCELLED)
        assertOnlyAction("account-deletion.submit")
        compose.onNodeWithTag("account-deletion.submit").performClick()
        compose.runOnIdle { assertEquals(2, submitCallbacks) }

        show(DeletionStatus.RECEIVED)
        assertOnlyAction("account-deletion.cancel")
        compose.onNodeWithTag("account-deletion.cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancelCallbacks) }

        show(DeletionStatus.FAILED)
        assertOnlyAction("account-deletion.retry")
        compose.onNodeWithTag("account-deletion.retry").performClick()
        compose.runOnIdle { assertEquals(3, submitCallbacks) }

        show(DeletionStatus.PARTIALLY_FAILED)
        assertOnlyAction("account-deletion.retry")
        compose.onNodeWithTag("account-deletion.retry").performClick()
        compose.runOnIdle { assertEquals(4, submitCallbacks) }

        show(DeletionStatus.COMPLETED)
        assertOnlyAction("account-deletion.done")
        compose.onNodeWithTag("account-deletion.done").performClick()
        compose.runOnIdle { assertEquals(1, doneCallbacks) }

        show(DeletionStatus.PROCESSING)
        assertOnlyAction(null)
    }

    @Test
    fun `deletion screen discloses photo lifecycle and server scope`() {
        compose.setContent {
            PlanteriorTheme { AccountDeletionScreen(ready(null), AccountDeletionActions(), {}) }
        }

        compose.onNodeWithTag("account-deletion.screen").assertIsDisplayed()
        compose.onNodeWithTag("account-deletion.scope").assertIsDisplayed()
        compose
            .onNodeWithText("사진 분석 원본은 요청마다 처리 후 24시간 이내 삭제되며, 도감 대표 사진은 별도로 선택한 경우에만 저장돼요.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun setApi37Partial(fontScale: Float) {
        val state = ready(DeletionStatus.PARTIALLY_FAILED)
        assertEquals(AccountDeletionCategory.entries, state.scope.categories)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale)
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .padding(
                            top = API37_TOP_SYSTEM_AND_SCAFFOLD_INSET,
                            bottom = API37_BOTTOM_SYSTEM_INSET,
                        )
                ) {
                    PlanteriorTheme { AccountDeletionScreen(state, AccountDeletionActions(), {}) }
                }
            }
        }
        compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).assertCountEquals(1)
    }

    private fun scopeRows(assertDisplayed: Boolean): List<SemanticsNode> {
        val expectedTags =
            AccountDeletionCategory.entries.map { "account-deletion.scope-row.${it.serverId}" }
        val scope = compose.onNodeWithTag("account-deletion.scope").fetchSemanticsNode()
        assertEquals(
            "scope labels must retain server category order",
            expectedTags,
            collectTags(scope).filter { it.startsWith("account-deletion.scope-row.") },
        )
        return expectedTags.map { tag ->
            val node = compose.onNodeWithTag(tag)
            if (assertDisplayed) node.assertIsDisplayed()
            node.fetchSemanticsNode().also(::assertReadableScopeLabel)
        }
    }

    private fun assertTwoColumnScope(rows: List<SemanticsNode>) {
        val columnGap = with(compose.density) { SCOPE_COLUMN_GAP.toPx() }
        rows.chunked(2).forEach { row ->
            if (row.size == 2) {
                assertEquals("paired labels must share a row", row[0].top, row[1].top, 1f)
                assertEquals(
                    "scope columns must be equal within pixel rounding",
                    row[0].size.width.toFloat(),
                    row[1].size.width.toFloat(),
                    1f,
                )
                assertTrue(
                    "scope columns must not overlap",
                    row[0].right + columnGap <= row[1].left,
                )
            }
        }
        val leftColumn = rows.filterIndexed { index, _ -> index % 2 == 0 }
        val rightColumn = rows.filterIndexed { index, _ -> index % 2 == 1 }
        leftColumn.forEach { assertEquals(leftColumn.first().left, it.left, 1f) }
        rightColumn.forEach { assertEquals(rightColumn.first().left, it.left, 1f) }
        val rowGap = with(compose.density) { SCOPE_ROW_GAP.toPx() }
        rows.chunked(2).zipWithNext().forEach { (upper, lower) ->
            assertTrue(
                "scope grid rows must retain a clear gap",
                upper.maxOf { it.bottom } + rowGap <= lower.minOf { it.top },
            )
        }
    }

    private fun assertOneColumnScope(rows: List<SemanticsNode>) {
        val scope = compose.onNodeWithTag("account-deletion.scope").fetchSemanticsNode()
        rows.forEach { row ->
            assertEquals(
                "one-column labels must share a start edge",
                rows.first().left,
                row.left,
                1f,
            )
            assertTrue("scope label must stay inside card start", row.left >= scope.left)
            assertTrue("scope label must stay inside card end", row.right <= scope.right)
        }
        val rowGap = with(compose.density) { SCOPE_ROW_GAP.toPx() }
        rows.zipWithNext().forEach { (upper, lower) ->
            assertTrue(
                "one-column labels must retain a clear gap",
                upper.bottom + rowGap <= lower.top,
            )
        }
    }

    private fun assertNormalPartialBounds(rows: List<SemanticsNode>) {
        val scroll = compose.onNodeWithTag("account-deletion.scroll").fetchSemanticsNode()
        val scope = compose.onNodeWithTag("account-deletion.scope").fetchSemanticsNode()
        val confirmation =
            compose
                .onNodeWithTag("account-deletion.final-confirmation")
                .assertIsDisplayed()
                .fetchSemanticsNode()

        assertTrue("scope top must remain visible", scope.top >= scroll.top)
        rows.forEach { row ->
            assertTrue(
                "every scope label must remain inside the viewport",
                row.bottom <= scroll.bottom,
            )
        }
        assertTrue("confirmation top must stay inside the viewport", confirmation.top >= scroll.top)
        assertTrue(
            "the full confirmation checkbox row must stay above the clipping boundary",
            confirmation.bottom <= scroll.bottom,
        )
        assertFixedFooterBounds()
    }

    private fun assertFixedFooterBounds() {
        val screen = compose.onNodeWithTag("account-deletion.screen").fetchSemanticsNode()
        val scroll = compose.onNodeWithTag("account-deletion.scroll").fetchSemanticsNode()
        val footer = compose.onNodeWithTag("account-deletion.footer").fetchSemanticsNode()
        val retry =
            compose
                .onNodeWithTag("account-deletion.retry")
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode()
        val minimumBottomMargin = with(compose.density) { SCREEN_VERTICAL_PADDING.toPx() }

        assertEquals(screen.size.width, scroll.size.width)
        assertEquals(screen.size.width, footer.size.width)
        assertEquals(screen.size.width, retry.size.width)
        assertTrue("footer must start after the scroll viewport", scroll.bottom <= footer.top)
        assertTrue("retry must not overlap scroll content", scroll.bottom <= retry.top)
        assertTrue("retry must remain inside screen", retry.bottom <= screen.bottom)
        assertTrue(
            "retry must retain its bottom margin",
            screen.bottom - retry.bottom >= minimumBottomMargin,
        )
    }

    private fun collectTags(node: SemanticsNode): List<String> = buildList {
        node.config.getOrNull(SemanticsProperties.TestTag)?.let(::add)
        node.children.forEach { addAll(collectTags(it)) }
    }

    private fun assertReadableScopeLabel(node: SemanticsNode) {
        val text =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(separator = "") {
                it.text
            }
        assertTrue("scope label must remain individually readable", text.isNotBlank())
        assertFalse("scope label must not contain tofu", '\uFFFD' in text)
        assertFalse(
            "scope label must not contain invisible control characters",
            text.any {
                Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt()
            },
        )
    }

    private val SemanticsNode.left: Float
        get() = positionInRoot.x

    private val SemanticsNode.top: Float
        get() = positionInRoot.y

    private val SemanticsNode.right: Float
        get() = left + size.width

    private val SemanticsNode.bottom: Float
        get() = top + size.height

    private fun AccountDeletionUiState.Ready.confirmed() =
        copy(reauthenticated = true, finalConfirmed = true)

    private fun ready(
        status: DeletionStatus?,
        completedCategories: Set<AccountDeletionCategory> =
            when (status) {
                DeletionStatus.COMPLETED -> AccountDeletionCategory.entries.toSet()
                DeletionStatus.PARTIALLY_FAILED ->
                    AccountDeletionCategory.entries.toSet() - AccountDeletionCategory.ACCOUNT_MEDIA
                else -> emptySet()
            },
        remainingCategories: Set<AccountDeletionCategory> =
            when (status) {
                DeletionStatus.COMPLETED -> emptySet()
                DeletionStatus.PARTIALLY_FAILED -> setOf(AccountDeletionCategory.ACCOUNT_MEDIA)
                else -> AccountDeletionCategory.entries.toSet()
            },
    ): AccountDeletionUiState.Ready {
        val scope =
            AccountDeletionScope(
                AccountDeletionScopeHash("a".repeat(64)),
                AccountDeletionCategory.entries,
            )
        return AccountDeletionUiState.Ready(
            scope = scope,
            workflow =
                status?.let {
                    AccountDeletionWorkflow(
                        DeletionRequestId("deletion-request-1"),
                        scope,
                        Instant.parse("2026-08-24T04:00:00Z"),
                        Instant.parse("2026-08-31T04:00:00Z"),
                        it,
                        completedCategories = completedCategories,
                        remainingCategories = remainingCategories,
                    )
                },
        )
    }

    private companion object {
        val API37_TOP_SYSTEM_AND_SCAFFOLD_INSET = 80.dp
        val API37_BOTTOM_SYSTEM_INSET = 24.dp
        val SCREEN_VERTICAL_PADDING = 12.dp
        val SCOPE_COLUMN_GAP = 8.dp
        val SCOPE_ROW_GAP = 4.dp
        val PRIMARY_ACTION_TAGS =
            listOf(
                "account-deletion.submit",
                "account-deletion.cancel",
                "account-deletion.retry",
                "account-deletion.done",
            )
    }
}
