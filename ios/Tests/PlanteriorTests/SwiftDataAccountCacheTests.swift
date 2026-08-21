import Foundation
import PlanteriorData
import PlanteriorDomain
import Testing

struct SwiftDataAccountCacheTests {
    @Test
    @MainActor
    func outboxesArePhysicallyPartitionedByAccount() throws {
        let first = try AccountID.parse("account-a")
        let second = try AccountID.parse("account-b")
        let mutation = try SyncMutation(
            id: OperationID.parse("operation-f"),
            revision: Revision.parse(0),
            payload: Data("swiftdata".utf8)
        )
        let root = FileManager.default.temporaryDirectory
            .appending(path: "PlanteriorSwiftData-\(UUID().uuidString)")
        let cache = SwiftDataAccountCache(rootDirectory: root)

        let firstContainer = try cache.makeContainer(for: first)
        let secondContainer = try cache.makeContainer(for: second)

        #expect(firstContainer.configurations.first?.url == cache.storeURL(for: first))
        #expect(secondContainer.configurations.first?.url == cache.storeURL(for: second))
        #expect(cache.storeURL(for: first) != cache.storeURL(for: second))
        try cache.save(mutation, for: first)
        #expect(try cache.queuedOperationIDs(for: first) == [mutation.id.rawValue])
        #expect(try cache.queuedOperationIDs(for: second).isEmpty)
    }

    @Test
    @MainActor
    func deletionFailsWhenStoreDirectoryCannotBeEnumerated() async throws {
        let accountID = try AccountID.parse("failed-delete-account")
        let root = FileManager.default.temporaryDirectory
            .appending(path: "PlanteriorDeletionFailure-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        try Data("not-a-directory".utf8).write(to: root)
        let cache = SwiftDataAccountCache(rootDirectory: root)

        await #expect(throws: (any Error).self) {
            try await cache.delete(for: accountID)
        }
        #expect(FileManager.default.fileExists(atPath: root.path))
    }

    @Test
    @MainActor
    func deletionDiscardPhysicallyDestroysAccountStore() async throws {
        let accountID = try AccountID.parse("delete-account")
        let root = FileManager.default.temporaryDirectory
            .appending(path: "PlanteriorDeletion-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let cache = SwiftDataAccountCache(rootDirectory: root)
        let engine = AccountSyncEngine(persistence: cache)

        await engine.mount(accountID)
        #expect(FileManager.default.fileExists(atPath: cache.storeURL(for: accountID).path))

        await engine.discardAndUnmount(accountID)

        #expect(!FileManager.default.fileExists(atPath: cache.storeURL(for: accountID).path))
    }
}
