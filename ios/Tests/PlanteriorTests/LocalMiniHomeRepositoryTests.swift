import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct LocalMiniHomeRepositoryTests {
    @Test
    func firstRenameCreatesAndPersistsRoomWhenAccountHasNoMiniHome() throws {
        // Given
        let suiteName = "LocalMiniHomeRepositoryTests-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let repository = try LocalMiniHomeRepository(
            accountID: "new-account",
            defaults: defaults,
            now: Instant.parse("2026-08-21T00:00:00Z")
        )
        #expect(repository.load() == nil)

        // When
        let outcome = try repository.rename("첫 홈")

        // Then
        guard case let .committed(room) = outcome else {
            Issue.record("A first rename must create a committed room")
            return
        }
        #expect(room.name == "첫 홈")
        #expect(room.revision.rawValue == 1)
        #expect(repository.load() == room)
    }
}
