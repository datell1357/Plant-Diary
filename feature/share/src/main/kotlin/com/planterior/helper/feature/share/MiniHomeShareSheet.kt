package com.planterior.helper.feature.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import com.planterior.helper.core.model.ClientProductEvent
import com.planterior.helper.core.model.ProductEventRecorder

/**
 * 시스템 공유 시트로 넘길 intent를 만든다.
 *
 * 이미지 URI에는 읽기 권한만 부여하고, 쓰기·영구·prefix 권한은 절대 주지 않는다.
 */
object MiniHomeShareSheet {
    const val IMAGE_MIME = "image/png"
    const val TEXT_MIME = "text/plain"

    fun imageIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = IMAGE_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            // MIME은 ContentResolver 조회 결과가 아니라 우리가 인코딩한 형식으로 고정한다.
            clipData =
                ClipData(
                    ClipDescription(null, arrayOf(IMAGE_MIME)),
                    ClipData.Item(uri),
                )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun linkIntent(url: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = TEXT_MIME
            putExtra(Intent.EXTRA_TEXT, url)
        }

    /** chooser는 payload intent를 감싸기만 하고 URL을 자기 extras로 복제하지 않는다. */
    fun chooser(context: Context, payload: Intent): Intent =
        Intent.createChooser(payload, context.getString(R.string.mini_home_share_chooser_title))

    fun hasTarget(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null
}

/** Opens a chooser only when resolvable and records only a successful Android handoff. */
class MiniHomeShareSheetHandoff(
    private val hasTarget: (Intent) -> Boolean,
    private val launch: (Intent) -> Unit,
    private val productEventRecorder: ProductEventRecorder = ProductEventRecorder {},
) {
    fun open(payload: Intent, chooser: Intent = payload): MiniHomeShareSheetOutcome {
        if (!hasTarget(payload)) return MiniHomeShareSheetOutcome.NoTarget
        return try {
            launch(chooser)
            productEventRecorder.record(ClientProductEvent.MINI_HOME_SHARE_SHEET_OPENED)
            MiniHomeShareSheetOutcome.Opened
        } catch (_: Exception) {
            MiniHomeShareSheetOutcome.Failed
        }
    }
}

/**
 * 링크를 클립보드에 정확히 복사한다.
 *
 * API 33부터는 시스템이 자체 복사 안내를 띄우므로 앱이 같은 내용을 중복해서 보여주지 않는다. [copy]는 앱이 자체 피드백을 보여야 하는지를 돌려준다.
 */
class MiniHomeShareClipboard(private val context: Context) {
    fun copy(url: String): Boolean {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip =
            ClipData.newPlainText(
                context.getString(R.string.mini_home_share_clip_label),
                url,
            )
        // API 33+의 민감 클립 플래그이다. 낮은 API에서는 무시되므로 상수 참조 없이 값만 쓴다.
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    }

    private companion object {
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
