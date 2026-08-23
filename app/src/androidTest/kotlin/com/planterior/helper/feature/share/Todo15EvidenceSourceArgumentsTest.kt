package com.planterior.helper.feature.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Todo15EvidenceSourceArgumentsTest {
    private val head = "1".repeat(40)
    private val tree = "a".repeat(40)

    @Test
    fun acceptsExactLowercaseGitObjectIds() {
        assertEquals(
            Todo15EvidenceSource(head, tree),
            Todo15EvidenceSourceArguments.require(head, tree),
        )
    }

    @Test
    fun rejectsMissingSourceArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            Todo15EvidenceSourceArguments.require(null, tree)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Todo15EvidenceSourceArguments.require(head, null)
        }
    }

    @Test
    fun rejectsMalformedSourceArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            Todo15EvidenceSourceArguments.require("A".repeat(40), tree)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Todo15EvidenceSourceArguments.require(head.dropLast(1), tree)
        }
    }
}
