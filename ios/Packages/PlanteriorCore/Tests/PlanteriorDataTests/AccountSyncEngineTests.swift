import Foundation
@testable import PlanteriorData
import PlanteriorDomain
import Testing

struct AccountSyncEngineTests {
    @Test
    func partitionsAccountsAndPersistsQueuedWrites() async throws {
        let first = try AccountID.parse("account-a")
        let second = try AccountID.parse("account-b")
        let engine = AccountSyncEngine()
        let mutation = try fixture(id: "operation-a")

        await engine.mount(first)
        await engine.queue(mutation)
        await engine.mount(second)

        #expect(await engine.snapshot().queued.isEmpty)
        #expect(await engine.snapshot(for: first).queued == [mutation])
    }

    @Test
    func boundedRetriesPersistAcrossRemount() async throws {
        let account = try AccountID.parse("account-a")
        let root = temporaryRoot("retry")
        let persistence = AccountSyncPersistence(rootDirectory: root)
        let mutation = try fixture(id: "operation-b")
        let engine = AccountSyncEngine(
            maximumRetryCount: 2,
            persistence: persistence
        )
        await engine.mount(account)
        await engine.queue(mutation)
        await engine.markTransientFailure(mutation.id, for: account)
        await engine.markTransientFailure(mutation.id, for: account)

        let restored = AccountSyncEngine(
            maximumRetryCount: 2,
            persistence: persistence
        )
        await restored.mount(account)
        #expect(await restored.snapshot().retryCounts[mutation.id] == 2)
        await restored.markTransientFailure(mutation.id, for: account)
        #expect(await restored.snapshot().failed == [mutation])
    }

    @Test
    func conflictsPreserveCommittedRevisionUntilExplicitReapply() async throws {
        let account = try AccountID.parse("account-a")
        let mutation = try fixture(id: "operation-c", revision: 1)
        let committedRevision = try Revision.parse(2)
        let committedPayload = Data("server-value".utf8)
        let engine = AccountSyncEngine()
        await engine.mount(account)
        await engine.queue(mutation)

        await engine.markConflict(
            mutation.id,
            committedRevision: committedRevision,
            committedPayload: committedPayload,
            for: account
        )
        #expect(await engine.snapshot().conflicts == [mutation])
        #expect(
            await engine.snapshot().committedRevisions[mutation.id]
                == committedRevision
        )
        #expect(
            await engine.snapshot().committedPayloads[mutation.id]
                == committedPayload
        )

        await engine.reapplyConflict(mutation.id)
        #expect(await engine.snapshot().queued.first?.revision == committedRevision)
        #expect(await engine.snapshot().queued.first?.payload == mutation.payload)
        #expect(await engine.snapshot().conflicts.isEmpty)
    }

    @Test
    func discardClearsDurableOutboxBeforeRemount() async throws {
        let account = try AccountID.parse("account-a")
        let persistence = AccountSyncPersistence(
            rootDirectory: temporaryRoot("discard")
        )
        let mutation = try fixture(id: "operation-d")
        let engine = AccountSyncEngine(persistence: persistence)
        await engine.mount(account)
        await engine.queue(mutation)

        await engine.discardAndUnmount(account)

        let restored = AccountSyncEngine(persistence: persistence)
        await restored.mount(account)
        #expect(await restored.snapshot().queued.isEmpty)
    }

    @Test
    func duplicateOperationIDsAreIdempotent() async throws {
        let account = try AccountID.parse("account-a")
        let mutation = try fixture(id: "operation-e")
        let engine = AccountSyncEngine()
        await engine.mount(account)

        await engine.queue(mutation)
        await engine.queue(mutation)

        #expect(await engine.snapshot().queued == [mutation])
    }

    private func fixture(
        id: String,
        revision: UInt64 = 0
    ) throws -> SyncMutation {
        try SyncMutation(
            id: OperationID.parse(id),
            revision: Revision.parse(revision),
            payload: Data(id.utf8)
        )
    }

    private func temporaryRoot(_ name: String) -> URL {
        FileManager.default.temporaryDirectory
            .appending(path: "Planterior-\(name)-\(UUID().uuidString)")
    }
}
