package com.planterior.helper.feature.minihome

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import java.text.Normalizer
import java.time.Instant
import kotlin.math.roundToInt

object MiniHomeGrid {
    const val COLUMNS = 5
    const val ROWS = 4
    const val MAX_PLACEMENTS = COLUMNS * ROWS
}

@JvmInline
value class MiniHomeNormalizedCoordinate(val value: Double) {
    init {
        require(value.isFinite() && value in 0.0..1.0)
    }
}

@JvmInline
value class MiniHomeZIndex(val value: Int) {
    init {
        require(value >= 0)
    }
}

data class GridPosition(val column: Int, val row: Int) {
    init {
        require(column in 0 until MiniHomeGrid.COLUMNS)
        require(row in 0 until MiniHomeGrid.ROWS)
    }

    val normalizedX: MiniHomeNormalizedCoordinate
        get() = MiniHomeNormalizedCoordinate((column + 0.5) / MiniHomeGrid.COLUMNS)

    val normalizedY: MiniHomeNormalizedCoordinate
        get() = MiniHomeNormalizedCoordinate((row + 0.5) / MiniHomeGrid.ROWS)

    companion object {
        fun fromNormalized(x: Double, y: Double): GridPosition {
            val safeX = if (x.isFinite()) x.coerceIn(0.0, 1.0) else 0.0
            val safeY = if (y.isFinite()) y.coerceIn(0.0, 1.0) else 0.0
            return GridPosition(
                (safeX * MiniHomeGrid.COLUMNS - 0.5)
                    .roundToInt()
                    .coerceIn(0, MiniHomeGrid.COLUMNS - 1),
                (safeY * MiniHomeGrid.ROWS - 0.5).roundToInt().coerceIn(0, MiniHomeGrid.ROWS - 1),
            )
        }

        fun parsePersisted(x: Double, y: Double): GridPosition {
            require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0)
            val position = fromNormalized(x, y)
            require(kotlin.math.abs(position.normalizedX.value - x) < 0.000000001)
            require(kotlin.math.abs(position.normalizedY.value - y) < 0.000000001)
            return position
        }
    }
}

sealed interface MiniHomePlacementTarget {
    val stableId: String

    data class Plant(val plantId: PersonalPlantId) : MiniHomePlacementTarget {
        override val stableId: String = "plant:${plantId.value}"
    }

    data class Decoration(val itemId: ItemId) : MiniHomePlacementTarget {
        override val stableId: String = "item:${itemId.value}"
    }
}

data class MiniHomePlacement(
    val id: PlacementId,
    val target: MiniHomePlacementTarget,
    val position: GridPosition,
    val zIndex: MiniHomeZIndex,
)

data class MiniHomeLayout(
    val id: MiniHomeId,
    val name: String,
    val placements: List<MiniHomePlacement>,
    val revision: Revision,
    val updatedAt: Instant,
) {
    init {
        require(name.codePointCount(0, name.length) <= MiniHomeRequestContract.MAX_NAME_CODE_POINTS)
        require(placements.size <= MiniHomeGrid.MAX_PLACEMENTS)
        require(MiniHomePlacementPolicy.isValid(placements))
    }
}

object MiniHomePlacementPolicy {
    fun layer(placements: List<MiniHomePlacement>): List<MiniHomePlacement> =
        placements
            .sortedWith(
                compareBy<MiniHomePlacement> {
                        MiniHomeIsometricProjection.depth(it.position)
                    }
                    .thenBy { MiniHomeIsometricProjection.horizontal(it.position) }
                    .thenBy { it.id.value }
            )
            .mapIndexed { index, placement ->
                placement.copy(zIndex = MiniHomeZIndex(index))
            }

    fun isValid(placements: List<MiniHomePlacement>): Boolean {
        if (placements.size > MiniHomeGrid.MAX_PLACEMENTS) return false
        if (placements.map { it.id }.distinct().size != placements.size) return false
        if (placements.map { it.target.stableId }.distinct().size != placements.size) return false
        if (placements.map { it.position }.distinct().size != placements.size) return false
        val canonical = layer(placements).associate { it.id to it.zIndex }
        return placements.all { canonical[it.id] == it.zIndex }
    }
}

