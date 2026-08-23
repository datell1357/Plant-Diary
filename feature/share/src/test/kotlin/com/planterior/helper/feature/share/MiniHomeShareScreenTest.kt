package com.planterior.helper.feature.share

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
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
class MiniHomeShareScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun link() = MiniHomeShareFixtures.link()

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
        onCreate: () -> Unit = {},
        onCopy: () -> Unit = {},
        onShareImage: () -> Unit = {},
        onShareLink: () -> Unit = {},
        onRevoke: () -> Unit = {},
        onRetryRender: () -> Unit = {},
        onRetry: () -> Unit = {},
        copyFeedbackVisible: Boolean = true,
    ) {
        compose.setContent {
            PlanteriorTheme {
                MiniHomeShareScreen(
                    state = state,
                    onBack = {},
                    onCreateLink = onCreate,
                    onCopyLink = onCopy,
                    onShareImage = onShareImage,
                    onShareLink = onShareLink,
                    onRevokeLink = onRevoke,
                    onRetryRender = onRetryRender,
                    onRetryLoad = onRetry,
                    showsInAppCopyFeedback = copyFeedbackVisible,
                )
            }
        }
    }

    @Test
    fun `screen title and persistent privacy notice are always shown`() {
        setScreen(ready())

        compose.onNodeWithText("미니홈 공유").assertIsDisplayed()
        compose
            .onNodeWithTag(MiniHomeShareTestTags.PRIVACY_NOTICE)
            .performScrollTo()
            .assertIsDisplayed()
            .assert(hasTextContaining("개인 식물 사진의 원본과 픽셀은 제외하고 미니어처로 표시해요"))
    }

    @Test
    fun `committed revision is shown for the exported layout`() {
        setScreen(ready())

        compose
            .onNodeWithTag(MiniHomeShareTestTags.REVISION)
            .performScrollTo()
            .assert(hasTextContaining("7"))
    }

    @Test
    fun `render progress ready and failure retry are distinct states`() {
        var state by
            mutableStateOf<MiniHomeShareUiState>(ready(render = MiniHomeShareRenderState.Rendering))
        var retried = 0
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
                    onRetryRender = { retried += 1 },
                    onRetryLoad = {},
                )
            }
        }

        compose.onNodeWithTag(MiniHomeShareTestTags.RENDER_PROGRESS).assertIsDisplayed()

        state = ready(render = MiniHomeShareRenderState.Failed)
        compose
            .onNodeWithTag(MiniHomeShareTestTags.RENDER_FAILURE)
            .performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag(MiniHomeShareTestTags.RENDER_RETRY).performScrollTo().performClick()
        assertEquals(1, retried)

        state = ready(render = MiniHomeShareRenderState.Ready)
        compose.onNodeWithTag(MiniHomeShareTestTags.PREVIEW).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `active link exposes the full selectable url with copy share and revoke actions`() {
        var copied = 0
        var revoked = 0
        setScreen(
            ready(linkState = MiniHomeShareLinkState.Active(link())),
            onCopy = { copied += 1 },
            onRevoke = { revoked += 1 },
        )

        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_URL)
            .performScrollTo()
            .assertTextEquals(MiniHomeShareFixtures.URL)
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_COPY).performScrollTo().performClick()
        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_SHARE)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_REVOKE).performScrollTo().performClick()

        assertEquals(1, copied)
        assertEquals(1, revoked)
        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_EXPIRY)
            .performScrollTo()
            .assert(hasTextContaining("2026년"))
    }

    @Test
    fun `link generating state is shown and the create action is unavailable`() {
        setScreen(ready(linkState = MiniHomeShareLinkState.Generating))

        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_GENERATING)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_CREATE).assertIsNotDisplayed()
    }

    @Test
    fun `offline link failure has an offline specific message and a retry action`() {
        setScreen(ready(linkState = MiniHomeShareLinkState.Failed(MiniHomeShareFailure.OFFLINE)))

        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_FAILURE)
            .performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            .assert(hasTextContaining("연결"))
        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_CREATE)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a deadline failure is shown as a retryable connection problem`() {
        setScreen(ready(linkState = MiniHomeShareLinkState.Failed(MiniHomeShareFailure.DEADLINE)))

        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_FAILURE)
            .performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_CREATE)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `permanent link failure states it cannot be retried the same way`() {
        setScreen(
            ready(
                linkState = MiniHomeShareLinkState.Failed(MiniHomeShareFailure.MALFORMED_RESPONSE)
            )
        )

        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_FAILURE)
            .performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test
    fun `revoked confirmation is shown and the url is gone`() {
        setScreen(ready(linkState = MiniHomeShareLinkState.Revoked))

        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_REVOKED)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_URL).assertIsNotDisplayed()
    }

    @Test
    fun `copy feedback is shown inline when the system does not show its own overlay`() {
        setScreen(
            ready(
                linkState = MiniHomeShareLinkState.Active(link()),
                feedback = MiniHomeShareFeedback.LINK_COPIED,
            ),
            copyFeedbackVisible = true,
        )

        compose.onNodeWithTag(MiniHomeShareTestTags.FEEDBACK).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `copy feedback is not duplicated when the system shows its own overlay`() {
        setScreen(
            ready(
                linkState = MiniHomeShareLinkState.Active(link()),
                feedback = MiniHomeShareFeedback.LINK_COPIED,
            ),
            copyFeedbackVisible = false,
        )

        compose.onNodeWithTag(MiniHomeShareTestTags.FEEDBACK).assertIsNotDisplayed()
        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_COPY)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun `sheet feedback claims the sheet opened and never a delivery`() {
        setScreen(ready(feedback = MiniHomeShareFeedback.SHEET_OPENED))

        val text = feedbackText()
        assertTrue(text.contains("공유 시트"))
        assertFalse(text.contains("보냈"))
        assertFalse(text.contains("전송했"))
        assertFalse(text.contains("전달했"))
    }

    @Test
    fun `sheet cancellation feedback is neutral`() {
        setScreen(ready(feedback = MiniHomeShareFeedback.SHEET_CANCELLED))

        compose
            .onNodeWithTag(MiniHomeShareTestTags.FEEDBACK)
            .performScrollTo()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        assertFalse(MiniHomeShareFeedback.SHEET_CANCELLED.error)
    }

    @Test
    fun `no share target state offers no share actions`() {
        setScreen(MiniHomeShareUiState.NoTarget(MiniHomeShareFixtures.owner))

        compose.onNodeWithTag(MiniHomeShareTestTags.NO_TARGET).assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_CREATE).assertIsNotDisplayed()
        compose.onNodeWithTag(MiniHomeShareTestTags.IMAGE_SHARE).assertIsNotDisplayed()
    }

    @Test
    fun `every interactive target is at least 48dp tall`() {
        setScreen(ready(linkState = MiniHomeShareLinkState.Active(link())))

        listOf(
                MiniHomeShareTestTags.IMAGE_SHARE,
                MiniHomeShareTestTags.LINK_COPY,
                MiniHomeShareTestTags.LINK_SHARE,
                MiniHomeShareTestTags.LINK_REVOKE,
            )
            .forEach { tag ->
                compose.onNodeWithTag(tag).performScrollTo().assertHeightIsAtLeast(48.dp)
            }
        compose.onNodeWithTag(MiniHomeShareTestTags.BACK).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `content scrolls without clipping at 200 percent font scale`() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                PlanteriorTheme {
                    MiniHomeShareScreen(
                        state = ready(linkState = MiniHomeShareLinkState.Active(link())),
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
        }

        compose
            .onNodeWithTag(MiniHomeShareTestTags.LINK_REVOKE)
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithTag(MiniHomeShareTestTags.PRIVACY_NOTICE)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `no rendered semantics leak owner identity or the operation id`() {
        setScreen(ready(linkState = MiniHomeShareLinkState.Active(link())))

        val rendered = renderedSemanticsText()
        assertFalse(rendered.contains(MiniHomeShareFixtures.owner.value))
        assertFalse(rendered.contains(MiniHomeShareFixtures.layout().id.value))
        assertFalse(rendered.contains("share-op"))
        assertFalse(rendered.contains(MiniHomeShareFixtures.SHARE_ID))
    }

    private fun renderedSemanticsText(): String =
        compose
            .onNodeWithTag(MiniHomeShareTestTags.SCREEN)
            .fetchSemanticsNode()
            .subtreeText()
            .joinToString("\n")

    private fun SemanticsNode.subtreeText(): List<String> = buildList {
        config.getOrNull(SemanticsProperties.Text).orEmpty().forEach { add(it.text) }
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().forEach(::add)
        config.getOrNull(SemanticsProperties.StateDescription)?.let(::add)
        children.forEach { addAll(it.subtreeText()) }
    }

    private fun feedbackText(): String =
        compose
            .onNodeWithTag(MiniHomeShareTestTags.FEEDBACK)
            .performScrollTo()
            .fetchSemanticsNode()
            .subtreeText()
            .joinToString(" ")

    private fun hasTextContaining(fragment: String): SemanticsMatcher =
        SemanticsMatcher("subtree text contains $fragment") { node ->
            node.subtreeText().any { it.contains(fragment) }
        }
}
