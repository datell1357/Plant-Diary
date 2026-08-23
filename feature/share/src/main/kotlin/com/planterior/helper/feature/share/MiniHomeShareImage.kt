package com.planterior.helper.feature.share

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.Revision
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 공유 이미지의 고정 규격이다.
 *
 * 미니홈 방의 정규 종횡비 1.2를 그대로 지켜 어떤 기기에서 캡처해도 같은 픽셀 규격으로 내보낸다.
 */
object MiniHomeShareImage {
    const val WIDTH_PX = 1200
    const val HEIGHT_PX = 1000

    /** 파일 이름에는 확정 revision만 담는다. 소유자·홈 ID는 절대 넣지 않는다. */
    fun fileName(revision: Revision): String = "mini-home-r${revision.value}.png"
}

/**
 * 캡처된 비트맵을 정확히 1200x1000 PNG로 인코딩한다.
 *
 * PNG는 손실 없는 형식이고 Android 인코더는 EXIF·텍스트 chunk를 만들지 않으므로 결과 바이트에는 촬영 정보, 경로, 식별자가 남지 않는다.
 */
object MiniHomeShareImageEncoder {
    /**
     * 캡처가 정규 방 비율인지 확인한다.
     *
     * 임의 비율을 억지로 1200x1000으로 늘리면 공유 이미지가 화면에서 본 방과 다른 모양이 된다. 밀도 반올림으로 1px 어긋나는 경우만 허용하고 그 밖은 거부해
     * 조용한 왜곡 대신 실패를 드러낸다.
     */
    fun requireCanonicalCapture(width: Int, height: Int) {
        require(width > 0 && height > 0) {
            "Share capture must have a positive size but was ${width}x$height"
        }
        val expectedWidth =
            Math.round(
                height.toDouble() * MiniHomeShareImage.WIDTH_PX / MiniHomeShareImage.HEIGHT_PX
            )
        require(Math.abs(expectedWidth - width) <= ROUNDING_SLACK_PX) {
            "Share capture must keep the canonical 1.2 room ratio but was ${width}x$height"
        }
    }

    fun encode(captured: ImageBitmap): ByteArray {
        val source = captured.asAndroidBitmap()
        requireCanonicalCapture(source.width, source.height)
        val exact =
            source.width == MiniHomeShareImage.WIDTH_PX &&
                source.height == MiniHomeShareImage.HEIGHT_PX
        // 호출자가 넘긴 비트맵은 호출자 소유이므로 절대 recycle 하지 않는다.
        val scaled =
            if (exact) source
            else source.scale(MiniHomeShareImage.WIDTH_PX, MiniHomeShareImage.HEIGHT_PX)
        return try {
            ByteArrayOutputStream().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Share image could not be encoded"
                }
                output.toByteArray()
            }
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    /** 밀도 반올림으로 생기는 최대 오차이다. */
    private const val ROUNDING_SLACK_PX = 1
}

/** 내보낼 이미지의 요청 값이다. 확정 revision에서만 파생된다. */
data class MiniHomeShareExportRequest(
    val revision: Revision,
    val fileName: String,
    val widthPx: Int,
    val heightPx: Int,
) {
    companion object {
        fun of(target: MiniHomeShareTarget): MiniHomeShareExportRequest =
            MiniHomeShareExportRequest(
                revision = target.committed.revision,
                fileName = MiniHomeShareImage.fileName(target.committed.revision),
                widthPx = MiniHomeShareImage.WIDTH_PX,
                heightPx = MiniHomeShareImage.HEIGHT_PX,
            )
    }
}

/**
 * 앱 전용 `cache/share/`에만 이미지를 두는 저장소이다.
 *
 * 항상 최신 한 개만 남겨 URI 권한이 필요한 파일 외에는 남기지 않는다. 계정 전환·로그아웃에서는 [clear]로 전부 지운다.
 */
class MiniHomeShareImageStore(private val context: Context) : MiniHomeShareImageSink {
    private val directory: File
        get() = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

    override fun write(owner: AccountId, revision: Revision, bytes: ByteArray): Uri {
        require(owner.value.isNotEmpty())
        clear()
        val file = File(directory, MiniHomeShareImage.fileName(revision))
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    override fun clear() {
        directory.listFiles().orEmpty().forEach { it.delete() }
    }

    private companion object {
        const val DIRECTORY = "share"
    }
}
