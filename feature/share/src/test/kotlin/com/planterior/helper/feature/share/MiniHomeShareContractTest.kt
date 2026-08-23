package com.planterior.helper.feature.share

import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val JS_ISO_FORMATTER =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

private fun Instant.jsIso(): String = JS_ISO_FORMATTER.format(this)

class MiniHomeShareContractTest {
    @Test
    fun `create request carries only the operation id and expected revision`() {
        val request = MiniHomeShareLinkRequest(OperationId("share-operation-1"), Revision(7))

        assertEquals(
            mapOf<String, Any>(
                "operationId" to "share-operation-1",
                "expectedRevision" to 7L,
            ),
            request.callablePayload(),
        )
    }

    @Test
    fun `revoke request carries only the share id`() {
        assertEquals(
            mapOf<String, Any>("shareId" to MiniHomeShareFixtures.SHARE_ID),
            MiniHomeShareRevokeRequest(MiniHomeShareFixtures.shareId).callablePayload(),
        )
    }

    // 1) 서버가 실제로 돌려주는 ISO-8601 문자열 응답

    @Test
    fun `the exact server create response parses into an active link`() {
        val parsed =
            MiniHomeShareLinkCodec.decodeCreate(
                MiniHomeShareFixtures.request,
                MiniHomeShareFixtures.createResponse(),
            )

        val link = (parsed as MiniHomeShareLinkDecode.Link).link
        assertEquals(MiniHomeShareFixtures.shareId, link.shareId)
        assertEquals(MiniHomeShareFixtures.URL, link.url)
        assertEquals(Revision(7), link.sourceRevision)
        assertEquals(MiniHomeShareFixtures.createdAt, link.createdAt)
        assertEquals(MiniHomeShareFixtures.expiresAt, link.expiresAt)
    }

    @Test
    fun `the exact server revoke response parses into a revoked timestamp`() {
        assertEquals(
            MiniHomeShareRevokeDecode.Revoked(MiniHomeShareFixtures.revokedAt),
            MiniHomeShareLinkCodec.decodeRevoke(
                MiniHomeShareFixtures.revokeRequest,
                MiniHomeShareFixtures.revokeResponse(),
            ),
        )
    }

    @Test
    fun `epoch millis timestamps are rejected because the server never emits them`() {
        val millis =
            MiniHomeShareFixtures.createResponse() +
                mapOf(
                    "createdAt" to MiniHomeShareFixtures.createdAt.toEpochMilli(),
                    "expiresAt" to MiniHomeShareFixtures.expiresAt.toEpochMilli(),
                )

        assertEquals(
            MiniHomeShareLinkDecode.Malformed,
            MiniHomeShareLinkCodec.decodeCreate(MiniHomeShareFixtures.request, millis),
        )
        assertEquals(
            MiniHomeShareRevokeDecode.Malformed,
            MiniHomeShareLinkCodec.decodeRevoke(
                MiniHomeShareFixtures.revokeRequest,
                mapOf(
                    "shareId" to MiniHomeShareFixtures.SHARE_ID,
                    "revokedAt" to MiniHomeShareFixtures.revokedAt.toEpochMilli(),
                ),
            ),
        )
    }

    @Test
    fun `mixed encodings inside one response are rejected`() {
        val mixed =
            MiniHomeShareFixtures.createResponse() +
                mapOf("expiresAt" to MiniHomeShareFixtures.expiresAt.toEpochMilli())

        assertEquals(
            MiniHomeShareLinkDecode.Malformed,
            MiniHomeShareLinkCodec.decodeCreate(MiniHomeShareFixtures.request, mixed),
        )
    }

    @Test
    fun `only the exact javascript millisecond ISO form is accepted`() {
        // Date#toISOString은 항상 밀리초 3자리를 붙인다. 그 밖의 표기는 서버가 만들 수 없다.
        listOf(
                "2026-08-22T00:00:00Z",
                "2026-08-22T00:00:00.0Z",
                "2026-08-22T00:00:00.00Z",
                "2026-08-22T00:00:00.0000Z",
                "2026-08-22T00:00:00.000000Z",
                "2026-08-22T00:00:00.000000000Z",
                "2026-08-22T00:00:00+09:00",
                "2026-08-22T00:00:00.000+00:00",
                "2026-08-22T00:00:00",
                "2026-08-22T00:00:00.000z",
                "2026-08-22 00:00:00.000Z",
                "20260822T000000.000Z",
                "",
                "   ",
            )
            .forEach { candidate ->
                assertEquals(
                    "$candidate must be malformed",
                    MiniHomeShareLinkDecode.Malformed,
                    MiniHomeShareLinkCodec.decodeCreate(
                        MiniHomeShareFixtures.request,
                        MiniHomeShareFixtures.createResponse() + ("createdAt" to candidate),
                    ),
                )
            }
    }

