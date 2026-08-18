package com.planterior.helper.feature.registration

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlant
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.RegistrationMethod
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.camera.PhotoError
import com.planterior.helper.feature.camera.PreparedPhoto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface RegistrationSeed {
    data object Manual : RegistrationSeed

    data class Identified(
        val content: RegistrationContent,
        val requestId: String? = null,
    ) : RegistrationSeed
}

data class RegistrationContent(val id: PlantContentId, val name: String)

data class ExistingPersonalPlant(val id: PersonalPlantId, val displayName: String)

data class RegistrationSession(val accountId: AccountId, val zoneId: ZoneId)

sealed interface RepresentativePhoto {
    val extension: String
    val contentType: String

    data class Bytes(
        val bytes: ByteArray,
        override val extension: String,
        override val contentType: String,
    ) : RepresentativePhoto

    data class Prepared(val photo: PreparedPhoto) : RepresentativePhoto {
        override val extension =
            when (photo.mime) {
                com.planterior.helper.feature.camera.PhotoMime.Jpeg -> "jpg"
                com.planterior.helper.feature.camera.PhotoMime.Png -> "png"
                com.planterior.helper.feature.camera.PhotoMime.Webp -> "webp"
                com.planterior.helper.feature.camera.PhotoMime.Heif -> "heic"
            }
        override val contentType =
            when (photo.mime) {
                com.planterior.helper.feature.camera.PhotoMime.Jpeg -> "image/jpeg"
                com.planterior.helper.feature.camera.PhotoMime.Png -> "image/png"
                com.planterior.helper.feature.camera.PhotoMime.Webp -> "image/webp"
                com.planterior.helper.feature.camera.PhotoMime.Heif -> "image/heic"
            }
    }

    data class IdentificationOriginal(val requestId: String) : RepresentativePhoto {
        override val extension = "webp"
        override val contentType = "image/webp"
    }
}

data class RegistrationDraft(
    val plantId: PersonalPlantId,
    val operationId: OperationId?,
    val name: String,
    val selectedContent: RegistrationContent?,
    val photo: RepresentativePhoto?,
    val lastWateredDate: String?,
    val duplicateApprovalFor: PlantContentId? = null,
    val photoError: PhotoError? = null,
)

data class PendingRegistration(
    val accountId: AccountId,
    val plantId: PersonalPlantId,
    val operationId: OperationId,
    val displayName: String,
    val contentId: PlantContentId?,
    val method: RegistrationMethod,
    val photo: RepresentativePhoto?,
    val lastWateredDate: LocalDate?,
) {
    fun toPersonalPlant(
        revision: Long,
        updatedAt: Instant,
        photoPath: String? = null,
    ) =
        PersonalPlant(
            plantId,
            displayName,
            contentId,
            method,
            photoPath,
            null,
            null,
            lastWateredDate,
            Revision(revision),
            updatedAt,
        )
}

enum class RegistrationValidationError {
    NAME_REQUIRED,
    NAME_TOO_LONG,
    INVALID_LAST_WATERED_DATE,
    FUTURE_LAST_WATERED_DATE,
}

enum class RegistrationFailure {
    UNAUTHENTICATED,
    PROFILE_UNAVAILABLE,
    SEARCH_UNAVAILABLE,
    DUPLICATE_CHECK_UNAVAILABLE,
    PHOTO_UPLOAD_FAILED,
    REMOTE_WRITE_FAILED,
    REVISION_CONFLICT,
    CACHE_WRITE_FAILED,
    INCONSISTENT_RECEIPT,
    DATABASE_UNAVAILABLE,
    OUTBOX_MISMATCH,
}

sealed interface RegistrationCheckpoint {
    data object NotStarted : RegistrationCheckpoint

    data class PhotoStored(val path: String) : RegistrationCheckpoint

    data class PlantCommitted(val revision: Long, val photoPath: String?) : RegistrationCheckpoint
}

sealed interface RegistrationAttempt {
    data class Completed(val plant: PersonalPlant) : RegistrationAttempt

    data class Failed(
        val failure: RegistrationFailure,
        val checkpoint: RegistrationCheckpoint,
    ) : RegistrationAttempt
}

sealed interface RegistrationSearchState {
    data object Idle : RegistrationSearchState

    data object Loading : RegistrationSearchState

    data class Results(val items: List<RegistrationContent>) : RegistrationSearchState

    data object Empty : RegistrationSearchState

    data object Failed : RegistrationSearchState
}

sealed interface RegistrationUiState {
    data object LoadingSession : RegistrationUiState

    data class SessionFailed(val failure: RegistrationFailure) : RegistrationUiState

    data class Editing(
        val draft: RegistrationDraft,
        val search: RegistrationSearchState = RegistrationSearchState.Idle,
        val errors: Set<RegistrationValidationError> = emptySet(),
        val failure: RegistrationFailure? = null,
    ) : RegistrationUiState

    data class CheckingDuplicates(val draft: RegistrationDraft) : RegistrationUiState

    data class DuplicateFound(
        val draft: RegistrationDraft,
        val existing: List<ExistingPersonalPlant>,
    ) : RegistrationUiState

    data class Saving(
        val submission: PendingRegistration,
        val checkpoint: RegistrationCheckpoint,
    ) : RegistrationUiState

    data class SaveFailed(
        val submission: PendingRegistration,
        val checkpoint: RegistrationCheckpoint,
        val failure: RegistrationFailure,
    ) : RegistrationUiState

    data class Completed(val plant: PersonalPlant) : RegistrationUiState
}
