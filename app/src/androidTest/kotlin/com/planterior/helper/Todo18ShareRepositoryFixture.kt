package com.planterior.helper

import com.planterior.helper.feature.minihome.MiniHomeLoadResult
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.feature.share.MiniHomeShareCreateResult
import com.planterior.helper.feature.share.MiniHomeShareFailure
import com.planterior.helper.feature.share.MiniHomeShareId
import com.planterior.helper.feature.share.MiniHomeShareLink
import com.planterior.helper.feature.share.MiniHomeShareLinkRequest
import com.planterior.helper.feature.share.MiniHomeShareLoadResult
import com.planterior.helper.feature.share.MiniHomeShareRepository
import com.planterior.helper.feature.share.MiniHomeShareRevokeResult
import com.planterior.helper.feature.share.MiniHomeShareTarget

/** Share boundary fixture that reads the production mini-home repository. */
internal class Todo18ShareRepositoryFixture(
    private val miniHome: MiniHomeRepository,
    private val scenario: Todo18Scenario,
) : MiniHomeShareRepository {
    override suspend fun loadCommitted(): MiniHomeShareLoadResult {
        if (scenario.shareMode == Todo18ShareMode.DELETED) {
            scenario.emit("share-deleted", scenario.accountId.value)
            return MiniHomeShareLoadResult.NoTarget
        }
        return when (val loaded = miniHome.load()) {
            is MiniHomeLoadResult.Ready ->
                MiniHomeShareLoadResult.Ready(
                    MiniHomeShareTarget(
                        loaded.accountId,
                        loaded.committed,
                        loaded.plants,
                        loaded.decorations,
                    )
                )
            MiniHomeLoadResult.Forbidden -> MiniHomeShareLoadResult.Forbidden
            MiniHomeLoadResult.Failed -> MiniHomeShareLoadResult.Failed
        }
    }

    override suspend fun createLink(request: MiniHomeShareLinkRequest): MiniHomeShareCreateResult {
        scenario.emit("share-create", request.operationId.value)
        if (scenario.shareMode == Todo18ShareMode.EXPIRED) {
            return MiniHomeShareCreateResult.Failed(MiniHomeShareFailure.INVALID_REQUEST)
        }
        val createdAt = scenario.now()
        return MiniHomeShareCreateResult.Created(
            MiniHomeShareLink(
                MiniHomeShareId("s".repeat(43)),
                "https://share.planterior.test/view?token=${"t".repeat(43)}",
                request.expectedRevision,
                createdAt,
                createdAt.plus(MiniHomeShareLink.LIFETIME),
            )
        )
    }

    override suspend fun revokeLink(shareId: MiniHomeShareId): MiniHomeShareRevokeResult =
        MiniHomeShareRevokeResult.Revoked(scenario.now())
}
