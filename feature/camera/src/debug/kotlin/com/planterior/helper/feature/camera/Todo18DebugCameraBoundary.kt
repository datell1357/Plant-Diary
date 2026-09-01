package com.planterior.helper.feature.camera

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArraySet

/** Debug-only overrides for the two Android boundaries owned by [CameraRoute]. */
object Todo18DebugCameraBoundary {
    private val listeners = CopyOnWriteArraySet<(Todo18DebugPhotoPreparationEvent) -> Unit>()

    @Volatile private var permission: CameraPermission? = null
    @Volatile private var pickerUri: String? = null

    fun installPermission(value: CameraPermission) {
        permission = value
    }

    fun installPickerUri(value: String) {
        pickerUri = value
    }

    fun subscribe(listener: (Todo18DebugPhotoPreparationEvent) -> Unit): Closeable {
        listeners += listener
        return Closeable { listeners -= listener }
    }

    fun clear() {
        permission = null
        pickerUri = null
        listeners.clear()
    }

    internal fun permissionOr(actual: CameraPermission): CameraPermission = permission ?: actual

    internal fun pickerUriOrNull(): String? = pickerUri

    internal suspend fun observePhotoPreparation(
        uri: String,
        prepareAndApply: suspend () -> Boolean,
    ): Boolean {
        try {
            val accepted = prepareAndApply()
            publish(
                Todo18DebugPhotoPreparationEvent(
                    uri,
                    Todo18DebugPhotoPreparationTerminal.Returned(accepted),
                )
            )
            return accepted
        } catch (cancellation: CancellationException) {
            publish(
                Todo18DebugPhotoPreparationEvent(
                    uri,
                    Todo18DebugPhotoPreparationTerminal.Cancelled(cancellation),
                )
            )
            throw cancellation
        } catch (failure: Throwable) {
            publish(
                Todo18DebugPhotoPreparationEvent(
                    uri,
                    Todo18DebugPhotoPreparationTerminal.Thrown(failure),
                )
            )
            throw failure
        }
    }

    private fun publish(event: Todo18DebugPhotoPreparationEvent) {
        listeners.forEach { listener ->
            try {
                listener(event)
            } catch (_: AssertionError) {} catch (_: Exception) {}
        }
    }
}

sealed interface Todo18DebugPhotoPreparationTerminal {
    data class Returned(val accepted: Boolean) : Todo18DebugPhotoPreparationTerminal

    data class Thrown(val failure: Throwable) : Todo18DebugPhotoPreparationTerminal

    data class Cancelled(val cancellation: java.util.concurrent.CancellationException) :
        Todo18DebugPhotoPreparationTerminal
}

data class Todo18DebugPhotoPreparationEvent(
    val uri: String,
    val terminal: Todo18DebugPhotoPreparationTerminal,
)

internal fun todo18DebugCameraPermission(actual: CameraPermission): CameraPermission =
    Todo18DebugCameraBoundary.permissionOr(actual)

internal fun todo18DebugPhotoPickerUri(): String? = Todo18DebugCameraBoundary.pickerUriOrNull()

internal suspend fun todo18DebugObservePhotoPreparation(
    uri: String,
    prepareAndApply: suspend () -> Boolean,
): Boolean = Todo18DebugCameraBoundary.observePhotoPreparation(uri, prepareAndApply)
