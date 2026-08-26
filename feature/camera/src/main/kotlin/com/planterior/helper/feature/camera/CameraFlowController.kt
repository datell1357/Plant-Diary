package com.planterior.helper.feature.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.planterior.helper.core.model.ClientProductEvent
import com.planterior.helper.core.model.ProductEventRecorder
import java.time.Clock
import java.time.Instant

sealed interface CameraPermission {
    data object NotRequested : CameraPermission

    data object Granted : CameraPermission

    data class Denied(val permanently: Boolean) : CameraPermission
}

sealed interface CameraCommand {
    data object RequestPermission : CameraCommand

    data class LaunchCamera(val temporaryUri: String) : CameraCommand

    data object LaunchPhotoPicker : CameraCommand

    data object OpenAppSettings : CameraCommand

    data object OpenDirectRegistration : CameraCommand
}

/** 사진 처리 목적과 원격 처리 수명주기를 요청마다 보여 주는 고지 계약이다. */
data class PhotoDisclosure(
    val purpose: String,
    val remoteProcessing: Boolean,
    val originalRetentionHours: Int,
    val representativePhotoStoredOnlyBySeparateChoice: Boolean,
) {
    companion object {
        val Product =
            PhotoDisclosure(
                purpose = "식물 종류 식별",
                remoteProcessing = true,
                originalRetentionHours = 24,
                representativePhotoStoredOnlyBySeparateChoice = true,
            )
    }
}

data class PhotoSubmission(
    val requestId: String,
    val photo: PreparedPhoto,
    val disclosure: PhotoDisclosure,
    val approvedAt: Instant,
)

fun interface IdentificationGateway {
    suspend fun submit(submission: PhotoSubmission)
}

sealed interface CameraFlowState {
    val draft: PreparedPhoto?

    data class Source(
        override val draft: PreparedPhoto? = null,
        val error: PhotoError? = null,
    ) : CameraFlowState

    data class PermissionBlocked(
        val permanentlyDenied: Boolean,
        override val draft: PreparedPhoto? = null,
    ) : CameraFlowState

    data class Capturing(
        val temporaryUri: String,
        override val draft: PreparedPhoto? = null,
    ) : CameraFlowState

    data class Processing(override val draft: PreparedPhoto? = null) : CameraFlowState

    data class Review(
        val photo: PreparedPhoto,
        val error: PhotoError? = null,
    ) : CameraFlowState {
        override val draft: PreparedPhoto = photo
    }

    data class Disclosure(
        val photo: PreparedPhoto,
        val requestId: String,
        val disclosure: PhotoDisclosure,
    ) : CameraFlowState {
        override val draft: PreparedPhoto = photo
    }

    data class Submitted(val submission: PhotoSubmission) : CameraFlowState {
        override val draft: PreparedPhoto = submission.photo
    }
}

data class CameraFlowSnapshot(val state: CameraFlowState)

