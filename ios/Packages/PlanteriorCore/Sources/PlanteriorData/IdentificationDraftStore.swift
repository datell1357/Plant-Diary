import Foundation

public actor IdentificationDraftStore: PhotoTransferRequesting {
    public static let shared = IdentificationDraftStore()
    public static let retentionInterval: TimeInterval = 24 * 60 * 60

    private struct StoredDraft: Codable {
        let ownerID: String
        let createdAt: Date
        let photos: [NormalizedPhoto]
    }

    private let defaults: UserDefaults
    private let rootDirectory: URL
    private let removeItem: @Sendable (URL) throws -> Void
    private var mountedAccountID: String?
    private var draft: StoredDraft?
    private let legacyStorageKey = "identification.draft"

    public init() {
        defaults = .standard
        rootDirectory = FileManager.default.urls(
            for: .cachesDirectory,
            in: .userDomainMask
        )[0].appending(path: "IdentificationDrafts", directoryHint: .isDirectory)
        removeItem = { try FileManager.default.removeItem(at: $0) }
    }

    public init(suiteName: String) {
        defaults = UserDefaults(suiteName: suiteName) ?? .standard
        rootDirectory = FileManager.default.temporaryDirectory
            .appending(
                path: "IdentificationDrafts-\(Self.fileComponent(suiteName))",
                directoryHint: .isDirectory
            )
        removeItem = { try FileManager.default.removeItem(at: $0) }
    }

    public init(suiteName: String, rootDirectory: URL) {
        defaults = UserDefaults(suiteName: suiteName) ?? .standard
        self.rootDirectory = rootDirectory
        removeItem = { try FileManager.default.removeItem(at: $0) }
    }

    init(
        suiteName: String,
        rootDirectory: URL,
        removeItem: @escaping @Sendable (URL) throws -> Void
    ) {
        defaults = UserDefaults(suiteName: suiteName) ?? .standard
        self.rootDirectory = rootDirectory
        self.removeItem = removeItem
    }

    public func mount(accountID: String?) {
        discardLegacyDraft()
        guard mountedAccountID != accountID else {
            removeDrafts(except: accountID)
            restore(now: Date())
            return
        }
        if mountedAccountID != nil {
            try? clear()
        }
        mountedAccountID = accountID
        removeDrafts(except: accountID)
        restore(now: Date())
    }

    public func transfer(_ photos: [NormalizedPhoto]) {
        transfer(photos, createdAt: Date())
    }

    public func transfer(_ photos: [NormalizedPhoto], createdAt: Date) {
        guard let mountedAccountID else {
            return
        }
        let stored = StoredDraft(
            ownerID: mountedAccountID,
            createdAt: createdAt,
            photos: photos
        )
        draft = stored
        persist(stored)
    }

    public func load(now: Date = Date()) -> [NormalizedPhoto]? {
        guard let draft,
              draft.ownerID == mountedAccountID,
              now.timeIntervalSince(draft.createdAt) < Self.retentionInterval
        else {
            try? clear()
            return nil
        }
        return draft.photos
    }

    public func clear() throws {
        guard let mountedAccountID else {
            draft = nil
            return
        }
        try clear(accountID: mountedAccountID)
    }

    public func clear(accountID: String) throws {
        if mountedAccountID == accountID {
            draft = nil
        }
        let url = draftURL(for: accountID)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        try removeItem(url)
        guard !FileManager.default.fileExists(atPath: url.path) else {
            throw CocoaError(.fileWriteUnknown)
        }
    }

    private func restore(now: Date) {
        draft = nil
        guard let mountedAccountID,
              let data = try? Data(contentsOf: draftURL(for: mountedAccountID)),
              let stored = try? JSONDecoder().decode(StoredDraft.self, from: data),
              stored.ownerID == mountedAccountID,
              now.timeIntervalSince(stored.createdAt) < Self.retentionInterval
        else {
            try? clear()
            return
        }
        draft = stored
    }

    private func persist(_ stored: StoredDraft) {
        let url = draftURL(for: stored.ownerID)
        do {
            try FileManager.default.createDirectory(
                at: rootDirectory,
                withIntermediateDirectories: true
            )
            let data = try JSONEncoder().encode(stored)
            try data.write(to: url, options: [.atomic])
            try FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.complete],
                ofItemAtPath: url.path
            )
        } catch {
            draft = nil
            try? removeItem(url)
        }
    }

    private func discardLegacyDraft() {
        defaults.removeObject(forKey: legacyStorageKey)
    }

    private func removeDrafts(except accountID: String?) {
        let retainedPath = accountID.map {
            draftURL(for: $0).standardizedFileURL.path
        }
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: rootDirectory,
            includingPropertiesForKeys: nil
        ) else {
            return
        }
        for url in urls where url.standardizedFileURL.path != retainedPath {
            try? removeItem(url)
        }
    }

    private func draftURL(for accountID: String) -> URL {
        rootDirectory.appending(
            path: "\(Self.fileComponent(accountID)).draft",
            directoryHint: .notDirectory
        )
    }

    private static func fileComponent(_ value: String) -> String {
        value.utf8.map { String(format: "%02x", $0) }.joined()
    }
}
