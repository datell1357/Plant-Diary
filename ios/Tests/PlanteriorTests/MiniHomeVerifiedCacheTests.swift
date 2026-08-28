import Foundation
@testable import Planterior
import Testing

@MainActor
struct MiniHomeVerifiedCacheTests {
    @Test
    func snapshotsAreAccountIsolated_whenStoredAndLoaded() throws {
        // Given
        let defaults = try makeDefaults()
        let cache = MiniHomeVerifiedCache(defaults: defaults)
        let snapshot = try MiniHomeAuthoritativeServiceTests.snapshot()

        // When
        try cache.store(snapshot)

        // Then
        #expect(cache.load(accountID: snapshot.accountID) == snapshot)
        #expect(cache.load(accountID: "owner-other") == nil)
    }

    @Test
    func unverifiedOrCorruptSnapshotsNeverLoad_whenPersistenceIsTampered() throws {
        // Given
        let defaults = try makeDefaults()
        let cache = MiniHomeVerifiedCache(defaults: defaults)
        let snapshot = try MiniHomeAuthoritativeServiceTests.snapshot()
        let key = MiniHomeVerifiedCache.snapshotKey(accountID: snapshot.accountID)
        let unverified = try MiniHomeAuthoritativeServiceTests.mutatedData(key: "snapshot") {
            $0["snapshotHash"] = String(repeating: "0", count: 64)
        }

        // When / Then
        defaults.set(unverified, forKey: key)
        #expect(cache.load(accountID: snapshot.accountID) == nil)

        defaults.set(Data("not-json".utf8), forKey: key)
        #expect(cache.load(accountID: snapshot.accountID) == nil)
    }

    @Test
    func localCandidateRemainsExplicitAndUnmodified_whenAccountLegacyStateExists() throws {
        // Given
        let defaults = try makeDefaults()
        let cache = MiniHomeVerifiedCache(defaults: defaults)
        let draft = try MiniHomeAuthoritativeServiceTests.draft()
        let legacyKey = "home.owner-fixture.committed-mini-home"
        try defaults.set(JSONEncoder().encode(draft), forKey: legacyKey)

        // When
        let first = cache.localCandidate(accountID: "owner-fixture")
        let second = cache.localCandidate(accountID: "owner-fixture")

        // Then
        let candidate = MiniHomeLocalCandidate(
            accountID: "owner-fixture",
            home: draft,
            sourceKey: legacyKey
        )
        #expect(first == candidate)
        #expect(second == candidate)
        #expect(defaults.data(forKey: legacyKey) != nil)
        #expect(cache.load(accountID: "owner-fixture") == nil)
    }

    @Test
    func signedOutStateIsNeverMigrated_whenAccountCandidateIsMissing() throws {
        // Given
        let defaults = try makeDefaults()
        let cache = MiniHomeVerifiedCache(defaults: defaults)
        let draft = try MiniHomeAuthoritativeServiceTests.draft()
        try defaults.set(
            JSONEncoder().encode(draft),
            forKey: "home.signed-out.committed-mini-home"
        )

        // When
        let result = cache.localCandidate(accountID: "owner-fixture")

        // Then
        #expect(result == nil)
        #expect(cache.load(accountID: "owner-fixture") == nil)
        #expect(defaults.data(forKey: "home.signed-out.committed-mini-home") != nil)
    }

    private func makeDefaults() throws -> UserDefaults {
        let suite = "MiniHomeVerifiedCacheTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }
}