data class MiniHomePlantChoice(
    val id: PersonalPlantId,
    val displayName: String,
    val representativePhotoPath: String?,
)

data class MiniHomeDecorationChoice(val id: ItemId, val displayName: String)

data class MiniHomeDiscardHandle(
    val accountId: AccountId,
    val aggregateType: String,
    val rowOperationId: String,
    val rowLineageId: String?,
    val rowHandleId: String,
    val rowVersion: Long = 0,
) {
    init {
        require(aggregateType.isNotBlank() && rowHandleId.isNotBlank() && rowVersion >= 0)
    }
}

data class MiniHomeReconciliationDetails(
    val rowOperationId: String,
    val rowLineageId: String?,
    val rawEnvelopeJson: String?,
    val envelopeOperationId: String?,
    val rawName: String?,
    val storedPayloadHash: String?,
    val recomputedPayloadHash: String?,
    val authoritativeOperationId: String?,
    val authoritativeExpectedRevision: Long?,
    val authoritativeRevision: Long?,
    val authoritativePayloadHash: String?,
)

data class MiniHomePendingSave(
    val operationId: OperationId,
    val expectedRevision: Revision,
    val layout: MiniHomeLayout,
    val state: MiniHomePendingState,
    val failure: MiniHomeSaveFailure? = null,
    val failureDetails: String? = null,
    val lineageId: OperationId = operationId,
    val supersedesOperationId: OperationId? = null,
    val discardHandle: MiniHomeDiscardHandle? = null,
    val reconciliationDetails: MiniHomeReconciliationDetails? = null,
)

enum class MiniHomePendingState {
    PENDING,
    MAY_HAVE_COMMITTED,
    RECONCILIATION_REQUIRED,
}

data class MiniHomeCommittedReceipt(
    val operationId: OperationId,
    val expectedRevision: Revision,
    val committedRevision: Revision,
    val payloadHash: String,
) {
    init {
        require(payloadHash.matches(Regex("^[a-f0-9]{64}$")))
    }
}

sealed interface MiniHomeLoadResult {
    data class Ready(
        val accountId: AccountId,
        val committed: MiniHomeLayout,
        val plants: List<MiniHomePlantChoice>,
        val decorations: List<MiniHomeDecorationChoice>,
        val stale: Boolean,
        val pending: MiniHomePendingSave?,
        val committedReceipt: MiniHomeCommittedReceipt? = null,
    ) : MiniHomeLoadResult

    data object Forbidden : MiniHomeLoadResult

    data object Failed : MiniHomeLoadResult
}

data class MiniHomeSaveRequest(
    val accountId: AccountId,
    val operationId: OperationId,
    val expectedRevision: Revision,
    val layout: MiniHomeLayout,
    val lineageId: OperationId = operationId,
    val supersedesOperationId: OperationId? = null,
)

data class MiniHomeRequestViolation(val field: String, val details: String)

/** Mirrors the callable's machine-consumed request contract before an operation is frozen. */
object MiniHomeRequestContract {
    const val MAX_NAME_CODE_POINTS = 100
    const val MAX_SAFE_REVISION = 9_007_199_254_740_990L

    /** Unicode White_Space property, pinned explicitly instead of using platform classifiers. */
    val UNICODE_WHITE_SPACE_CODE_POINTS: Set<Int> =
        setOf(
            0x0009,
            0x000A,
            0x000B,
            0x000C,
            0x000D,
            0x0020,
            0x0085,
            0x00A0,
            0x1680,
            0x2000,
            0x2001,
            0x2002,
            0x2003,
            0x2004,
            0x2005,
            0x2006,
            0x2007,
            0x2008,
            0x2009,
            0x200A,
            0x2028,
            0x2029,
            0x202F,
            0x205F,
            0x3000,
        )

    /** UAX #9 directional formatting controls forbidden inside display names. */
    val BIDI_CONTROL_CODE_POINTS: Set<Int> =
        setOf(
            0x061C,
            0x200E,
            0x200F,
            0x202A,
            0x202B,
            0x202C,
            0x202D,
            0x202E,
            0x2066,
            0x2067,
            0x2068,
            0x2069,
        )

