import Foundation
import PlanteriorDomain

public enum SyncTransportResult: Equatable, Sendable {
    case confirmed
    case transientFailure
    case permanentFailure
    case conflict(committedRevision: Revision, committedPayload: Data)
}

public protocol SyncTransport: Sendable {
    func send(_ mutation: SyncMutation, accountID: AccountID) async -> SyncTransportResult
    func events(accountID: AccountID, domain: SyncDomain) -> AsyncStream<SyncRemoteEvent>
}

public actor AccountSyncRepository {
    private let engine: AccountSyncEngine
    private let transport: any SyncTransport
    private var accountID: AccountID?
    private var listeners: [SyncDomain: Task<Void, Never>] = [:]

    public init(engine: AccountSyncEngine, transport: any SyncTransport) {
        self.engine = engine
        self.transport = transport
    }

    public func mount(_ accountID: AccountID) async {
        cancelListeners()
        self.accountID = accountID
        await engine.mount(accountID)
        for domain in SyncDomain.allCases {
            listeners[domain] = Task { [engine, transport] in
                for await event in transport.events(
                    accountID: accountID,
                    domain: domain
                ) {
                    guard !Task.isCancelled else {
                        return
                    }
                    await engine.markServerSnapshot(
                        at: event.receivedAt,
                        for: accountID
                    )
                }
            }
        }
    }

    public func enqueue(_ mutation: SyncMutation) async {
        await engine.queue(mutation)
    }

    public func reapplyConflict(_ id: OperationID) async {
        await engine.reapplyConflict(id)
    }

    public func reconnect() async {
        guard let accountID else {
            return
        }
        for mutation in await engine.snapshot(for: accountID).queued {
            await apply(
                transport.send(mutation, accountID: accountID),
                mutation: mutation,
                accountID: accountID
            )
        }
    }

    public func flush() async {
        await reconnect()
    }

    public func logout(action: LogoutPendingAction) async -> LogoutOutcome {
        guard let accountID else {
            return .loggedOut
        }
        switch action {
        case .cancel:
            return .cancelled
        case .sync:
            await reconnect()
            guard await !engine.hasUnresolvedWrites(for: accountID) else {
                return .cancelled
            }
        case .discard:
            await engine.discardAndUnmount(accountID)
        }
        cancelListeners()
        self.accountID = nil
        await engine.unmount(accountID)
        return .loggedOut
    }

    private func apply(
        _ result: SyncTransportResult,
        mutation: SyncMutation,
        accountID: AccountID
    ) async {
        switch result {
        case .confirmed:
            await engine.markConfirmed(mutation.id, for: accountID)
        case .transientFailure:
            await engine.markTransientFailure(mutation.id, for: accountID)
        case .permanentFailure:
            await engine.markPermanentFailure(mutation.id, for: accountID)
        case let .conflict(committedRevision, committedPayload):
            await engine.markConflict(
                mutation.id,
                committedRevision: committedRevision,
                committedPayload: committedPayload,
                for: accountID
            )
        }
    }

    private func cancelListeners() {
        listeners.values.forEach { $0.cancel() }
        listeners.removeAll()
    }
}
