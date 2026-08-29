package com.planterior.helper

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Todo18DiagnosticReceiptFinalizerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `path creation failure preserves the exact primary assertion`() {
        val primary = AssertionError("known MiniHome wait failure")
        val pathFailure = IllegalStateException("external evidence directory unavailable")
        val finalizer = finalizer { throw pathFailure }

        val actual =
            assertThrows(AssertionError::class.java) {
                preserveTodo18PrimaryFailure(
                    block = { throw primary },
                    finish = { failure -> finalizer.finish(failure != null, "complete") {} },
                )
            }

        assertSame(primary, actual)
        assertEquals(1, actual.suppressed.size)
        assertSame(pathFailure, actual.suppressed.single().cause)
    }

    @Test
    fun `path creation failure preserves the exact primary exception`() {
        val primary = IllegalArgumentException("known MiniHome wait exception")
        val pathFailure = IllegalStateException("cannot create receipt directory")
        val finalizer = finalizer { throw pathFailure }

        val actual =
            assertThrows(IllegalArgumentException::class.java) {
                preserveTodo18PrimaryFailure(
                    block = { throw primary },
                    finish = { failure -> finalizer.finish(failure != null, "complete") {} },
                )
            }

        assertSame(primary, actual)
        assertEquals(1, actual.suppressed.size)
        assertSame(pathFailure, actual.suppressed.single().cause)
    }

    @Test
    fun `valid receipt path still writes and successful block returns unchanged`() {
        val receipt = File(temporaryFolder.newFolder("receipt"), "diagnostic.json")
        val finalizer = finalizer { receipt }

        val result =
            preserveTodo18PrimaryFailure(
                block = { "journey-result" },
                finish = { failure ->
                    finalizer.finish(failure != null, "complete") { file ->
                        file.writeText("{\"status\":\"complete\"}")
                    }
                },
            )

        assertEquals("journey-result", result)
        assertEquals("{\"status\":\"complete\"}", receipt.readText())
    }

    @Test
    fun `known timeout preserves primary and still finalizes receipt JSON`() {
        val receipt = File(temporaryFolder.newFolder("known-timeout"), "diagnostic.json")
        val timeout = AssertionError("known MiniHome Editing wait timeout")
        val finalizer = finalizer { receipt }

        val actual =
            assertThrows(AssertionError::class.java) {
                preserveTodo18PrimaryFailure(
                    block = { throw timeout },
                    finish = { failure ->
                        finalizer.finish(failure != null, "complete") { file ->
                            file.writeText("{\"await\":\"failure\"}")
                        }
                    },
                )
            }

        assertSame(timeout, actual)
        assertEquals("{\"await\":\"failure\"}", receipt.readText())
        assertEquals(1, actual.suppressed.size)
        assertTrue(actual.suppressed.single().message!!.contains("status=complete"))
    }

    @Test
    fun `write failure without a primary is an explicit diagnostic failure`() {
        val receipt = File(temporaryFolder.newFolder("write-failure"), "diagnostic.json")
        val writeFailure = IllegalStateException("receipt write failed")
        val finalizer = finalizer { receipt }

        val actual =
            assertThrows(IllegalStateException::class.java) {
                preserveTodo18PrimaryFailure(
                    block = {},
                    finish = { failure ->
                        finalizer.finish(failure != null, "complete") { throw writeFailure }
                    },
                )
            }

        assertTrue(actual.message, actual.message!!.contains(receipt.absolutePath))
        assertTrue(actual.message, actual.message!!.contains("receipt-finalization-failed"))
        assertSame(writeFailure, actual.cause)
    }

    @Test
    fun `path creation failure without a primary is an explicit diagnostic failure`() {
        val pathFailure = IllegalStateException("external path failed")
        val finalizer = finalizer { throw pathFailure }

        val actual =
            assertThrows(IllegalStateException::class.java) {
                preserveTodo18PrimaryFailure(
                    block = {},
                    finish = { failure -> finalizer.finish(failure != null, "complete") {} },
                )
            }

        assertTrue(actual.message, actual.message!!.contains("receipt=<unavailable>"))
        assertTrue(actual.message, actual.message!!.contains("receipt-finalization-failed"))
        assertSame(pathFailure, actual.cause)
    }

    private fun finalizer(path: () -> File) =
        Todo18DiagnosticReceiptFinalizer(
            receiptFile = path,
            diagnosticName = "Todo18 MiniHome diagnostic",
        )
}
