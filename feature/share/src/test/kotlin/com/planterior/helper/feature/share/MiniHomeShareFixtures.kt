package com.planterior.helper.feature.share

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.GridPosition
import com.planterior.helper.feature.minihome.MiniHomeDecorationChoice
import com.planterior.helper.feature.minihome.MiniHomeLayout
import com.planterior.helper.feature.minihome.MiniHomePlacement
import com.planterior.helper.feature.minihome.MiniHomePlacementPolicy
import com.planterior.helper.feature.minihome.MiniHomePlacementTarget
import com.planterior.helper.feature.minihome.MiniHomePlantChoice
import com.planterior.helper.feature.minihome.MiniHomeZIndex
import java.time.Instant

/**
 * 서버가 실제로 돌려주는 값에서 그대로 옮긴 고정 값이다.
 *
 * `shareId`와 `token`은 백엔드의 base64url SHA-256/HMAC 유도 결과라 항상 43자이고, 시각은 `Date#toISOString`이 만드는 정규
 * UTC 문자열이다. 임의로 만든 값을 쓰면 계약이 어긋나도 테스트가 통과하므로 실제 유도 결과만 쓴다.
 */
internal object MiniHomeShareFixtures {
    val owner = AccountId("owner-share-1")
    val otherOwner = AccountId("owner-share-2")

    /** sha256("planterior:mini-home-share-id:v1\0" + owner + "\0" + operationId) in base64url. */
    const val SHARE_ID = "rqxVkqvaT6uD13c_RUgvqQSyeNS546E0osARtDLgpqo"

    /** HMAC-SHA256 share token in base64url. 43 characters, no padding. */
    const val TOKEN = "_3-HzHiDPL_2kAV2l-4dRdsS1b6gdpWQlk0wY401NH4"

    const val URL = "https://share.planterior.app/m?token=$TOKEN"

    /** 백엔드 테스트가 쓰는 고정 시계 값이다. `new Date("2026-08-22T00:00:00.000Z")`. */
    const val CREATED_AT_ISO = "2026-08-22T00:00:00.000Z"

    /** 고정 시계 + `MINI_HOME_SHARE_LIFETIME_MILLIS`(30일). */
    const val EXPIRES_AT_ISO = "2026-09-21T00:00:00.000Z"

    const val REVOKED_AT_ISO = "2026-08-22T04:30:00.000Z"

    val createdAt: Instant = Instant.parse(CREATED_AT_ISO)
    val expiresAt: Instant = Instant.parse(EXPIRES_AT_ISO)
    val revokedAt: Instant = Instant.parse(REVOKED_AT_ISO)

    val shareId = MiniHomeShareId(SHARE_ID)

    val request = MiniHomeShareLinkRequest(OperationId("share-operation-1"), Revision(7))

    val revokeRequest = MiniHomeShareRevokeRequest(shareId)

    val plant = MiniHomePlantChoice(PersonalPlantId("plant-a"), "몬스테라", null)
    val decoration = MiniHomeDecorationChoice(ItemId("item-a"), "원목 테이블")

    /** 서버가 성공적으로 만든 링크의 정확한 응답이다. */
    fun createResponse(): Map<String, Any> =
        mapOf(
            "shareId" to SHARE_ID,
            "url" to URL,
            "sourceRevision" to 7L,
            "createdAt" to CREATED_AT_ISO,
            "expiresAt" to EXPIRES_AT_ISO,
        )

    fun revokeResponse(): Map<String, Any> =
        mapOf("shareId" to SHARE_ID, "revokedAt" to REVOKED_AT_ISO)

    fun link(revision: Long = 7L): MiniHomeShareLink =
        MiniHomeShareLink(shareId, URL, Revision(revision), createdAt, expiresAt)

    fun layout(revision: Long = 7L, placements: Int = 2): MiniHomeLayout =
        MiniHomeLayout(
            MiniHomeId("mini-home-a"),
            "우리 집 식물원",
            MiniHomePlacementPolicy.layer(
                buildList {
                    if (placements >= 1) {
                        add(
                            MiniHomePlacement(
                                PlacementId("placement-a"),
                                MiniHomePlacementTarget.Plant(plant.id),
                                GridPosition(1, 1),
                                MiniHomeZIndex(0),
                            )
                        )
                    }
                    if (placements >= 2) {
                        add(
                            MiniHomePlacement(
                                PlacementId("placement-b"),
                                MiniHomePlacementTarget.Decoration(decoration.id),
                                GridPosition(3, 2),
                                MiniHomeZIndex(1),
                            )
                        )
                    }
                }
            ),
            Revision(revision),
            Instant.ofEpochMilli(1_700_000_000_000L),
        )

    fun target(revision: Long = 7L, placements: Int = 2): MiniHomeShareTarget =
        MiniHomeShareTarget(
            owner = owner,
            committed = layout(revision, placements),
            plants = listOf(plant),
            decorations = listOf(decoration),
        )
}
