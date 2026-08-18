package com.planterior.helper.feature.registration

import android.icu.text.BreakIterator
import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.RegistrationMethod
import com.planterior.helper.feature.camera.PhotoError
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RegistrationController(
    private val seed: RegistrationSeed,
    private val repository: RegistrationRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val idFactory: () -> PersonalPlantId = {
        PersonalPlantId("plant-${UUID.randomUUID().toString().replace("-", "")}")
    },
    private val onOpenExisting: (PersonalPlantId) -> Unit = {},
    private val savedStateHandle: SavedStateHandle? = null,
) {
    private val restored = RegistrationSavedState.restore(savedStateHandle)
    private var session: RegistrationSession? = restored?.session
    private val initialContent = (seed as? RegistrationSeed.Identified)?.content
    private val initialName = initialContent?.name.orEmpty()
    private var currentDraft =
        restored?.draft
            ?: RegistrationDraft(
                idFactory(),
                null,
                initialName,
                initialContent,
                null,
                null,
            )
    private val _state =
        MutableStateFlow(restored?.state ?: RegistrationUiState.Editing(currentDraft))
    val state: StateFlow<RegistrationUiState> = _state.asStateFlow()
    private var searchGeneration = 0L

    init {
        saveState()
    }

    suspend fun start() {
        if (session == null) {
            setState(RegistrationUiState.LoadingSession)
            val loaded =
                try {
                    repository.session()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    setState(
                        RegistrationUiState.SessionFailed(RegistrationFailure.PROFILE_UNAVAILABLE)
                    )
                    return
                }
            session = loaded
            currentDraft =
                currentDraft.copy(
                    operationId =
                        currentDraft.operationId
                            ?: OperationId.stable(
                                loaded.accountId,
                                currentDraft.plantId.value,
                                "register",
                                currentDraft.plantId.value,
                            )
                )
        }
        when (val current = _state.value) {
            is RegistrationUiState.SaveFailed -> reconcileRestoredFailure(current)
            RegistrationUiState.LoadingSession,
            is RegistrationUiState.SessionFailed ->
                setState(RegistrationUiState.Editing(currentDraft))
            else -> saveState()
        }
    }

    fun editing(): RegistrationUiState.Editing =
        _state.value as? RegistrationUiState.Editing ?: error("Registration is not editable")

    fun changeName(value: String) = updateDraft { draft ->
        val selected = draft.selectedContent?.takeIf { value == it.name }
        draft.copy(
            name = value,
            selectedContent = selected,
            duplicateApprovalFor = null,
        )
    }

    fun changeLastWateredDate(value: String?) = updateDraft {
        it.copy(lastWateredDate = value?.takeIf(String::isNotBlank))
    }

    fun selectContent(content: RegistrationContent) = updateDraft {
        it.copy(
            name = content.name,
            selectedContent = content,
            duplicateApprovalFor = null,
        )
    }

    fun setPhoto(photo: RepresentativePhoto?) = updateDraft {
        it.copy(photo = photo, photoError = null)
    }

    fun rejectPhoto(error: PhotoError) = updateDraft { it.copy(photo = null, photoError = error) }

    suspend fun search(query: String) {
        val current = _state.value as? RegistrationUiState.Editing ?: return
        val generation = ++searchGeneration
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            setState(current.copy(search = RegistrationSearchState.Empty))
            return
        }
        setState(current.copy(search = RegistrationSearchState.Loading, failure = null))
        val result =
            try {
                repository.searchPublicContents(normalized)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == searchGeneration && _state.value is RegistrationUiState.Editing) {
                    setState(editing().copy(search = RegistrationSearchState.Failed))
                }
                return
            }
        if (generation != searchGeneration || _state.value !is RegistrationUiState.Editing) return
        setState(
            editing()
                .copy(
                    search =
                        if (result.isEmpty()) RegistrationSearchState.Empty
                        else RegistrationSearchState.Results(result)
                )
        )
    }

    suspend fun submit() {
        val current = _state.value as? RegistrationUiState.Editing ?: return
        val loaded = session
        if (loaded == null) {
            setState(RegistrationUiState.SessionFailed(RegistrationFailure.PROFILE_UNAVAILABLE))
            return
        }
        val (normalized, errors) = validate(current.draft, loaded)
        currentDraft = normalized
        if (errors.isNotEmpty()) {
            setState(current.copy(draft = normalized, errors = errors, failure = null))
            return
        }
        val contentId = normalized.selectedContent?.id
        if (contentId != null && normalized.duplicateApprovalFor != contentId) {
            setState(RegistrationUiState.CheckingDuplicates(normalized))
            val duplicates =
                try {
                    repository.findDuplicates(loaded.accountId, contentId, normalized.plantId)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    setState(
                        RegistrationUiState.Editing(
                            normalized,
                            failure = RegistrationFailure.DUPLICATE_CHECK_UNAVAILABLE,
                        )
                    )
                    return
                }
            if (duplicates.isNotEmpty()) {
                setState(RegistrationUiState.DuplicateFound(normalized, duplicates))
                return
            }
        }
        save(normalized, loaded, RegistrationCheckpoint.NotStarted)
    }

    fun openExisting(id: PersonalPlantId) = onOpenExisting(id)

    /**
     * State-only helper retained for controller tests; the production duplicate cancel exits the
     * route.
     */
    fun cancelDuplicate() {
        val duplicate = _state.value as? RegistrationUiState.DuplicateFound ?: return
        setState(RegistrationUiState.Editing(duplicate.draft))
    }

    suspend fun addAnother() {
        val duplicate = _state.value as? RegistrationUiState.DuplicateFound ?: return
        val loaded = session ?: return
        val approved =
            duplicate.draft.copy(duplicateApprovalFor = duplicate.draft.selectedContent?.id)
        currentDraft = approved
        save(approved, loaded, RegistrationCheckpoint.NotStarted)
    }

    suspend fun retry() {
        when (val current = _state.value) {
            is RegistrationUiState.SessionFailed -> {
                session = null
                start()
            }
            is RegistrationUiState.SaveFailed -> persist(current.submission, current.checkpoint)
            is RegistrationUiState.Editing ->
                if (current.failure == RegistrationFailure.DUPLICATE_CHECK_UNAVAILABLE) submit()
            else -> Unit
        }
    }

    private suspend fun reconcileRestoredFailure(failed: RegistrationUiState.SaveFailed) {
        val checkpoint =
            try {
                repository.reconcileCheckpoint(failed.submission, failed.checkpoint)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                setState(failed.copy(failure = RegistrationFailure.DATABASE_UNAVAILABLE))
                return
            }
        setState(failed.copy(checkpoint = checkpoint))
    }

    private suspend fun save(
        draft: RegistrationDraft,
        loaded: RegistrationSession,
        checkpoint: RegistrationCheckpoint,
    ) {
        val operation = checkNotNull(draft.operationId)
        val selected = draft.selectedContent
        val method =
            when (seed) {
                RegistrationSeed.Manual -> RegistrationMethod.MANUAL
                is RegistrationSeed.Identified ->
                    if (selected?.id == seed.content.id && draft.name == seed.content.name)
                        RegistrationMethod.IDENTIFIED
                    else RegistrationMethod.IDENTIFICATION_EDITED
            }
        persist(
            PendingRegistration(
                loaded.accountId,
                draft.plantId,
                operation,
                draft.name,
                selected?.id,
                method,
                draft.photo,
                draft.lastWateredDate?.let(LocalDate::parse),
            ),
            checkpoint,
        )
    }

    private suspend fun persist(
        submission: PendingRegistration,
        checkpoint: RegistrationCheckpoint,
    ) {
        setState(RegistrationUiState.Saving(submission, checkpoint))
        val result =
            try {
                repository.register(submission, checkpoint)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RegistrationAttempt.Failed(RegistrationFailure.DATABASE_UNAVAILABLE, checkpoint)
            }
        when (result) {
            is RegistrationAttempt.Completed ->
                setState(RegistrationUiState.Completed(result.plant))
            is RegistrationAttempt.Failed ->
                setState(
                    RegistrationUiState.SaveFailed(
                        submission,
                        result.checkpoint,
                        result.failure,
                    )
                )
        }
    }

    private fun updateDraft(transform: (RegistrationDraft) -> RegistrationDraft) {
        val current = _state.value as? RegistrationUiState.Editing ?: return
        searchGeneration += 1
        currentDraft = transform(current.draft)
        setState(
            current.copy(
                draft = currentDraft,
                search = RegistrationSearchState.Idle,
                errors = emptySet(),
                failure = null,
            )
        )
    }

    private fun setState(value: RegistrationUiState) {
        _state.value = value
        when (value) {
            is RegistrationUiState.Editing -> currentDraft = value.draft
            is RegistrationUiState.CheckingDuplicates -> currentDraft = value.draft
            is RegistrationUiState.DuplicateFound -> currentDraft = value.draft
            else -> Unit
        }
        saveState()
    }

    private fun saveState() {
        RegistrationSavedState.save(savedStateHandle, session, currentDraft, _state.value)
    }

    private fun validate(
        draft: RegistrationDraft,
        loaded: RegistrationSession,
    ): Pair<RegistrationDraft, Set<RegistrationValidationError>> {
        val normalizedName = draft.name.trim { it.isWhitespace() }
        val errors = mutableSetOf<RegistrationValidationError>()
        if (normalizedName.isEmpty()) errors += RegistrationValidationError.NAME_REQUIRED
        else if (graphemeCount(normalizedName) > 100)
            errors += RegistrationValidationError.NAME_TOO_LONG
        val date =
            draft.lastWateredDate?.let {
                runCatching { LocalDate.parse(it) }
                    .getOrElse {
                        errors += RegistrationValidationError.INVALID_LAST_WATERED_DATE
                        null
                    }
            }
        if (date != null && date > LocalDate.now(clock.withZone(loaded.zoneId)))
            errors += RegistrationValidationError.FUTURE_LAST_WATERED_DATE
        return draft.copy(name = normalizedName) to errors
    }

    private fun graphemeCount(value: String): Int {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(value)
        var count = 0
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            val next = iterator.next()
            if (next != BreakIterator.DONE) count += 1
            boundary = next
        }
        return count
    }
}
