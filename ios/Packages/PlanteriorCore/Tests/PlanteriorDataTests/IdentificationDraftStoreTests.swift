import Foundation
@testable import PlanteriorData
import Testing

struct IdentificationDraftStoreTests {
    @Test
    func discardsLegacyDraftWithoutAccountOwnership() async throws {
        let suite = "IdentificationDraftStoreTests.legacy.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        let root = draftRoot(suite)
        defaults.removePersistentDomain(forName: suite)
        defer { cleanup(suite: suite, defaults: defaults, root: root) }
        try defaults.set(
            JSONEncoder().encode(privatePhoto),
            forKey: "identification.draft"
        )

        let store = IdentificationDraftStore(
            suiteName: suite,
            rootDirectory: root
        )
        await store.mount(accountID: "account-a")

        #expect(await store.load() == nil)
        #expect(defaults.data(forKey: "identification.draft") == nil)
    }

    @Test
    func scopesDraftToAccountAndClearsItOnAccountChange() async throws {
        let suite = "IdentificationDraftStoreTests.account.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        let root = draftRoot(suite)
        defaults.removePersistentDomain(forName: suite)
        defer { cleanup(suite: suite, defaults: defaults, root: root) }
        let store = IdentificationDraftStore(
            suiteName: suite,
            rootDirectory: root
        )

        await store.mount(accountID: "account-a")
        await store.transfer(privatePhoto)
        #expect(await store.load() == privatePhoto)

        let relaunchedStore = IdentificationDraftStore(
            suiteName: suite,
            rootDirectory: root
        )
        await relaunchedStore.mount(accountID: "account-b")
        #expect(await relaunchedStore.load() == nil)

        let returningStore = IdentificationDraftStore(
            suiteName: suite,
            rootDirectory: root
        )
        await returningStore.mount(accountID: "account-a")
        #expect(await returningStore.load() == nil)
    }

    @Test
    func clearPropagatesRemovalFailureAndLeavesDraftOnDisk() async throws {
        let suite = "IdentificationDraftStoreTests.failure.\(UUID().uuidString)"
        let root = draftRoot(suite)
        defer { try? FileManager.default.removeItem(at: root) }
        let writer = IdentificationDraftStore(suiteName: suite, rootDirectory: root)
        await writer.mount(accountID: "account-a")
        await writer.transfer(privatePhoto)
        let relaunched = IdentificationDraftStore(
            suiteName: suite,
            rootDirectory: root,
            removeItem: { _ in throw CocoaError(.fileWriteUnknown) }
        )

        await #expect(throws: (any Error).self) {
            try await relaunched.clear(accountID: "account-a")
        }
        #expect(try FileManager.default.contentsOfDirectory(atPath: root.path).count == 1)
    }

    @Test
    func expiresPersistedDraftAtTwentyFourHours() async throws {
        let suite = "IdentificationDraftStoreTests.expiry.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        let root = draftRoot(suite)
        defaults.removePersistentDomain(forName: suite)
        defer { cleanup(suite: suite, defaults: defaults, root: root) }
        let store = IdentificationDraftStore(
            suiteName: suite,
            rootDirectory: root
        )
        let createdAt = Date(timeIntervalSince1970: 1000)

        await store.mount(accountID: "account-a")
        await store.transfer(privatePhoto, createdAt: createdAt)

        #expect(
            await store.load(
                now: createdAt.addingTimeInterval(
                    IdentificationDraftStore.retentionInterval - 1
                )
            ) == privatePhoto
        )
        #expect(
            await store.load(
                now: createdAt.addingTimeInterval(
                    IdentificationDraftStore.retentionInterval
                )
            ) == nil
        )
    }

    private var privatePhoto: NormalizedPhoto {
        NormalizedPhoto(
            data: Data("private-photo".utf8),
            pixelWidth: 300,
            pixelHeight: 300,
            contentType: "image/jpeg"
        )
    }

    private func draftRoot(_ suite: String) -> URL {
        FileManager.default.temporaryDirectory.appending(
            path: suite,
            directoryHint: .isDirectory
        )
    }

    private func cleanup(
        suite: String,
        defaults: UserDefaults,
        root: URL
    ) {
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: root)
    }
}
