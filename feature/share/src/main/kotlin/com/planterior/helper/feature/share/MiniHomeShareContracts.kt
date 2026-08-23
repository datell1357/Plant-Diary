package com.planterior.helper.feature.share

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.MiniHomeDecorationChoice
import com.planterior.helper.feature.minihome.MiniHomeLayout
import com.planterior.helper.feature.minihome.MiniHomePlantChoice
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * 공개 링크의 불투명 식별자이다.
 *
 * 서버는 SHA-256 결과를 base64url로 인코딩해 항상 정확히 43자를 만든다. 길이나 문자 집합을 느슨하게 두면 서버가 절대 만들지 않는 값도 받아들이게 되므로
 * 정확한 형태만 허용한다. 링크 URL 자체는 bearer 데이터라 로그나 route 인자에 넣지 않고 이 ID만 진단에 쓴다.
 */
@JvmInline
value class MiniHomeShareId(val value: String) {
    init {
        require(BASE64URL_256.matches(value)) {
            "Share ID must be a 43 character base64url identifier"
        }
    }
}

/** base64url로 인코딩한 256비트 값의 정확한 형태이다. 패딩은 없다. */
internal val BASE64URL_256 = Regex("^[A-Za-z0-9_-]{43}$")

/**
 * `Date#toISOString`이 만드는 정규 UTC 표기만 허용한다.
 *
 * 오프셋 표기나 `Z` 누락을 받아들이면 서버가 만들지 않는 값을 조용히 해석하게 된다.
 */
internal val CANONICAL_INSTANT = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")

/** JS가 안전하게 표현하는 정수 상한이다. 서버 계약과 같은 값을 쓴다. */
internal const val MAX_SAFE_REVISION = 9_007_199_254_740_991L

/**
 * 공개 링크 URL이 서버가 실제로 만드는 형태인지 검사한다.
 *
 * https만 허용하고, 호스트가 있어야 하며, 자격 정보나 fragment를 담을 수 없다. 질의 문자열은 43자 base64url `token` 하나뿐이어야 한다. 다른
 * 인자가 붙어 있으면 소유자 식별자 같은 bearer 자료가 함께 실려 온 것이므로 거부한다.
 */
internal fun isCanonicalShareUrl(value: String): Boolean {
    if (value.isEmpty() || value.any { it.isISOControl() || it.isWhitespace() }) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    if (uri.scheme != "https") return false
    if (uri.host.isNullOrBlank()) return false
    if (uri.userInfo != null || uri.rawFragment != null) return false
    if (uri.isOpaque) return false
    val query = uri.rawQuery ?: return false
    val parameters = query.split('&')
    if (parameters.size != 1) return false
    val token = parameters.single().removePrefix("token=")
    if (token == parameters.single()) return false
    return BASE64URL_256.matches(token)
}

/**
 * 서버가 발급한 공개 링크이다.
 *
 * [url]은 이 값을 아는 사람이면 누구나 열 수 있는 bearer 자격이다. [toString]은 URL을 절대 포함하지 않아 실수로 로그에 남지 않게 한다.
 */
class MiniHomeShareLink(
    val shareId: MiniHomeShareId,
    val url: String,
    val sourceRevision: Revision,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(isCanonicalShareUrl(url)) { "Share URL must be a canonical HTTPS token link" }
        require(sourceRevision.value >= 1) { "A shared link must reference a saved revision" }
        require(Duration.between(createdAt, expiresAt) == LIFETIME) {
            "Share link must expire exactly 30 days after it is created"
        }
    }

    companion object {
        val LIFETIME: Duration = Duration.ofHours(30 * 24L)
    }

    override fun equals(other: Any?): Boolean =
        other is MiniHomeShareLink &&
            shareId == other.shareId &&
            url == other.url &&
            sourceRevision == other.sourceRevision &&
            createdAt == other.createdAt &&
            expiresAt == other.expiresAt

    override fun hashCode(): Int {
        var result = shareId.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + sourceRevision.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        return result
    }

    /** URL은 bearer 데이터라 진단 문자열에서 제외한다. */
    override fun toString(): String =
        "MiniHomeShareLink(shareId=${shareId.value}, sourceRevision=${sourceRevision.value})"
}

/**
 * 링크 생성 실패 원인이다.
 *
 * 연결이 끊겼거나 응답을 기다리다 만료된 경우에만 같은 요청을 그대로 다시 보낼 수 있다. [DEADLINE]은 서버가 이미 커밋했을 수도 있는 모호한 실패라 반드시 같은
 * operation ID로 재생해야 링크가 중복 생성되지 않는다.
 */
enum class MiniHomeShareFailure {
    OFFLINE,
    DEADLINE,
    PERMISSION_DENIED,
    REVISION_CONFLICT,
    INVALID_REQUEST,
    MALFORMED_RESPONSE;

    val retryable: Boolean
        get() = this == OFFLINE || this == DEADLINE
}

data class MiniHomeShareLinkRequest(
    val operationId: OperationId,
    val expectedRevision: Revision,
) {
    fun callablePayload(): Map<String, Any> =
        mapOf("operationId" to operationId.value, "expectedRevision" to expectedRevision.value)
}

data class MiniHomeShareRevokeRequest(val shareId: MiniHomeShareId) {
    fun callablePayload(): Map<String, Any> = mapOf("shareId" to shareId.value)
}

sealed interface MiniHomeShareLinkDecode {
    data class Link(val link: MiniHomeShareLink) : MiniHomeShareLinkDecode

    data object Malformed : MiniHomeShareLinkDecode
}

sealed interface MiniHomeShareRevokeDecode {
    data class Revoked(val revokedAt: Instant) : MiniHomeShareRevokeDecode

