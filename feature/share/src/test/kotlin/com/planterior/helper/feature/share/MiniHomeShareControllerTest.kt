package com.planterior.helper.feature.share

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.MiniHomeAuthOwnership
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MiniHomeShareControllerTest {
    @Test
    fun `loading reads the authoritative committed layout and never a draft`() = runTest {
        val repository = FakeShareRepository()
        val controller = controller(repository)

        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

        val ready = controller.state.value as MiniHomeShareUiState.Ready
        assertEquals(MiniHomeShareFixtures.layout(7), ready.target.committed)
        assertEquals(1, repository.loadCount)
    }

    // 7) 저장된 revision이 있으면 배치가 비어 있어도 공유할 수 있다

    @Test
    fun `a saved revision with zero placements is still shareable`() = runTest {
        val repository =
            FakeShareRepository(
                loadResult =
                    MiniHomeShareLoadResult.Ready(MiniHomeShareFixtures.target(1, placements = 0))
            )
        val controller = controller(repository)

        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

        val ready = controller.state.value as MiniHomeShareUiState.Ready
        assertEquals(Revision(1), ready.target.committed.revision)
        assertTrue(ready.target.committed.placements.isEmpty())
    }

    @Test
    fun `an unsaved mini home at revision zero reports no share target`() = runTest {
        val repository =
            FakeShareRepository(
                loadResult =
                    MiniHomeShareLoadResult.Ready(MiniHomeShareFixtures.target(0, placements = 0))
            )
        val controller = controller(repository)

        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

        assertEquals(
            MiniHomeShareUiState.NoTarget(MiniHomeShareFixtures.owner),
            controller.state.value,
        )
    }

    @Test
    fun `an explicit no target load reports no share target`() = runTest {
        val controller =
            controller(FakeShareRepository(loadResult = MiniHomeShareLoadResult.NoTarget))

        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

        assertEquals(
            MiniHomeShareUiState.NoTarget(MiniHomeShareFixtures.owner),
            controller.state.value,
        )
    }

    // 3) 캡처 신호가 오기 전에는 준비 완료가 아니다

    @Test
    fun `render stays in progress until the draw record signal arrives`() = runTest {
        val controller = controller(FakeShareRepository())
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

        assertEquals(MiniHomeShareRenderState.Rendering, readyState(controller).render)

        val token = requireNotNull(controller.captureToken())
        controller.onRecorded(token)

        assertEquals(MiniHomeShareRenderState.Ready, readyState(controller).render)
    }

    @Test
    fun `a record signal from a stale generation never marks render ready`() = runTest {
        val repository = FakeShareRepository(ownerScopedTargets = true)
        val controller = controller(repository)
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))
        val staleToken = requireNotNull(controller.captureToken())

        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.otherOwner))
        controller.onRecorded(staleToken)

        assertEquals(MiniHomeShareRenderState.Rendering, readyState(controller).render)
        assertNotEquals(staleToken, controller.captureToken())
    }

    @Test
    fun `retrying render clears readiness until a new signal arrives`() = runTest {
        val controller = controller(FakeShareRepository())
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))
        controller.onRecorded(requireNotNull(controller.captureToken()))
        assertEquals(MiniHomeShareRenderState.Ready, readyState(controller).render)

        controller.retryRender()

        assertEquals(MiniHomeShareRenderState.Rendering, readyState(controller).render)
        controller.onRecorded(requireNotNull(controller.captureToken()))
        assertEquals(MiniHomeShareRenderState.Ready, readyState(controller).render)
    }

    @Test
    fun `capture token carries the committed revision and the owner generation`() = runTest {
        val controller = controller(FakeShareRepository())
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

        val token = requireNotNull(controller.captureToken())

        assertEquals(MiniHomeShareFixtures.owner, token.owner)
        assertEquals(Revision(7), token.revision)
        assertTrue(controller.isCurrent(token))
    }

    // 5) 재시도 가능한 실패는 같은 얼어붙은 요청을 다시 보낸다

    @Test
    fun `an offline failure retains the frozen operation id and revision for replay`() = runTest {
        val repository =
            FakeShareRepository(
                createResults =
                    mutableListOf(
                        MiniHomeShareCreateResult.Failed(MiniHomeShareFailure.OFFLINE),
                        MiniHomeShareCreateResult.Created(MiniHomeShareFixtures.link()),
                    )
            )
        val controller = controller(repository, operationIds = listOf("share-op-1", "share-op-2"))
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

        controller.createLink()
        controller.createLink()

        assertEquals(
            listOf(OperationId("share-op-1"), OperationId("share-op-1")),
            repository.createRequests.map { it.operationId },
        )
        assertEquals(
            listOf(Revision(7), Revision(7)),
            repository.createRequests.map { it.expectedRevision },
        )
        assertEquals(
            MiniHomeShareLinkState.Active(MiniHomeShareFixtures.link()),
            readyState(controller).link,
        )
    }

    @Test
    fun `an ambiguous deadline failure replays the same operation and adopts the committed link`() =
        runTest {
            val repository =
                FakeShareRepository(
                    createResults =
                        mutableListOf(
                            // 서버가 이미 커밋했을 수 있는 모호한 실패이다.
                            MiniHomeShareCreateResult.Failed(MiniHomeShareFailure.DEADLINE),
                            MiniHomeShareCreateResult.Failed(MiniHomeShareFailure.OFFLINE),
                            // 같은 operation ID의 재생 응답이 원래 커밋 결과를 그대로 돌려준다.
                            MiniHomeShareCreateResult.Created(MiniHomeShareFixtures.link()),
                        )
                )
            val controller =
                controller(
                    repository,
                    operationIds = listOf("share-op-1", "share-op-2", "share-op-3"),
                )
            controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

            controller.createLink()
            controller.createLink()
            controller.createLink()

            assertEquals(1, repository.createRequests.map { it.operationId }.distinct().size)
            assertEquals(OperationId("share-op-1"), repository.createRequests.first().operationId)
            assertEquals(
                MiniHomeShareLinkState.Active(MiniHomeShareFixtures.link()),
                readyState(controller).link,
            )
        }

    @Test
    fun `a permanent failure abandons the frozen operation and the next attempt is new`() =
        runTest {
            val repository =
                FakeShareRepository(
                    createResults =
                        mutableListOf(
                            MiniHomeShareCreateResult.Failed(
                                MiniHomeShareFailure.MALFORMED_RESPONSE
                            ),
                            MiniHomeShareCreateResult.Created(MiniHomeShareFixtures.link()),
                        )
                )
            val controller =
                controller(repository, operationIds = listOf("share-op-1", "share-op-2"))
            controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

            controller.createLink()
            controller.createLink()

            assertEquals(
                listOf(OperationId("share-op-1"), OperationId("share-op-2")),
                repository.createRequests.map { it.operationId },
            )
        }

    @Test
    fun `a newly committed revision abandons the frozen operation`() = runTest {
        val repository =
            FakeShareRepository(
                createResults =
                    mutableListOf(
                        MiniHomeShareCreateResult.Failed(MiniHomeShareFailure.OFFLINE),
                        MiniHomeShareCreateResult.Created(MiniHomeShareFixtures.link(9)),
                    )
            )
        val controller = controller(repository, operationIds = listOf("share-op-1", "share-op-2"))
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))
        controller.createLink()

        // 사용자가 미니홈을 다시 저장해 확정 revision이 올라간 뒤 재시도한다.
        repository.loadResultOverride =
            MiniHomeShareLoadResult.Ready(MiniHomeShareFixtures.target(9))
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))
        controller.createLink()

        assertEquals(
            listOf(OperationId("share-op-1"), OperationId("share-op-2")),
            repository.createRequests.map { it.operationId },
        )
        assertEquals(
            listOf(Revision(7), Revision(9)),
            repository.createRequests.map { it.expectedRevision },
        )
    }

    @Test
    fun `the frozen operation survives recreation but the url and token never do`() = runTest {
        val repository =
            FakeShareRepository(
                createResults =
                    mutableListOf(MiniHomeShareCreateResult.Failed(MiniHomeShareFailure.OFFLINE))
            )
        val controller = controller(repository)
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))
        controller.createLink()

        val persisted = controller.persistableState()
        val rendered = persisted.values.joinToString(" ")

        assertFalse(rendered.contains(MiniHomeShareFixtures.TOKEN))
        assertFalse(rendered.contains(MiniHomeShareFixtures.URL))
        assertFalse(rendered.contains(MiniHomeShareFixtures.SHARE_ID))
        assertTrue(rendered.contains("share-op-1"))
        assertTrue(rendered.contains("7"))
    }

    @Test
    fun `a restored frozen operation is resent instead of generating a new one`() = runTest {
        val repository = FakeShareRepository()
        val controller = controller(repository, operationIds = listOf("share-op-fresh"))
        controller.restore(
            mapOf(
                "mini-home-share.operation" to "share-op-restored",
                "mini-home-share.revision" to "7",
            )
        )
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

        controller.createLink()

        assertEquals(
            OperationId("share-op-restored"),
            repository.createRequests.single().operationId,
        )
    }

    @Test
    fun `creating a link freezes the same in flight operation id`() = runTest {
        val gate = CompletableDeferred<MiniHomeShareCreateResult>()
        val repository = FakeShareRepository(createGate = gate)
        val controller = controller(repository, operationIds = listOf("share-op-1", "share-op-2"))
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

        val first = launch { controller.createLink() }
        runCurrent()
        assertEquals(MiniHomeShareLinkState.Generating, readyState(controller).link)
        controller.createLink()
        controller.createLink()
        runCurrent()

        assertEquals(1, repository.createRequests.size)

        gate.complete(MiniHomeShareCreateResult.Created(MiniHomeShareFixtures.link()))
        first.join()
        runCurrent()
        assertEquals(
            MiniHomeShareLinkState.Active(MiniHomeShareFixtures.link()),
            readyState(controller).link,
        )
    }

    @Test
    fun `offline create failure is reported distinctly from a permanent failure`() = runTest {
        val controller =
            controller(
                FakeShareRepository(
                    createResults =
                        mutableListOf(
                            MiniHomeShareCreateResult.Failed(MiniHomeShareFailure.OFFLINE)
                        )
                )
            )
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

        controller.createLink()

        val failed = readyState(controller).link as MiniHomeShareLinkState.Failed
        assertEquals(MiniHomeShareFailure.OFFLINE, failed.failure)
        assertTrue(failed.failure.retryable)
    }

    @Test
    fun `malformed create failure is permanent and does not offer retry`() = runTest {
        val controller =
            controller(
                FakeShareRepository(
                    createResults =
                        mutableListOf(
                            MiniHomeShareCreateResult.Failed(
                                MiniHomeShareFailure.MALFORMED_RESPONSE
                            )
                        )
                )
            )
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

        controller.createLink()

        assertFalse(
            (readyState(controller).link as MiniHomeShareLinkState.Failed).failure.retryable
        )
    }

    @Test
    fun `revoking clears the active link immediately`() = runTest {
        val repository = FakeShareRepository()
        val controller = controller(repository)
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))
        controller.createLink()

        controller.revokeLink()

        assertEquals(MiniHomeShareLinkState.Revoked, readyState(controller).link)
        assertEquals(listOf(MiniHomeShareFixtures.shareId), repository.revoked)
    }

    @Test
    fun `revoke failure keeps the link visible so the user can retry`() = runTest {
        val repository =
            FakeShareRepository(
                revokeResult = MiniHomeShareRevokeResult.Failed(MiniHomeShareFailure.OFFLINE)
            )
        val controller = controller(repository)
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))
        controller.createLink()

        controller.revokeLink()

        val active = readyState(controller).link as MiniHomeShareLinkState.Active
        assertEquals(MiniHomeShareFixtures.link(), active.link)
        assertEquals(MiniHomeShareFailure.OFFLINE, active.revokeFailure)
    }

    @Test
    fun `account switch clears the bearer link and reloads for the new owner`() = runTest {
        val repository = FakeShareRepository(ownerScopedTargets = true)
        val controller = controller(repository)
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))
        controller.createLink()
        assertTrue(readyState(controller).link is MiniHomeShareLinkState.Active)

        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.otherOwner))

        assertFalse(controller.state.value.holdsLink())
        assertEquals(MiniHomeShareFixtures.otherOwner, controller.state.value.owner)
        assertTrue(repository.clearedCaches)
    }

    @Test
    fun `account switch abandons the frozen operation of the previous owner`() = runTest {
        val repository =
            FakeShareRepository(
                ownerScopedTargets = true,
                createResults =
                    mutableListOf(
                        MiniHomeShareCreateResult.Failed(MiniHomeShareFailure.OFFLINE),
                        MiniHomeShareCreateResult.Created(MiniHomeShareFixtures.link()),
                    ),
            )
        val controller = controller(repository, operationIds = listOf("share-op-1", "share-op-2"))
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))
        controller.createLink()

        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.otherOwner))
        controller.createLink()

        assertEquals(
            listOf(OperationId("share-op-1"), OperationId("share-op-2")),
            repository.createRequests.map { it.operationId },
        )
    }

    @Test
    fun `logout drops the link and every owner scoped state`() = runTest {
        val repository = FakeShareRepository()
        val controller = controller(repository)
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))
        controller.createLink()

        controller.start(MiniHomeAuthOwnership.SignedOut)

        assertEquals(MiniHomeShareUiState.Forbidden, controller.state.value)
        assertFalse(controller.state.value.holdsLink())
        assertTrue(repository.clearedCaches)
        assertNull(controller.captureToken())
        assertFalse(controller.persistableState().values.joinToString(" ").contains("share-op"))
    }

    @Test
    fun `an in flight create for a previous owner cannot publish into the new owner state`() =
        runTest {
            val gate = CompletableDeferred<MiniHomeShareCreateResult>()
            val repository = FakeShareRepository(createGate = gate, ownerScopedTargets = true)
            val controller = controller(repository)
            controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))
            val creating = launch { controller.createLink() }
            runCurrent()

            controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.otherOwner))
            gate.complete(MiniHomeShareCreateResult.Created(MiniHomeShareFixtures.link()))
            creating.join()
            runCurrent()

            assertFalse(controller.state.value.holdsLink())
        }

    @Test
    fun `a target belonging to another owner is never published`() = runTest {
        val controller = controller(FakeShareRepository())

        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.otherOwner))

        assertEquals(MiniHomeShareUiState.Error, controller.state.value)
    }

    @Test
    fun `sheet outcomes are neutral and never claim delivery`() = runTest {
        val controller = controller(FakeShareRepository())
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

        controller.onSheetOutcome(MiniHomeShareSheetOutcome.Cancelled)
        assertEquals(MiniHomeShareFeedback.SHEET_CANCELLED, readyState(controller).feedback)
        assertFalse(MiniHomeShareFeedback.SHEET_CANCELLED.error)

        controller.onSheetOutcome(MiniHomeShareSheetOutcome.Opened)
        assertEquals(MiniHomeShareFeedback.SHEET_OPENED, readyState(controller).feedback)

        controller.onSheetOutcome(MiniHomeShareSheetOutcome.NoTarget)
        assertEquals(MiniHomeShareFeedback.NO_TARGET, readyState(controller).feedback)
    }

    @Test
    fun `external share outcomes never mutate the committed revision`() = runTest {
        val repository = FakeShareRepository()
        val controller = controller(repository)
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))
        val before = readyState(controller).target.committed

        listOf(
                MiniHomeShareSheetOutcome.Opened,
                MiniHomeShareSheetOutcome.Cancelled,
                MiniHomeShareSheetOutcome.NoTarget,
                MiniHomeShareSheetOutcome.Failed,
                MiniHomeShareSheetOutcome.Stale,
            )
            .forEach(controller::onSheetOutcome)

        assertEquals(before, readyState(controller).target.committed)
    }

    @Test
    fun `render failure is retryable and keeps the committed target`() = runTest {
        val controller = controller(FakeShareRepository())
        controller.start(MiniHomeAuthOwnership.Authenticated(MiniHomeShareFixtures.owner))

        controller.onRenderFailed()
        assertEquals(MiniHomeShareRenderState.Failed, readyState(controller).render)

        controller.retryRender()
        assertEquals(MiniHomeShareRenderState.Rendering, readyState(controller).render)
        assertEquals(MiniHomeShareFixtures.layout(7), readyState(controller).target.committed)
    }

    private fun readyState(controller: MiniHomeShareController): MiniHomeShareUiState.Ready =
        controller.state.value as MiniHomeShareUiState.Ready

    private fun controller(
        repository: MiniHomeShareRepository,
        operationIds: List<String> = listOf("share-op-1"),
    ): MiniHomeShareController {
        val ids = operationIds.iterator()
        return MiniHomeShareController(
            repository = repository,
            operationIdFactory = { OperationId(ids.next()) },
        )
    }

    private class FakeShareRepository(
        private val loadResult: MiniHomeShareLoadResult =
            MiniHomeShareLoadResult.Ready(MiniHomeShareFixtures.target(7)),
        private val ownerScopedTargets: Boolean = false,
        private val createResults: MutableList<MiniHomeShareCreateResult> =
            mutableListOf(MiniHomeShareCreateResult.Created(MiniHomeShareFixtures.link())),
        private val createGate: CompletableDeferred<MiniHomeShareCreateResult>? = null,
        private val revokeResult: MiniHomeShareRevokeResult =
            MiniHomeShareRevokeResult.Revoked(MiniHomeShareFixtures.revokedAt),
    ) : MiniHomeShareRepository {
        var loadCount = 0
        var clearedCaches = false
        var loadResultOverride: MiniHomeShareLoadResult? = null
        val createRequests = mutableListOf<MiniHomeShareLinkRequest>()
        val revoked = mutableListOf<MiniHomeShareId>()
        private var nextOwner: AccountId? = MiniHomeShareFixtures.owner

        override suspend fun loadCommitted(): MiniHomeShareLoadResult {
            loadCount += 1
            loadResultOverride?.let {
                return it
            }
            if (!ownerScopedTargets) return loadResult
            val owner = nextOwner ?: MiniHomeShareFixtures.otherOwner
            nextOwner = MiniHomeShareFixtures.otherOwner
            return MiniHomeShareLoadResult.Ready(
                MiniHomeShareFixtures.target(7).copy(owner = owner)
            )
        }

        override suspend fun createLink(
            request: MiniHomeShareLinkRequest
        ): MiniHomeShareCreateResult {
            createRequests += request
            createGate?.let {
                return it.await()
            }
            return if (createResults.size > 1) createResults.removeAt(0) else createResults.first()
        }

        override suspend fun revokeLink(shareId: MiniHomeShareId): MiniHomeShareRevokeResult {
            revoked += shareId
            return revokeResult
        }

        override suspend fun clearOwnerArtifacts() {
            clearedCaches = true
        }
    }
}

private fun MiniHomeShareUiState.holdsLink(): Boolean =
    (this as? MiniHomeShareUiState.Ready)?.link is MiniHomeShareLinkState.Active
