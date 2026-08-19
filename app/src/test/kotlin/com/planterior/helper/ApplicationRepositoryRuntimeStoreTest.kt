package com.planterior.helper

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationRepositoryRuntimeStoreTest {
    @Test
    fun `activity recreation reuses repositories until deterministic final shutdown`() {
        val closeCount = AtomicInteger()
        val store = ApplicationRepositoryRuntimeStore { TestRuntime(closeCount) }

        val beforeRotation = store.acquire()
        val afterRotation = store.acquire()

        assertSame(beforeRotation, afterRotation)
        assertEquals(0, closeCount.get())
        val firstShutdown = store.shutdown()
        assertTrue(firstShutdown.closed)
        assertEquals(1L, firstShutdown.generation)
        assertEquals(1, closeCount.get())

        val duplicateShutdown = store.shutdown()
        assertFalse(duplicateShutdown.closed)
        assertEquals(1L, duplicateShutdown.generation)
        assertEquals(1, closeCount.get())

        val afterFinalShutdown = store.acquire()
        assertNotSame(beforeRotation, afterFinalShutdown)
        assertEquals(2L, store.snapshot().generation)
        assertEquals(1, closeCount.get())
    }

    private class TestRuntime(private val closeCount: AtomicInteger) : AutoCloseable {
        override fun close() {
            closeCount.incrementAndGet()
        }
    }
}
