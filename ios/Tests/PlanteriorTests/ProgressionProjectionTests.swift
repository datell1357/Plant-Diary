@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct ProgressionProjectionTests {
    @Test
    func offlineProjectionNeverAddsUnconfirmedXP() throws {
        let fixture = try ProgressionRepositoryFixture()
        let repository = fixture.repository(allowsLocal: true)
        repository.mount(accountID: fixture.accountA)
        fixture.seed(repository, totalXP: 0)
        let eventID = try OperationID.parse("todo16-offline-1")

        #expect(
            repository.queue(eventID: eventID, kind: .watering) == .queued
        )
        #expect(repository.projection?.serverXP == 0)
        #expect(repository.projection?.pendingCount == 1)
        repository.reconnect()
        #expect(repository.snapshot?.totalXP == 100)
        #expect(repository.pendingEvents.isEmpty)
    }
}
