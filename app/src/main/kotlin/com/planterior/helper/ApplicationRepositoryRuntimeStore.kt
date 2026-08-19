package com.planterior.helper

/**
 * Owns process-wide data resources independently from Activity recreation.
 *
 * A runtime is replaced only after an explicit final shutdown. The synchronized close prevents a
 * replacement Room instance from opening the same file while the prior instance is still closing.
 */
internal class ApplicationRepositoryRuntimeStore<T : AutoCloseable>(private val factory: () -> T) {
    private var runtime: T? = null
    private var generation = 0L

    @Synchronized
    fun acquire(): T {
        runtime?.let {
            return it
        }
        return factory().also {
            generation += 1
            runtime = it
        }
    }

    @Synchronized
    fun shutdown(): ApplicationRuntimeShutdownReceipt {
        val active =
            runtime
                ?: return ApplicationRuntimeShutdownReceipt(closed = false, generation = generation)
        active.close()
        runtime = null
        return ApplicationRuntimeShutdownReceipt(closed = true, generation = generation)
    }

    @Synchronized
    fun snapshot(): ApplicationRuntimeSnapshot =
        ApplicationRuntimeSnapshot(generation = generation, active = runtime != null)
}

internal data class ApplicationRuntimeSnapshot(val generation: Long, val active: Boolean)

internal data class ApplicationRuntimeShutdownReceipt(val closed: Boolean, val generation: Long)
