package com.planterior.helper.feature.camera

import android.net.Uri
import android.os.SystemClock

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

internal val DEFAULT_TRACE_WRITER = Todo18DebugCameraTraceWriter { record ->
    android.util.Log.i("Todo18CameraTrace", record.toLogLine())
}

internal val NO_TRACE_WRITER = Todo18DebugCameraTraceWriter {}

internal fun safeTrace(
    traceWriter: Todo18DebugCameraTraceWriter,
    record: Todo18DebugCameraTraceRecord,
) {
    try {
        traceWriter.write(record)
    } catch (_: AssertionError) {} catch (_: Exception) {}
}

internal fun classifyUri(uri: String?): Todo18DebugCameraTraceUriKind {
    if (uri == null) return Todo18DebugCameraTraceUriKind.ABSENT
    val parsed = Uri.parse(uri)
    return if (
        parsed.scheme == "file" &&
            parsed.authority.isNullOrEmpty() &&
            parsed.query == null &&
            parsed.fragment == null &&
            parsed.path?.endsWith("/cache/todo18/missing-photo.jpg") == true
    ) {
        Todo18DebugCameraTraceUriKind.TODO18_APP_CACHE_MISSING_FILE
    } else {
        Todo18DebugCameraTraceUriKind.REDACTED_OTHER
    }
}

private fun Todo18DebugCameraTraceRecord.toLogLine(): String {
    val elapsedNanos =
        (SystemClock.elapsedRealtimeNanos() - token.startedElapsedRealtimeNanos).coerceAtLeast(0L)
    return buildString {
        append("schema=todo18-camera-trace-v1")
        append(" operationId=").append(token.operationId)
        append(" processId=").append(token.processId.toUInt().toString(16))
        append(" boundaryIdentity=").append(token.boundaryIdentity.toUInt().toString(16))
        append(" stage=").append(stage.name)
        append(" uriKind=").append(uriKind.name)
        append(" terminal=").append(terminal.name)
        append(" listenerIdentity=").append(listenerIdentity?.toUInt()?.toString(16) ?: "none")
        append(" listenerIndex=").append(listenerIndex?.toString() ?: "none")
        append(" listenerCount=").append(listenerCount)
        append(" elapsedNanos=").append(elapsedNanos)
        append(" thread=").append(sanitizeThread(Thread.currentThread().name))
    }
}

private fun sanitizeThread(value: String): String = buildString {
    value.forEach { character ->
        append(
            if (
                character in 'A'..'Z' ||
                    character in 'a'..'z' ||
                    character in '0'..'9' ||
                    character == '_' ||
                    character == '.' ||
                    character == '-'
            ) {
                character
            } else {
                '_'
            }
        )
    }
}

internal fun writeListenerTrace(
    token: Todo18DebugCameraTraceToken?,
    stage: Todo18DebugCameraTraceStage,
    uri: String,
    terminal: Todo18DebugCameraTraceTerminal,
    index: Int,
    count: Int,
    listener: (Todo18DebugPhotoPreparationEvent) -> Unit,
    traceWriter: Todo18DebugCameraTraceWriter,
) =
    writeCameraTrace(
        token,
        stage,
        uri,
        terminal,
        System.identityHashCode(listener),
        index,
        count,
        traceWriter,
    )

internal fun writeCameraTrace(
    token: Todo18DebugCameraTraceToken?,
    stage: Todo18DebugCameraTraceStage,
    uri: String?,
    terminal: Todo18DebugCameraTraceTerminal,
    listenerIdentity: Int?,
    listenerIndex: Int?,
    listenerCount: Int,
    traceWriter: Todo18DebugCameraTraceWriter,
) {
    if (token == null) return
    val record =
        Todo18DebugCameraTraceRecord(
            token,
            stage,
            classifyUri(uri),
            terminal,
            listenerIdentity,
            listenerIndex,
            listenerCount,
        )
    safeTrace(traceWriter, record)
}