/** 런처 호출과 제출을 상태 전이 뒤에 실행해 취소/중복 callback이 부작용을 만들지 않게 한다. */
class CameraFlowController(
    private val temporaryUriFactory: TemporaryUriFactory,
    private val requestIdFactory: RequestIdFactory,
    private val clock: Clock,
    private val gateway: IdentificationGateway,
    private val launch: (CameraCommand) -> Unit,
    private val discard: (String) -> Unit = {},
    restored: CameraFlowSnapshot? = null,
    private val productEventRecorder: ProductEventRecorder = ProductEventRecorder {},
) {
    var state: CameraFlowState by
        mutableStateOf(restored?.state.safeRestoredState() ?: CameraFlowState.Source())
        private set

    fun chooseCamera(permission: CameraPermission) {
        when (permission) {
            CameraPermission.NotRequested -> launch(CameraCommand.RequestPermission)
            CameraPermission.Granted -> launchCamera()
            is CameraPermission.Denied ->
                state = CameraFlowState.PermissionBlocked(permission.permanently, state.draft)
        }
    }

    fun cameraPermissionDenied(permanently: Boolean) {
        state = CameraFlowState.PermissionBlocked(permanently, state.draft)
    }

    fun choosePicker() {
        state = CameraFlowState.Source(state.draft)
        launch(CameraCommand.LaunchPhotoPicker)
    }

    fun openSettings() = launch(CameraCommand.OpenAppSettings)

    fun chooseDirectRegistration() {
        state.draft?.let { discard(it.privateUri) }
        state = CameraFlowState.Source()
        launch(CameraCommand.OpenDirectRegistration)
    }

    fun exit() {
        (state as? CameraFlowState.Capturing)?.temporaryUri?.let(discard)
        state.draft?.let { discard(it.privateUri) }
        state = CameraFlowState.Source()
    }

    fun captureStarted() {
        state = CameraFlowState.Processing(state.draft)
    }

    fun photoPrepared(photo: PreparedPhoto) {
        state.draft?.takeIf { it.privateUri != photo.privateUri }?.let { discard(it.privateUri) }
        state = CameraFlowState.Review(photo)
    }

    fun photoRejected(error: PhotoError) {
        val draft = state.draft
        state =
            if (draft != null) CameraFlowState.Review(draft, error)
            else CameraFlowState.Source(error = error)
    }

    fun replacePhoto() = choosePicker()

    fun pickerCancelled() {
        state.draft?.let { state = CameraFlowState.Review(it) }
    }

    fun retakePhoto(permission: CameraPermission) = chooseCamera(permission)

    fun captureCancelled() {
        (state as? CameraFlowState.Capturing)?.temporaryUri?.let(discard)
        state = state.draft?.let { CameraFlowState.Review(it) } ?: CameraFlowState.Source()
    }

    fun requestIdentification() {
        val photo = (state as? CameraFlowState.Review)?.photo ?: return
        state =
            CameraFlowState.Disclosure(photo, requestIdFactory.create(), PhotoDisclosure.Product)
    }

    fun cancelDisclosure() {
        val disclosure = state as? CameraFlowState.Disclosure ?: return
        state = CameraFlowState.Review(disclosure.photo)
    }

    suspend fun approveDisclosure() {
        val disclosureState = state as? CameraFlowState.Disclosure ?: return
        val submission =
            PhotoSubmission(
                requestId = disclosureState.requestId,
                photo = disclosureState.photo,
                disclosure = disclosureState.disclosure,
                approvedAt = clock.instant(),
            )
        state = CameraFlowState.Submitted(submission)
        try {
            gateway.submit(submission)
            productEventRecorder.record(ClientProductEvent.IDENTIFICATION_REQUEST_SUBMITTED)
        } catch (error: kotlinx.coroutines.CancellationException) {
            state = CameraFlowState.Review(disclosureState.photo, PhotoError.SubmissionFailed)
            throw error
        } catch (_: Exception) {
            state = CameraFlowState.Review(disclosureState.photo, PhotoError.SubmissionFailed)
        }
    }

    fun back() {
        when (val current = state) {
            is CameraFlowState.Disclosure -> state = CameraFlowState.Review(current.photo)
            is CameraFlowState.Capturing -> captureCancelled()
            is CameraFlowState.Processing ->
                state =
                    current.draft?.let { CameraFlowState.Review(it) } ?: CameraFlowState.Source()
            is CameraFlowState.Review -> {
                discard(current.photo.privateUri)
                state = CameraFlowState.Source()
            }
            else -> Unit
        }
    }

    fun snapshot(): CameraFlowSnapshot = CameraFlowSnapshot(state)

    private fun launchCamera() {
        val uri = temporaryUriFactory.create()
        state = CameraFlowState.Capturing(uri, state.draft)
        launch(CameraCommand.LaunchCamera(uri))
    }
}

private fun CameraFlowState?.safeRestoredState(): CameraFlowState? =
    when (this) {
        is CameraFlowState.Capturing ->
            draft?.let { CameraFlowState.Review(it) } ?: CameraFlowState.Source()
        is CameraFlowState.Processing ->
            draft?.let { CameraFlowState.Review(it) } ?: CameraFlowState.Source()
        else -> this
    }
