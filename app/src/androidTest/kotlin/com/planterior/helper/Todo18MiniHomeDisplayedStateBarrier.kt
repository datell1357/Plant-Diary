package com.planterior.helper

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.minihome.MiniHomeUiState
import com.planterior.helper.minihome.Todo18MiniHomeLoadProgress

internal data class Todo18MiniHomeLoadIdentity(
    val accountId: AccountId,
    val loadId: Long,
) {
    init {
        require(loadId > 0L)
    }
}

internal data class Todo18MiniHomeDisplayedStateBarrier(
    val sequenceFloor: Long,
    val loadIdentity: Todo18MiniHomeLoadIdentity,
) {
    init {
        require(sequenceFloor >= 0L)
    }

    fun accepts(
        event: Todo18MiniHomeStateEvent,
        progress: Todo18MiniHomeLoadProgress,
    ): Boolean {
        val viewing = event.state as? MiniHomeUiState.Viewing ?: return false
        val load =
            progress.loads.singleOrNull { it.loadId.value == loadIdentity.loadId } ?: return false
        return progress.valid &&
            load.lastReachedStage == "terminal-ready" &&
            event.sequence > sequenceFloor &&
            event.loadIdentity?.value == loadIdentity.loadId &&
            viewing.owner == loadIdentity.accountId
    }
}