    data object Malformed : MiniHomeShareRevokeDecode
}

/**
 * callable 응답을 도메인 값으로 옮긴다.
 *
 * 서버는 시각을 ISO-8601 UTC 문자열로만 보낸다. epoch 밀리초나 오프셋 표기를 함께 받아들이면 계약이 조용히 갈라지므로 정규 표기 하나만 해석한다. 계약에 없는
 * 필드가 있으면 응답 자체가 다른 계약이라는 뜻이라 거부한다.
 */
object MiniHomeShareLinkCodec {
    private val CREATE_FIELDS = setOf("shareId", "url", "sourceRevision", "createdAt", "expiresAt")
    private val REVOKE_FIELDS = setOf("shareId", "revokedAt")

    fun decodeCreate(request: MiniHomeShareLinkRequest, response: Any?): MiniHomeShareLinkDecode {
        val map = response.exactRecord(CREATE_FIELDS) ?: return MiniHomeShareLinkDecode.Malformed
        val shareId =
            (map["shareId"] as? String)?.let { runCatching { MiniHomeShareId(it) }.getOrNull() }
                ?: return MiniHomeShareLinkDecode.Malformed
        val url =
            (map["url"] as? String)?.takeIf(::isCanonicalShareUrl)
                ?: return MiniHomeShareLinkDecode.Malformed
        val sourceRevision =
            map.safeRevision("sourceRevision") ?: return MiniHomeShareLinkDecode.Malformed
        val createdAt = map.instant("createdAt") ?: return MiniHomeShareLinkDecode.Malformed
        val expiresAt = map.instant("expiresAt") ?: return MiniHomeShareLinkDecode.Malformed
        if (sourceRevision != request.expectedRevision) return MiniHomeShareLinkDecode.Malformed
        if (Duration.between(createdAt, expiresAt) != MiniHomeShareLink.LIFETIME) {
            return MiniHomeShareLinkDecode.Malformed
        }
        return MiniHomeShareLinkDecode.Link(
            MiniHomeShareLink(shareId, url, sourceRevision, createdAt, expiresAt)
        )
    }

    fun decodeRevoke(
        request: MiniHomeShareRevokeRequest,
        response: Any?,
    ): MiniHomeShareRevokeDecode {
        val map = response.exactRecord(REVOKE_FIELDS) ?: return MiniHomeShareRevokeDecode.Malformed
        if (map["shareId"] != request.shareId.value) return MiniHomeShareRevokeDecode.Malformed
        val revokedAt = map.instant("revokedAt") ?: return MiniHomeShareRevokeDecode.Malformed
        return MiniHomeShareRevokeDecode.Revoked(revokedAt)
    }

    /** 정확히 기대한 키 집합만 가진 객체만 통과시킨다. */
    private fun Any?.exactRecord(fields: Set<String>): Map<*, *>? {
        val map = this as? Map<*, *> ?: return null
        val keys = map.keys.filterIsInstance<String>().toSet()
        return map.takeIf { keys.size == map.size && keys == fields }
    }

    private fun Map<*, *>.safeRevision(field: String): Revision? {
        val number = this[field]
        if (number !is Number || number is Double || number is Float) return null
        val value = number.toLong()
        if (value < 1 || value > MAX_SAFE_REVISION) return null
        return runCatching { Revision(value) }.getOrNull()
    }

    private fun Map<*, *>.instant(field: String): Instant? {
        val text = this[field] as? String ?: return null
        if (!CANONICAL_INSTANT.matches(text)) return null
        return try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/** 만료 시각을 사람이 읽는 한국어로 옮긴다. 원시 타임스탬프는 화면에 절대 노출하지 않는다. */
fun miniHomeShareExpiryText(
    expiresAt: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val local: ZonedDateTime = expiresAt.atZone(zone)
    return "${local.year}년 ${local.monthValue}월 ${local.dayOfMonth}일 ${local.hour}시까지 볼 수 있어요"
}

/** 공유 대상이 되는 확정 미니홈이다. 편집 중 draft는 절대 담기지 않는다. */
data class MiniHomeShareTarget(
    val owner: AccountId,
    val committed: MiniHomeLayout,
    val plants: List<MiniHomePlantChoice>,
    val decorations: List<MiniHomeDecorationChoice>,
)

sealed interface MiniHomeShareLoadResult {
    data class Ready(val target: MiniHomeShareTarget) : MiniHomeShareLoadResult

    data object NoTarget : MiniHomeShareLoadResult

    data object Forbidden : MiniHomeShareLoadResult

    data object Failed : MiniHomeShareLoadResult
}

sealed interface MiniHomeShareCreateResult {
    data class Created(val link: MiniHomeShareLink) : MiniHomeShareCreateResult

    data class Failed(val failure: MiniHomeShareFailure) : MiniHomeShareCreateResult
}

sealed interface MiniHomeShareRevokeResult {
    data class Revoked(val revokedAt: Instant) : MiniHomeShareRevokeResult

    data class Failed(val failure: MiniHomeShareFailure) : MiniHomeShareRevokeResult
}

interface MiniHomeShareRepository {
    /** 권위 있는 확정 구성만 다시 읽는다. 편집 중 draft는 절대 반환하지 않는다. */
    suspend fun loadCommitted(): MiniHomeShareLoadResult

    suspend fun createLink(request: MiniHomeShareLinkRequest): MiniHomeShareCreateResult

    suspend fun revokeLink(shareId: MiniHomeShareId): MiniHomeShareRevokeResult

    /** 계정 전환·로그아웃에서 이 소유자의 로컬 공유 산출물을 지운다. */
    suspend fun clearOwnerArtifacts() = Unit
}
