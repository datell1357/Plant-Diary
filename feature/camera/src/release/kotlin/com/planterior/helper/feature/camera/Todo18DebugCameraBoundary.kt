package com.planterior.helper.feature.camera

import java.io.Closeable
import java.util.concurrent.CancellationException

object Todo18DebugCameraBoundary {
    fun clear() = Unit

    fun subscribe(listener: (Todo18DebugPhotoPreparationEvent) -> Unit): Closeable = Closeable {}

    internal fun startCameraTrace(
        uri: String?,
        traceWriter: Todo18DebugCameraTraceWriter,
    ): Todo18DebugCameraTraceToken? {
        return null
    }

    internal fun traceCameraStage(
        token: Todo18DebugCameraTraceToken?,
        stage: Todo18DebugCameraTraceStage,
        uri: String?,
        terminal: Todo18DebugCameraTraceTerminal,
        traceWriter: Todo18DebugCameraTraceWriter,
    ) = Unit

    internal suspend fun observePhotoPreparation(
        token: Todo18DebugCameraTraceToken?,
        uri: String,
        traceWriter: Todo18DebugCameraTraceWriter,
        prepareAndApply: suspend () -> Boolean,
    ): Boolean {
        return prepareAndApply()
    }

    internal suspend fun observePhotoPreparation(
        uri: String,
        prepareAndApply: suspend () -> Boolean,
    ): Boolean {
        return prepareAndApply()
    }
}

sealed interface Todo18DebugPhotoPreparationTerminal {
    data class Returned(val accepted: Boolean) : Todo18DebugPhotoPreparationTerminal

    data class Thrown(val failure: Throwable) : Todo18DebugPhotoPreparationTerminal

    data class Cancelled(val cancellation: CancellationException) :
        Todo18DebugPhotoPreparationTerminal
}

data class Todo18DebugPhotoPreparationEvent(
    val uri: String,
    val terminal: Todo18DebugPhotoPreparationTerminal,
)

internal fun todo18DebugCameraPermission(actual: CameraPermission): CameraPermission = actual

internal fun todo18DebugPhotoPickerUri(): String? = null

internal suspend fun todo18DebugObservePhotoPreparation(
    uri: String,
    prepareAndApply: suspend () -> Boolean,
): Boolean {
    return Todo18DebugCameraBoundary.observePhotoPreparation(uri, prepareAndApply)
}

internal fun todo18DebugStartCameraTrace(uri: String?): Todo18DebugCameraTraceToken? {
    return null
}

internal fun todo18DebugTraceCameraStage(
    token: Todo18DebugCameraTraceToken?,
    stage: Todo18DebugCameraTraceStage,
    uri: String?,
    terminal: Todo18DebugCameraTraceTerminal = Todo18DebugCameraTraceTerminal.NONE,
) = Unit

internal suspend fun todo18DebugObservePhotoPreparation(
    token: Todo18DebugCameraTraceToken?,
    uri: String,
    prepareAndApply: suspend () -> Boolean,
): Boolean {
    return prepareAndApply()
}

internal data class Todo18DebugCameraTraceToken(
    val operationId: Long,
    val processId: Int,
    val boundaryIdentity: Int,
    val startedElapsedRealtimeNanos: Long,
)

internal enum class Todo18DebugCameraTraceStage {
    COMMAND_RESOLVED,
    COROUTINE_SCHEDULED,
    COROUTINE_ENTERED,
    WRAPPER_ENTERED,
    PREPARE_ENTERED,
    PREPARE_RETURNED,
    FOLD_RETURNED,
    DELEGATE_RETURNED,
    TERMINAL_SELECTED,
    PUBLISH_BEGIN,
    LISTENER_DELIVERED,
    LISTENER_FAULT,
    PUBLISH_COMPLETE,
}

internal enum class Todo18DebugCameraTraceTerminal {
    NONE,
    RETURNED_TRUE,
    RETURNED_FALSE,
    THROWN,
    CANCELLED,
}

internal enum class Todo18DebugCameraTraceUriKind {
    ABSENT,
    TODO18_APP_CACHE_MISSING_FILE,
    REDACTED_OTHER,
}

internal data class Todo18DebugCameraTraceRecord(
    val token: Todo18DebugCameraTraceToken,
    val stage: Todo18DebugCameraTraceStage,
    val uriKind: Todo18DebugCameraTraceUriKind,
    val terminal: Todo18DebugCameraTraceTerminal,
    val listenerIdentity: Int?,
    val listenerIndex: Int?,
    val listenerCount: Int,
)

internal fun interface Todo18DebugCameraTraceWriter {
    fun write(record: Todo18DebugCameraTraceRecord)
}
