package com.planterior.helper.feature.camera

import android.os.Process
import android.os.SystemClock
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

private typealias Todo18DebugCameraListener = (Todo18DebugPhotoPreparationEvent) -> Unit

private typealias Todo18DebugCameraListenerSnapshot = () -> List<Todo18DebugCameraListener>

private val cameraTraceOperationIds = AtomicLong()

internal fun todo18DebugStartCameraTraceRuntime(
    uri: String?,
    traceWriter: Todo18DebugCameraTraceWriter,
): Todo18DebugCameraTraceToken? {
    val token =
        Todo18DebugCameraTraceToken(
            cameraTraceOperationIds.incrementAndGet(),
            Process.myPid(),
            System.identityHashCode(Todo18DebugCameraBoundary),
            SystemClock.elapsedRealtimeNanos(),
        )
    todo18DebugTraceCameraStageRuntime(
        token,
        Todo18DebugCameraTraceStage.COMMAND_RESOLVED,
        uri,
        Todo18DebugCameraTraceTerminal.NONE,
        traceWriter,
    )
    return token
}

internal fun todo18DebugTraceCameraStageRuntime(
    token: Todo18DebugCameraTraceToken?,
    stage: Todo18DebugCameraTraceStage,
    uri: String?,
    terminal: Todo18DebugCameraTraceTerminal,
    traceWriter: Todo18DebugCameraTraceWriter,
) {
    if (token == null) return
    safeTrace(
        traceWriter,
        Todo18DebugCameraTraceRecord(
            token,
            stage,
            classifyUri(uri),
            terminal,
            null,
            null,
            0,
        ),
    )
}

internal suspend fun todo18DebugObservePhotoPreparationRuntime(
    token: Todo18DebugCameraTraceToken?,
    uri: String,
    traceWriter: Todo18DebugCameraTraceWriter,
    listenerSnapshot: Todo18DebugCameraListenerSnapshot,
    prepareAndApply: suspend () -> Boolean,
): Boolean {
    if (token != null) {
        safeTrace(
            traceWriter,
            Todo18DebugCameraTraceRecord(
                token,
                Todo18DebugCameraTraceStage.WRAPPER_ENTERED,
                classifyUri(uri),
                Todo18DebugCameraTraceTerminal.NONE,
                null,
                null,
                0,
            ),
        )
    }
    try {
        val accepted = prepareAndApply()
        todo18DebugTraceCameraStageRuntime(
            token,
            Todo18DebugCameraTraceStage.DELEGATE_RETURNED,
            uri,
            Todo18DebugCameraTraceTerminal.NONE,
            traceWriter,
        )
        val terminal =
            if (accepted) {
                Todo18DebugCameraTraceTerminal.RETURNED_TRUE
            } else {
                Todo18DebugCameraTraceTerminal.RETURNED_FALSE
            }
        finishPhotoPreparation(
            token,
            uri,
            terminal,
            Todo18DebugCameraTraceTerminal.NONE,
            traceWriter,
            listenerSnapshot,
            Todo18DebugPhotoPreparationTerminal.Returned(accepted),
        )
        return accepted
    } catch (failure: Throwable) {
        val terminal =
            if (failure is CancellationException) {
                Todo18DebugCameraTraceTerminal.CANCELLED
            } else {
                Todo18DebugCameraTraceTerminal.THROWN
            }
        val eventTerminal =
            if (failure is CancellationException) {
                Todo18DebugPhotoPreparationTerminal.Cancelled(failure)
            } else {
                Todo18DebugPhotoPreparationTerminal.Thrown(failure)
            }
        finishPhotoPreparation(
            token,
            uri,
            terminal,
            terminal,
            traceWriter,
            listenerSnapshot,
            eventTerminal,
        )
        throw failure
    }
}

private fun finishPhotoPreparation(
    token: Todo18DebugCameraTraceToken?,
    uri: String,
    selectedTerminal: Todo18DebugCameraTraceTerminal,
    publishedTerminal: Todo18DebugCameraTraceTerminal,
    traceWriter: Todo18DebugCameraTraceWriter,
    listenerSnapshot: Todo18DebugCameraListenerSnapshot,
    eventTerminal: Todo18DebugPhotoPreparationTerminal,
) {
    todo18DebugTraceCameraStageRuntime(
        token,
        Todo18DebugCameraTraceStage.TERMINAL_SELECTED,
        uri,
        selectedTerminal,
        traceWriter,
    )
    todo18DebugPublishCameraTraceRuntime(
        token,
        uri,
        Todo18DebugPhotoPreparationEvent(uri, eventTerminal),
        publishedTerminal,
        traceWriter,
        listenerSnapshot,
    )
}

internal suspend fun todo18DebugObservePhotoPreparationRuntime(
    uri: String,
    listenerSnapshot: Todo18DebugCameraListenerSnapshot,
    prepareAndApply: suspend () -> Boolean,
): Boolean {
    try {
        val accepted = prepareAndApply()
        publishLegacyPhotoPreparation(
            uri,
            Todo18DebugPhotoPreparationTerminal.Returned(accepted),
            listenerSnapshot,
        )
        return accepted
    } catch (cancellation: CancellationException) {
        publishLegacyPhotoPreparation(
            uri,
            Todo18DebugPhotoPreparationTerminal.Cancelled(cancellation),
            listenerSnapshot,
        )
        throw cancellation
    } catch (failure: Throwable) {
        publishLegacyPhotoPreparation(
            uri,
            Todo18DebugPhotoPreparationTerminal.Thrown(failure),
            listenerSnapshot,
        )
        throw failure
    }
}

private fun publishLegacyPhotoPreparation(
    uri: String,
    terminal: Todo18DebugPhotoPreparationTerminal,
    listenerSnapshot: Todo18DebugCameraListenerSnapshot,
) =
    todo18DebugPublishCameraTraceRuntime(
        null,
        uri,
        Todo18DebugPhotoPreparationEvent(uri, terminal),
        Todo18DebugCameraTraceTerminal.NONE,
        NO_TRACE_WRITER,
        listenerSnapshot,
    )

private fun todo18DebugPublishCameraTraceRuntime(
    token: Todo18DebugCameraTraceToken?,
    uri: String,
    event: Todo18DebugPhotoPreparationEvent,
    terminal: Todo18DebugCameraTraceTerminal,
    traceWriter: Todo18DebugCameraTraceWriter,
    listenerSnapshot: Todo18DebugCameraListenerSnapshot,
) {
    val snapshot = listenerSnapshot()
    writeCameraTrace(
        token,
        Todo18DebugCameraTraceStage.PUBLISH_BEGIN,
        uri,
        terminal,
        null,
        null,
        snapshot.size,
        traceWriter,
    )
    snapshot.forEachIndexed { index, listener ->
        val listenerStage =
            try {
                listener(event)
                Todo18DebugCameraTraceStage.LISTENER_DELIVERED
            } catch (_: AssertionError) {
                Todo18DebugCameraTraceStage.LISTENER_FAULT
            } catch (_: Exception) {
                Todo18DebugCameraTraceStage.LISTENER_FAULT
            }
        writeListenerTrace(
            token,
            listenerStage,
            uri,
            terminal,
            index,
            snapshot.size,
            listener,
            traceWriter,
        )
    }
    writeCameraTrace(
        token,
        Todo18DebugCameraTraceStage.PUBLISH_COMPLETE,
        uri,
        terminal,
        null,
        null,
        snapshot.size,
        traceWriter,
    )
}
