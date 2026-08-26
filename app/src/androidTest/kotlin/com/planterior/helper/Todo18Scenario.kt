package com.planterior.helper

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.PersonalPlant
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.RegistrationMethod
import com.planterior.helper.feature.collection.RemotePersonalPlant
import com.planterior.helper.feature.minihome.MiniHomeSaveRequest
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

data class Todo18BoundaryEvent(val kind: String, val identity: String)

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

    internal fun emit(kind: String, identity: String) {
        val event = Todo18BoundaryEvent(kind, identity)
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
