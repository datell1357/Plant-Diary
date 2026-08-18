package com.planterior.helper.feature.collection

import android.icu.text.BreakIterator
import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.feature.watering.WateringScheduleCalculator
import com.planterior.helper.feature.watering.WateringScheduleStatus
import com.planterior.helper.feature.watering.WateringUnavailableReason
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CollectionController(
    private val repository: CollectionRepository,
    private val savedStateHandle: SavedStateHandle,
) {
    private val _state = MutableStateFlow<CollectionUiState>(CollectionUiState.Loading)
    val state: StateFlow<CollectionUiState> = _state.asStateFlow()
    private var generation = 0L

    val listPosition: CollectionListPosition
        get() =
            CollectionListPosition(
                savedStateHandle[LIST_INDEX] ?: 0,
                savedStateHandle[LIST_OFFSET] ?: 0,
            )

    suspend fun start() = load()

    suspend fun retry() = load()

    fun updateListPosition(index: Int, offset: Int) {
        if (index < 0 || offset < 0) return
        savedStateHandle[LIST_INDEX] = index
        savedStateHandle[LIST_OFFSET] = offset
    }

    private suspend fun load() {
        val request = ++generation
        _state.value = CollectionUiState.Loading
        val result =
            try {
                repository.loadCollection()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                CollectionLoad.Failed
            }
        if (request != generation) return
        _state.value =
            when (result) {
                is CollectionLoad.Fresh ->
                    if (result.items.isEmpty()) CollectionUiState.Empty
                    else CollectionUiState.Content(result.items, stale = false)
                is CollectionLoad.Stale ->
                    if (result.items.isEmpty()) CollectionUiState.Error
                    else
                        CollectionUiState.Content(
                            result.items,
                            stale = true,
                            lastSuccessfulAt = result.lastSuccessfulAt,
                        )
                CollectionLoad.Failed -> CollectionUiState.Error
            }
    }

    private companion object {
        const val LIST_INDEX = "collection.list.index"
        const val LIST_OFFSET = "collection.list.offset"
    }
}