    fun validate(request: MiniHomeSaveRequest): MiniHomeRequestViolation? {
        validateName(request.layout.name)?.let {
            return it
        }
        if (request.expectedRevision.value > MAX_SAFE_REVISION) {
            return MiniHomeRequestViolation(
                "expectedRevision",
                "expectedRevision is outside the supported range",
            )
        }
        if (request.layout.revision != request.expectedRevision) {
            return MiniHomeRequestViolation(
                "expectedRevision",
                "layout revision must match expectedRevision",
            )
        }
        if (!MiniHomePlacementPolicy.isValid(request.layout.placements)) {
            return MiniHomeRequestViolation("placements", "placements violate the room policy")
        }
        if (request.layout.placements != MiniHomePlacementPolicy.layer(request.layout.placements)) {
            return MiniHomeRequestViolation(
                "placements",
                "placements must be contiguous and depth ordered",
            )
        }
        return null
    }

    fun validateName(name: String): MiniHomeRequestViolation? {
        if (name.isEmpty() || name.hasUnpairedSurrogate()) return invalidName()
        val codePoints = name.codePoints().toArray()
        if (
            Normalizer.normalize(name, Normalizer.Form.NFC) != name ||
                codePoints.size > MAX_NAME_CODE_POINTS ||
                codePoints.first() in UNICODE_WHITE_SPACE_CODE_POINTS ||
                codePoints.last() in UNICODE_WHITE_SPACE_CODE_POINTS ||
                codePoints.any(::isControl) ||
                codePoints.any(BIDI_CONTROL_CODE_POINTS::contains)
        ) {
            return invalidName()
        }
        return null
    }

    private fun invalidName() =
        MiniHomeRequestViolation(
            "name",
            "name must be NFC, safe, and contain 1 to 100 Unicode code points",
        )

    private fun isControl(codePoint: Int): Boolean =
        codePoint in 0x0000..0x001F || codePoint in 0x007F..0x009F

    private fun String.hasUnpairedSurrogate(): Boolean = indices.any { index ->
        when {
            this[index].isHighSurrogate() ->
                index + 1 >= length || !this[index + 1].isLowSurrogate()
            this[index].isLowSurrogate() -> index == 0 || !this[index - 1].isHighSurrogate()
            else -> false
        }
    }
}

enum class MiniHomeSaveFailure {
    NETWORK,
    DATABASE,
    INCONSISTENT_RECEIPT,
    OUTBOX_MISMATCH,
    PAYLOAD_MISMATCH,
    UNAVAILABLE_ENTITY,
    REVISION_CONFLICT,
    INVALID_REQUEST,
    PERMISSION_DENIED,
    MALFORMED_RESPONSE;

    val retryable: Boolean
        get() = this == NETWORK || this == DATABASE || this == INCONSISTENT_RECEIPT

    val requiresCorrection: Boolean
        get() = this == INVALID_REQUEST

    val requiresReconciliation: Boolean
        get() = !retryable && !requiresCorrection

    val permanent: Boolean
        get() = !retryable
}

sealed interface MiniHomeDiscardResult {
    data object Consumed : MiniHomeDiscardResult

    /** The save crossed its commit boundary before discard linearized. */
    data class Committed(val authoritative: MiniHomeLayout) : MiniHomeDiscardResult

    data class StaleHandle(val current: MiniHomePendingSave?) : MiniHomeDiscardResult

    data object Missing : MiniHomeDiscardResult

    data object OwnerMismatch : MiniHomeDiscardResult

    data object Rejected : MiniHomeDiscardResult
}

enum class MiniHomeDiscardFeedback {
    STALE_HANDLE,
    RETRY_REQUIRED,
}

sealed interface MiniHomeAuthOwnership {
    data object Restoring : MiniHomeAuthOwnership

    data object Unknown : MiniHomeAuthOwnership

    data object SignedOut : MiniHomeAuthOwnership

    /** Explicitly disables auth-owner enforcement for previews and deterministic fixtures. */
    data object Unmanaged : MiniHomeAuthOwnership

