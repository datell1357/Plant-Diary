import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct ProgressionRepositoryFixture {
    let defaults: UserDefaults
    let accountA: AccountID
    let accountB: AccountID
    let now: Instant

    init() throws {
        let suiteName = "ProgressionRepositoryTests-\(UUID())"
        defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        accountA = try AccountID.parse("progress-account-a")
        accountB = try AccountID.parse("progress-account-b")
        now = try Instant.parse("2026-08-11T00:00:00Z")
    }

    func repository(allowsLocal: Bool) -> MilestoneRepository {
        MilestoneRepository(
            defaults: defaults,
            now: now,
            allowsLocalAuthoritativeService: allowsLocal
        )
    }

    func seed(_ repository: MilestoneRepository, totalXP: Int) {
        repository.definitions = (try? MilestoneRepository.qaDefinitions())
            ?? []
        repository.snapshot = ProgressionSnapshot(
            accountID: accountA,
            totalXP: totalXP,
            receipts: [],
            earnedMilestoneIDs: [],
            claimedMilestoneIDs: [],
            revision: .zero
        )
        repository.persist()
    }
}
