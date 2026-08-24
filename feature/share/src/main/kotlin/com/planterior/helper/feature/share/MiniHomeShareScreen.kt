package com.planterior.helper.feature.share

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.component.PlanteriorCard
import com.planterior.helper.core.designsystem.component.PlanteriorScreenScaffold
import com.planterior.helper.core.designsystem.theme.PlanteriorBorderWidth
import com.planterior.helper.core.designsystem.theme.PlanteriorRadius
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.feature.minihome.MiniHomePhotoLoader
import com.planterior.helper.feature.minihome.PlaceholderMiniHomePhotoLoader
import java.time.ZoneId

/**
 * 승인된 iOS `MiniHomeShareView` 참조가 정한 공유 화면 전용 치수이다.
 *
 * 참조의 스크롤 여백 20dp와 세로 리듬 16dp는 화면 기본 여백(16dp)·카드 간격(12dp)과 다른 값이라 공용 spacing 스케일 대신 이 화면 계약으로 고정한다.
 * 미리보기 최소 높이도 참조와 같은 220dp를 쓴다.
 */
object MiniHomeShareLayout {
    /** 참조 `.padding(20)`. 스크롤 본문 사방 여백. */
    val SCROLL_INSET: Dp = 20.dp

    /** 참조 `VStack(spacing: 16)`. 본문 요소 사이 세로 리듬. */
    val VERTICAL_RHYTHM: Dp = 16.dp

    /** 참조 `minHeight: 220`. 미리보기가 준비 중이어도 자리를 잃지 않는 최소 높이. */
    val PREVIEW_MIN_HEIGHT: Dp = 220.dp
}

private val ShareAuxiliaryPhrases = listOf(listOf("볼", "수", "있어요"), listOf("만들", "수", "있어요"))

object MiniHomeShareTestTags {
    const val SCREEN = "mini-home-share:screen"
    const val SCROLL = "mini-home-share:scroll"
    const val STATUS = "mini-home-share:status"
    const val BACK = "mini-home-share:back"
    const val LOADING = "mini-home-share:loading"
    const val PRIVACY_NOTICE = "mini-home-share:privacy-notice"
    const val PRIVACY_BODY = "mini-home-share:privacy-body"
    const val REVISION = "mini-home-share:revision"
    const val PREVIEW = "mini-home-share:preview"
    const val CAPTURE = "mini-home-share:capture"
    const val CAPTURE_PLACEMENT_PREFIX = "mini-home-share:capture-placement:"
    const val RENDER_FAILURE = "mini-home-share:render-failure"
    const val RENDER_RETRY = "mini-home-share:render-retry"
    const val IMAGE_SHARE = "mini-home-share:image-share"
    const val LINK_CREATE = "mini-home-share:link-create"
    const val LINK_GENERATING = "mini-home-share:link-generating"
    const val LINK_URL = "mini-home-share:link-url"
    const val LINK_COPY = "mini-home-share:link-copy"
    const val LINK_SHARE = "mini-home-share:link-share"
    const val LINK_REVOKE = "mini-home-share:link-revoke"
    const val LINK_REVOKED = "mini-home-share:link-revoked"
    const val LINK_EXPIRY = "mini-home-share:link-expiry"
    const val LINK_FAILURE = "mini-home-share:link-failure"
    const val FEEDBACK = "mini-home-share:feedback"
    const val NO_TARGET = "mini-home-share:no-target"
    const val ERROR = "mini-home-share:error"
    const val ERROR_RETRY = "mini-home-share:error-retry"

    fun capturePlacement(id: com.planterior.helper.core.model.PlacementId) =
        "$CAPTURE_PLACEMENT_PREFIX${id.value}"
}

/**
 * 미니홈 공유 화면이다.
 *
 * 저장된 구성만 다루고, 사용자가 직접 실행하기 전까지는 어떤 것도 외부로 나가지 않는다는 안내를 항상 유지한다.
 */
