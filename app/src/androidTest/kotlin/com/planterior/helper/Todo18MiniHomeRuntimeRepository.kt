package com.planterior.helper

import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.feature.minihome.FirebaseMiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnostic
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnosticRecorder
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnosticRepository

internal fun todo18MiniHomeRuntimeRepository(
    database: PlanteriorDatabase,
    boundary: Todo18Scenario,
    diagnostics: Todo18MiniHomeLoadDiagnosticRecorder,
): MiniHomeRepository =
    Todo18MiniHomeLoadDiagnosticRepository(
        delegate =
            FirebaseMiniHomeRepository(
                database,
                Todo18MiniHomeRepositoryFixture(boundary, diagnostics),
                boundary::now,
                beforeCacheApply = { accountId ->
                    diagnostics.recordCurrent(
                        Todo18MiniHomeLoadDiagnostic.CacheApplyEntered(accountId)
                    )
                },
                afterCacheApply = { accountId, current ->
                    diagnostics.recordCurrent(
                        Todo18MiniHomeLoadDiagnostic.CacheApplyReturned(accountId, current)
                    )
                },
                beforePublicationRead = { _, readIdentity ->
                    diagnostics.recordCurrentPublicationRead(
                        Todo18MiniHomeLoadDiagnostic.PublicationReadEntered,
                        readIdentity.value,
                    )
                },
                afterPublicationRead = { _, readIdentity ->
                    diagnostics.recordCurrentPublicationRead(
                        Todo18MiniHomeLoadDiagnostic.PublicationReadReturned,
                        readIdentity.value,
                    )
                },
                beforePendingRead = { accountId, pendingIdentity ->
                    diagnostics.recordCurrent(
                        Todo18MiniHomeLoadDiagnostic.PendingReadEntered(accountId, pendingIdentity)
                    )
                },
                afterPendingRead = { accountId, pendingIdentity, outcome ->
                    diagnostics.recordCurrent(
                        when (outcome) {
                            com.planterior.helper.feature.minihome.MiniHomePendingReadOutcome
                                .Returned ->
                                Todo18MiniHomeLoadDiagnostic.PendingReadReturned(
                                    accountId,
                                    pendingIdentity,
                                )
                            is com.planterior.helper.feature.minihome.MiniHomePendingReadOutcome.Threw ->
                                Todo18MiniHomeLoadDiagnostic.PendingReadThrew(
                                    accountId,
                                    pendingIdentity,
                                    outcome.failure,
                                )
                            is com.planterior.helper.feature.minihome.MiniHomePendingReadOutcome.Cancelled ->
                                Todo18MiniHomeLoadDiagnostic.PendingReadCancelled(
                                    accountId,
                                    pendingIdentity,
                                    outcome.failure,
                                )
                        }
                    )
                },
            ),
        diagnostics = diagnostics,
    )
