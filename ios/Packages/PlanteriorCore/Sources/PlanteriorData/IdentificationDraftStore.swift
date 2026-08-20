import Foundation

public actor IdentificationDraftStore: PhotoTransferRequesting {
    public static let shared = IdentificationDraftStore()
    public static let retentionInterval: TimeInterval = 24 * 60 * 60

    private struct StoredDraft: Codable {
        let ownerID: String
        let createdAt: Date
        let photo: NormalizedPhoto
    }

    private let defaults: UserDefaults
    private let rootDirectory: URL
    private var mountedAccountID: String?
    private var draft: StoredDraft?
    private let legacyStorageKey = "identification.draft"

    public init() {
        defaults = .standard
        rootDirectory = FileManager.default.urls(
            for: .cachesDirectory,
            in: .userDomainMask
        )[0].appending(path: "IdentificationDrafts", directoryHint: .isDirectory)
    }

    public init(suiteName: String) {
        defaults = UserDefaults(suiteName: suiteName) ?? .standard
        rootDirectory = FileManager.default.temporaryDirectory
            .appending(
                path: "IdentificationDrafts-\(Self.fileComponent(suiteName))",
                directoryHint: .isDirectory
            )
    }

    public init(suiteName: String, rootDirectory: URL) {
        defaults = UserDefaults(suiteName: suiteName) ?? .standard
        self.rootDirectory = rootDirectory
    }

    public func mount(accountID: String?) {
        discardLegacyDraft()
        guard mountedAccountID != accountID else {
            removeDrafts(except: accountID)
            restore(now: Date())
            return
        }
        if mountedAccountID != nil {
            clear()
        }
        mountedAccountID = accountID
        removeDrafts(except: accountID)
        restore(now: Date())
    }

    public func transfer(_ photo: NormalizedPhoto) {
        transfer(photo, createdAt: Date())
    }

    public func transfer(_ photo: NormalizedPhoto, createdAt: Date) {
        guard let mountedAccountID else {
            return
        }
        let stored = StoredDraft(
            ownerID: mountedAccountID,
            createdAt: createdAt,
            photo: photo
        )
        draft = stored
        persist(stored)
    }

    public func load(now: Date = Date()) -> NormalizedPhoto? {
        guard let draft,
              draft.ownerID == mountedAccountID,
              now.timeIntervalSince(draft.createdAt) < Self.retentionInterval
        else {
            clear()
            return nil
        }
        return draft.photo
    }

    public func clear() {
        draft = nil
        guard let mountedAccountID else {
            return
        }
        try? FileManager.default.removeItem(at: draftURL(for: mountedAccountID))
    }

    private func restore(now: Date) {
        draft = nil
        guard let mountedAccountID,
              let data = try? Data(contentsOf: draftURL(for: mountedAccountID)),
              let stored = try? JSONDecoder().decode(StoredDraft.self, from: data),
              stored.ownerID == mountedAccountID,
              now.timeIntervalSince(stored.createdAt) < Self.retentionInterval
        else {
            clear()
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
            try? FileManager.default.removeItem(at: url)
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
            try? FileManager.default.removeItem(at: url)
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
