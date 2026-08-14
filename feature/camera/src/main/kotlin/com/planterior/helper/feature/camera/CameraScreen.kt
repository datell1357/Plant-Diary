package com.planterior.helper.feature.camera

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.theme.PlanteriorBorderWidth
import com.planterior.helper.core.designsystem.theme.PlanteriorRadius
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme

private val MinimumTouchTarget = 48.dp
private val PreviewHeight = 380.dp
private val ShutterSize = 72.dp

object CameraTestTags {
    const val CAMERA = "camera:choose-camera"
    const val PICKER = "camera:choose-picker"
    const val DIRECT = "camera:direct"
    const val SETTINGS = "camera:settings"
    const val CAPTURE = "camera:capture"
    const val REPLACE = "camera:replace"
    const val RETAKE = "camera:retake"
    const val SUBMIT = "camera:submit"
    const val DISCLOSURE = "camera:disclosure"
    const val APPROVE = "camera:approve"
    const val CANCEL = "camera:cancel"
    const val PREVIEW = "camera:preview"
    const val PROCESSING = "camera:processing"
}

data class CameraPreviewImage(val bitmap: ImageBitmap, val rotationDegrees: Int)

/** 사진 선택부터 요청별 처리 고지까지의 제품 화면이다. */
@Composable
fun CameraScreen(
    state: CameraFlowState,
    preview: CameraPreviewImage?,
    onCamera: () -> Unit,
    onPicker: () -> Unit,
    onDirect: () -> Unit,
    onSettings: () -> Unit,
    onCapture: () -> Unit,
    onCloseCapture: () -> Unit,
    onReplace: () -> Unit,
    onRetake: () -> Unit,
    onSubmit: () -> Unit,
    onApprove: () -> Unit,
    onCancelDisclosure: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    cameraPreview: (@Composable (Modifier) -> Unit)? = null,
) {
    if (state is CameraFlowState.Capturing) {
        CaptureScreen(cameraPreview, onCapture, onCloseCapture, modifier)
        return
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(PlanteriorTheme.spacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.sizeIn(minHeight = MinimumTouchTarget),
                ) {
                    Text("이전")
                }
                Text(
                    text = "식물 촬영",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            when (state) {
                is CameraFlowState.Source -> SourceContent(state, onCamera, onPicker, onDirect)
                is CameraFlowState.PermissionBlocked ->
                    PermissionContent(state, onSettings, onPicker, onDirect)
                is CameraFlowState.Processing -> ProcessingContent()
                is CameraFlowState.Review ->
                    ReviewContent(state, preview, onReplace, onRetake, onSubmit)
                is CameraFlowState.Disclosure ->
                    DisclosureContent(state, preview, onApprove, onCancelDisclosure)
                is CameraFlowState.Submitted -> SubmittedContent()
                is CameraFlowState.Capturing -> Unit
            }
        }
    }
}

