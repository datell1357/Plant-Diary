package com.planterior.helper.feature.collection

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.PublicationState
import com.planterior.helper.core.model.RegistrationMethod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 개인 도감 목록에 필요한 최소 정보. 계정 ID나 메모는 목록 UI로 전달하지 않는다. */
data class CollectionPlant(
    val id: PersonalPlantId,
    val displayName: String,
    val representativePhotoPath: String?,
)

data class CollectionListPosition(val index: Int, val offset: Int) {
    init {
        require(index >= 0 && offset >= 0)
    }

    companion object {
        val ZERO = CollectionListPosition(0, 0)
    }
}

sealed interface CollectionLoad {
    data class Fresh(val items: List<CollectionPlant>) : CollectionLoad

    data class Stale(
        val items: List<CollectionPlant>,
        val lastSuccessfulAt: Instant? = null,
    ) : CollectionLoad

    data object Failed : CollectionLoad
}

sealed interface CollectionUiState {
    data object Loading : CollectionUiState

    data class Content(
        val items: List<CollectionPlant>,
        val stale: Boolean,
        val lastSuccessfulAt: Instant? = null,
    ) : CollectionUiState

    data object Empty : CollectionUiState

    data object Error : CollectionUiState
}

data class PersonalPlantDetail(
    val accountId: AccountId,
    val id: PersonalPlantId,
    val displayName: String,
    val contentId: PlantContentId?,
    val registrationMethod: RegistrationMethod,
    val representativePhotoPath: String?,
    val location: String?,
    val privateNote: String?,
    val lastWateredDate: LocalDate?,
    val revision: Long,
    val updatedAt: Instant,
)

data class PublicSymptomGuidance(
    val id: String,
    val symptom: String,
    val possibleCause: String,
    val action: String,
)

data class PlantCareGuidance(
    val wateringIntervalDays: Int?,
    val lightGuidance: String?,
    val minimumTemperatureCelsius: Double?,
    val maximumTemperatureCelsius: Double?,
    val minimumHumidityPercent: Int?,
    val maximumHumidityPercent: Int?,
    val symptoms: List<PublicSymptomGuidance>,
)

data class PlantDetail(
    val plant: PersonalPlantDetail,
    val accountZone: ZoneId,
    val guidance: PlantCareGuidance,
)

enum class CareField {
    WATER,
    LIGHT,
    TEMPERATURE,
    HUMIDITY,
    SYMPTOMS,
}

sealed interface DetailLoad {
    data class Fresh(val detail: PlantDetail) : DetailLoad

    data class Partial(val detail: PlantDetail, val missing: Set<CareField>) : DetailLoad

    /** Stale은 본인 소유의 로컬 기록만 표시한다. 공개 여부를 재검증할 수 없는 관리 콘텐츠는 재생하지 않는다. */
    data class Stale(
        val plant: PersonalPlantDetail,
        val guidance: PlantCareGuidance?,
        val editingAllowed: Boolean,
        val accountZone: ZoneId?,
    ) : DetailLoad

    data class NoStandardContent(
        val plant: PersonalPlantDetail,
        val accountZone: ZoneId,
    ) : DetailLoad

    data object Forbidden : DetailLoad

    data object NotFound : DetailLoad

    data object Failed : DetailLoad
}

enum class EditValidationError {
    INVALID_LAST_WATERED_DATE,
    FUTURE_LAST_WATERED_DATE,
    LOCATION_TOO_LONG,
    NOTE_TOO_LONG,
}

enum class EditFailure {
    REMOTE_WRITE_FAILED,
    REVISION_CONFLICT,
    INCONSISTENT_RECEIPT,
    DATABASE_UNAVAILABLE,
    OUTBOX_MISMATCH,
}

data class EditorState(
    val isEditing: Boolean = false,
    val operationId: OperationId? = null,
    val lastWateredDate: String = "",
    val location: String = "",
    val privateNote: String = "",
    val errors: Set<EditValidationError> = emptySet(),
    val saving: Boolean = false,
    val failure: EditFailure? = null,
) {
    val isFrozen: Boolean
        get() = failure != null

    val requiresReconciliation: Boolean
        get() = failure == EditFailure.REVISION_CONFLICT || failure == EditFailure.OUTBOX_MISMATCH

    val canRetryExactSnapshot: Boolean
        get() = failure != null && !requiresReconciliation

    companion object {
        fun from(plant: PersonalPlantDetail) =
            EditorState(
                lastWateredDate = plant.lastWateredDate?.toString().orEmpty(),
                location = plant.location.orEmpty(),
                privateNote = plant.privateNote.orEmpty(),
            )
    }
}