    data class Authenticated(val accountId: AccountId) : MiniHomeAuthOwnership
}

data class MiniHomeControllerSessionToken(
    val controllerEpoch: Long,
    val generation: Long,
    val owner: AccountId?,
) {
    init {
        require(controllerEpoch >= 0 && generation >= 0)
    }
}

enum class MiniHomeExitOutcomeKind {
    SAVED,
    DISCARDED,
}

data class MiniHomeExitOutcome(
    val kind: MiniHomeExitOutcomeKind,
    val owner: AccountId,
    val operationId: OperationId,
    val lineageId: OperationId,
    val discardHandle: MiniHomeDiscardHandle?,
)

enum class NavigationIntentAction {
    CONFIRM_EXIT,
    SAVE_AND_EXIT,
    DISCARD_AND_EXIT,
}

data class NavigationIntentToken(
    val owner: AccountId,
    val controllerEpoch: Long,
    val controllerGeneration: Long,
    val action: NavigationIntentAction,
    val operationId: OperationId,
    val lineageId: OperationId,
    val discardHandle: MiniHomeDiscardHandle?,
    val intentId: String,
) {
    init {
        require(controllerEpoch >= 0 && controllerGeneration >= 0 && intentId.isNotBlank())
    }
}

sealed interface MiniHomeSaveResult {
    data class Saved(val layout: MiniHomeLayout) : MiniHomeSaveResult

    data class Conflict(
        val authoritative: MiniHomeLayout,
        val plants: List<MiniHomePlantChoice> = emptyList(),
        val decorations: List<MiniHomeDecorationChoice> = emptyList(),
    ) : MiniHomeSaveResult

    data class Failed(
        val failure: MiniHomeSaveFailure,
        val discardHandle: MiniHomeDiscardHandle? = null,
    ) : MiniHomeSaveResult

    class RequiresCorrection(
        val failure: MiniHomeSaveFailure,
        val details: String? = null,
        val discardHandle: MiniHomeDiscardHandle? = null,
    ) : MiniHomeSaveResult {
        init {
            require(failure.requiresCorrection)
        }

        override fun equals(other: Any?): Boolean =
            other is RequiresCorrection && failure == other.failure && details == other.details

        override fun hashCode(): Int = 31 * failure.hashCode() + details.hashCode()

        override fun toString(): String = "RequiresCorrection(failure=$failure, details=$details)"
    }

    class RequiresReconciliation(
        val failure: MiniHomeSaveFailure,
        val discardHandle: MiniHomeDiscardHandle? = null,
    ) : MiniHomeSaveResult {
        init {
            require(failure.requiresReconciliation)
        }

        override fun equals(other: Any?): Boolean =
            other is RequiresReconciliation && failure == other.failure

        override fun hashCode(): Int = failure.hashCode()

        override fun toString(): String = "RequiresReconciliation(failure=$failure)"
    }

    data class Reconciled(
        val failure: MiniHomeSaveFailure,
        val authoritative: MiniHomeLayout,
        val plants: List<MiniHomePlantChoice>,
        val decorations: List<MiniHomeDecorationChoice>,
        val correctedDraft: MiniHomeLayout,
        val removedTargets: Int,
    ) : MiniHomeSaveResult {
        init {
            require(failure.requiresReconciliation)
            require(removedTargets >= 0)
        }
    }

    /** A remote result lost its full-row CAS race and must not be applied to the replacement. */
    data class PendingChanged(val current: MiniHomePendingSave?) : MiniHomeSaveResult

    data object Forbidden : MiniHomeSaveResult
}

interface MiniHomeRepository {
    suspend fun load(): MiniHomeLoadResult

    suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult

    suspend fun reconcile(
        request: MiniHomeSaveRequest,
        failure: MiniHomeSaveFailure,
    ): MiniHomeSaveResult = MiniHomeSaveResult.RequiresReconciliation(failure)

    suspend fun reconcile(
        request: MiniHomeSaveRequest,
        failure: MiniHomeSaveFailure,
        discardHandle: MiniHomeDiscardHandle?,
    ): MiniHomeSaveResult = reconcile(request, failure)