class PlantDetailController(
    private val plantId: PersonalPlantId,
    private val repository: CollectionRepository,
    private val clock: Clock,
    private val savedStateHandle: SavedStateHandle,
    private val operationIdFactory: () -> OperationId = OperationId::random,
) {
    private var restoredEditor = restoreEditor()
    private val _state = MutableStateFlow<PlantDetailUiState>(PlantDetailUiState.Loading)
    val state: StateFlow<PlantDetailUiState> = _state.asStateFlow()
    private var generation = 0L

    suspend fun start() = load()

    suspend fun retry() = load()

    suspend fun reclassifyAtNextAccountMidnight(): Boolean {
        val zone = currentAccountZone() ?: return false
        val now = clock.instant()
        val nextMidnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        delay(java.time.Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L))
        onResume()
        return true
    }

    fun onResume() {
        _state.value =
            when (val current = _state.value) {
                is PlantDetailUiState.Content ->
                    current.copy(
                        wateringSchedule =
                            wateringSchedule(
                                current.detail.plant,
                                current.detail.guidance.wateringIntervalDays,
                                current.detail.accountZone,
                            )
                    )
                is PlantDetailUiState.Partial ->
                    current.copy(
                        wateringSchedule =
                            wateringSchedule(
                                current.detail.plant,
                                current.detail.guidance.wateringIntervalDays,
                                current.detail.accountZone,
                            )
                    )
                is PlantDetailUiState.Stale ->
                    current.copy(
                        wateringSchedule =
                            current.accountZone?.let { zone ->
                                wateringSchedule(current.plant, null, zone)
                            } ?: unavailableWateringSchedule()
                    )
                is PlantDetailUiState.NoStandardContent ->
                    current.copy(
                        wateringSchedule =
                            wateringSchedule(current.plant, null, current.accountZone)
                    )
                else -> current
            }
    }

    fun beginEditing() {
        val plant = currentPlant() ?: return
        if (!editingAllowed()) return
        val current = currentEditor() ?: EditorState.from(plant)
        val operation = current.operationId ?: operationIdFactory()
        setEditor(current.copy(isEditing = true, operationId = operation, failure = null))
    }

    fun changeLastWateredDate(value: String) = updateEditor { it.copy(lastWateredDate = value) }

    fun changeLocation(value: String) = updateEditor { it.copy(location = value) }

    fun changePrivateNote(value: String) = updateEditor { it.copy(privateNote = value) }

    fun cancelEdit() {
        val plant = currentPlant() ?: return
        val editor = currentEditor() ?: return
        if (editor.isFrozen) return
        setEditor(EditorState.from(plant))
    }

    suspend fun saveEdit() {
        val plant = currentPlant() ?: return
        val editor = currentEditor()?.takeIf { it.isEditing } ?: return
        if (editor.requiresReconciliation) return
        val errors = validate(editor)
        if (errors.isNotEmpty()) {
            setEditor(editor.copy(errors = errors, saving = false, failure = null))
            return
        }
        val operation = editor.operationId ?: operationIdFactory()
        val normalizedLocation = editor.location.trim().takeIf(String::isNotEmpty)
        val normalizedNote = editor.privateNote.takeIf(String::isNotBlank)
        val parsedDate = editor.lastWateredDate.takeIf(String::isNotBlank)?.let(LocalDate::parse)
        val dirtyFields = buildSet {
            if (parsedDate != plant.lastWateredDate) add(PlantEditField.LAST_WATERED_DATE)
            if (normalizedLocation != plant.location) add(PlantEditField.LOCATION)
            if (normalizedNote != plant.privateNote) add(PlantEditField.NOTE)
        }
        if (dirtyFields.isEmpty()) {
            setEditor(EditorState.from(plant))
            saveEditor(null)
            return
        }
        val request =
            PlantEditRequest(
                accountId = plant.accountId,
                plantId = plant.id,
                operationId = operation,
                expectedRevision = plant.revision,
                displayName = plant.displayName,
                contentId = plant.contentId,
                registrationMethod = plant.registrationMethod,
                representativePhotoPath = plant.representativePhotoPath,
                lastWateredDate = parsedDate,
                location = normalizedLocation,
                privateNote = normalizedNote,
                dirtyFields = dirtyFields,
            )
        setEditor(
            editor.copy(
                operationId = operation,
                location = normalizedLocation.orEmpty(),
                saving = true,
                errors = emptySet(),
                failure = null,
            )
        )
        val result =
            try {
                repository.saveEdit(request)
            } catch (error: CancellationException) {
                setEditor(
                    editor.copy(
                        operationId = operation,
                        location = normalizedLocation.orEmpty(),
                        saving = false,
                        errors = emptySet(),
                        failure = null,
                    )
                )
                throw error
            } catch (_: Exception) {
                EditResult.Failed(EditFailure.DATABASE_UNAVAILABLE)
            }
        when (result) {
            is EditResult.Saved -> applySaved(result.plant)
            is EditResult.Failed ->
                setEditor(
                    requireNotNull(currentEditor()).copy(saving = false, failure = result.failure)
                )
            EditResult.Forbidden -> _state.value = PlantDetailUiState.Forbidden
            EditResult.NotFound -> _state.value = PlantDetailUiState.NotFound
        }
    }

    suspend fun reconcileFailedEdit() {
        val plant = currentPlant() ?: return
        val editor = currentEditor()?.takeIf { it.isFrozen } ?: return
        val operationId = editor.operationId ?: return
        val request = ++generation
        setEditor(editor.copy(saving = true))
        val result =
            try {
                repository.reconcileFailedEdit(plant.accountId, plant.id, operationId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                DetailLoad.Failed
            }
        if (request != generation) return
        if (result == DetailLoad.Failed) {
            setEditor(editor.copy(saving = false))
            return
        }
        restoredEditor = null
        applyLoad(result)
        saveEditor(null)
    }

    private suspend fun load() {
        val request = ++generation
        _state.value = PlantDetailUiState.Loading
        val result =
            try {
                repository.loadDetail(plantId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                DetailLoad.Failed
            }
        if (request != generation) return
        applyLoad(result)
        saveEditor(currentEditor())
    }

    private fun applyLoad(result: DetailLoad) {
        _state.value =
            when (result) {
                is DetailLoad.Fresh ->
                    PlantDetailUiState.Content(
                        result.detail,
                        editorFor(result.detail.plant),
                        wateringSchedule(
                            result.detail.plant,
                            result.detail.guidance.wateringIntervalDays,
                            result.detail.accountZone,
                        ),
                    )
                is DetailLoad.Partial ->
                    PlantDetailUiState.Partial(
                        result.detail,
                        result.missing,
                        editorFor(result.detail.plant),
                        wateringSchedule(
                            result.detail.plant,
                            result.detail.guidance.wateringIntervalDays,
                            result.detail.accountZone,
                        ),
                    )
                is DetailLoad.Stale ->
                    PlantDetailUiState.Stale(
                        result.plant,
                        result.guidance,
                        if (result.editingAllowed) editorFor(result.plant)
                        else EditorState.from(result.plant).also { restoredEditor = null },
                        result.editingAllowed,
                        result.accountZone,
                        result.accountZone?.let { zone ->
                            wateringSchedule(
                                result.plant,
                                publicIntervalDays = null,
                                zone,
                            )
                        } ?: unavailableWateringSchedule(),
                    )
                is DetailLoad.NoStandardContent ->
                    PlantDetailUiState.NoStandardContent(
                        result.plant,
                        editorFor(result.plant),
                        result.accountZone,
                        wateringSchedule(result.plant, null, result.accountZone),
                    )
                DetailLoad.Forbidden -> PlantDetailUiState.Forbidden
                DetailLoad.NotFound -> PlantDetailUiState.NotFound
                DetailLoad.Failed -> PlantDetailUiState.Error
            }
    }

    private fun editorFor(plant: PersonalPlantDetail): EditorState {
        val restored = restoredEditor?.takeIf { it.isEditing }
        restoredEditor = null
        return restored ?: EditorState.from(plant)
    }

    private fun updateEditor(transform: (EditorState) -> EditorState) {
        val current = currentEditor()?.takeIf { it.isEditing && !it.isFrozen } ?: return
        setEditor(transform(current).copy(errors = emptySet(), failure = null))
    }

    private fun setEditor(editor: EditorState) {
        _state.value =
            when (val current = _state.value) {
                is PlantDetailUiState.Content -> current.copy(editor = editor)
                is PlantDetailUiState.Partial -> current.copy(editor = editor)
                is PlantDetailUiState.Stale -> current.copy(editor = editor)
                is PlantDetailUiState.NoStandardContent -> current.copy(editor = editor)
                else -> current
            }
        saveEditor(editor)
    }

    private fun applySaved(plant: PersonalPlantDetail) {
        val editor = EditorState.from(plant)
        _state.value =
            when (val current = _state.value) {
                is PlantDetailUiState.Content ->
                    current.copy(
                        detail = current.detail.copy(plant = plant),
                        editor = editor,
                        wateringSchedule =
                            wateringSchedule(
                                plant,
                                current.detail.guidance.wateringIntervalDays,
                                current.detail.accountZone,
                            ),
                    )
                is PlantDetailUiState.Partial ->
                    current.copy(
                        detail = current.detail.copy(plant = plant),
                        editor = editor,
                        wateringSchedule =
                            wateringSchedule(
                                plant,
                                current.detail.guidance.wateringIntervalDays,
                                current.detail.accountZone,
                            ),
                    )
                is PlantDetailUiState.Stale ->
                    current.copy(
                        plant = plant,
                        editor = editor,
                        wateringSchedule =
                            current.accountZone?.let { zone ->
                                wateringSchedule(
                                    plant,
                                    publicIntervalDays = null,
                                    zone,
                                )
                            } ?: unavailableWateringSchedule(),
                    )
                is PlantDetailUiState.NoStandardContent ->
                    current.copy(
                        plant = plant,
                        editor = editor,
                        wateringSchedule = wateringSchedule(plant, null, current.accountZone),
                    )
                else -> current
            }
        saveEditor(null)
    }

    private fun wateringSchedule(
        plant: PersonalPlantDetail,
        publicIntervalDays: Int?,
        accountZone: java.time.ZoneId,
    ): WateringScheduleStatus =
        WateringScheduleCalculator.calculate(
            plant.lastWateredDate,
            publicIntervalDays,
            accountZone,
            clock,
        )

    private fun unavailableWateringSchedule(): WateringScheduleStatus =
        WateringScheduleStatus.Unavailable(WateringUnavailableReason.MISSING_PUBLIC_INTERVAL)

    private fun currentPlant(): PersonalPlantDetail? =
        when (val current = _state.value) {
            is PlantDetailUiState.Content -> current.detail.plant
            is PlantDetailUiState.Partial -> current.detail.plant
            is PlantDetailUiState.Stale -> current.plant
            is PlantDetailUiState.NoStandardContent -> current.plant
            else -> null
        }

    private fun editingAllowed(): Boolean =
        when (val current = _state.value) {
            is PlantDetailUiState.Stale -> current.editingAllowed
            is PlantDetailUiState.Content,
            is PlantDetailUiState.Partial,
            is PlantDetailUiState.NoStandardContent -> true
            else -> false
        }

    private fun currentAccountZone(): java.time.ZoneId? =
        when (val current = _state.value) {
            is PlantDetailUiState.Content -> current.detail.accountZone
            is PlantDetailUiState.Partial -> current.detail.accountZone
            is PlantDetailUiState.Stale -> current.accountZone
            is PlantDetailUiState.NoStandardContent -> current.accountZone
            else -> null
        }

    private fun currentEditor(): EditorState? =
        when (val current = _state.value) {
            is PlantDetailUiState.Content -> current.editor
            is PlantDetailUiState.Partial -> current.editor
            is PlantDetailUiState.Stale -> current.editor
            is PlantDetailUiState.NoStandardContent -> current.editor
            else -> null
        }

    private fun validate(editor: EditorState): Set<EditValidationError> {
        val errors = mutableSetOf<EditValidationError>()
        val date =
            editor.lastWateredDate.takeIf(String::isNotBlank)?.let {
                runCatching { LocalDate.parse(it) }
                    .getOrElse {
                        errors += EditValidationError.INVALID_LAST_WATERED_DATE
                        null
                    }
            }
        val accountZone = currentAccountZone()
        if (
            date != null &&
                (accountZone == null || date > LocalDate.now(clock.withZone(accountZone)))
        ) {
            errors += EditValidationError.FUTURE_LAST_WATERED_DATE
        }
        if (graphemeCount(editor.location.trim()) > LOCATION_LIMIT) {
            errors += EditValidationError.LOCATION_TOO_LONG
        }
        if (graphemeCount(editor.privateNote) > NOTE_LIMIT) {
            errors += EditValidationError.NOTE_TOO_LONG
        }
        return errors
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

    private fun restoreEditor(): EditorState? {
        if (savedStateHandle.get<Boolean>(EDITING) != true) return null
        return EditorState(
            isEditing = true,
            operationId = savedStateHandle.get<String>(OPERATION)?.let(::OperationId),
            lastWateredDate = savedStateHandle[DATE] ?: "",
            location = savedStateHandle[LOCATION] ?: "",
            privateNote = savedStateHandle[NOTE] ?: "",
            failure =
                savedStateHandle.get<String>(FAILURE)?.let {
                    runCatching { EditFailure.valueOf(it) }.getOrNull()
                },
        )
    }

    private fun saveEditor(editor: EditorState?) {
        val saved = editor?.takeIf { it.isEditing }
        savedStateHandle[EDITING] = saved != null
        savedStateHandle[OPERATION] = saved?.operationId?.value
        savedStateHandle[DATE] = saved?.lastWateredDate
        savedStateHandle[LOCATION] = saved?.location
        savedStateHandle[NOTE] = saved?.privateNote
        savedStateHandle[FAILURE] = saved?.failure?.name
    }

    private companion object {
        const val LOCATION_LIMIT = 50
        const val NOTE_LIMIT = 1000
        const val EDITING = "detail.editing"
        const val OPERATION = "detail.operation"
        const val DATE = "detail.date"
        const val LOCATION = "detail.location"
        const val NOTE = "detail.note"
        const val FAILURE = "detail.failure"
    }
}
