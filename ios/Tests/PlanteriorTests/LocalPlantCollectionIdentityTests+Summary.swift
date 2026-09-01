@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
extension LocalPlantCollectionIdentityTests {
    @Test
    func collectionSummaryIsDerivedFromWateringModels() throws {
        let (_, store) = try makeStore()
        store.plants = [
            draft(named: "지연", lastWateredOn: "2026-07-01", intervalDays: 10),
            draft(named: "오늘", lastWateredOn: "2026-08-01", intervalDays: 10),
            draft(named: "예정", lastWateredOn: "2026-08-10", intervalDays: 5),
            draft(named: "미설정")
        ]
        store.reconcilePlantIdentities()

        let summary = try store.careSummary(
            today: CalendarDate.parse("2026-08-11")
        )

        #expect(summary.total == 4)
        #expect(summary.overdue == 1)
        #expect(summary.dueToday == 1)
        #expect(summary.upcoming == 1)
        #expect(summary.unconfigured == 1)
    }
}