@Composable
fun MiniHomeShareScreen(
    state: MiniHomeShareUiState,
    onBack: () -> Unit,
    onCreateLink: () -> Unit,
    onCopyLink: () -> Unit,
    onShareImage: () -> Unit,
    onShareLink: () -> Unit,
    onRevokeLink: () -> Unit,
    onRetryRender: () -> Unit,
    onRetryLoad: () -> Unit,
    modifier: Modifier = Modifier,
    photoLoader: MiniHomePhotoLoader = PlaceholderMiniHomePhotoLoader,
    captureHandle: MiniHomeShareCaptureHandle? = null,
    captureToken: MiniHomeShareCaptureToken? = null,
    showsInAppCopyFeedback: Boolean = true,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val backDescription = stringResource(R.string.mini_home_share_back_description)
    PlanteriorScreenScaffold(
        title = stringResource(R.string.mini_home_share_title),
        modifier = modifier.testTag(MiniHomeShareTestTags.SCREEN),
        contentHorizontalPadding = MiniHomeShareLayout.SCROLL_INSET,
        topAction = {
            TextButton(
                onClick = onBack,
                modifier =
                    Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag(MiniHomeShareTestTags.BACK)
                        .semantics { contentDescription = backDescription },
            ) {
                Text(stringResource(R.string.mini_home_share_back))
            }
        },
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .testTag(MiniHomeShareTestTags.SCROLL)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MiniHomeShareLayout.VERTICAL_RHYTHM),
        ) {
            when (state) {
                is MiniHomeShareUiState.Loading -> {
                    val loadingLabel = stringResource(R.string.mini_home_share_loading)
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .sizeIn(minHeight = MiniHomeShareLayout.PREVIEW_MIN_HEIGHT)
                                .clip(RoundedCornerShape(PlanteriorRadius.Medium))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .testTag(MiniHomeShareTestTags.LOADING)
                                .semantics { contentDescription = loadingLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is MiniHomeShareUiState.Ready ->
                    ReadyBody(
                        state = state,
                        onCreateLink = onCreateLink,
                        onCopyLink = onCopyLink,
                        onShareImage = onShareImage,
                        onShareLink = onShareLink,
                        onRevokeLink = onRevokeLink,
                        onRetryRender = onRetryRender,
                        photoLoader = photoLoader,
                        captureHandle = captureHandle,
                        captureToken = captureToken,
                        showsInAppCopyFeedback = showsInAppCopyFeedback,
                        zone = zone,
                    )
                is MiniHomeShareUiState.NoTarget ->
                    ShareStatusCard(
                        title = stringResource(R.string.mini_home_share_no_target_title),
                        body = stringResource(R.string.mini_home_share_no_target_body),
                        tag = MiniHomeShareTestTags.NO_TARGET,
                    )
                MiniHomeShareUiState.Forbidden ->
                    ShareStatusCard(
                        title = stringResource(R.string.mini_home_share_forbidden_title),
                        body = stringResource(R.string.mini_home_share_forbidden_body),
                        error = true,
                        tag = MiniHomeShareTestTags.ERROR,
                    )
                MiniHomeShareUiState.Error -> {
                    ShareStatusCard(
                        title = stringResource(R.string.mini_home_share_error_title),
                        body = stringResource(R.string.mini_home_share_error_body),
                        error = true,
                        tag = MiniHomeShareTestTags.ERROR,
                    )
                    Button(
                        onClick = onRetryLoad,
                        modifier = Modifier.action(MiniHomeShareTestTags.ERROR_RETRY),
                    ) {
                        Text(stringResource(R.string.mini_home_share_retry))
                    }
                }
            }
            if (state !is MiniHomeShareUiState.Ready) PrivacyNotice()
        }
    }
}

