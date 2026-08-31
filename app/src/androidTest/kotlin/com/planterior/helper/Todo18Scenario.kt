package com.planterior.helper

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlant
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.RegistrationMethod
import com.planterior.helper.feature.collection.RemotePersonalPlant
import com.planterior.helper.feature.minihome.MiniHomeSaveActionDiagnostics
import com.planterior.helper.feature.minihome.MiniHomeSaveActionObservation
import com.planterior.helper.feature.minihome.MiniHomeSaveActionStage
import com.planterior.helper.feature.minihome.MiniHomeSaveRequest
import com.planterior.helper.feature.watering.WateringConfirmActionDiagnostics
import com.planterior.helper.feature.watering.WateringConfirmActionObservation
import com.planterior.helper.feature.watering.WateringConfirmActionStage
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnostic
import com.planterior.helper.minihome.Todo18MiniHomeLoadObservation
import java.io.Closeable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

enum class Todo18MiniHomeSaveMode {
    APPLY,
    OFFLINE_ONCE,
    REVISION_CONFLICT,
}

enum class Todo18ShareMode {
    READY,
    EXPIRED,
    DELETED,
}

data class Todo18BoundaryEvent(
    val kind: String,
    val identity: String,
    val loadId: Long? = null,
    val readId: Long? = null,
    val diagnosticOrder: Long? = null,
    val cacheOutcome: String? = null,
    val pendingReadLoadId: Long? = null,
    val pendingReadId: Long? = null,
    val pendingReadOutcome: String? = null,
    val pendingReadFailureClass: String? = null,
    val pendingReadFailureMessage: String? = null,
)

/** Shared deterministic state and event stream for the Todo18 boundary fixtures. */
class Todo18Scenario(val accountId: AccountId) {
    private val listeners = CopyOnWriteArraySet<(Todo18BoundaryEvent) -> Unit>()

    internal val instant = Instant.parse("2026-08-26T03:00:00Z")
    internal val zone = ZoneId.of("Asia/Seoul")
    internal val contentId = PlantContentId("species-monstera")
    internal val plants = linkedMapOf<PersonalPlantId, RemotePersonalPlant>()

    val miniHomeSaveRequests = CopyOnWriteArrayList<MiniHomeSaveRequest>()
    var miniHomeSaveMode: Todo18MiniHomeSaveMode = Todo18MiniHomeSaveMode.APPLY
    var shareMode: Todo18ShareMode = Todo18ShareMode.READY
    var collectionOnline: Boolean = true
    var mutationConflict: Boolean = false

    fun now(): Instant = instant

    fun subscribe(listener: (Todo18BoundaryEvent) -> Unit): Closeable {
        listeners += listener
        return Closeable { listeners -= listener }
    }

    fun listenerCount(): Int = listeners.size

    internal fun emit(kind: String, identity: String) {
        val event = Todo18BoundaryEvent(kind, identity)
        listeners.forEach { listener ->
            when (kind) {
                "mini-home-save-attempt" ->
                    MiniHomeSaveActionDiagnostics.observe(
                        MiniHomeSaveActionObservation(
                            MiniHomeSaveActionStage.LISTENER_DELIVERY,
                            OperationId(identity),
                        )
                    )
                "watering-receipt" ->
                    WateringConfirmActionDiagnostics.observe(
                        WateringConfirmActionObservation(
                            WateringConfirmActionStage.LISTENER_DELIVERY,
                            operationId = OperationId(identity),
                        )
                    )
            }
            listener(event)
        }
    }

