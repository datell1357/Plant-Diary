import Foundation
import PlanteriorDomain

func replacing(
    _ state: AccountSyncSnapshot,
    queued: [SyncMutation]? = nil,
    failed: [SyncMutation]? = nil,
    conflicts: [SyncMutation]? = nil,
    sent: [OperationID]? = nil,
    lastServerSync: Date?? = nil,
    committedRevisions: [OperationID: Revision]? = nil,
    committedPayloads: [OperationID: Data]? = nil,
    retryCounts: [OperationID: Int]? = nil
) -> AccountSyncSnapshot {
    AccountSyncSnapshot(
        queued: queued ?? state.queued,
        failed: failed ?? state.failed,
        conflicts: conflicts ?? state.conflicts,
        sent: sent ?? state.sent,
        lastServerSync: lastServerSync ?? state.lastServerSync,
        committedRevisions: committedRevisions ?? state.committedRevisions,
        committedPayloads: committedPayloads ?? state.committedPayloads,
        retryCounts: retryCounts ?? state.retryCounts
    )
}
