package com.planterior.helper.feature.share

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 승인된 iOS `MiniHomeShareView` 참조 계약을 Android 화면에 고정한다.
 *
 * 참조는 미리보기를 가장 먼저 놓고 그 뒤에 저장본 표기와 상태를 간결하게 이어 붙인 다음, 전폭 primary 행동을 세로로 쌓는다. Android 확장(비공개 안내, 링크
 * 상세, 오류 복구)은 이 우선순위를 앞지르지 않고 뒤에 따라온다. 여기서는 문구, 순서, 관측 가능한 치수만 고정하고 픽셀 비교는 API 37 시각 증거가 담당한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MiniHomeShareReferenceParityTest {
    @get:Rule val compose = createComposeRule()

    private fun ready(
        render: MiniHomeShareRenderState = MiniHomeShareRenderState.Ready,
        linkState: MiniHomeShareLinkState = MiniHomeShareLinkState.Idle,
        feedback: MiniHomeShareFeedback? = null,
    ) =
        MiniHomeShareUiState.Ready(
            target = MiniHomeShareFixtures.target(7),
            render = render,
            link = linkState,
            feedback = feedback,
        )

    private fun setScreen(
        state: MiniHomeShareUiState,
        onBack: () -> Unit = {},
        onCreateLink: () -> Unit = {},
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale = fontScale)
            ) {
                PlanteriorTheme {
                    MiniHomeShareScreen(
                        state = state,
                        onBack = onBack,
                        onCreateLink = onCreateLink,
                        onCopyLink = {},
                        onShareImage = {},
                        onShareLink = {},
                        onRevokeLink = {},
                        onRetryRender = {},
                        onRetryLoad = {},
                    )
                }
            }
        }
    }

    @Test
    fun `preview leads the ready screen ahead of every secondary detail`() {
        setScreen(ready(linkState = MiniHomeShareLinkState.Active(MiniHomeShareFixtures.link())))

        val order = orderedTags()
        val preview = order.indexOf(MiniHomeShareTestTags.PREVIEW)
        assertTrue("preview must be rendered", preview >= 0)
        listOf(
                MiniHomeShareTestTags.REVISION,
                MiniHomeShareTestTags.IMAGE_SHARE,
                MiniHomeShareTestTags.PRIVACY_NOTICE,
                MiniHomeShareTestTags.LINK_URL,
                MiniHomeShareTestTags.LINK_EXPIRY,
                MiniHomeShareTestTags.LINK_COPY,
                MiniHomeShareTestTags.LINK_SHARE,
            )
            .forEach { tag ->
                val index = order.indexOf(tag)
                assertTrue("$tag must be rendered", index >= 0)
                assertTrue("preview must precede $tag", preview < index)
            }
    }

    @Test
    fun `reference order runs preview revision status then stacked primary actions`() {
        setScreen(ready())

        val order = orderedTags()
        val expected =
            listOf(
                MiniHomeShareTestTags.PREVIEW,
                MiniHomeShareTestTags.REVISION,
                MiniHomeShareTestTags.STATUS,
                MiniHomeShareTestTags.IMAGE_SHARE,
                MiniHomeShareTestTags.LINK_CREATE,
            )
        assertEquals(expected, order.filter { it in expected })
    }

    @Test
    fun `active link keeps create before revoke and create remains actionable`() {
        var created = 0
        setScreen(
            ready(linkState = MiniHomeShareLinkState.Active(MiniHomeShareFixtures.link())),
            onCreateLink = { created += 1 },
        )

        val order = orderedTags()
        val create = order.indexOf(MiniHomeShareTestTags.LINK_CREATE)
        val revoke = order.indexOf(MiniHomeShareTestTags.LINK_REVOKE)
        assertTrue("create link must be rendered while active", create >= 0)
        assertTrue("revoke link must be rendered while active", revoke >= 0)
        assertTrue("create link must precede revoke", create < revoke)

        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_CREATE).performScrollTo().performClick()
        assertEquals(1, created)
    }

    @Test
    fun `android privacy disclosure follows the reference primary actions`() {
        setScreen(ready())

        val order = orderedTags()
        val privacy = order.indexOf(MiniHomeShareTestTags.PRIVACY_NOTICE)
        val createLink = order.indexOf(MiniHomeShareTestTags.LINK_CREATE)
        assertTrue("privacy notice must be rendered", privacy >= 0)
        assertTrue("link create must be rendered", createLink >= 0)
        assertTrue("privacy disclosure must follow the primary actions", createLink < privacy)
    }

    @Test
    fun `close action uses the concise reference label and pops the screen`() {
        var closed = 0
        setScreen(ready(), onBack = { closed += 1 })

        compose.onNodeWithText("닫기").assertIsDisplayed()
        val close = compose.onNodeWithTag(MiniHomeShareTestTags.BACK).fetchSemanticsNode()
        val preview = compose.onNodeWithTag(MiniHomeShareTestTags.PREVIEW).fetchSemanticsNode()
        assertTrue("close action must stay compact", close.size.width < preview.size.width)
        assertTrue(
            "close action must remain in top chrome",
            close.boundsInRoot.bottom < preview.boundsInRoot.top,
        )
        compose.onNodeWithTag(MiniHomeShareTestTags.BACK).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `loading state reserves the reference preview frame`() {
        setScreen(MiniHomeShareUiState.Loading(MiniHomeShareFixtures.owner))

        compose
            .onNodeWithTag(MiniHomeShareTestTags.LOADING)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(220.dp)
    }

    @Test
    fun `revision line uses the concise reference sentence`() {
        setScreen(ready())

        compose.onNodeWithText("저장된 7판").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `primary actions use the reference labels and fill the content width`() {
        setScreen(ready())

        compose.onNodeWithText("이미지 공유").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("공유 링크 만들기").performScrollTo().assertIsDisplayed()

        val contentWidth =
            compose.onNodeWithTag(MiniHomeShareTestTags.PREVIEW).fetchSemanticsNode().size.width
        listOf(MiniHomeShareTestTags.IMAGE_SHARE, MiniHomeShareTestTags.LINK_CREATE).forEach { tag
            ->
            val node = compose.onNodeWithTag(tag).performScrollTo().fetchSemanticsNode()
            assertEquals("$tag must span the content width", contentWidth, node.size.width)
        }
    }

    @Test
    fun `preview keeps the reference minimum height and spans the content width`() {
        setScreen(ready())

        compose
            .onNodeWithTag(MiniHomeShareTestTags.PREVIEW)
            .performScrollTo()
            .assertHeightIsAtLeast(220.dp)
            .assertWidthIsAtLeast(1.dp)
    }

    @Test
    fun `scroll content keeps the reference inset and vertical rhythm`() {
        setScreen(ready())

        val root = compose.onNodeWithTag(MiniHomeShareTestTags.SCREEN).fetchSemanticsNode()
        val preview = compose.onNodeWithTag(MiniHomeShareTestTags.PREVIEW).fetchSemanticsNode()
        val inset = with(compose.density) { MiniHomeShareLayout.SCROLL_INSET.roundToPx() }
        assertEquals(
            "preview must sit at the reference scroll inset",
            root.positionInRoot.x.toInt() + inset,
            preview.positionInRoot.x.toInt(),
        )

        val revision = compose.onNodeWithTag(MiniHomeShareTestTags.REVISION).fetchSemanticsNode()
        val rhythm = with(compose.density) { MiniHomeShareLayout.VERTICAL_RHYTHM.roundToPx() }
        assertEquals(
            "revision must follow the preview by the reference rhythm",
            preview.boundsInRoot.bottom.toInt() + rhythm,
            revision.positionInRoot.y.toInt(),
        )
    }

    @Test
    fun `revoke is offered only while a link is active`() {
        var state by
            mutableStateOf<MiniHomeShareUiState>(
                ready(linkState = MiniHomeShareLinkState.Active(MiniHomeShareFixtures.link()))
            )
        compose.setContent {
            PlanteriorTheme {
                MiniHomeShareScreen(
                    state = state,
                    onBack = {},
                    onCreateLink = {},
                    onCopyLink = {},
                    onShareImage = {},
                    onShareLink = {},
                    onRevokeLink = {},
                    onRetryRender = {},
                    onRetryLoad = {},
                )
            }
        }

        compose.onNodeWithText("링크 해제").performScrollTo().assertIsDisplayed()

        state = ready(linkState = MiniHomeShareLinkState.Idle)
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_REVOKE).assertIsNotDisplayed()

        state = ready(linkState = MiniHomeShareLinkState.Revoked)
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_REVOKE).assertIsNotDisplayed()
    }

    @Test
    fun `active link details stay below the reference primary actions`() {
        setScreen(ready(linkState = MiniHomeShareLinkState.Active(MiniHomeShareFixtures.link())))

        val order = orderedTags()
        val image = order.indexOf(MiniHomeShareTestTags.IMAGE_SHARE)
        val revoke = order.indexOf(MiniHomeShareTestTags.LINK_REVOKE)
        assertTrue("image share must be rendered", image >= 0)
        assertTrue("revoke must be rendered", revoke >= 0)
        assertTrue("reference primary actions come first", image < revoke)
        listOf(MiniHomeShareTestTags.LINK_URL, MiniHomeShareTestTags.LINK_EXPIRY).forEach { tag ->
            assertTrue("$tag must follow the primary actions", image < order.indexOf(tag))
        }
    }

    @Test
    fun `compact status line replaces the standalone revision and progress cards`() {
        setScreen(ready(render = MiniHomeShareRenderState.Rendering))

        val order = orderedTags()
        assertEquals(
            listOf(MiniHomeShareTestTags.REVISION, MiniHomeShareTestTags.STATUS),
            order.filter {
                it == MiniHomeShareTestTags.REVISION || it == MiniHomeShareTestTags.STATUS
            },
        )
        compose.onNodeWithTag(MiniHomeShareTestTags.STATUS).performScrollTo().assertIsDisplayed()
        // 준비 상태는 별도 카드 표면이 아니라 간결한 한 줄로만 나타난다.
        compose.onNodeWithTag(MiniHomeShareTestTags.RENDER_FAILURE).assertIsNotDisplayed()
    }

    @Test
    fun `reference hierarchy survives 200 percent font scale`() {
        setScreen(
            ready(linkState = MiniHomeShareLinkState.Active(MiniHomeShareFixtures.link())),
            fontScale = 2f,
        )

        val order = orderedTags()
        val preview = order.indexOf(MiniHomeShareTestTags.PREVIEW)
        assertTrue(preview >= 0)
        assertTrue(preview < order.indexOf(MiniHomeShareTestTags.IMAGE_SHARE))
        assertTrue(preview < order.indexOf(MiniHomeShareTestTags.PRIVACY_NOTICE))
        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_REVOKE)
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
    }

    /** 화면 전체를 렌더 순서대로 훑어 태그가 붙은 노드만 남긴다. */
    private fun orderedTags(): List<String> =
        compose.onNodeWithTag(MiniHomeShareTestTags.SCREEN).fetchSemanticsNode().subtreeTags()

    private fun SemanticsNode.subtreeTags(): List<String> = buildList {
        config.getOrNull(SemanticsProperties.TestTag)?.let(::add)
        children.forEach { addAll(it.subtreeTags()) }
    }
}
