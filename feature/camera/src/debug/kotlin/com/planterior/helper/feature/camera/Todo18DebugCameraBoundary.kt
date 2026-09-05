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

    internal fun startCameraTrace(
        uri: String?,
        traceWriter: Todo18DebugCameraTraceWriter,
    ): Todo18DebugCameraTraceToken? = todo18DebugStartCameraTraceRuntime(uri, traceWriter)

    internal fun traceCameraStage(
        token: Todo18DebugCameraTraceToken?,
        stage: Todo18DebugCameraTraceStage,
        uri: String?,
        terminal: Todo18DebugCameraTraceTerminal,
        traceWriter: Todo18DebugCameraTraceWriter,
    ) = todo18DebugTraceCameraStageRuntime(token, stage, uri, terminal, traceWriter)

    internal fun <T> traceCameraIo(
        token: Todo18DebugCameraTraceToken?,
        uri: String,
        traceWriter: Todo18DebugCameraTraceWriter,
        operation: () -> T,
    ): T = todo18DebugTraceCameraIoRuntime(token, uri, traceWriter, operation)

    internal suspend fun observePhotoPreparation(
        token: Todo18DebugCameraTraceToken?,
        uri: String,
        traceWriter: Todo18DebugCameraTraceWriter,
        prepareAndApply: suspend () -> Boolean,
    ): Boolean =
        todo18DebugObservePhotoPreparationRuntime(
            token,
            uri,
            traceWriter,
            { listeners.toList() },
            prepareAndApply,
        )

    internal suspend fun observePhotoPreparation(
        uri: String,
        prepareAndApply: suspend () -> Boolean,
    ): Boolean =
        todo18DebugObservePhotoPreparationRuntime(
            uri,
            { listeners.toList() },
            prepareAndApply,
        )
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

internal fun todo18DebugStartCameraTrace(uri: String?): Todo18DebugCameraTraceToken? =
    Todo18DebugCameraBoundary.startCameraTrace(uri, DEFAULT_TRACE_WRITER)

internal fun todo18DebugTraceCameraStage(
    token: Todo18DebugCameraTraceToken?,
    stage: Todo18DebugCameraTraceStage,
    uri: String?,
    terminal: Todo18DebugCameraTraceTerminal = Todo18DebugCameraTraceTerminal.NONE,
) =
    Todo18DebugCameraBoundary.traceCameraStage(
        token,
        stage,
        uri,
        terminal,
        DEFAULT_TRACE_WRITER,
    )

internal fun <T> todo18DebugTraceCameraIo(
    token: Todo18DebugCameraTraceToken?,
    uri: String,
    operation: () -> T,
): T = Todo18DebugCameraBoundary.traceCameraIo(token, uri, DEFAULT_TRACE_WRITER, operation)

internal suspend fun todo18DebugObservePhotoPreparation(
    token: Todo18DebugCameraTraceToken?,
    uri: String,
    prepareAndApply: suspend () -> Boolean,
): Boolean =
    Todo18DebugCameraBoundary.observePhotoPreparation(
        token,
        uri,
        DEFAULT_TRACE_WRITER,
        prepareAndApply,
    )
