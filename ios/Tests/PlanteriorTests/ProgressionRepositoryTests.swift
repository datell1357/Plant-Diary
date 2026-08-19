import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct ProgressionRepositoryTests {
    @Test
    func duplicateClaimAndAccountRemountStayIsolated() throws {
        let fixture = try ProgressionRepositoryFixture()
        let repository = fixture.repository(allowsLocal: true)
        repository.mount(accountID: fixture.accountA)
        fixture.seed(repository, totalXP: 50)
        let eventID = try OperationID.parse("todo16-registration-1")

        #expect(
            repository.submit(
                eventID: eventID,
                kind: .registration
            ) == .applied
        )
        #expect(
            repository.submit(
                eventID: eventID,
                kind: .registration
            ) == .duplicate
        )
        let milestoneID = try MilestoneID.parse("registration-1")
        #expect(repository.claim(milestoneID) == .claimed)
        #expect(repository.claim(milestoneID) == .alreadyClaimed)
        #expect(repository.snapshot?.totalXP == 100)

        repository.mount(accountID: fixture.accountB)
        #expect(repository.snapshot?.totalXP == 0)
        #expect(repository.definitions.isEmpty)

        repository.mount(accountID: fixture.accountA)
        #expect(repository.snapshot?.claimedMilestoneIDs == [milestoneID])
        #expect(repository.duplicateCount == 2)
    }
}
