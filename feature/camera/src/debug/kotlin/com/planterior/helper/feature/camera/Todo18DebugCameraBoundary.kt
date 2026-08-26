package com.planterior.helper.feature.camera

import java.io.Closeable
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

    internal fun photoPreparationFinished(uri: String, accepted: Boolean) {
        val event = Todo18DebugPhotoPreparationEvent(uri, accepted)
        listeners.forEach { it(event) }
    }
}

data class Todo18DebugPhotoPreparationEvent(val uri: String, val accepted: Boolean)

internal fun todo18DebugCameraPermission(actual: CameraPermission): CameraPermission =
    Todo18DebugCameraBoundary.permissionOr(actual)

internal fun todo18DebugPhotoPickerUri(): String? = Todo18DebugCameraBoundary.pickerUriOrNull()

internal fun todo18DebugPhotoPreparationFinished(uri: String, accepted: Boolean) {
    Todo18DebugCameraBoundary.photoPreparationFinished(uri, accepted)
}
