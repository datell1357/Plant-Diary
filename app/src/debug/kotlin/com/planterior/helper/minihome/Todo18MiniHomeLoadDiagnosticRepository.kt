package com.planterior.helper.minihome

import com.planterior.helper.feature.minihome.MiniHomeLoadResult
import com.planterior.helper.feature.minihome.MiniHomeRepository
import kotlinx.coroutines.CancellationException

internal sealed interface Todo18MiniHomeLoadDiagnostic {
    val receiptStage: String

    data object LoadEntered : Todo18MiniHomeLoadDiagnostic {
        override val receiptStage = "load-entered"
    }

    data object RemoteLoadEntered : Todo18MiniHomeLoadDiagnostic {
        override val receiptStage = "remote-load-entered"
    }

    data object RemoteLoadReturned : Todo18MiniHomeLoadDiagnostic {
        override val receiptStage = "remote-load-returned"
    }

    data object PublicationReadEntered : Todo18MiniHomeLoadDiagnostic {
        override val receiptStage = "publication-read-entered"
    }

    sealed interface Terminal : Todo18MiniHomeLoadDiagnostic

    data object Ready : Terminal {
        override val receiptStage = "terminal-ready"
    }

    data object Forbidden : Terminal {
        override val receiptStage = "terminal-forbidden"
    }

    data object Failed : Terminal {
        override val receiptStage = "terminal-failed"
    }

    data object Cancelled : Terminal {
        override val receiptStage = "terminal-cancelled"
    }
}

/** Todo18-only load observation that delegates all repository behavior to the installed runtime. */
internal class Todo18MiniHomeLoadDiagnosticRepository(
    private val delegate: MiniHomeRepository,
    private val diagnostics: Todo18MiniHomeLoadDiagnosticRecorder,
) : MiniHomeRepository by delegate {
    constructor(
        delegate: MiniHomeRepository,
        onDiagnostic: (Todo18MiniHomeLoadDiagnostic) -> Unit,
    ) : this(
        delegate,
        Todo18MiniHomeLoadDiagnosticRecorder { observation ->
            onDiagnostic(observation.diagnostic)
        },
    )

    fun loadProgressSnapshot(): Todo18MiniHomeLoadProgress = diagnostics.snapshot()

    override suspend fun load(): MiniHomeLoadResult {
        val load = diagnostics.startLoad()
        load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        return try {
            val result = diagnostics.withLoad(load) { delegate.load() }
            val terminal =
                when (result) {
                    is MiniHomeLoadResult.Ready -> Todo18MiniHomeLoadDiagnostic.Ready
                    MiniHomeLoadResult.Forbidden -> Todo18MiniHomeLoadDiagnostic.Forbidden
                    MiniHomeLoadResult.Failed -> Todo18MiniHomeLoadDiagnostic.Failed
                }
            load.record(terminal)
            result
        } catch (error: CancellationException) {
            load.record(Todo18MiniHomeLoadDiagnostic.Cancelled)
            throw error
        } catch (error: Exception) {
            load.record(Todo18MiniHomeLoadDiagnostic.Failed)
            throw error
        }
    }
}
