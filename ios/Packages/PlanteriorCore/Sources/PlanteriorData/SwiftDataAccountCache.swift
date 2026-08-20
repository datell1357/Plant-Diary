#if canImport(SwiftData)
    import Foundation
    import PlanteriorDomain
    import SwiftData

    @Model
    final class CachedDomainRecord {
        @Attribute(.unique) var id: String
        var domain: String
        var payload: Data
        var revision: UInt64
        var updatedAt: Date

        init(
            id: String,
            domain: String,
            payload: Data,
            revision: UInt64,
            updatedAt: Date
        ) {
            self.id = id
            self.domain = domain
            self.payload = payload
            self.revision = revision
            self.updatedAt = updatedAt
        }
    }

    @Model
    final class CachedOutboxMutation {
        @Attribute(.unique) var operationID: String
        var payload: Data
        var revision: UInt64
        var state: String
        var retryCount: Int

        init(mutation: SyncMutation, state: String = "queued", retryCount: Int = 0) {
            operationID = mutation.id.rawValue
            payload = mutation.payload
            revision = mutation.revision.rawValue
            self.state = state
            self.retryCount = retryCount
        }
    }

    @Model
    final class CachedSyncSnapshot {
        @Attribute(.unique) var accountID: String
        var payload: Data

        init(accountID: String, payload: Data) {
            self.accountID = accountID
            self.payload = payload
        }
    }

    @MainActor
    public final class SwiftDataAccountCache: @unchecked Sendable, AccountSyncPersisting {
        private let rootDirectory: URL
        private var containers: [AccountID: ModelContainer] = [:]

        public init(rootDirectory: URL) {
            self.rootDirectory = rootDirectory
        }

        public func makeContainer(for accountID: AccountID) throws -> ModelContainer {
            if let container = containers[accountID] {
                return container
            }
            try FileManager.default.createDirectory(
                at: rootDirectory,
                withIntermediateDirectories: true
            )
            let schema = Schema([
                CachedDomainRecord.self,
                CachedOutboxMutation.self,
                CachedSyncSnapshot.self
            ])
            let configuration = ModelConfiguration(
                AccountStoreLocation.fileName(for: accountID),
                schema: schema,
                url: storeURL(for: accountID),
                cloudKitDatabase: .none
            )
            let container = try ModelContainer(
                for: schema,
                configurations: [configuration]
            )
            containers[accountID] = container
            return container
        }

        public nonisolated func storeURL(for accountID: AccountID) -> URL {
            rootDirectory.appending(
                path: AccountStoreLocation.fileName(for: accountID)
            )
        }

        public func save(
            _ mutation: SyncMutation,
            for accountID: AccountID
        ) throws {
            let context = try makeContainer(for: accountID).mainContext
            context.insert(CachedOutboxMutation(mutation: mutation))
            try context.save()
        }

        public func queuedOperationIDs(for accountID: AccountID) throws -> [String] {
            let context = try makeContainer(for: accountID).mainContext
            let descriptor = FetchDescriptor<CachedOutboxMutation>(
                predicate: #Predicate { $0.state == "queued" }
            )
            return try context.fetch(descriptor).map(\.operationID)
        }

        public func save(
            _ snapshot: AccountSyncSnapshot,
            for accountID: AccountID
        ) async throws {
            let context = try makeContainer(for: accountID).mainContext
            let rawAccountID = accountID.rawValue
            let descriptor = FetchDescriptor<CachedSyncSnapshot>(
                predicate: #Predicate { $0.accountID == rawAccountID }
            )
            let data = try JSONEncoder().encode(snapshot)
            if let stored = try context.fetch(descriptor).first {
                stored.payload = data
            } else {
                context.insert(
                    CachedSyncSnapshot(accountID: rawAccountID, payload: data)
                )
            }
            try context.save()
        }

        public func load(for accountID: AccountID) async throws -> AccountSyncSnapshot {
            let context = try makeContainer(for: accountID).mainContext
            let rawAccountID = accountID.rawValue
            let descriptor = FetchDescriptor<CachedSyncSnapshot>(
                predicate: #Predicate { $0.accountID == rawAccountID }
            )
            guard let data = try context.fetch(descriptor).first?.payload else {
                return AccountSyncSnapshot()
            }
            return try JSONDecoder().decode(AccountSyncSnapshot.self, from: data)
        }

        public func delete(for accountID: AccountID) async throws {
            containers[accountID] = nil
            let fileName = AccountStoreLocation.fileName(for: accountID)
            guard let urls = try? FileManager.default.contentsOfDirectory(
                at: rootDirectory,
                includingPropertiesForKeys: nil
            ) else {
                return
            }
            for url in urls {
                let isStoreFile = url.lastPathComponent == fileName
                    || url.lastPathComponent.hasPrefix("\(fileName)-")
                guard isStoreFile else { continue }
                try FileManager.default.removeItem(at: url)
            }
        }
    }
#endif
