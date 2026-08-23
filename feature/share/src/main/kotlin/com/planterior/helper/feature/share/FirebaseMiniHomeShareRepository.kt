package com.planterior.helper.feature.share

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.minihome.MiniHomeLoadResult
import com.planterior.helper.feature.minihome.MiniHomeRepository
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 공유 링크 callable을 감싸는 저장소이다.
 *
 * 확정 구성은 미니 식물원 저장소를 그대로 다시 읽어 draft가 끼어들 수 없게 한다. Auth와 AppCheck는 서버가 강제하고, 여기서는 응답 계약만 검사한다.
 */
class FirebaseMiniHomeShareRepository(
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions,
    private val miniHomeRepository: MiniHomeRepository,
    private val imageStore: MiniHomeShareImageStore? = null,
) : MiniHomeShareRepository {
    override suspend fun loadCommitted(): MiniHomeShareLoadResult =
        when (val loaded = miniHomeRepository.load()) {
            is MiniHomeLoadResult.Ready ->
                // 저장된 적 없는 미니홈만 공유 대상이 아니다. 빈 방이라도 확정 revision이 있으면 공유할 수 있다.
                if (loaded.committed.revision.value < 1) {
                    MiniHomeShareLoadResult.NoTarget
                } else {
                    MiniHomeShareLoadResult.Ready(
                        MiniHomeShareTarget(
                            owner = loaded.accountId,
                            committed = loaded.committed,
                            plants = loaded.plants,
                            decorations = loaded.decorations,
                        )
                    )
                }
            MiniHomeLoadResult.Forbidden -> MiniHomeShareLoadResult.Forbidden
            MiniHomeLoadResult.Failed -> MiniHomeShareLoadResult.Failed
        }

    override suspend fun createLink(request: MiniHomeShareLinkRequest): MiniHomeShareCreateResult {
        val owner = activeAccount() ?: return permissionDenied()
        return try {
            val response =
                functions
                    .getHttpsCallable(CREATE_CALLABLE)
                    .call(request.callablePayload())
                    .await()
                    .data
            ensureAccount(owner)
            when (val decoded = MiniHomeShareLinkCodec.decodeCreate(request, response)) {
                is MiniHomeShareLinkDecode.Link -> MiniHomeShareCreateResult.Created(decoded.link)
                MiniHomeShareLinkDecode.Malformed ->
                    MiniHomeShareCreateResult.Failed(MiniHomeShareFailure.MALFORMED_RESPONSE)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: FirebaseFunctionsException) {
            MiniHomeShareCreateResult.Failed(mapShareFailure(error.code))
        } catch (_: IOException) {
            MiniHomeShareCreateResult.Failed(MiniHomeShareFailure.OFFLINE)
        } catch (_: Exception) {
            MiniHomeShareCreateResult.Failed(MiniHomeShareFailure.MALFORMED_RESPONSE)
        }
    }

    override suspend fun revokeLink(shareId: MiniHomeShareId): MiniHomeShareRevokeResult {
        val owner =
            activeAccount()
                ?: return MiniHomeShareRevokeResult.Failed(MiniHomeShareFailure.PERMISSION_DENIED)
        return try {
            val response =
                functions
                    .getHttpsCallable(REVOKE_CALLABLE)
                    .call(MiniHomeShareRevokeRequest(shareId).callablePayload())
                    .await()
                    .data
            ensureAccount(owner)
            when (
                val decoded =
                    MiniHomeShareLinkCodec.decodeRevoke(
                        MiniHomeShareRevokeRequest(shareId),
                        response,
                    )
            ) {
                is MiniHomeShareRevokeDecode.Revoked ->
                    MiniHomeShareRevokeResult.Revoked(decoded.revokedAt)
                MiniHomeShareRevokeDecode.Malformed ->
                    MiniHomeShareRevokeResult.Failed(MiniHomeShareFailure.MALFORMED_RESPONSE)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: FirebaseFunctionsException) {
            MiniHomeShareRevokeResult.Failed(mapShareFailure(error.code))
        } catch (_: IOException) {
            MiniHomeShareRevokeResult.Failed(MiniHomeShareFailure.OFFLINE)
        } catch (_: Exception) {
            MiniHomeShareRevokeResult.Failed(MiniHomeShareFailure.MALFORMED_RESPONSE)
        }
    }

    override suspend fun clearOwnerArtifacts() {
        imageStore?.clear()
    }

    private fun activeAccount(): AccountId? = auth.currentUser?.uid?.let(::AccountId)

    private fun ensureAccount(expected: AccountId) {
        if (activeAccount() != expected) throw SecurityException("Active account changed")
    }

    private fun permissionDenied() =
        MiniHomeShareCreateResult.Failed(MiniHomeShareFailure.PERMISSION_DENIED)

    private companion object {
        const val CREATE_CALLABLE = "createMiniHomeShareLink"
        const val REVOKE_CALLABLE = "revokeMiniHomeShareLink"
    }
}

/** callable 오류 코드를 공유 실패 원인으로 옮긴다. 오프라인 계열만 같은 요청으로 재시도할 수 있다. */
internal fun mapShareFailure(code: FirebaseFunctionsException.Code): MiniHomeShareFailure =
    when (code) {
        FirebaseFunctionsException.Code.UNAVAILABLE,
        FirebaseFunctionsException.Code.CANCELLED,
        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> MiniHomeShareFailure.OFFLINE
        // 응답을 못 받았을 뿐 서버는 이미 링크를 만들었을 수 있다. 같은 요청을 그대로 재생해야 안전하다.
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> MiniHomeShareFailure.DEADLINE
        FirebaseFunctionsException.Code.PERMISSION_DENIED,
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> MiniHomeShareFailure.PERMISSION_DENIED
        FirebaseFunctionsException.Code.ABORTED,
        FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
            MiniHomeShareFailure.REVISION_CONFLICT
        FirebaseFunctionsException.Code.INVALID_ARGUMENT,
        FirebaseFunctionsException.Code.NOT_FOUND,
        FirebaseFunctionsException.Code.ALREADY_EXISTS,
        FirebaseFunctionsException.Code.OUT_OF_RANGE,
        FirebaseFunctionsException.Code.UNIMPLEMENTED -> MiniHomeShareFailure.INVALID_REQUEST
        FirebaseFunctionsException.Code.DATA_LOSS,
        FirebaseFunctionsException.Code.UNKNOWN,
        FirebaseFunctionsException.Code.INTERNAL,
        FirebaseFunctionsException.Code.OK -> MiniHomeShareFailure.MALFORMED_RESPONSE
    }

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}
