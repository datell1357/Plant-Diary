package com.planterior.helper.feature.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.component.PlanteriorCard
import com.planterior.helper.core.designsystem.component.PlanteriorScreenScaffold
import com.planterior.helper.core.designsystem.component.PlanteriorStatusCard
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.feature.minihome.MiniHomePhotoLoader
import com.planterior.helper.feature.minihome.PlaceholderMiniHomePhotoLoader
import java.time.ZoneId

object MiniHomeShareTestTags {
    const val SCREEN = "mini-home-share:screen"
    const val BACK = "mini-home-share:back"
    const val LOADING = "mini-home-share:loading"
    const val PRIVACY_NOTICE = "mini-home-share:privacy-notice"
    const val REVISION = "mini-home-share:revision"
    const val PREVIEW = "mini-home-share:preview"
    const val CAPTURE = "mini-home-share:capture"
    const val CAPTURE_PLACEMENT_PREFIX = "mini-home-share:capture-placement:"
    const val RENDER_PROGRESS = "mini-home-share:render-progress"
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
    PlanteriorScreenScaffold(
        title = stringResource(R.string.mini_home_share_title),
        modifier = modifier.testTag(MiniHomeShareTestTags.SCREEN),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.action(MiniHomeShareTestTags.BACK)) {
            Text(stringResource(R.string.mini_home_share_back))
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
        ) {
            when (state) {
                is MiniHomeShareUiState.Loading -> {
                    val loadingLabel = stringResource(R.string.mini_home_share_loading)
                    CircularProgressIndicator(
                        modifier =
                            Modifier.align(Alignment.CenterHorizontally)
                                .testTag(MiniHomeShareTestTags.LOADING)
                                .semantics { contentDescription = loadingLabel }
                    )
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
                is MiniHomeShareUiState.NoTarget -> {
                    PrivacyNotice()
                    PlanteriorStatusCard(
                        title = stringResource(R.string.mini_home_share_no_target_title),
                        body = stringResource(R.string.mini_home_share_no_target_body),
                        tag = MiniHomeShareTestTags.NO_TARGET,
                    )
                }
                MiniHomeShareUiState.Forbidden ->
                    PlanteriorStatusCard(
                        title = stringResource(R.string.mini_home_share_forbidden_title),
                        body = stringResource(R.string.mini_home_share_forbidden_body),
                        error = true,
                        tag = MiniHomeShareTestTags.ERROR,
                    )
                MiniHomeShareUiState.Error -> {
                    PlanteriorStatusCard(
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
    PrivacyNotice()
    PlanteriorStatusCard(
        title = stringResource(R.string.mini_home_share_revision_title),
        body =
            stringResource(
                R.string.mini_home_share_revision_body,
                state.target.committed.revision.value.toString(),
            ),
        tag = MiniHomeShareTestTags.REVISION,
    )
    when (state.render) {
        MiniHomeShareRenderState.Rendering ->
            PlanteriorCard(modifier = Modifier.testTag(MiniHomeShareTestTags.RENDER_PROGRESS)) {
                Text(
                    stringResource(R.string.mini_home_share_render_progress),
                    style = MaterialTheme.typography.titleMedium,
                )
                CircularProgressIndicator(
                    modifier =
                        Modifier.align(Alignment.CenterHorizontally)
                            .padding(top = PlanteriorTheme.spacing.medium)
                )
            }
        MiniHomeShareRenderState.Failed -> {
            PlanteriorStatusCard(
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
        MiniHomeShareRenderState.Ready -> Unit
    }
    if (state.render != MiniHomeShareRenderState.Failed) {
        MiniHomeSharePreview(
            target = state.target,
            modifier = Modifier.testTag(MiniHomeShareTestTags.PREVIEW),
            photoLoader = photoLoader,
            handle = captureHandle,
            captureToken = captureToken,
            contentDescription = stringResource(R.string.mini_home_share_preview_description),
        )
    }
    if (state.render == MiniHomeShareRenderState.Ready) {
        Button(
            onClick = onShareImage,
            modifier = Modifier.action(MiniHomeShareTestTags.IMAGE_SHARE),
        ) {
            Text(stringResource(R.string.mini_home_share_image_action))
        }
    }
    LinkSection(
        link = state.link,
        onCreateLink = onCreateLink,
        onCopyLink = onCopyLink,
        onShareLink = onShareLink,
        onRevokeLink = onRevokeLink,
        showsInAppCopyFeedback = showsInAppCopyFeedback,
        copied = state.feedback == MiniHomeShareFeedback.LINK_COPIED,
        zone = zone,
    )
    state.feedback?.let { feedback ->
        if (feedback != MiniHomeShareFeedback.LINK_COPIED || showsInAppCopyFeedback) {
            PlanteriorStatusCard(
                title = stringResource(feedback.titleRes()),
                body = stringResource(feedback.bodyRes()),
                error = feedback.error,
                tag = MiniHomeShareTestTags.FEEDBACK,
            )
        }
    }
}

@Composable
private fun LinkSection(
    link: MiniHomeShareLinkState,
    onCreateLink: () -> Unit,
    onCopyLink: () -> Unit,
    onShareLink: () -> Unit,
    onRevokeLink: () -> Unit,
    showsInAppCopyFeedback: Boolean,
    copied: Boolean,
    zone: ZoneId,
) {
    PlanteriorCard {
        Text(
            stringResource(R.string.mini_home_share_link_section),
            style = MaterialTheme.typography.titleMedium,
        )
        when (link) {
            MiniHomeShareLinkState.Idle ->
                Text(
                    stringResource(R.string.mini_home_share_link_idle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
                )
            MiniHomeShareLinkState.Generating ->
                Text(
                    stringResource(R.string.mini_home_share_link_generating),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(top = PlanteriorTheme.spacing.medium)
                            .testTag(MiniHomeShareTestTags.LINK_GENERATING),
                )
            is MiniHomeShareLinkState.Active ->
                Text(
                    stringResource(R.string.mini_home_share_link_active_title),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = PlanteriorTheme.spacing.medium),
                )
            is MiniHomeShareLinkState.Failed -> Unit
            MiniHomeShareLinkState.Revoked -> Unit
        }
    }
    when (link) {
        MiniHomeShareLinkState.Idle ->
            Button(
                onClick = onCreateLink,
                modifier = Modifier.action(MiniHomeShareTestTags.LINK_CREATE),
            ) {
                Text(stringResource(R.string.mini_home_share_link_create))
            }
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
                Text(
                    miniHomeShareExpiryText(link.link.expiresAt, zone),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(top = PlanteriorTheme.spacing.medium)
                            .testTag(MiniHomeShareTestTags.LINK_EXPIRY),
                )
            }
            val copiedState = stringResource(R.string.mini_home_share_link_copied_state)
            OutlinedButton(
                onClick = onCopyLink,
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
                modifier = Modifier.action(MiniHomeShareTestTags.LINK_SHARE),
            ) {
                Text(stringResource(R.string.mini_home_share_link_share))
            }
            link.revokeFailure?.let {
                PlanteriorStatusCard(
                    title = stringResource(R.string.mini_home_share_failure_permanent_title),
                    body = stringResource(R.string.mini_home_share_revoke_failure),
                    error = true,
                    tag = MiniHomeShareTestTags.LINK_FAILURE,
                )
            }
            OutlinedButton(
                onClick = onRevokeLink,
                enabled = !link.revoking,
                modifier = Modifier.action(MiniHomeShareTestTags.LINK_REVOKE),
            ) {
                Text(
                    stringResource(
                        if (link.revoking) R.string.mini_home_share_link_revoking
                        else R.string.mini_home_share_link_revoke
                    )
                )
            }
        }
        is MiniHomeShareLinkState.Failed -> {
            PlanteriorStatusCard(
                title = stringResource(link.failure.titleRes()),
                body = stringResource(link.failure.bodyRes()),
                error = true,
                tag = MiniHomeShareTestTags.LINK_FAILURE,
            )
            Button(
                onClick = onCreateLink,
                modifier = Modifier.action(MiniHomeShareTestTags.LINK_CREATE),
            ) {
                Text(stringResource(R.string.mini_home_share_link_create))
            }
        }
        MiniHomeShareLinkState.Revoked -> {
            PlanteriorStatusCard(
                title = stringResource(R.string.mini_home_share_link_revoked_title),
                body = stringResource(R.string.mini_home_share_link_revoked_body),
                tag = MiniHomeShareTestTags.LINK_REVOKED,
            )
            Button(
                onClick = onCreateLink,
                modifier = Modifier.action(MiniHomeShareTestTags.LINK_CREATE),
            ) {
                Text(stringResource(R.string.mini_home_share_link_create))
            }
        }
    }
}

@Composable
private fun PrivacyNotice() {
    PlanteriorStatusCard(
        title = stringResource(R.string.mini_home_share_notice_title),
        body = stringResource(R.string.mini_home_share_notice_body),
        tag = MiniHomeShareTestTags.PRIVACY_NOTICE,
    )
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

private fun MiniHomeShareFeedback.titleRes(): Int =
    when (this) {
        MiniHomeShareFeedback.LINK_COPIED -> R.string.mini_home_share_feedback_copied
        MiniHomeShareFeedback.SHEET_OPENED -> R.string.mini_home_share_feedback_sheet_opened
        MiniHomeShareFeedback.SHEET_CANCELLED -> R.string.mini_home_share_feedback_sheet_cancelled
        MiniHomeShareFeedback.NO_TARGET -> R.string.mini_home_share_feedback_no_target
        MiniHomeShareFeedback.SHEET_FAILED -> R.string.mini_home_share_feedback_sheet_failed
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