sealed interface PlantDetailUiState {
    data object Loading : PlantDetailUiState

    data class Content(val detail: PlantDetail, val editor: EditorState) : PlantDetailUiState

    data class Partial(
        val detail: PlantDetail,
        val missing: Set<CareField>,
        val editor: EditorState,
    ) : PlantDetailUiState

    data class Stale(
        val plant: PersonalPlantDetail,
        val guidance: PlantCareGuidance?,
        val editor: EditorState,
        val editingAllowed: Boolean,
        val accountZone: ZoneId?,
    ) : PlantDetailUiState

    data class NoStandardContent(
        val plant: PersonalPlantDetail,
        val editor: EditorState,
        val accountZone: ZoneId,
    ) : PlantDetailUiState

    data object Forbidden : PlantDetailUiState

    data object NotFound : PlantDetailUiState

    data object Error : PlantDetailUiState
}

enum class PlantEditField(val payloadName: String) {
    LAST_WATERED_DATE("lastWateredDate"),
    LOCATION("location"),
    NOTE("note"),
}

data class PlantEditRequest(
    val accountId: AccountId,
    val plantId: PersonalPlantId,
    val operationId: OperationId,
    val expectedRevision: Long,
    val displayName: String,
    val contentId: PlantContentId?,
    val registrationMethod: RegistrationMethod,
    val representativePhotoPath: String?,
    val lastWateredDate: LocalDate?,
    val location: String?,
    val privateNote: String?,
    val dirtyFields: Set<PlantEditField>,
)

sealed interface EditResult {
    data class Saved(val plant: PersonalPlantDetail) : EditResult

    data class Failed(val failure: EditFailure) : EditResult

    data object Forbidden : EditResult

    data object NotFound : EditResult
}

interface CollectionRepository {
    suspend fun loadCollection(): CollectionLoad

    suspend fun loadDetail(plantId: PersonalPlantId): DetailLoad

    suspend fun saveEdit(request: PlantEditRequest): EditResult

    suspend fun reconcileFailedEdit(
        accountId: AccountId,
        plantId: PersonalPlantId,
        operationId: OperationId,
    ): DetailLoad
}

/** Firebase에서 읽은 owner 문서. ownerUid 검증이 끝나기 전에는 UI 모델로 바꾸지 않는다. */
data class RemotePersonalPlant(
    val accountId: AccountId,
    val id: PersonalPlantId,
    val displayName: String,
    val contentId: PlantContentId?,
    val registrationMethod: RegistrationMethod,
    val representativePhotoPath: String?,
    val location: String?,
    val privateNote: String?,
    val lastWateredDate: LocalDate?,
    val revision: Long,
    val updatedAt: Instant,
)

sealed interface RemotePlantLookup {
    data class Found(val plant: RemotePersonalPlant) : RemotePlantLookup

    data object Forbidden : RemotePlantLookup

    data object NotFound : RemotePlantLookup

    data object Failed : RemotePlantLookup
}

data class RemotePlantContent(
    val id: PlantContentId,
    val wateringIntervalDays: Int?,
    val lightGuidance: String?,
    val minimumTemperatureCelsius: Double?,
    val maximumTemperatureCelsius: Double?,
    val minimumHumidityPercent: Int?,
    val maximumHumidityPercent: Int?,
    val publicationState: PublicationState,
)

data class RemoteSymptomGuidance(
    val id: String,
    val symptom: String,
    val possibleCause: String,
    val action: String,
    val publicationState: PublicationState,
)

interface CollectionRemoteDataSource {
    fun activeAccount(): AccountId

    suspend fun accountZone(accountId: AccountId): ZoneId

    suspend fun plants(accountId: AccountId): List<RemotePersonalPlant>

    suspend fun plant(accountId: AccountId, plantId: PersonalPlantId): RemotePlantLookup

    suspend fun publicContent(contentId: PlantContentId): RemotePlantContent?

    suspend fun publicSymptoms(contentId: PlantContentId): List<RemoteSymptomGuidance>
}