@Composable
private fun ReadyBody(
    state: MiniHomeShareUiState.Ready,
    onCreateLink: () -> Unit,
    onCopyLink: () -> Unit,
    onShareImage: () -> Unit,
    onShareLink: () -> Unit,
    onRevokeLink: () -> Unit,
    onRetryRender: () -> Unit,
    photoLoader: MiniHomePhotoLoader,
    captureHandle: MiniHomeShareCaptureHandle?,
    captureToken: MiniHomeShareCaptureToken?,
    showsInAppCopyFeedback: Boolean,
    zone: ZoneId,
) {
    // 참조는 미리보기를 가장 먼저 놓는다. 렌더가 실패한 동안에도 자리를 유지해 화면 위쪽이 오류 카드로 바뀌지 않는다.
    if (state.render == MiniHomeShareRenderState.Failed) {
        MiniHomeSharePreviewPlaceholder()
    } else {
        MiniHomeSharePreview(
            target = state.target,
            modifier =
                Modifier.testTag(MiniHomeShareTestTags.PREVIEW)
                    .sizeIn(minHeight = MiniHomeShareLayout.PREVIEW_MIN_HEIGHT)
                    .clip(RoundedCornerShape(PlanteriorRadius.Medium)),
            photoLoader = photoLoader,
            handle = captureHandle,
            captureToken = captureToken,
            contentDescription = stringResource(R.string.mini_home_share_preview_description),
        )
    }
    val revision = state.target.committed.revision.value.toString()
    val revisionDescription =
        stringResource(R.string.mini_home_share_revision_description, revision)
    Text(
        stringResource(R.string.mini_home_share_revision, revision),
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier.fillMaxWidth().testTag(MiniHomeShareTestTags.REVISION).semantics {
                contentDescription = revisionDescription
            },
    )
    ShareProse(
        text = stringResource(state.render.statusRes()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag(MiniHomeShareTestTags.STATUS),
    )
    if (state.render == MiniHomeShareRenderState.Ready) {
        Button(
            onClick = onShareImage,
            modifier = Modifier.action(MiniHomeShareTestTags.IMAGE_SHARE),
        ) {
            Text(stringResource(R.string.mini_home_share_image_action))
        }
    }
    LinkPrimaryAction(link = state.link, onCreateLink = onCreateLink)
    // 참조의 `revokedAt == nil` 조건은 Android에서 해제된 링크가 `Revoked`로 넘어가므로 `Active` 그 자체이다.
    if (state.link is MiniHomeShareLinkState.Active) {
        Button(
            onClick = onRevokeLink,
            enabled = !state.link.revoking,
            modifier = Modifier.action(MiniHomeShareTestTags.LINK_REVOKE),
        ) {
            Text(
                stringResource(
                    if (state.link.revoking) R.string.mini_home_share_link_revoking
                    else R.string.mini_home_share_link_revoke
                )
            )
        }
    }
    // 여기부터는 Android 확장이다. 참조의 기본 행동을 앞지르지 않고 아래에 이어 붙인다.
    if (state.render == MiniHomeShareRenderState.Failed) {
        ShareStatusCard(
            title = stringResource(R.string.mini_home_share_render_failure_title),
            body = stringResource(R.string.mini_home_share_render_failure_body),
            error = true,
            tag = MiniHomeShareTestTags.RENDER_FAILURE,
        )
        Button(
            onClick = onRetryRender,
            modifier = Modifier.action(MiniHomeShareTestTags.RENDER_RETRY),
        ) {
            Text(stringResource(R.string.mini_home_share_render_retry))
        }
    }
    LinkDetails(
        link = state.link,
        onCopyLink = onCopyLink,
        onShareLink = onShareLink,
        showsInAppCopyFeedback = showsInAppCopyFeedback,
        copied = state.feedback == MiniHomeShareFeedback.LINK_COPIED,
        zone = zone,
    )
    state.feedback?.let { feedback ->
        if (feedback != MiniHomeShareFeedback.LINK_COPIED || showsInAppCopyFeedback) {
            val message = stringResource(feedback.bodyRes())
            ShareProse(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (feedback.error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier =
                    Modifier.fillMaxWidth().testTag(MiniHomeShareTestTags.FEEDBACK).semantics {
                        if (feedback.error) error(message)
                    },
            )
        }
    }
    PrivacyNotice()
}

/** 렌더 실패 동안에도 참조의 미리보기 자리를 유지한다. */
@Composable
private fun MiniHomeSharePreviewPlaceholder() {
    val label = stringResource(R.string.mini_home_share_render_failure_title)
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .sizeIn(minHeight = MiniHomeShareLayout.PREVIEW_MIN_HEIGHT)
                .clip(RoundedCornerShape(PlanteriorRadius.Medium))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .testTag(MiniHomeShareTestTags.PREVIEW)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ShareProse(
            text = stringResource(R.string.mini_home_share_status_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun MiniHomeShareRenderState.statusRes(): Int =
    when (this) {
        MiniHomeShareRenderState.Rendering -> R.string.mini_home_share_status_rendering
        MiniHomeShareRenderState.Failed -> R.string.mini_home_share_status_failed
        MiniHomeShareRenderState.Ready -> R.string.mini_home_share_status_ready
    }

/** 참조의 두 번째 전폭 primary 행동이다. 생성 중이 아닐 때는 현재 링크 상태와 관계없이 유지한다. */
@Composable
private fun LinkPrimaryAction(link: MiniHomeShareLinkState, onCreateLink: () -> Unit) {
    when (link) {
        MiniHomeShareLinkState.Generating ->
            ShareProse(
                text = stringResource(R.string.mini_home_share_link_generating),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().testTag(MiniHomeShareTestTags.LINK_GENERATING),
            )
        MiniHomeShareLinkState.Idle,
        is MiniHomeShareLinkState.Active,
        is MiniHomeShareLinkState.Failed,
        MiniHomeShareLinkState.Revoked ->
            Button(
                onClick = onCreateLink,
                modifier = Modifier.action(MiniHomeShareTestTags.LINK_CREATE),
            ) {
                Text(stringResource(R.string.mini_home_share_link_create))
            }
    }
}

/**
 * 참조에 없는 Android 링크 상세이다.
 *
 * 주소 확인, 만료 안내, 복사와 외부 공유는 참조의 primary 행동 아래에만 놓여 위율을 바꾸지 않는다.
 */
@Composable
private fun LinkDetails(
    link: MiniHomeShareLinkState,
    onCopyLink: () -> Unit,
    onShareLink: () -> Unit,
    showsInAppCopyFeedback: Boolean,
    copied: Boolean,
    zone: ZoneId,
) {
    when (link) {
        MiniHomeShareLinkState.Idle ->
            ShareProse(
                text = stringResource(R.string.mini_home_share_link_idle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        MiniHomeShareLinkState.Generating -> Unit
        is MiniHomeShareLinkState.Active -> {
            val urlLabel = stringResource(R.string.mini_home_share_link_url_description)
            PlanteriorCard {
                Text(
                    urlLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                SelectionContainer {
                    Text(
                        link.link.url,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(top = PlanteriorTheme.spacing.small)
                                .testTag(MiniHomeShareTestTags.LINK_URL),
                    )
                }
                ShareProse(
                    text = miniHomeShareExpiryText(link.link.expiresAt, zone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(top = PlanteriorTheme.spacing.medium)
                            .testTag(MiniHomeShareTestTags.LINK_EXPIRY),
                )
            }
            val copiedState = stringResource(R.string.mini_home_share_link_copied_state)
            OutlinedButton(
                onClick = onCopyLink,
                border =
                    BorderStroke(
                        PlanteriorBorderWidth,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                modifier =
                    Modifier.action(MiniHomeShareTestTags.LINK_COPY)
                        .then(
                            if (!showsInAppCopyFeedback && copied) {
                                Modifier.semantics { stateDescription = copiedState }
                            } else {
                                Modifier
                            }
                        ),
            ) {
                Text(stringResource(R.string.mini_home_share_link_copy))
            }
            OutlinedButton(
                onClick = onShareLink,
                border =
                    BorderStroke(
                        PlanteriorBorderWidth,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                modifier = Modifier.action(MiniHomeShareTestTags.LINK_SHARE),
            ) {
                Text(stringResource(R.string.mini_home_share_link_share))
            }
            link.revokeFailure?.let {
                ShareStatusCard(
                    title = stringResource(R.string.mini_home_share_failure_permanent_title),
                    body = stringResource(R.string.mini_home_share_revoke_failure),
                    error = true,
                    tag = MiniHomeShareTestTags.LINK_FAILURE,
                )
            }
        }
        is MiniHomeShareLinkState.Failed ->
            ShareStatusCard(
                title = stringResource(link.failure.titleRes()),
                body = stringResource(link.failure.bodyRes()),
                error = true,
                tag = MiniHomeShareTestTags.LINK_FAILURE,
            )
        MiniHomeShareLinkState.Revoked ->
            ShareStatusCard(
                title = stringResource(R.string.mini_home_share_link_revoked_title),
                body = stringResource(R.string.mini_home_share_link_revoked_body),
                tag = MiniHomeShareTestTags.LINK_REVOKED,
            )
    }
}

@Composable
private fun PrivacyNotice() {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(MiniHomeShareTestTags.PRIVACY_NOTICE),
        verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.small),
    ) {
        Text(
            stringResource(R.string.mini_home_share_notice_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        ShareProse(
            text = stringResource(R.string.mini_home_share_notice_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(MiniHomeShareTestTags.PRIVACY_BODY),
        )
    }
}

@Composable
private fun ShareStatusCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    tag: String? = null,
) {
    PlanteriorCard(
        modifier =
            modifier
                .fillMaxWidth()
                .then(tag?.let(Modifier::testTag) ?: Modifier)
                .then(if (error) Modifier.semantics { this.error(body) } else Modifier),
        containerColor =
            if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        ShareProse(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (error) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
        )
    }
}

@Composable
private fun ShareProse(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val spaceSize =
        remember(textMeasurer, style, density) {
            textMeasurer.measure(" ", style = style, softWrap = false, maxLines = 1).size
        }
    val spaceWidth = with(density) { spaceSize.width.toDp() }
    val lineHeight = with(density) { spaceSize.height.toDp() }
    Column(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                this.text = AnnotatedString(text)
            }
    ) {
        miniHomeShareProseLines(text).forEach { chunks ->
            if (chunks.isEmpty()) {
                Spacer(Modifier.height(lineHeight))
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spaceWidth),
                ) {
                    chunks.forEach { chunk ->
                        Text(
                            text = chunk,
                            style = style,
                            color = color,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
            }
        }
    }
}

internal fun miniHomeShareProseLines(text: String): List<List<String>> =
    text.split("\n", ignoreCase = false, limit = Int.MAX_VALUE).map(::miniHomeShareLineChunks)

private fun miniHomeShareLineChunks(line: String): List<String> {
    if (line.isEmpty()) return emptyList()
    val words = line.split(' ')
    return buildList {
        var index = 0
        while (index < words.size) {
            val phrase = ShareAuxiliaryPhrases.firstOrNull { candidate ->
                miniHomeSharePhraseMatches(words, index, candidate)
            }
            if (phrase == null) {
                add(words[index])
                index += 1
            } else {
                add(words.subList(index, index + phrase.size).joinToString(" "))
                index += phrase.size
            }
        }
    }
}

private fun miniHomeSharePhraseMatches(
    words: List<String>,
    start: Int,
    phrase: List<String>,
): Boolean {
    if (start + phrase.size > words.size) return false
    return phrase.indices.all { offset ->
        val word = words[start + offset]
        val expected = phrase[offset]
        if (offset == phrase.lastIndex) {
            word.trimEnd { character -> !character.isLetterOrDigit() } == expected
        } else {
            word == expected
        }
    }
}

private fun MiniHomeShareFailure.titleRes(): Int =
    when (this) {
        MiniHomeShareFailure.OFFLINE,
        MiniHomeShareFailure.DEADLINE -> R.string.mini_home_share_failure_offline_title
        else -> R.string.mini_home_share_failure_permanent_title
    }

private fun MiniHomeShareFailure.bodyRes(): Int =
    when (this) {
        MiniHomeShareFailure.OFFLINE -> R.string.mini_home_share_failure_offline_body
        MiniHomeShareFailure.DEADLINE -> R.string.mini_home_share_failure_deadline_body
        MiniHomeShareFailure.REVISION_CONFLICT -> R.string.mini_home_share_failure_conflict_body
        MiniHomeShareFailure.PERMISSION_DENIED -> R.string.mini_home_share_failure_permission_body
        MiniHomeShareFailure.INVALID_REQUEST,
        MiniHomeShareFailure.MALFORMED_RESPONSE -> R.string.mini_home_share_failure_permanent_body
    }

private fun MiniHomeShareFeedback.bodyRes(): Int =
    when (this) {
        MiniHomeShareFeedback.LINK_COPIED -> R.string.mini_home_share_feedback_copied
        MiniHomeShareFeedback.SHEET_OPENED -> R.string.mini_home_share_feedback_sheet_opened
        MiniHomeShareFeedback.SHEET_CANCELLED -> R.string.mini_home_share_feedback_sheet_cancelled
        MiniHomeShareFeedback.NO_TARGET -> R.string.mini_home_share_feedback_no_target
        MiniHomeShareFeedback.SHEET_FAILED -> R.string.mini_home_share_feedback_sheet_failed
    }

@Composable
private fun Modifier.action(tag: String): Modifier =
    fillMaxWidth().sizeIn(minHeight = 48.dp).testTag(tag)