@Composable
private fun SourceContent(
    state: CameraFlowState.Source,
    onCamera: () -> Unit,
    onPicker: () -> Unit,
    onDirect: () -> Unit,
) {
    Text(
        "잎과 줄기가 선명하게 보이는 사진 한 장을 준비해 주세요.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.error?.let { ErrorNotice(it) }
    state.draft?.let {
        Text(
            "기존 사진은 새 사진이 준비될 때까지 안전하게 보관됩니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    PrimaryAction("카메라로 촬영", CameraTestTags.CAMERA, onCamera)
    SecondaryAction("사진 보관함에서 선택", CameraTestTags.PICKER, onPicker)
    TextAction("식물 이름을 직접 등록", CameraTestTags.DIRECT, onDirect)
}

@Composable
private fun PermissionContent(
    state: CameraFlowState.PermissionBlocked,
    onSettings: () -> Unit,
    onPicker: () -> Unit,
    onDirect: () -> Unit,
) {
    NoticeCard {
        Text("카메라 권한이 필요해요", style = MaterialTheme.typography.titleMedium)
        Text(
            if (state.permanentlyDenied) {
                "기기 설정에서 카메라 권한을 허용하거나 다른 방법을 선택해 주세요."
            } else {
                "권한 요청을 반복하지 않습니다. 설정 또는 다른 방법으로 계속할 수 있어요."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    PrimaryAction("기기 설정 열기", CameraTestTags.SETTINGS, onSettings)
    SecondaryAction("사진 보관함에서 선택", CameraTestTags.PICKER, onPicker)
    TextAction("식물 이름을 직접 등록", CameraTestTags.DIRECT, onDirect)
}

@Composable
private fun ProcessingContent() {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .height(PreviewHeight)
                .testTag(CameraTestTags.PROCESSING)
                .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("사진을 안전하게 확인하고 있어요", modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun ReviewContent(
    state: CameraFlowState.Review,
    preview: CameraPreviewImage?,
    onReplace: () -> Unit,
    onRetake: () -> Unit,
    onSubmit: () -> Unit,
) {
    Text("이 사진으로 식물을 알아볼까요?", style = MaterialTheme.typography.titleMedium)
    PhotoPreview(preview)
    state.error?.let { ErrorNotice(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium)) {
        OutlinedButton(
            onClick = onReplace,
            modifier =
                Modifier.weight(1f).height(MinimumTouchTarget).testTag(CameraTestTags.REPLACE),
        ) {
            Text("사진 변경")
        }
        OutlinedButton(
            onClick = onRetake,
            modifier =
                Modifier.weight(1f).height(MinimumTouchTarget).testTag(CameraTestTags.RETAKE),
        ) {
            Text("다시 촬영")
        }
    }
    PrimaryAction("식별 요청하기", CameraTestTags.SUBMIT, onSubmit)
}

@Composable
private fun DisclosureContent(
    state: CameraFlowState.Disclosure,
    preview: CameraPreviewImage?,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
) {
    PhotoPreview(preview)
    NoticeCard(Modifier.testTag(CameraTestTags.DISCLOSURE)) {
        Text("사진 처리 안내", style = MaterialTheme.typography.titleMedium)
        Text(
            "이 사진은 ${state.disclosure.purpose}을 위해 국외 이미지 분석 서비스로 전송됩니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "분석 원본은 처리 완료 또는 실패 후 ${state.disclosure.originalRetentionHours}시간 이내 삭제합니다. 도감 대표 사진은 별도로 선택한 경우에만 저장합니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    PrimaryAction("동의하고 식별 요청", CameraTestTags.APPROVE, onApprove)
    SecondaryAction("취소하고 사진 확인으로", CameraTestTags.CANCEL, onCancel)
}

@Composable
private fun SubmittedContent() {
    NoticeCard {
        Text("식별 요청을 준비했어요", style = MaterialTheme.typography.titleMedium)
        Text("승인된 사진 한 장만 다음 식별 단계로 전달했습니다.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PhotoPreview(preview: CameraPreviewImage?) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(PreviewHeight)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(PlanteriorRadius.Card),
                )
                .clipToBoundsCompat()
                .testTag(CameraTestTags.PREVIEW)
                .semantics { contentDescription = "선택한 식물 사진" },
        contentAlignment = Alignment.Center,
    ) {
        if (preview == null) {
            CircularProgressIndicator()
        } else {
            Image(
                bitmap = preview.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier.fillMaxSize()
                        .graphicsLayer(rotationZ = preview.rotationDegrees.toFloat()),
            )
        }
    }
}

@Composable
private fun CaptureScreen(
    cameraPreview: (@Composable (Modifier) -> Unit)?,
    onCapture: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        cameraPreview?.invoke(Modifier.fillMaxSize())
        TextButton(
            onClick = onClose,
            modifier =
                Modifier.align(Alignment.TopStart)
                    .padding(16.dp)
                    .sizeIn(minHeight = MinimumTouchTarget),
        ) {
            Text("닫기", color = androidx.compose.ui.graphics.Color.White)
        }
        Text(
            "잎 전체가 화면 안에 들어오도록 촬영해 주세요",
            color = androidx.compose.ui.graphics.Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(72.dp, 24.dp),
        )
        Button(
            onClick = onCapture,
            shape = CircleShape,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .size(ShutterSize)
                    .testTag(CameraTestTags.CAPTURE),
        ) {
            Text("촬영")
        }
    }
}

@Composable
private fun PrimaryAction(label: String, tag: String, action: () -> Unit) {
    Button(
        onClick = action,
        modifier = Modifier.fillMaxWidth().height(MinimumTouchTarget).testTag(tag),
    ) {
        Text(label)
    }
}

@Composable
private fun SecondaryAction(label: String, tag: String, action: () -> Unit) {
    OutlinedButton(
        onClick = action,
        modifier = Modifier.fillMaxWidth().height(MinimumTouchTarget).testTag(tag),
    ) {
        Text(label)
    }
}

@Composable
private fun TextAction(label: String, tag: String, action: () -> Unit) {
    TextButton(
        onClick = action,
        modifier = Modifier.fillMaxWidth().height(MinimumTouchTarget).testTag(tag),
    ) {
        Text(label)
    }
}

@Composable
private fun NoticeCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(PlanteriorRadius.Medium),
                )
                .border(
                    PlanteriorBorderWidth,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(PlanteriorRadius.Medium),
                )
                .padding(PlanteriorTheme.spacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
    ) {
        content()
    }
}

@Composable
private fun ErrorNotice(error: PhotoError) {
    Text(
        text = error.userMessage(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier =
            Modifier.fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.errorContainer,
                    RoundedCornerShape(PlanteriorRadius.Small),
                )
                .padding(PlanteriorTheme.spacing.large),
    )
}

private fun PhotoError.userMessage(): String =
    when (this) {
        PhotoError.MissingUri -> "사진을 찾을 수 없어요. 다른 사진을 선택해 주세요."
        PhotoError.Unreadable -> "이 사진을 읽을 수 없어요. 다른 방법으로 계속해 주세요."
        PhotoError.Corrupt -> "손상된 사진이에요. 다른 사진을 선택해 주세요."
        PhotoError.UnsupportedMime -> "JPEG, PNG, WebP 또는 HEIF 사진을 사용해 주세요."
        PhotoError.TooLarge -> "사진은 20MiB 이하만 사용할 수 있어요."
        PhotoError.DimensionsOutOfRange -> "사진의 가로와 세로는 각각 256~8192px여야 해요."
        PhotoError.CaptureFailed -> "촬영을 완료하지 못했어요. 다시 촬영하거나 보관함에서 선택해 주세요."
        PhotoError.SubmissionFailed -> "식별 요청을 준비하지 못했어요. 사진은 그대로 보관했습니다."
    }

private fun Modifier.clipToBoundsCompat(): Modifier = graphicsLayer(clip = true)
