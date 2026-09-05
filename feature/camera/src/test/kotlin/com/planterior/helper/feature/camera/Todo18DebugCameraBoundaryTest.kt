package com.planterior.helper.feature.camera

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Todo18DebugCameraBoundaryTest {
    @Test
    fun ioTraceRecordsReturnAndRunsOnIoThread() = runBlocking {
        val records = CopyOnWriteArrayList<Todo18DebugCameraTraceRecord>()
        val writer = Todo18DebugCameraTraceWriter(records::add)
        val uri = "file:///io-return.jpg"
        val token = Todo18DebugCameraBoundary.startCameraTrace(uri, writer)
        val callerThread = Thread.currentThread().name

        val value =
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                Todo18DebugCameraBoundary.traceCameraIo(token, uri, writer) {
                    assertTrue(Thread.currentThread().name != callerThread)
                    "prepared"
                }
            }

        assertEquals("prepared", value)
        assertEquals(
            listOf(Todo18DebugCameraTraceStage.IO_BEGIN, Todo18DebugCameraTraceStage.IO_RETURN),
            records.filter { it.stage.name.startsWith("IO_") }.map { it.stage },
        )
        assertEquals(1, records.map { it.token }.distinct().size)
    }

    @Test
    fun ioTracePreservesThrownIdentity() = runBlocking {
        val records = CopyOnWriteArrayList<Todo18DebugCameraTraceRecord>()
        val writer = Todo18DebugCameraTraceWriter(records::add)
        val failure = IllegalStateException("io failure")
        val uri = "file:///io-throw.jpg"
        val token = Todo18DebugCameraBoundary.startCameraTrace(uri, writer)

        val thrown =
            try {
                Todo18DebugCameraBoundary.traceCameraIo(token, uri, writer) { throw failure }
                error("operation returned")
            } catch (caught: IllegalStateException) {
                caught
            }

        assertSame(failure, thrown)
        assertEquals(Todo18DebugCameraTraceStage.IO_BEGIN, records[1].stage)
        assertEquals(Todo18DebugCameraTraceStage.IO_THROW, records[2].stage)
        assertEquals(Todo18DebugCameraTraceTerminal.THROWN, records[2].terminal)
    }

    @Test
    fun ioTracePreservesCancellationIdentity() = runBlocking {
        val records = CopyOnWriteArrayList<Todo18DebugCameraTraceRecord>()
        val writer = Todo18DebugCameraTraceWriter(records::add)
        val cancellation = CancellationException("io cancellation")
        val uri = "file:///io-cancel.jpg"
        val token = Todo18DebugCameraBoundary.startCameraTrace(uri, writer)

        val thrown =
            try {
                Todo18DebugCameraBoundary.traceCameraIo(token, uri, writer) { throw cancellation }
                error("operation returned")
            } catch (caught: CancellationException) {
                caught
            }

        assertSame(cancellation, thrown)
        assertEquals(Todo18DebugCameraTraceStage.IO_BEGIN, records[1].stage)
        assertEquals(Todo18DebugCameraTraceStage.IO_CANCEL, records[2].stage)
        assertEquals(Todo18DebugCameraTraceTerminal.CANCELLED, records[2].terminal)
    }

    @Test
    fun returnedFalseTraceUsesOneOperationAndOrderedPublication() = runBlocking {
        val records = CopyOnWriteArrayList<Todo18DebugCameraTraceRecord>()
        val writer = Todo18DebugCameraTraceWriter(records::add)
        val uri = "file:///cache/todo18/missing-photo.jpg"
        val token = Todo18DebugCameraBoundary.startCameraTrace(uri, writer)
        val listener: Closeable = Todo18DebugCameraBoundary.subscribe {}

        listener.use {
            val accepted =
                Todo18DebugCameraBoundary.observePhotoPreparation(token, uri, writer) {
                    Todo18DebugCameraBoundary.traceCameraStage(
                        token,
                        Todo18DebugCameraTraceStage.PREPARE_ENTERED,
                        uri,
                        Todo18DebugCameraTraceTerminal.NONE,
                        writer,
                    )
                    Todo18DebugCameraBoundary.traceCameraStage(
                        token,
                        Todo18DebugCameraTraceStage.PREPARE_RETURNED,
                        uri,
                        Todo18DebugCameraTraceTerminal.NONE,
                        writer,
                    )
                    Todo18DebugCameraBoundary.traceCameraStage(
                        token,
                        Todo18DebugCameraTraceStage.FOLD_RETURNED,
                        uri,
                        Todo18DebugCameraTraceTerminal.NONE,
                        writer,
                    )
                    false
                }

            assertFalse(accepted)
            assertTrue(records.isNotEmpty())
            assertEquals(1, records.map { it.token }.distinct().size)
            assertEquals(
                listOf(
                    Todo18DebugCameraTraceStage.COMMAND_RESOLVED,
                    Todo18DebugCameraTraceStage.WRAPPER_ENTERED,
                    Todo18DebugCameraTraceStage.PREPARE_ENTERED,
                    Todo18DebugCameraTraceStage.PREPARE_RETURNED,
                    Todo18DebugCameraTraceStage.FOLD_RETURNED,
                    Todo18DebugCameraTraceStage.DELEGATE_RETURNED,
                    Todo18DebugCameraTraceStage.TERMINAL_SELECTED,
                    Todo18DebugCameraTraceStage.PUBLISH_BEGIN,
                    Todo18DebugCameraTraceStage.LISTENER_DELIVERED,
                    Todo18DebugCameraTraceStage.PUBLISH_COMPLETE,
                ),
                records.map { it.stage },
            )
            assertEquals(
                Todo18DebugCameraTraceTerminal.RETURNED_FALSE,
                records.last { it.stage == Todo18DebugCameraTraceStage.TERMINAL_SELECTED }.terminal,
            )
        }
    }

    @Test
    fun thrownTracePreservesIdentityAndWriterFaultsAreIsolated() = runBlocking {
        val records = CopyOnWriteArrayList<Todo18DebugCameraTraceRecord>()
        val writer = Todo18DebugCameraTraceWriter { record ->
            if (record.stage == Todo18DebugCameraTraceStage.WRAPPER_ENTERED) {
                throw RuntimeException("trace writer failure")
            }
            records += record
        }
        val failure = IllegalStateException("delegate failure")
        val token = Todo18DebugCameraBoundary.startCameraTrace("file:///thrown.jpg", writer)

        val thrown =
            try {
                Todo18DebugCameraBoundary.observePhotoPreparation(
                    token,
                    "file:///thrown.jpg",
                    writer,
                ) {
                    throw failure
                }
                error("delegate returned")
            } catch (caught: IllegalStateException) {
                caught
            }

        assertSame(failure, thrown)
        assertEquals(
            Todo18DebugCameraTraceTerminal.THROWN,
            records.last { it.stage == Todo18DebugCameraTraceStage.TERMINAL_SELECTED }.terminal,
        )
    }

    @Test
    fun cancelledTracePreservesIdentity() = runBlocking {
        val records = CopyOnWriteArrayList<Todo18DebugCameraTraceRecord>()
        val writer = Todo18DebugCameraTraceWriter(records::add)
        val cancellation = CancellationException("delegate cancellation")
        val token = Todo18DebugCameraBoundary.startCameraTrace("file:///cancelled.jpg", writer)

        val thrown =
            try {
                Todo18DebugCameraBoundary.observePhotoPreparation(
                    token,
                    "file:///cancelled.jpg",
                    writer,
                ) {
                    throw cancellation
                }
                error("delegate returned")
            } catch (caught: CancellationException) {
                caught
            }

        assertSame(cancellation, thrown)
        assertEquals(
            Todo18DebugCameraTraceTerminal.CANCELLED,
            records.last { it.stage == Todo18DebugCameraTraceStage.TERMINAL_SELECTED }.terminal,
        )
    }

    @Test
    fun heldDelegateHasNoPrematureTerminalTrace() = runBlocking {
        val records = CopyOnWriteArrayList<Todo18DebugCameraTraceRecord>()
        val writer = Todo18DebugCameraTraceWriter(records::add)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Boolean>()
        val uri = "file:///held.jpg"
        val token = Todo18DebugCameraBoundary.startCameraTrace(uri, writer)

        val operation = launch {
            Todo18DebugCameraBoundary.observePhotoPreparation(token, uri, writer) {
                Todo18DebugCameraBoundary.traceCameraStage(
                    token,
                    Todo18DebugCameraTraceStage.PREPARE_ENTERED,
                    uri,
                    Todo18DebugCameraTraceTerminal.NONE,
                    writer,
                )
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        assertTrue(records.any { it.stage == Todo18DebugCameraTraceStage.WRAPPER_ENTERED })
        assertTrue(records.none { it.stage == Todo18DebugCameraTraceStage.TERMINAL_SELECTED })
        assertTrue(records.none { it.stage == Todo18DebugCameraTraceStage.PUBLISH_BEGIN })

        release.complete(true)
        operation.join()
    }

    @Test
    fun releasePassThroughReturnsWithoutTraceEvaluation() = runBlocking {
        val writerCalls = AtomicInteger()
        val writer = Todo18DebugCameraTraceWriter {
            writerCalls.incrementAndGet()
            throw RuntimeException("release trace writer")
        }
        val uri = "file:///release-returned.jpg"
        val token = Todo18DebugCameraBoundary.startCameraTrace(uri, writer)

        assertEquals(null, token)
        assertFalse(Todo18DebugCameraBoundary.observePhotoPreparation(token, uri, writer) { false })
        assertEquals(0, writerCalls.get())
    }

    @Test
    fun releasePassThroughPreservesThrownIdentityWithoutTraceEvaluation() = runBlocking {
        val writerCalls = AtomicInteger()
        val writer = Todo18DebugCameraTraceWriter {
            writerCalls.incrementAndGet()
            throw RuntimeException("release trace writer")
        }
        val failure = IllegalStateException("release delegate failure")
        val token = Todo18DebugCameraBoundary.startCameraTrace("file:///release-thrown.jpg", writer)

        val thrown =
            try {
                Todo18DebugCameraBoundary.observePhotoPreparation(
                    token,
                    "file:///release-thrown.jpg",
                    writer,
                ) {
                    throw failure
                }
                error("delegate returned")
            } catch (caught: IllegalStateException) {
                caught
            }

        assertSame(failure, thrown)
        assertEquals(0, writerCalls.get())
    }

    @Test
    fun releasePassThroughPreservesCancellationIdentityWithoutTraceEvaluation() = runBlocking {
        val writerCalls = AtomicInteger()
        val writer = Todo18DebugCameraTraceWriter {
            writerCalls.incrementAndGet()
            throw RuntimeException("release trace writer")
        }
        val cancellation = CancellationException("release cancellation")
        val token =
            Todo18DebugCameraBoundary.startCameraTrace("file:///release-cancelled.jpg", writer)

        val thrown =
            try {
                Todo18DebugCameraBoundary.observePhotoPreparation(
                    token,
                    "file:///release-cancelled.jpg",
                    writer,
                ) {
                    throw cancellation
                }
                error("delegate returned")
            } catch (caught: CancellationException) {
                caught
            }

        assertSame(cancellation, thrown)
        assertEquals(0, writerCalls.get())
    }

    @Test
    fun returnedTerminalIsEmittedOnceAfterDelegateCompletes() = runBlocking {
        val events = CopyOnWriteArrayList<Todo18DebugPhotoPreparationEvent>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Boolean>()
        val subscription: Closeable = Todo18DebugCameraBoundary.subscribe(events::add)

        subscription.use {
            val operation = launch {
                assertTrue(
                    todo18DebugObservePhotoPreparation("file:///missing.jpg") {
                        entered.complete(Unit)
                        release.await()
                    }
                )
            }
            entered.await()
            assertTrue(events.isEmpty())

            release.complete(true)
            operation.join()

            assertEquals(1, events.size)
            assertEquals("file:///missing.jpg", events.single().uri)
            val terminal = events.single().terminal
            assertTrue(terminal is Todo18DebugPhotoPreparationTerminal.Returned)
            assertTrue((terminal as Todo18DebugPhotoPreparationTerminal.Returned).accepted)
        }
    }

    @Test
    fun thrownTerminalPreservesIdentityAndObserverFaultsAreIsolated() = runBlocking {
        val firstEvents = CopyOnWriteArrayList<Todo18DebugPhotoPreparationEvent>()
        val secondEvents = CopyOnWriteArrayList<Todo18DebugPhotoPreparationEvent>()
        val failure = IllegalStateException("delegate failure")
        val first: Closeable = Todo18DebugCameraBoundary.subscribe {
            firstEvents += it
            throw RuntimeException("observer failure")
        }
        val second: Closeable = Todo18DebugCameraBoundary.subscribe(secondEvents::add)

        first.use {
            second.use {
                val thrown =
                    try {
                        todo18DebugObservePhotoPreparation("file:///thrown.jpg") { throw failure }
                        error("delegate returned")
                    } catch (caught: IllegalStateException) {
                        caught
                    }
                assertSame(failure, thrown)
                assertEquals(1, firstEvents.size)
                assertEquals(1, secondEvents.size)
                val terminal = secondEvents.single().terminal
                assertTrue(terminal is Todo18DebugPhotoPreparationTerminal.Thrown)
                assertSame(
                    failure,
                    (terminal as Todo18DebugPhotoPreparationTerminal.Thrown).failure,
                )
            }
        }

        val assertionEvents = CopyOnWriteArrayList<Todo18DebugPhotoPreparationEvent>()
        val observedAfterAssertion = CopyOnWriteArrayList<Todo18DebugPhotoPreparationEvent>()
        val assertionObserver: Closeable = Todo18DebugCameraBoundary.subscribe {
            assertionEvents += it
            throw AssertionError("observer assertion")
        }
        val laterObserver: Closeable =
            Todo18DebugCameraBoundary.subscribe(observedAfterAssertion::add)
        assertionObserver.use {
            laterObserver.use {
                val assertionFailure = IllegalArgumentException("delegate assertion path")
                val thrown =
                    try {
                        todo18DebugObservePhotoPreparation("file:///assertion.jpg") {
                            throw assertionFailure
                        }
                        error("delegate returned")
                    } catch (caught: IllegalArgumentException) {
                        caught
                    }
                assertSame(assertionFailure, thrown)
                assertEquals(1, assertionEvents.size)
                assertEquals(1, observedAfterAssertion.size)
            }
        }
    }

    @Test
    fun cancelledTerminalPreservesIdentity() = runBlocking {
        val events = CopyOnWriteArrayList<Todo18DebugPhotoPreparationEvent>()
        val cancellation = CancellationException("delegate cancellation")
        val subscription: Closeable = Todo18DebugCameraBoundary.subscribe(events::add)

        subscription.use {
            val thrown =
                try {
                    todo18DebugObservePhotoPreparation("file:///cancelled.jpg") {
                        throw cancellation
                    }
                    error("delegate returned")
                } catch (caught: CancellationException) {
                    caught
                }
            assertSame(cancellation, thrown)
            assertEquals(1, events.size)
            val terminal = events.single().terminal
            assertTrue(terminal is Todo18DebugPhotoPreparationTerminal.Cancelled)
            assertSame(
                cancellation,
                (terminal as Todo18DebugPhotoPreparationTerminal.Cancelled).cancellation,
            )
        }
    }

    @Test
    fun parentCancellationIsClassifiedWithoutReturnOrThrow() = runBlocking {
        val events = CopyOnWriteArrayList<Todo18DebugPhotoPreparationEvent>()
        val entered = CompletableDeferred<Unit>()
        val subscription: Closeable = Todo18DebugCameraBoundary.subscribe(events::add)

        subscription.use {
            val operation = launch {
                todo18DebugObservePhotoPreparation("file:///parent-cancelled.jpg") {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            assertTrue(events.isEmpty())

            operation.cancel(CancellationException("surrounding cancellation"))
            operation.join()

            assertFalse(operation.isActive)
            assertTrue(operation.isCancelled)
            assertEquals(1, events.size)
            assertEquals(
                0,
                events.count { it.terminal is Todo18DebugPhotoPreparationTerminal.Returned },
            )
            assertEquals(
                0,
                events.count { it.terminal is Todo18DebugPhotoPreparationTerminal.Thrown },
            )
            assertEquals(
                1,
                events.count { it.terminal is Todo18DebugPhotoPreparationTerminal.Cancelled },
            )
        }
    }
}
