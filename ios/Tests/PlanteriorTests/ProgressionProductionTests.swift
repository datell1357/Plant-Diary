@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct ProgressionProductionTests {
    @Test
    func productionIgnoresLocalAuthoritativeSnapshot() throws {
        let fixture = try ProgressionRepositoryFixture()
        let local = fixture.repository(allowsLocal: true)
        local.mount(accountID: fixture.accountA)
        fixture.seed(local, totalXP: 0)
        let firstID = try OperationID.parse("todo16-watering-1")
        _ = local.submit(eventID: firstID, kind: .watering)

        let production = fixture.repository(allowsLocal: false)
        production.mount(accountID: fixture.accountA)
        let secondID = try OperationID.parse("todo16-watering-2")

        #expect(production.snapshot == nil)
        #expect(production.definitions.isEmpty)
        #expect(
            production.submit(
                eventID: secondID,
                kind: .watering
            ) == .unavailable
        )
    }
}
