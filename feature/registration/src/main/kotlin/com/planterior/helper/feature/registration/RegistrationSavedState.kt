package com.planterior.helper.feature.registration

import android.os.Bundle
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.RegistrationMethod
import com.planterior.helper.feature.camera.PhotoMime
import com.planterior.helper.feature.camera.PhotoSource
import com.planterior.helper.feature.camera.PreparedPhoto
import java.time.LocalDate
import java.time.ZoneId

internal data class RestoredRegistration(
    val session: RegistrationSession?,
    val draft: RegistrationDraft,
    val state: RegistrationUiState,
)

internal object RegistrationSavedState {
    private const val KEY = "registration.state"

    fun restore(handle: androidx.lifecycle.SavedStateHandle?): RestoredRegistration? =
        handle?.get<Bundle>(KEY)?.decode()

    fun save(
        handle: androidx.lifecycle.SavedStateHandle?,
        session: RegistrationSession?,
        draft: RegistrationDraft,
        state: RegistrationUiState,
    ) {
        handle?.set(
            KEY,
            Bundle().apply {
                putString("account", session?.accountId?.value)
                putString("zone", session?.zoneId?.id)
                putBundle("draft", draft.bundle())
                putBundle("ui", state.bundle())
            },
        )
    }

    private fun Bundle.decode(): RestoredRegistration? {
        val draft = getBundle("draft")?.draft() ?: return null
        val session =
            getString("account")?.let { account ->
                RegistrationSession(
                    AccountId(account),
                    ZoneId.of(requireNotNull(getString("zone"))),
                )
            }
        val state = getBundle("ui")?.ui(draft) ?: RegistrationUiState.Editing(draft)
        return RestoredRegistration(session, draft, state)
    }
}

private fun RegistrationDraft.bundle() =
    Bundle().apply {
        putString("plant", plantId.value)
        putString("operation", operationId?.value)
        putString("name", name)
        putString("content", selectedContent?.id?.value)
        putString("contentName", selectedContent?.name)
        putBundle("photo", photo.bundle())
        putString("watered", lastWateredDate)
        putString("approval", duplicateApprovalFor?.value)
        putString("photoError", photoError?.javaClass?.simpleName)
    }

private fun Bundle.draft() =
    RegistrationDraft(
        PersonalPlantId(requireNotNull(getString("plant"))),
        getString("operation")?.let(::OperationId),
        getString("name").orEmpty(),
        getString("content")?.let {
            RegistrationContent(PlantContentId(it), requireNotNull(getString("contentName")))
        },
        getBundle("photo")?.photo(),
        getString("watered"),
        getString("approval")?.let(::PlantContentId),
    )

private fun RepresentativePhoto?.bundle(): Bundle? =
    when (this) {
        null -> null
        is RepresentativePhoto.Bytes -> null // Raw user bytes are never written to saved state.
        is RepresentativePhoto.IdentificationOriginal ->
            Bundle().apply {
                putString("kind", "identification")
                putString("request", requestId)
            }
        is RepresentativePhoto.Prepared ->
            Bundle().apply {
                putString("kind", "prepared")
                putString("uri", photo.privateUri)
                putString("mime", photo.mime.name)
                putLong("bytes", photo.byteSize)
                putInt("width", photo.width)
                putInt("height", photo.height)
                putInt("rotation", photo.rotationDegrees)
                putString("source", photo.source.name)
                putBoolean("mirrored", photo.mirroredHorizontally)
            }
    }

private fun Bundle.photo(): RepresentativePhoto? =
    when (getString("kind")) {
        "identification" ->
            RepresentativePhoto.IdentificationOriginal(requireNotNull(getString("request")))
        "prepared" ->
            RepresentativePhoto.Prepared(
                PreparedPhoto(
                    requireNotNull(getString("uri")),
                    PhotoMime.valueOf(requireNotNull(getString("mime"))),
                    getLong("bytes"),
                    getInt("width"),
                    getInt("height"),
                    getInt("rotation"),
                    PhotoSource.valueOf(requireNotNull(getString("source"))),
                    getBoolean("mirrored"),
                )
            )
        else -> null
    }

