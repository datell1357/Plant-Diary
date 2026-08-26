package com.planterior.helper.feature.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ClientProductEvent
import com.planterior.helper.core.model.ProductEventRecorder
import com.planterior.helper.core.model.Revision
import kotlinx.coroutines.CancellationException

/** 공유 이미지를 담아 두는 앱 전용 저장소 경계이다. */
interface MiniHomeShareImageSink {
    fun write(owner: AccountId, revision: Revision, bytes: ByteArray): Uri

    fun clear()
}

sealed interface MiniHomeShareExportOutcome {
    /** 시트를 실제로 열었다. 결과는 Activity Result 콜백이 알려준다. */
    data object Launched : MiniHomeShareExportOutcome

    /** 소유자나 확정 revision이 바뀌어 캡처가 낡았다. 파일을 지우고 아무것도 열지 않았다. */
    data object Stale : MiniHomeShareExportOutcome

    data object NoTarget : MiniHomeShareExportOutcome

    data object Failed : MiniHomeShareExportOutcome
}

/**
 * 캡처한 이미지를 시트로 넘기는 과정을 한 곳에 모은 흐름이다.
 *
 * 중단 지점마다, 그리고 파일을 쓰기 직전과 직후, 시트를 열기 직전에 다시 현재 소유자와 revision을 확인한다. 한 번이라도 어긋나면 남은 파일을 지우고 어떤 시트도
 * 열지 않는다. 계정 전환이 진행 중인 캡처를 가로채 다른 계정의 방을 내보내는 일을 이 규칙이 막는다.
 */
class MiniHomeShareImageExporter(
    private val sink: MiniHomeShareImageSink,
    private val recorder: MiniHomeShareCaptureRecorder,
    private val isCurrent: (MiniHomeShareCaptureToken) -> Boolean,
    private val hasTarget: (Uri) -> Boolean,
    private val launch: (Intent) -> Unit,
    private val onWritten: () -> Unit = {},
    private val productEventRecorder: ProductEventRecorder = ProductEventRecorder {},
) {
    suspend fun export(token: MiniHomeShareCaptureToken): MiniHomeShareExportOutcome {
        if (!isCurrent(token)) return abandon()

        // 1) 레이어가 실제로 기록될 때까지 기다린다.
        val recorded =
            try {
                recorder.awaitRecorded(token)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return abandon(MiniHomeShareExportOutcome.Failed)
            }
        if (!recorded || !isCurrent(token)) return abandon()

        // 2) 기록된 레이어를 확정 규격 바이트로 만든다.
        val bytes =
            try {
                recorder.encode(token)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return abandon(MiniHomeShareExportOutcome.Failed)
            }
        if (!isCurrent(token)) return abandon()

        // 3) 파일을 쓰기 직전과 직후 모두 확인한다.
        val uri =
            try {
                sink.write(token.owner, token.revision, bytes)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return abandon(MiniHomeShareExportOutcome.Failed)
            }
        onWritten()
        if (!isCurrent(token)) return abandon()

        // 4) 열 수 있는 대상이 없으면 권한이 걸린 파일을 남기지 않는다.
        val intent = MiniHomeShareSheet.imageIntent(uri)
        if (!hasTarget(uri)) return abandon(MiniHomeShareExportOutcome.NoTarget)
        if (!isCurrent(token)) return abandon()

        return try {
            launch(intent)
            productEventRecorder.record(ClientProductEvent.MINI_HOME_SHARE_SHEET_OPENED)
            MiniHomeShareExportOutcome.Launched
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            abandon(MiniHomeShareExportOutcome.Failed)
        }
    }

    private fun abandon(
        outcome: MiniHomeShareExportOutcome = MiniHomeShareExportOutcome.Stale
    ): MiniHomeShareExportOutcome {
        sink.clear()
        return outcome
    }
}

/**
 * 시스템 공유 시트의 결과 코드를 사용자에게 보여줄 상태로 옮긴다.
 *
 * chooser는 사용자가 무엇을 골랐는지도, 실제로 전달됐는지도 알려주지 않는다. 취소만 확실히 구분할 수 있으므로 취소는 중립 상태로, 나머지는 전부 "시트를 열었다"로만
 * 옮긴다. 어떤 코드도 전달 성공을 뜻하지 않는다.
 */
fun miniHomeShareSheetOutcome(resultCode: Int): MiniHomeShareSheetOutcome =
    when (resultCode) {
        Activity.RESULT_CANCELED -> MiniHomeShareSheetOutcome.Cancelled
        else -> MiniHomeShareSheetOutcome.Opened
    }
