package com.planterior.helper.feature.share

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.planterior.helper.feature.minihome.MiniHomeCommittedRoom
import com.planterior.helper.feature.minihome.MiniHomePhotoLoader
import com.planterior.helper.feature.minihome.MiniHomeRoomRenderer
import com.planterior.helper.feature.minihome.PlaceholderMiniHomePhotoLoader

/**
 * 캡처 레이어와 그 기록 신호를 함께 들고 다니는 손잡이이다.
 *
 * 캡처는 [MiniHomeShareRecordSignal]이 정확히 같은 토큰으로 기록됐다고 알린 뒤에만 진행한다. 프레임 수를 세거나 시간을 기다리지 않는다.
 */
@Stable
class MiniHomeShareCaptureHandle(
    val layer: GraphicsLayer,
    val signal: MiniHomeShareRecordSignal = MiniHomeShareRecordSignal(),
) : MiniHomeShareCaptureRecorder {
    override suspend fun awaitRecorded(token: MiniHomeShareCaptureToken): Boolean =
        signal.awaitRecorded(token)

    override suspend fun encode(token: MiniHomeShareCaptureToken): ByteArray {
        check(signal.isRecorded(token)) { "The share layer was not recorded for this capture" }
        return MiniHomeShareImageEncoder.encode(layer.toImageBitmap())
    }
}

@Composable
fun rememberMiniHomeShareCaptureHandle(): MiniHomeShareCaptureHandle {
    val layer = rememberGraphicsLayer()
    return remember(layer) { MiniHomeShareCaptureHandle(layer) }
}

/**
 * 확정 구성만 그리는 캡처 표면이다.
 *
 * 화면에 보이는 이 노드를 [GraphicsLayer]로 기록해 그대로 내보내므로 미리보기와 내보낸 이미지가 같은 픽셀을 담는다. 편집 중 draft는 이 경로에 도달하지
 * 않는다. 기록이 실제로 끝난 draw 단계에서만 신호를 보낸다.
 */
@Composable
fun MiniHomeShareCaptureSurface(
    target: MiniHomeShareTarget,
    modifier: Modifier = Modifier,
    photoLoader: MiniHomePhotoLoader = PlaceholderMiniHomePhotoLoader,
    handle: MiniHomeShareCaptureHandle? = null,
    captureToken: MiniHomeShareCaptureToken? = null,
    contentDescription: String? = null,
) {
    MiniHomeCommittedRoom(
        layout = target.committed,
        plants = target.plants,
        decorations = target.decorations,
        photoLoader = photoLoader,
        placementTagPrefix = MiniHomeShareTestTags.CAPTURE_PLACEMENT_PREFIX,
        modifier =
            modifier
                .testTag(MiniHomeShareTestTags.CAPTURE)
                .then(
                    if (handle == null) {
                        Modifier
                    } else {
                        Modifier.drawWithContent {
                            handle.layer.record { this@drawWithContent.drawContent() }
                            drawLayer(handle.layer)
                            // 기록이 끝난 지금에야 캡처가 안전하다는 사실을 알린다.
                            captureToken?.let(handle.signal::markRecorded)
                        }
                    }
                )
                .then(
                    contentDescription?.let { description ->
                        Modifier.semantics { this.contentDescription = description }
                    } ?: Modifier
                ),
    )
}

/** 미리보기 크기의 캡처 표면이다. 정규 1.2 비율을 유지한다. */
@Composable
fun MiniHomeSharePreview(
    target: MiniHomeShareTarget,
    modifier: Modifier = Modifier,
    photoLoader: MiniHomePhotoLoader = PlaceholderMiniHomePhotoLoader,
    handle: MiniHomeShareCaptureHandle? = null,
    captureToken: MiniHomeShareCaptureToken? = null,
    contentDescription: String? = null,
) {
    MiniHomeShareCaptureSurface(
        target = target,
        modifier = modifier.fillMaxWidth().aspectRatio(MiniHomeRoomRenderer.ASPECT_RATIO),
        photoLoader = photoLoader,
        handle = handle,
        captureToken = captureToken,
        contentDescription = contentDescription,
    )
}
