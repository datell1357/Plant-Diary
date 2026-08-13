import Foundation
import PlanteriorDomain

public struct SyncMutation: Codable, Equatable, Sendable {
    public let id: OperationID
    public let revision: Revision
    public let payload: Data

    public init(id: OperationID, revision: Revision, payload: Data) {
        self.id = id
        self.revision = revision
        self.payload = payload
    }
}

public struct AccountSyncSnapshot: Codable, Equatable, Sendable {
    public let queued: [SyncMutation]
    public let failed: [SyncMutation]
    public let conflicts: [SyncMutation]
    public let sent: [OperationID]
    public let lastServerSync: Date?
    public let committedRevisions: [OperationID: Revision]
    public let committedPayloads: [OperationID: Data]
    public let retryCounts: [OperationID: Int]

    public init(
        queued: [SyncMutation] = [],
        failed: [SyncMutation] = [],
        conflicts: [SyncMutation] = [],
        sent: [OperationID] = [],
        lastServerSync: Date? = nil,
        committedRevisions: [OperationID: Revision] = [:],
        committedPayloads: [OperationID: Data] = [:],
        retryCounts: [OperationID: Int] = [:]
    ) {
        self.queued = queued
        self.failed = failed
        self.conflicts = conflicts
        self.sent = sent
        self.lastServerSync = lastServerSync
        self.committedRevisions = committedRevisions
        self.committedPayloads = committedPayloads
        self.retryCounts = retryCounts
    }
}

public enum LogoutPendingAction: CaseIterable, Sendable {
    case sync
    case cancel
    case discard
}

public enum LogoutOutcome: Equatable, Sendable {
    case loggedOut
    case cancelled
}

public enum SyncDomain: String, CaseIterable, Sendable {
    case plants
    case care
    case inventory
}

public struct SyncRemoteEvent: Equatable, Sendable {
    public let domain: SyncDomain
    public let receivedAt: Date

    public init(domain: SyncDomain, receivedAt: Date) {
        self.domain = domain
        self.receivedAt = receivedAt
    }
}
