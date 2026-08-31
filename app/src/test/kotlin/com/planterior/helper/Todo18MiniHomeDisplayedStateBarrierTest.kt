package com.planterior.helper

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.MiniHomeLayout
import com.planterior.helper.feature.minihome.MiniHomeLoadIdentity
import com.planterior.helper.feature.minihome.MiniHomeUiState
import com.planterior.helper.minihome.Todo18MiniHomeLoadId
import com.planterior.helper.minihome.Todo18MiniHomeLoadProgress
import com.planterior.helper.minihome.Todo18MiniHomePerLoadProgress
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18MiniHomeDisplayedStateBarrierTest {
    @Test
    fun `mini-home-loaded boundary alone cannot satisfy displayed barrier`() {
        val barrier = barrier(sequenceFloor = 4L, loadId = 8L)

        assertFalse(
            barrier.accepts(
                event(sequence = 5L, owner = ACCOUNT),
                progress(loadId = 8L, lastStage = "remote-load-returned"),
            )
        )
    }

    @Test
    fun `prior Viewing at the sequence floor is rejected`() {
        val barrier = barrier(sequenceFloor = 5L, loadId = 8L)

        assertFalse(
            barrier.accepts(
                event(sequence = 5L, owner = ACCOUNT),
                progress(loadId = 8L),
            )
        )
    }

    @Test
    fun `Viewing with the wrong account is rejected`() {
        val barrier = barrier(sequenceFloor = 5L, loadId = 8L)

        assertFalse(
            barrier.accepts(
                event(sequence = 6L, owner = AccountId("other-account")),
                progress(loadId = 8L),
            )
        )
    }

    @Test
    fun `Viewing with a different load identity is rejected`() {
        val barrier = barrier(sequenceFloor = 5L, loadId = 8L)

        assertFalse(
            barrier.accepts(
                event(sequence = 6L, owner = ACCOUNT, loadId = 8L),
                progress(loadId = 9L),
            )
        )
    }

    @Test
    fun `Viewing from an unrelated rendered load is rejected`() {
        val barrier = barrier(sequenceFloor = 5L, loadId = 8L)

        assertFalse(
            barrier.accepts(
                event(sequence = 6L, owner = ACCOUNT, loadId = 9L),
                progress(loadId = 8L),
            )
        )
    }

    @Test
    fun `new exact Viewing after terminal load satisfies displayed barrier`() {
        val barrier = barrier(sequenceFloor = 5L, loadId = 8L)

        assertTrue(
            barrier.accepts(
                event(sequence = 6L, owner = ACCOUNT, loadId = 8L),
                progress(loadId = 8L),
            )
        )
    }

    private fun barrier(sequenceFloor: Long, loadId: Long) =
        Todo18MiniHomeDisplayedStateBarrier(
            sequenceFloor = sequenceFloor,
            loadIdentity = Todo18MiniHomeLoadIdentity(ACCOUNT, loadId),
        )

    private fun event(sequence: Long, owner: AccountId, loadId: Long? = 8L) =
        Todo18MiniHomeStateEvent(sequence, viewing(owner, loadId))

    private fun viewing(owner: AccountId, loadId: Long?) =
        MiniHomeUiState.Viewing(
            committed =
                MiniHomeLayout(
                    id = MiniHomeId("todo18-home"),
                    name = "Todo18 room",
                    placements = emptyList(),
                    revision = Revision(1L),
                    updatedAt = Instant.EPOCH,
                ),
            plants = emptyList(),
            decorations = emptyList(),
            stale = false,
            owner = owner,
            loadIdentity = loadId?.let(::MiniHomeLoadIdentity),
        )

    private fun progress(
        loadId: Long,
        lastStage: String = "terminal-ready",
    ): Todo18MiniHomeLoadProgress {
        val stages =
            if (lastStage == "terminal-ready") {
                listOf("load-entered", "remote-load-entered", "remote-load-returned", lastStage)
            } else {
                listOf("load-entered", "remote-load-entered", lastStage)
            }
        return Todo18MiniHomeLoadProgress(
            activeStage = lastStage.takeUnless { it == "terminal-ready" },
            lastReachedStage = lastStage,
            reachedStages = stages,
            recorderFailures = emptyList(),
            loads =
                listOf(
                    Todo18MiniHomePerLoadProgress(
                        loadId = Todo18MiniHomeLoadId(loadId),
                        activeStage = lastStage.takeUnless { it == "terminal-ready" },
                        lastReachedStage = lastStage,
                        reachedStages = stages,
                        publicationReadIds = emptyList(),
                    )
                ),
        )
    }

    private companion object {
        val ACCOUNT = AccountId("todo18-integrated-owner")
    }
}