    /**
     * Authoritatively queries this owner's mini-home outbox. A successful no-row query returns
     * [MiniHomeDiscardResult.Missing]; query failures return [MiniHomeDiscardResult.Rejected]. If a
     * row exists, its complete persisted handle is CAS-consumed.
     */
    suspend fun abandonPending(
        accountId: AccountId,
        operationId: OperationId? = null,
    ): MiniHomeDiscardResult = MiniHomeDiscardResult.Rejected

    /** Explicit discard atomically removes this owner's complete mini-home draft lineage. */
    suspend fun abandon(
        accountId: AccountId,
        operationId: OperationId,
        lineageId: OperationId = operationId,
    ): MiniHomeDiscardResult = MiniHomeDiscardResult.Rejected

    /** Discards by the exact durable row identity, even when its payload IDs are malformed. */
    suspend fun abandon(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult
}

sealed interface MiniHomeSaveState {
    data object Idle : MiniHomeSaveState

    data object Saving : MiniHomeSaveState

    data class Failed(val failure: MiniHomeSaveFailure) : MiniHomeSaveState {
        init {
            require(failure.retryable)
        }
    }

    data class ValidationFailed(
        val failure: MiniHomeSaveFailure,
        val details: String? = null,
    ) : MiniHomeSaveState {
        init {
            require(failure.requiresCorrection)
        }
    }

    data class ReconciliationRequired(val failure: MiniHomeSaveFailure) : MiniHomeSaveState {
        init {
            require(failure.requiresReconciliation)
        }
    }

    data class Corrected(val failure: MiniHomeSaveFailure, val removedTargets: Int) :
        MiniHomeSaveState {
        init {
            require(failure.requiresReconciliation)
            require(removedTargets >= 0)
        }
    }

    data object Conflict : MiniHomeSaveState
}

sealed interface MiniHomeUiState {
    /** The only account whose private data this state may render. */
    val owner: AccountId?

    data class Loading(val accountId: AccountId?) : MiniHomeUiState {
        override val owner: AccountId? = accountId
    }

    data class Unavailable(val accountId: AccountId) : MiniHomeUiState {
        override val owner: AccountId = accountId
    }

    data class Viewing(
        val committed: MiniHomeLayout,
        val plants: List<MiniHomePlantChoice>,
        val decorations: List<MiniHomeDecorationChoice>,
        val stale: Boolean,
        val saved: Boolean = false,
        val exitOutcome: MiniHomeExitOutcome? = null,
        override val owner: AccountId = AccountId.LEGACY,
    ) : MiniHomeUiState

    data class Editing(
        val committed: MiniHomeLayout,
        val draft: MiniHomeLayout,
        val plants: List<MiniHomePlantChoice>,
        val decorations: List<MiniHomeDecorationChoice>,
        val selectedPlacementId: PlacementId?,
        val operationId: OperationId,
        val saveState: MiniHomeSaveState,
        val issue: MiniHomePlacementIssue? = null,
        val stale: Boolean = false,
        val lineageId: OperationId = operationId,
        val supersedesOperationId: OperationId? = null,
        val discardHandle: MiniHomeDiscardHandle? = null,
        val discardFeedback: MiniHomeDiscardFeedback? = null,
        override val owner: AccountId = AccountId.LEGACY,
    ) : MiniHomeUiState {
        val hasUnsavedChanges: Boolean
            get() = draft != committed || saveState is MiniHomeSaveState.Corrected

        val frozen: Boolean
            get() =
                saveState is MiniHomeSaveState.Saving ||
                    saveState is MiniHomeSaveState.Failed ||
                    saveState is MiniHomeSaveState.ReconciliationRequired ||
                    saveState is MiniHomeSaveState.Conflict
    }

    data object Forbidden : MiniHomeUiState {
        override val owner: AccountId? = null
    }

    data object Error : MiniHomeUiState {
        override val owner: AccountId? = null
    }
}

enum class MiniHomePlacementIssue {
    OCCUPIED,
    ALREADY_PLACED,
    ROOM_FULL,
    INVALID_NAME,
    INVALID_REQUEST,
}