private fun PendingRegistration.bundle() =
    Bundle().apply {
        putString("account", accountId.value)
        putString("plant", plantId.value)
        putString("operation", operationId.value)
        putString("name", displayName)
        putString("content", contentId?.value)
        putString("method", method.name)
        putBundle("photo", photo.bundle())
        putString("watered", lastWateredDate?.toString())
    }

private fun Bundle.pending() =
    PendingRegistration(
        AccountId(requireNotNull(getString("account"))),
        PersonalPlantId(requireNotNull(getString("plant"))),
        OperationId(requireNotNull(getString("operation"))),
        requireNotNull(getString("name")),
        getString("content")?.let(::PlantContentId),
        RegistrationMethod.valueOf(requireNotNull(getString("method"))),
        getBundle("photo")?.photo(),
        getString("watered")?.let(LocalDate::parse),
    )

private fun RegistrationCheckpoint.bundle() =
    Bundle().apply {
        when (this@bundle) {
            RegistrationCheckpoint.NotStarted -> putString("kind", "new")
            is RegistrationCheckpoint.PhotoStored -> {
                putString("kind", "photo")
                putString("path", path)
            }
            is RegistrationCheckpoint.PlantCommitted -> {
                putString("kind", "committed")
                putLong("revision", revision)
                putString("path", photoPath)
            }
        }
    }

private fun Bundle.checkpoint(): RegistrationCheckpoint =
    when (getString("kind")) {
        "photo" -> RegistrationCheckpoint.PhotoStored(requireNotNull(getString("path")))
        "committed" -> RegistrationCheckpoint.PlantCommitted(getLong("revision"), getString("path"))
        else -> RegistrationCheckpoint.NotStarted
    }

private fun RegistrationUiState.bundle() =
    Bundle().apply {
        when (this@bundle) {
            RegistrationUiState.LoadingSession -> putString("kind", "loading")
            is RegistrationUiState.SessionFailed -> {
                putString("kind", "sessionFailed")
                putString("failure", failure.name)
            }
            is RegistrationUiState.Editing -> {
                putString("kind", "editing")
                putString("failure", failure?.name)
            }
            is RegistrationUiState.CheckingDuplicates -> putString("kind", "checking")
            is RegistrationUiState.DuplicateFound -> {
                putString("kind", "duplicate")
                putStringArrayList("ids", ArrayList(existing.map { it.id.value }))
                putStringArrayList("names", ArrayList(existing.map { it.displayName }))
            }
            is RegistrationUiState.Saving -> {
                putString("kind", "saving")
                putBundle("submission", submission.bundle())
                putBundle("checkpoint", checkpoint.bundle())
            }
            is RegistrationUiState.SaveFailed -> {
                putString("kind", "saveFailed")
                putBundle("submission", submission.bundle())
                putBundle("checkpoint", checkpoint.bundle())
                putString("failure", failure.name)
            }
            is RegistrationUiState.Completed -> putString("kind", "completed")
        }
    }

private fun Bundle.ui(draft: RegistrationDraft): RegistrationUiState =
    when (getString("kind")) {
        "loading" -> RegistrationUiState.LoadingSession
        "sessionFailed" ->
            RegistrationUiState.SessionFailed(
                RegistrationFailure.valueOf(requireNotNull(getString("failure")))
            )
        "editing" ->
            RegistrationUiState.Editing(
                draft,
                failure = getString("failure")?.let(RegistrationFailure::valueOf),
            )
        "checking" ->
            RegistrationUiState.Editing(
                draft,
                failure = RegistrationFailure.DUPLICATE_CHECK_UNAVAILABLE,
            )
        "duplicate" -> {
            val ids = getStringArrayList("ids").orEmpty()
            val names = getStringArrayList("names").orEmpty()
            RegistrationUiState.DuplicateFound(
                draft,
                ids.zip(names).map { (id, name) ->
                    ExistingPersonalPlant(PersonalPlantId(id), name)
                },
            )
        }
        "saving",
        "saveFailed" -> {
            val submission = requireNotNull(getBundle("submission")).pending()
            val checkpoint = requireNotNull(getBundle("checkpoint")).checkpoint()
            RegistrationUiState.SaveFailed(
                submission,
                checkpoint,
                getString("failure")?.let(RegistrationFailure::valueOf)
                    ?: RegistrationFailure.REMOTE_WRITE_FAILED,
            )
        }
        else -> RegistrationUiState.Editing(draft)
    }
