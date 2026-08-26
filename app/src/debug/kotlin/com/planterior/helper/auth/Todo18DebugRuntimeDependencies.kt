package com.planterior.helper.auth

/** Process-local dependency slot used only by deterministic debug instrumentation. */
internal object Todo18DebugRuntimeDependencies {
    private val lock = Any()

    @Volatile private var installed: AuthRuntimeDependencyOverrides? = null

    fun install(overrides: AuthRuntimeDependencyOverrides) {
        synchronized(lock) {
            check(installed == null) { "Todo18 runtime dependencies are already installed" }
            installed = overrides
        }
    }

    fun clear() {
        synchronized(lock) { installed = null }
    }

    fun current(): AuthRuntimeDependencyOverrides? = installed
}

internal fun todo18DebugRuntimeDependencyOverrides(): AuthRuntimeDependencyOverrides? =
    Todo18DebugRuntimeDependencies.current()
