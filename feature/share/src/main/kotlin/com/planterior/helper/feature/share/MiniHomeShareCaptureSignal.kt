package com.planterior.helper.feature.share

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.Revision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * 하나의 캡처가 어떤 소유자와 확정 revision, 어떤 세대에 속하는지 가리키는 값이다.
 *
 * 계정이 바뀌거나 확정 구성이 갱신되면 세대가 올라가므로, 늦게 도착한 캡처가 새 상태에 섞이는 일을 이 값 하나로 막을 수 있다.
 */
data class MiniHomeShareCaptureToken(
    val owner: AccountId,
    val revision: Revision,
    val generation: Long,
) {
    init {
        require(generation >= 0) { "Capture generation cannot be negative" }
    }
}

/**
 * Compose가 실제로 레이어를 기록했다는 정확한 신호이다.
 *
 * 프레임 수를 세거나 시간을 기다리지 않는다. `GraphicsLayer.record`가 끝난 draw 단계에서만 [markRecorded]가 호출되고, 캡처는 그 신호를 받은
 * 뒤에만 진행한다.
 */
class MiniHomeShareRecordSignal {
    private val recorded = MutableStateFlow<MiniHomeShareCaptureToken?>(null)

    /** draw 단계에서 레이어 기록이 끝난 직후에만 호출한다. */
    fun markRecorded(token: MiniHomeShareCaptureToken) {
        recorded.value = token
    }

    fun isRecorded(token: MiniHomeShareCaptureToken): Boolean = recorded.value == token

    /** 확정 구성이 바뀌거나 다시 그려야 할 때 이전 기록을 무효화한다. */
    fun invalidate() {
        recorded.value = null
    }

    /**
     * 정확히 이 토큰이 기록될 때까지 중단한다.
     *
     * 다른 세대의 신호는 이 대기를 깨우지 않는다.
     */
    suspend fun awaitRecorded(token: MiniHomeShareCaptureToken): Boolean {
        recorded.first { it == token }
        return true
    }
}

/** 기록된 레이어에서 확정 규격 바이트를 만드는 경계이다. 테스트는 실제 draw 없이 이 경계를 대체한다. */
interface MiniHomeShareCaptureRecorder {
    suspend fun awaitRecorded(token: MiniHomeShareCaptureToken): Boolean

    suspend fun encode(token: MiniHomeShareCaptureToken): ByteArray
}
