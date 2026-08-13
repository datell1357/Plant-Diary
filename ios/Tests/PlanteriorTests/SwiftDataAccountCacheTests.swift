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
}
