import Foundation
import PlanteriorDomain

public protocol AccountSyncPersisting: Sendable {
    func save(
        _ snapshot: AccountSyncSnapshot,
        for accountID: AccountID
    ) async throws
    func load(for accountID: AccountID) async throws -> AccountSyncSnapshot
}

public enum AccountStoreLocation {
    public static func fileName(for accountID: AccountID) -> String {
        "Planterior-\(accountID.rawValue).store"
    }
}

public actor AccountSyncPersistence: AccountSyncPersisting {
    private let rootDirectory: URL

    public init(rootDirectory: URL) {
        self.rootDirectory = rootDirectory
    }

    public nonisolated func storeURL(for accountID: AccountID) -> URL {
        rootDirectory.appending(path: AccountStoreLocation.fileName(for: accountID))
    }

    public func save(
        _ snapshot: AccountSyncSnapshot,
        for accountID: AccountID
    ) async throws {
        try FileManager.default.createDirectory(
            at: rootDirectory,
            withIntermediateDirectories: true
        )
        let data = try JSONEncoder().encode(snapshot)
        try data.write(to: storeURL(for: accountID), options: .atomic)
    }

    public func load(for accountID: AccountID) async throws -> AccountSyncSnapshot {
        let url = storeURL(for: accountID)
        guard FileManager.default.fileExists(atPath: url.path()) else {
            return AccountSyncSnapshot()
        }
        return try JSONDecoder().decode(
            AccountSyncSnapshot.self,
            from: Data(contentsOf: url)
        )
    }
}
