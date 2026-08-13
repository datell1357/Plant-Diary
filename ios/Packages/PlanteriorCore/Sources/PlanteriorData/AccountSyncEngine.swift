import Foundation
import PlanteriorDomain

public actor AccountSyncEngine {
    private var mountedAccount: AccountID?
    private var accounts: [AccountID: AccountSyncSnapshot] = [:]
    private let maximumRetryCount: Int
    private let persistence: (any AccountSyncPersisting)?

    public init(
        maximumRetryCount: Int = 3,
        persistence: (any AccountSyncPersisting)? = nil
    ) {
        self.maximumRetryCount = maximumRetryCount
        self.persistence = persistence
    }

    public func mount(_ accountID: AccountID) async {
        mountedAccount = accountID
        if let persistence, let stored = try? await persistence.load(for: accountID) {
            accounts[accountID] = stored
        } else if accounts[accountID] == nil {
            accounts[accountID] = AccountSyncSnapshot()
        }
    }

    public func unmount(_ accountID: AccountID) {
        guard mountedAccount == accountID else {
            return
        }
        mountedAccount = nil
    }

    public func queue(_ mutation: SyncMutation) async {
        guard let mountedAccount else {
            return
        }
        var state = snapshot(for: mountedAccount)
        if !state.queued.contains(where: { $0.id == mutation.id }) {
            state = replacing(state, queued: state.queued + [mutation])
            await store(state, for: mountedAccount)
        }
    }

    public func markTransientFailure(
        _ id: OperationID,
        for accountID: AccountID
    ) async {
        var state = snapshot(for: accountID)
        var counts = state.retryCounts
        counts[id, default: 0] += 1
        if counts[id, default: 0] > maximumRetryCount {
            state = move(id, from: state, toConflict: false, retryCounts: counts)
        } else {
            state = replacing(state, retryCounts: counts)
        }
        await store(state, for: accountID)
    }

    public func markPermanentFailure(
        _ id: OperationID,
        for accountID: AccountID
    ) async {
        await store(
            move(id, from: snapshot(for: accountID), toConflict: false),
            for: accountID
        )
    }

    public func markConfirmed(_ id: OperationID, for accountID: AccountID) async {
        let state = snapshot(for: accountID)
        await store(
            replacing(
                state,
                queued: state.queued.filter { $0.id != id },
                sent: state.sent + [id]
            ),
            for: accountID
        )
    }

    public func markConflict(
        _ id: OperationID,
        committedRevision: Revision,
        committedPayload: Data,
        for accountID: AccountID
    ) async {
        var state = move(id, from: snapshot(for: accountID), toConflict: true)
        var revisions = state.committedRevisions
        var payloads = state.committedPayloads
        revisions[id] = committedRevision
        payloads[id] = committedPayload
        state = replacing(
            state,
            committedRevisions: revisions,
            committedPayloads: payloads
        )
        await store(state, for: accountID)
    }

    public func reapplyConflict(_ id: OperationID) async {
        guard let accountID = mountedAccount else {
            return
        }
        let state = snapshot(for: accountID)
        guard let mutation = state.conflicts.first(where: { $0.id == id }) else {
            return
        }
        let committedRevision = state.committedRevisions[id] ?? mutation.revision
        let nextMutation = SyncMutation(
            id: mutation.id,
            revision: committedRevision,
            payload: mutation.payload
        )
        await store(
            replacing(
                state,
                queued: state.queued + [nextMutation],
                conflicts: state.conflicts.filter { $0.id != id }
            ),
            for: accountID
        )
    }

    public func markServerSnapshot(
        at instant: Date,
        for accountID: AccountID? = nil
    ) async {
        guard let accountID = accountID ?? mountedAccount else {
            return
        }
        await store(
            replacing(snapshot(for: accountID), lastServerSync: instant),
            for: accountID
        )
    }

    public func snapshot() -> AccountSyncSnapshot {
        mountedAccount.map(snapshot(for:)) ?? AccountSyncSnapshot()
    }

    public func snapshot(for accountID: AccountID) -> AccountSyncSnapshot {
        accounts[accountID, default: AccountSyncSnapshot()]
    }

    public func hasUnresolvedWrites(for accountID: AccountID) -> Bool {
        let state = snapshot(for: accountID)
        return !state.queued.isEmpty
            || !state.failed.isEmpty
            || !state.conflicts.isEmpty
    }

    public func discardAndUnmount(_ accountID: AccountID) async {
        await store(AccountSyncSnapshot(), for: accountID)
        unmount(accountID)
    }

    private func store(
        _ state: AccountSyncSnapshot,
        for accountID: AccountID
    ) async {
        accounts[accountID] = state
        try? await persistence?.save(state, for: accountID)
    }

    private func move(
        _ id: OperationID,
        from state: AccountSyncSnapshot,
        toConflict: Bool,
        retryCounts: [OperationID: Int]? = nil
    ) -> AccountSyncSnapshot {
        let mutation = (state.queued + state.failed).first { $0.id == id }
        return replacing(
            state,
            queued: state.queued.filter { $0.id != id },
            failed: toConflict ? state.failed.filter { $0.id != id }
                : state.failed.filter { $0.id != id } + optional(mutation),
            conflicts: toConflict ? state.conflicts + optional(mutation)
                : state.conflicts.filter { $0.id != id },
            retryCounts: retryCounts ?? state.retryCounts
        )
    }
}

private func optional<T>(_ value: T?) -> [T] {
    value.map { [$0] } ?? []
}
