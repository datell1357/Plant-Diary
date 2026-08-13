import Foundation
@testable import PlanteriorData
import PlanteriorDomain
import Testing

struct SyncTransportTests {
    @Test
    func reconnectUsesTransportAndRoutesEveryOutcome() async throws {
        let account = try AccountID.parse("account-a")
        let mutations = try [
            "operation-g",
            "operation-h",
            "operation-i",
            "operation-j"
        ].map(fixture)
        let transport = try SyncTransportFake(
            outcomes: [
                mutations[0].id: .confirmed,
                mutations[1].id: .transientFailure,
                mutations[2].id: .permanentFailure,
                mutations[3].id: .conflict(
                    committedRevision: Revision.parse(2),
                    committedPayload: Data("server".utf8)
                )
            ]
        )
        let engine = AccountSyncEngine()
        let repository = AccountSyncRepository(engine: engine, transport: transport)
        await repository.mount(account)
        for mutation in mutations {
            await repository.enqueue(mutation)
        }

        await repository.reconnect()

        #expect(await transport.sentIDs == mutations.map(\.id))
        #expect(await engine.snapshot().sent == [mutations[0].id])
        #expect(await engine.snapshot().queued == [mutations[1]])
        #expect(await engine.snapshot().failed == [mutations[2]])
        #expect(await engine.snapshot().conflicts == [mutations[3]])
    }

    @Test
    func inFlightResultCannotMutateNewlyMountedAccount() async throws {
        let first = try AccountID.parse("account-a")
        let second = try AccountID.parse("account-b")
        let mutation = try fixture("operation-k")
        let transport = SuspendedTransport()
        let engine = AccountSyncEngine()
        let repository = AccountSyncRepository(engine: engine, transport: transport)
        await repository.mount(first)
        await repository.enqueue(mutation)

        let flush = Task { await repository.reconnect() }
        await transport.waitUntilSendStarts()
        await repository.mount(second)
        await transport.resume(with: .confirmed)
        await flush.value

        #expect(await engine.snapshot(for: first).sent == [mutation.id])
        #expect(await engine.snapshot(for: second).sent.isEmpty)
    }

    @Test
    func accountSwitchCancelsDomainListeners() async throws {
        let first = try AccountID.parse("account-a")
        let second = try AccountID.parse("account-b")
        let transport = SyncTransportFake()
        let repository = AccountSyncRepository(
            engine: AccountSyncEngine(),
            transport: transport
        )
        await repository.mount(first)
        await repository.mount(second)
        await transport.waitForCancellation(of: first)

        #expect(await transport.cancelledAccounts.contains(first))
    }

    @Test
    func syncLogoutRefusesPermanentAndConflictWrites() async throws {
        let account = try AccountID.parse("account-a")
        for result in try [
            SyncTransportResult.permanentFailure,
            .conflict(
                committedRevision: Revision.parse(2),
                committedPayload: Data("server".utf8)
            )
        ] {
            let mutation = try fixture("operation-logout")
            let transport = SyncTransportFake(outcomes: [mutation.id: result])
            let repository = AccountSyncRepository(
                engine: AccountSyncEngine(),
                transport: transport
            )
            await repository.mount(account)
            await repository.enqueue(mutation)

            #expect(await repository.logout(action: .sync) == .cancelled)
        }
    }

    @Test
    func logoutSupportsSuccessfulSyncCancelAndDiscard() async throws {
        let account = try AccountID.parse("account-a")
        let mutation = try fixture("operation-logout-actions")

        let confirmed = SyncTransportFake(
            outcomes: [mutation.id: .confirmed]
        )
        let syncRepository = AccountSyncRepository(
            engine: AccountSyncEngine(),
            transport: confirmed
        )
        await syncRepository.mount(account)
        await syncRepository.enqueue(mutation)
        #expect(await syncRepository.logout(action: .sync) == .loggedOut)

        let cancelRepository = AccountSyncRepository(
            engine: AccountSyncEngine(),
            transport: SyncTransportFake()
        )
        await cancelRepository.mount(account)
        await cancelRepository.enqueue(mutation)
        #expect(await cancelRepository.logout(action: .cancel) == .cancelled)

        let discardEngine = AccountSyncEngine()
        let discardRepository = AccountSyncRepository(
            engine: discardEngine,
            transport: SyncTransportFake()
        )
        await discardRepository.mount(account)
        await discardRepository.enqueue(mutation)
        #expect(await discardRepository.logout(action: .discard) == .loggedOut)
        #expect(await discardEngine.snapshot(for: account).queued.isEmpty)
    }

    private func fixture(_ id: String) throws -> SyncMutation {
        try SyncMutation(
            id: OperationID.parse(id),
            revision: Revision.parse(0),
            payload: Data(id.utf8)
        )
    }
}

private actor SyncTransportFake: SyncTransport {
    let outcomes: [OperationID: SyncTransportResult]
    private(set) var sentIDs: [OperationID] = []
    private(set) var cancelledAccounts: [AccountID] = []
    private var cancellationWaiters: [
        AccountID: CheckedContinuation<Void, Never>
    ] = [:]

    init(outcomes: [OperationID: SyncTransportResult] = [:]) {
        self.outcomes = outcomes
    }

    func send(
        _ mutation: SyncMutation,
        accountID: AccountID
    ) -> SyncTransportResult {
        sentIDs.append(mutation.id)
        return outcomes[mutation.id] ?? .permanentFailure
    }

    nonisolated func events(
        accountID: AccountID,
        domain: SyncDomain
    ) -> AsyncStream<SyncRemoteEvent> {
        AsyncStream { continuation in
            continuation.onTermination = { _ in
                Task { await self.recordCancellation(accountID) }
            }
        }
    }

    private func recordCancellation(_ accountID: AccountID) {
        cancelledAccounts.append(accountID)
        cancellationWaiters.removeValue(forKey: accountID)?.resume()
    }

    func waitForCancellation(of accountID: AccountID) async {
        guard !cancelledAccounts.contains(accountID) else {
            return
        }
        await withCheckedContinuation {
            cancellationWaiters[accountID] = $0
        }
    }
}