    @Test
    fun `exactly three fraction digits parse including a zero fraction`() {
        // .000Z는 Java가 소수부 없이 정규화하지만 서버가 실제로 보내는 형태이므로 반드시 통과해야 한다.
        mapOf(
                "2026-08-22T00:00:00.000Z" to Instant.parse("2026-08-22T00:00:00Z"),
                "2026-08-22T00:00:00.123Z" to Instant.parse("2026-08-22T00:00:00.123Z"),
            )
            .forEach { (candidate, expected) ->
                val decoded =
                    MiniHomeShareLinkCodec.decodeCreate(
                        MiniHomeShareFixtures.request,
                        MiniHomeShareFixtures.createResponse() +
                            mapOf(
                                "createdAt" to candidate,
                                "expiresAt" to expected.plus(MiniHomeShareLink.LIFETIME).jsIso(),
                            ),
                    )
                assertTrue("$candidate must parse", decoded is MiniHomeShareLinkDecode.Link)
                assertEquals(expected, (decoded as MiniHomeShareLinkDecode.Link).link.createdAt)
            }
    }

    @Test
    fun `the exact backend fixed clock vector round trips`() {
        val decoded =
            MiniHomeShareLinkCodec.decodeCreate(
                MiniHomeShareFixtures.request,
                MiniHomeShareFixtures.createResponse(),
            ) as MiniHomeShareLinkDecode.Link

        assertEquals(Instant.parse("2026-08-22T00:00:00Z"), decoded.link.createdAt)
        assertEquals(Instant.parse("2026-09-21T00:00:00Z"), decoded.link.expiresAt)
    }

    // 2) 링크 수명은 정확히 30일이다

    @Test
    fun `a lifetime of exactly thirty days is accepted`() {
        assertEquals(Duration.ofDays(30), MiniHomeShareLink.LIFETIME)
        assertEquals(
            MiniHomeShareLink.LIFETIME,
            Duration.between(MiniHomeShareFixtures.createdAt, MiniHomeShareFixtures.expiresAt),
        )
        assertTrue(
            MiniHomeShareLinkCodec.decodeCreate(
                MiniHomeShareFixtures.request,
                MiniHomeShareFixtures.createResponse(),
            ) is MiniHomeShareLinkDecode.Link
        )
    }

    @Test
    fun `a lifetime that is off by a single millisecond is malformed`() {
        listOf(-1L, 1L, -1000L, 1000L, Duration.ofDays(1).toMillis()).forEach { skewMillis ->
            val expiresAt =
                MiniHomeShareFixtures.createdAt
                    .plus(MiniHomeShareLink.LIFETIME)
                    .plusMillis(skewMillis)
            assertEquals(
                "a $skewMillis ms skew must be malformed",
                MiniHomeShareLinkDecode.Malformed,
                MiniHomeShareLinkCodec.decodeCreate(
                    MiniHomeShareFixtures.request,
                    MiniHomeShareFixtures.createResponse() + ("expiresAt" to expiresAt.jsIso()),
                ),
            )
        }
    }

