package com.planterior.helper.feature.camera

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18DebugCameraBoundaryTest {
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
