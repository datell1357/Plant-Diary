package com.planterior.helper.action

internal class OrderedActionStageTrace<S : Any>(private val expected: List<S>) {
    private val reached = mutableListOf<S>()

    fun record(stage: S) {
        reached += stage
    }

    fun snapshot(): List<S> = reached.toList()

    fun firstMissing(): S? {
        var expectedIndex = 0
        for (stage in reached) {
            if (expectedIndex == expected.size) break
            if (stage == expected[expectedIndex]) expectedIndex += 1
        }
        return expected.getOrNull(expectedIndex)
    }

    fun requireComplete() {
        firstMissing()?.let { throw AssertionError("first missing stage: $it; reached=$reached") }
        if (reached != expected) {
            throw AssertionError("ordered stage mismatch: expected=$expected; reached=$reached")
        }
    }
}