    @Test
    fun `a domain link cannot be built with a lifetime other than thirty days`() {
        val error = runCatching {
            MiniHomeShareLink(
                MiniHomeShareFixtures.shareId,
                MiniHomeShareFixtures.URL,
                Revision(7),
                MiniHomeShareFixtures.createdAt,
                MiniHomeShareFixtures.createdAt.plus(Duration.ofDays(29)),
            )
        }
            .exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    // 3) 응답은 반드시 요청과 묶인다

    @Test
    fun `a create response for another revision is malformed`() {
        listOf(1L, 6L, 8L, 99L).forEach { otherRevision ->
            assertEquals(
                "sourceRevision $otherRevision must not bind to expectedRevision 7",
                MiniHomeShareLinkDecode.Malformed,
                MiniHomeShareLinkCodec.decodeCreate(
                    MiniHomeShareFixtures.request,
                    MiniHomeShareFixtures.createResponse() + ("sourceRevision" to otherRevision),
                ),
            )
        }
    }

    @Test
    fun `a create response binds only to its own expected revision`() {
        val otherRequest = MiniHomeShareLinkRequest(OperationId("share-operation-2"), Revision(9))

        assertEquals(
            MiniHomeShareLinkDecode.Malformed,
            MiniHomeShareLinkCodec.decodeCreate(
                otherRequest,
                MiniHomeShareFixtures.createResponse(),
            ),
        )
    }

    @Test
    fun `a revoke response for another share is malformed`() {
        val otherShareId = MiniHomeShareId("b".repeat(43))

        assertEquals(
            MiniHomeShareRevokeDecode.Malformed,
            MiniHomeShareLinkCodec.decodeRevoke(
                MiniHomeShareRevokeRequest(otherShareId),
                MiniHomeShareFixtures.revokeResponse(),
            ),
        )
    }

    @Test
    fun `unknown response fields are rejected instead of ignored`() {
        assertEquals(
            MiniHomeShareLinkDecode.Malformed,
            MiniHomeShareLinkCodec.decodeCreate(
                MiniHomeShareFixtures.request,
                MiniHomeShareFixtures.createResponse() + ("token" to MiniHomeShareFixtures.TOKEN),
            ),
        )
        assertEquals(
            MiniHomeShareRevokeDecode.Malformed,
            MiniHomeShareLinkCodec.decodeRevoke(
                MiniHomeShareFixtures.revokeRequest,
                MiniHomeShareFixtures.revokeResponse() + ("ownerUid" to "owner-share-1"),
            ),
        )
    }

    @Test
    fun `every missing create field is malformed`() {
        MiniHomeShareFixtures.createResponse().keys.forEach { missing ->
            assertEquals(
                "missing $missing must be malformed",
                MiniHomeShareLinkDecode.Malformed,
                MiniHomeShareLinkCodec.decodeCreate(
                    MiniHomeShareFixtures.request,
                    MiniHomeShareFixtures.createResponse() - missing,
                ),
            )
        }
        assertEquals(
            MiniHomeShareLinkDecode.Malformed,
            MiniHomeShareLinkCodec.decodeCreate(MiniHomeShareFixtures.request, null),
        )
        assertEquals(
            MiniHomeShareLinkDecode.Malformed,
            MiniHomeShareLinkCodec.decodeCreate(MiniHomeShareFixtures.request, "not-an-object"),
        )
    }

    @Test
    fun `an expiry that does not follow creation is malformed`() {
        assertEquals(
            MiniHomeShareLinkDecode.Malformed,
            MiniHomeShareLinkCodec.decodeCreate(
                MiniHomeShareFixtures.request,
                MiniHomeShareFixtures.createResponse() +
                    ("expiresAt" to MiniHomeShareFixtures.CREATED_AT_ISO),
            ),
        )
    }

    @Test
    fun `source revision must be a positive safe integer`() {
        listOf<Any>("seven", 0L, -1L, 1.5, Long.MAX_VALUE).forEach { candidate ->
            assertEquals(
                "$candidate must be malformed",
                MiniHomeShareLinkDecode.Malformed,
                MiniHomeShareLinkCodec.decodeCreate(
                    MiniHomeShareFixtures.request,
                    MiniHomeShareFixtures.createResponse() + ("sourceRevision" to candidate),
                ),
            )
        }
    }

    @Test
    fun `revoke response must echo the requested share id`() {
        assertEquals(
            MiniHomeShareRevokeDecode.Malformed,
            MiniHomeShareLinkCodec.decodeRevoke(
                MiniHomeShareFixtures.revokeRequest,
                mapOf(
                    "shareId" to "Ab_cdefghijklmnopqrstuvwxyz0123456789ABCDEF",
                    "revokedAt" to MiniHomeShareFixtures.REVOKED_AT_ISO,
                ),
            ),
        )
    }

    // 2) share ID와 URL의 정확한 형태

    @Test
    fun `share ids are exactly 43 character base64url identifiers`() {
        assertEquals(MiniHomeShareFixtures.SHARE_ID, MiniHomeShareFixtures.shareId.value)
        assertEquals(43, MiniHomeShareFixtures.SHARE_ID.length)
        listOf(
                "",
                "short",
                "a".repeat(42),
                "a".repeat(44),
                "a".repeat(42) + "+",
                "a".repeat(42) + "/",
                "a".repeat(42) + "=",
                "a".repeat(42) + " ",
                "a".repeat(42) + "\n",
            )
            .forEach { candidate ->
                assertNull(
                    "$candidate must be rejected",
                    runCatching { MiniHomeShareId(candidate) }.getOrNull(),
                )
            }
    }

    @Test
    fun `a create response whose share id is not base64url is malformed`() {
        assertEquals(
            MiniHomeShareLinkDecode.Malformed,
            MiniHomeShareLinkCodec.decodeCreate(
                MiniHomeShareFixtures.request,
                MiniHomeShareFixtures.createResponse() + ("shareId" to "share-1"),
            ),
        )
    }

    @Test
    fun `only an https url with a host and an exact 43 character token is accepted`() {
        listOf(
                "http://share.planterior.app/m?token=${MiniHomeShareFixtures.TOKEN}",
                "planterior://m?token=${MiniHomeShareFixtures.TOKEN}",
                "https:///m?token=${MiniHomeShareFixtures.TOKEN}",
                "https://user:pw@share.planterior.app/m?token=${MiniHomeShareFixtures.TOKEN}",
                "https://share.planterior.app/m?token=${MiniHomeShareFixtures.TOKEN}#frag",
                "https://share.planterior.app/m",
                "https://share.planterior.app/m?token=",
                "https://share.planterior.app/m?token=short",
                "https://share.planterior.app/m?token=${"a".repeat(44)}",
                "https://share.planterior.app/m?token=${"a".repeat(42)}+",
                "https://share.planterior.app/m?tok=${MiniHomeShareFixtures.TOKEN}",
                "https://share.planterior.app/m?token=${MiniHomeShareFixtures.TOKEN}&uid=owner-1",
                "https://share.planterior.app/m?uid=owner-1&token=${MiniHomeShareFixtures.TOKEN}",
                "https://share.planterior.app/m?token=${MiniHomeShareFixtures.TOKEN}&token=x",
                "https://share.planterior.app/m?token=${MiniHomeShareFixtures.TOKEN}\u0000",
                "https://share.planterior.app/m?token=${MiniHomeShareFixtures.TOKEN}\n",
                "  ",
            )
            .forEach { candidate ->
                assertEquals(
                    "$candidate must be malformed",
                    MiniHomeShareLinkDecode.Malformed,
                    MiniHomeShareLinkCodec.decodeCreate(
                        MiniHomeShareFixtures.request,
                        MiniHomeShareFixtures.createResponse() + ("url" to candidate),
                    ),
                )
            }
        assertTrue(
            MiniHomeShareLinkCodec.decodeCreate(
                MiniHomeShareFixtures.request,
                MiniHomeShareFixtures.createResponse(),
            ) is MiniHomeShareLinkDecode.Link
        )
    }

    @Test
    fun `a local emulator endpoint is still rejected on the client`() {
        assertEquals(
            MiniHomeShareLinkDecode.Malformed,
            MiniHomeShareLinkCodec.decodeCreate(
                MiniHomeShareFixtures.request,
                MiniHomeShareFixtures.createResponse() +
                    ("url" to "http://127.0.0.1:5001/m?token=${MiniHomeShareFixtures.TOKEN}"),
            ),
        )
    }

    // 로그 안전성

    @Test
    fun `link never exposes its url or token in a diagnostic description`() {
        val link = MiniHomeShareFixtures.link()

        assertFalse(link.toString().contains(MiniHomeShareFixtures.TOKEN))
        assertFalse(link.toString().contains(MiniHomeShareFixtures.URL))
        assertTrue(link.toString().contains(MiniHomeShareFixtures.SHARE_ID))
        assertEquals(MiniHomeShareFixtures.URL, link.url)
    }

    @Test
    fun `offline and deadline failures are retryable and every other failure is permanent`() {
        assertTrue(MiniHomeShareFailure.OFFLINE.retryable)
        assertTrue(MiniHomeShareFailure.DEADLINE.retryable)
        assertFalse(MiniHomeShareFailure.MALFORMED_RESPONSE.retryable)
        assertFalse(MiniHomeShareFailure.PERMISSION_DENIED.retryable)
        assertFalse(MiniHomeShareFailure.REVISION_CONFLICT.retryable)
        assertFalse(MiniHomeShareFailure.INVALID_REQUEST.retryable)
    }

    @Test
    fun `expiry is rendered as human korean text without exposing raw timestamps`() {
        val expiry =
            miniHomeShareExpiryText(
                expiresAt = MiniHomeShareFixtures.expiresAt,
                zone = ZoneId.of("Asia/Seoul"),
            )

        assertEquals("2026년 9월 21일 9시까지 볼 수 있어요", expiry)
        assertFalse(expiry.contains("T"))
        assertFalse(expiry.contains("Z"))
    }
}
