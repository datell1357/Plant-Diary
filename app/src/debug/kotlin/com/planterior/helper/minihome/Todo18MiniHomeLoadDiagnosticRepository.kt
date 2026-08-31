package com.planterior.helper.minihome

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.minihome.MiniHomeLoadResult
import com.planterior.helper.feature.minihome.MiniHomePendingReadIdentity
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

    data class CacheApplyEntered(val accountId: AccountId) : Todo18MiniHomeLoadDiagnostic {
        override val receiptStage = "cache-apply-entered"
    }

    data class CacheApplyReturned(
        val accountId: AccountId,
        val current: Boolean,
    ) : Todo18MiniHomeLoadDiagnostic {
        override val receiptStage = "cache-apply-returned"
    }

    data object PublicationReadEntered : Todo18MiniHomeLoadDiagnostic {
        override val receiptStage = "publication-read-entered"
    }

    data object PublicationReadReturned : Todo18MiniHomeLoadDiagnostic {
        override val receiptStage = "publication-read-returned"
    }

    data class PendingReadEntered(
        val accountId: AccountId,
        val identity: MiniHomePendingReadIdentity,
    ) : Todo18MiniHomeLoadDiagnostic {
        override val receiptStage = "pending-read-entered"
    }

    data class PendingReadReturned(
        val accountId: AccountId,
        val identity: MiniHomePendingReadIdentity,
    ) : Todo18MiniHomeLoadDiagnostic {
        override val receiptStage = "pending-read-returned"
    }

    data class PendingReadThrew(
        val accountId: AccountId,
        val identity: MiniHomePendingReadIdentity,
        val failure: Throwable,
    ) : Todo18MiniHomeLoadDiagnostic {
        override val receiptStage = "pending-read-threw"
    }

    data class PendingReadCancelled(
        val accountId: AccountId,
        val identity: MiniHomePendingReadIdentity,
        val failure: CancellationException,
    ) : Todo18MiniHomeLoadDiagnostic {
        override val receiptStage = "pending-read-cancelled"
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
        } catch (error: AssertionError) {
            load.record(Todo18MiniHomeLoadDiagnostic.Failed)
            throw error
        } catch (error: Exception) {
            load.record(Todo18MiniHomeLoadDiagnostic.Failed)
            throw error
        }
    }
}