    internal fun emitMiniHomeLoadDiagnostic(observation: Todo18MiniHomeLoadObservation) {
        val kind =
            when (observation.diagnostic) {
                Todo18MiniHomeLoadDiagnostic.LoadEntered -> "load-entered"
                Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered -> "remote-load-entered"
                Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned -> "remote-load-returned"
                is Todo18MiniHomeLoadDiagnostic.CacheApplyEntered -> "cache-apply-entered"
                is Todo18MiniHomeLoadDiagnostic.CacheApplyReturned -> "cache-apply-returned"
                Todo18MiniHomeLoadDiagnostic.PublicationReadEntered -> "publication-read-entered"
                Todo18MiniHomeLoadDiagnostic.PublicationReadReturned -> "publication-read-returned"
                is Todo18MiniHomeLoadDiagnostic.PendingReadEntered -> "pending-read-entered"
                is Todo18MiniHomeLoadDiagnostic.PendingReadReturned -> "pending-read-returned"
                is Todo18MiniHomeLoadDiagnostic.PendingReadThrew -> "pending-read-threw"
                is Todo18MiniHomeLoadDiagnostic.PendingReadCancelled -> "pending-read-cancelled"
                is Todo18MiniHomeLoadDiagnostic.Terminal -> "load-terminal"
            }
        val identity =
            when (observation.diagnostic) {
                Todo18MiniHomeLoadDiagnostic.LoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered,
                Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned,
                Todo18MiniHomeLoadDiagnostic.PublicationReadEntered -> accountId.value
                Todo18MiniHomeLoadDiagnostic.PublicationReadReturned -> accountId.value
                is Todo18MiniHomeLoadDiagnostic.PendingReadEntered ->
                    observation.diagnostic.accountId.value
                is Todo18MiniHomeLoadDiagnostic.PendingReadReturned ->
                    observation.diagnostic.accountId.value
                is Todo18MiniHomeLoadDiagnostic.PendingReadThrew ->
                    observation.diagnostic.accountId.value
                is Todo18MiniHomeLoadDiagnostic.PendingReadCancelled ->
                    observation.diagnostic.accountId.value
                is Todo18MiniHomeLoadDiagnostic.CacheApplyEntered ->
                    observation.diagnostic.accountId.value
                is Todo18MiniHomeLoadDiagnostic.CacheApplyReturned ->
                    observation.diagnostic.accountId.value
                Todo18MiniHomeLoadDiagnostic.Ready -> "Ready"
                Todo18MiniHomeLoadDiagnostic.Forbidden -> "Forbidden"
                Todo18MiniHomeLoadDiagnostic.Failed -> "Failed"
                Todo18MiniHomeLoadDiagnostic.Cancelled -> "Cancelled"
            }
        val event =
            Todo18BoundaryEvent(
                kind = kind,
                identity = identity,
                loadId = observation.loadId.value,
                readId = observation.readId?.ordinal,
                diagnosticOrder = observation.order,
                cacheOutcome =
                    (observation.diagnostic as? Todo18MiniHomeLoadDiagnostic.CacheApplyReturned)
                        ?.let { if (it.current) "current" else "conflict" },
                pendingReadLoadId = observation.pendingReadId?.loadId?.value,
                pendingReadId = observation.pendingReadId?.queryOrdinal,
                pendingReadOutcome =
                    when (observation.diagnostic) {
                        is Todo18MiniHomeLoadDiagnostic.PendingReadReturned -> "returned"
                        is Todo18MiniHomeLoadDiagnostic.PendingReadThrew -> "threw"
                        is Todo18MiniHomeLoadDiagnostic.PendingReadCancelled -> "cancelled"
                        else -> null
                    },
                pendingReadFailureClass =
                    when (val diagnostic = observation.diagnostic) {
                        is Todo18MiniHomeLoadDiagnostic.PendingReadThrew ->
                            diagnostic.failure.javaClass.name
                        is Todo18MiniHomeLoadDiagnostic.PendingReadCancelled ->
                            diagnostic.failure.javaClass.name
                        else -> null
                    },
                pendingReadFailureMessage =
                    when (val diagnostic = observation.diagnostic) {
                        is Todo18MiniHomeLoadDiagnostic.PendingReadThrew ->
                            diagnostic.failure.message
                        is Todo18MiniHomeLoadDiagnostic.PendingReadCancelled ->
                            diagnostic.failure.message
                        else -> null
                    },
            )
        listeners.forEach { it(event) }
    }

    internal fun store(plant: PersonalPlant) {
        plants[plant.id] = plant.remote()
    }

    fun seedPlant(): PersonalPlantId {
        val plantId = PersonalPlantId("todo18-seeded-plant")
        plants[plantId] =
            RemotePersonalPlant(
                accountId = accountId,
                id = plantId,
                displayName = "Todo18 Monstera",
                contentId = contentId,
                registrationMethod = RegistrationMethod.MANUAL,
                representativePhotoPath = null,
                location = "거실",
                privateNote = null,
                lastWateredDate = LocalDate.of(2026, 8, 20),
                revision = 1,
                updatedAt = instant,
            )
        return plantId
    }

    private fun PersonalPlant.remote() =
        RemotePersonalPlant(
            accountId = accountId,
            id = id,
            displayName = displayName,
            contentId = contentId,
            registrationMethod = registrationMethod,
            representativePhotoPath = representativePhotoPath,
            location = location,
            privateNote = note,
            lastWateredDate = lastWateredDate,
            revision = revision.value,
            updatedAt = updatedAt,
        )
}
