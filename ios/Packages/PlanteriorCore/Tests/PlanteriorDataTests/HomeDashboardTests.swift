@testable import PlanteriorData
import PlanteriorDomain
import Testing

struct HomeDashboardTests {
    @Test
    func ordersOverdueDueUpcomingAndUnavailableCare() throws {
        let today = try CalendarDate.parse("2026-08-11")
        let coordinator = HomeDashboardCoordinator(today: today)
        let snapshot = try coordinator.snapshot(
            candidates: [
                candidate("upcoming", last: "2026-08-10", interval: 5),
                candidate("unavailable", last: nil, interval: 10),
                candidate("due", last: "2026-08-01", interval: 10),
                candidate("overdue", last: "2026-07-30", interval: 10)
            ],
            weather: .content(summary: "맑음")
        )

        #expect(
            snapshot.careItems.map(\.plantID.rawValue)
                == ["overdue", "due", "upcoming", "unavailable"]
        )
        #expect(snapshot.weather == .content(summary: "맑음"))
        #expect(snapshot.state == .content)
    }

    @Test
    func weatherFailureDoesNotReplaceCareContent() throws {
        let today = try CalendarDate.parse("2026-08-11")
        let coordinator = HomeDashboardCoordinator(today: today)
        let snapshot = try coordinator.snapshot(
            candidates: [candidate("due", last: "2026-08-01", interval: 10)],
            weather: .failed
        )

        #expect(snapshot.careItems.count == 1)
        #expect(snapshot.weather == .failed)
        #expect(snapshot.state == .partial)
    }

    @Test
    func equalStatusAndNameUsesStablePlantIDTieBreak() throws {
        let today = try CalendarDate.parse("2026-08-11")
        let lastWateredDate = try CalendarDate.parse("2026-08-01")
        let coordinator = HomeDashboardCoordinator(today: today)
        let snapshot = try coordinator.snapshot(
            candidates: [
                HomeCareCandidate(
                    plantID: PersonalPlantID.parse("plant-b"),
                    displayName: "몬스테라",
                    lastWateredDate: lastWateredDate,
                    intervalDays: 10
                ),
                HomeCareCandidate(
                    plantID: PersonalPlantID.parse("plant-a"),
                    displayName: "몬스테라",
                    lastWateredDate: lastWateredDate,
                    intervalDays: 10
                )
            ],
            weather: .unavailable
        )

        #expect(
            snapshot.careItems.map(\.plantID.rawValue)
                == ["plant-a", "plant-b"]
        )
    }

    @Test
    func invalidCandidateDoesNotRemoveValidCare() throws {
        let today = try CalendarDate.parse("2026-08-11")
        let lastWateredDate = try CalendarDate.parse("2026-08-01")
        let coordinator = HomeDashboardCoordinator(today: today)
        let snapshot = try coordinator.snapshot(
            candidates: [
                HomeCareCandidate(
                    plantID: PersonalPlantID.parse("valid"),
                    displayName: "정상 식물",
                    lastWateredDate: lastWateredDate,
                    intervalDays: 10
                ),
                HomeCareCandidate(
                    plantID: PersonalPlantID.parse("invalid"),
                    displayName: "잘못된 식물",
                    lastWateredDate: lastWateredDate,
                    intervalDays: 0
                )
            ],
            weather: .unavailable
        )

        #expect(snapshot.careItems.count == 2)
        #expect(snapshot.careItems.first?.plantID.rawValue == "valid")
        #expect(snapshot.careItems.last?.status == .unavailable)
    }

    @Test
    func exposesEmptyAndStaleStates() throws {
        let today = try CalendarDate.parse("2026-08-11")
        let updatedAt = try Instant.parse("2026-08-10T00:00:00Z")
        let coordinator = HomeDashboardCoordinator(today: today)

        let empty = try coordinator.snapshot(
            candidates: [],
            weather: .unavailable
        )
        let stale = try coordinator.snapshot(
            candidates: [
                candidate("plant-a", last: "2026-08-01", interval: 10)
            ],
            weather: .content(summary: "맑음"),
            freshness: .stale(lastUpdated: updatedAt)
        )

        #expect(empty.state == .empty)
        #expect(stale.state == .stale)
    }

    private func candidate(
        _ id: String,
        last: String?,
        interval: Int
    ) throws -> HomeCareCandidate {
        let plantID = try PersonalPlantID.parse(id)
        let lastWateredDate = try last.map(CalendarDate.parse)
        return HomeCareCandidate(
            plantID: plantID,
            displayName: id,
            lastWateredDate: lastWateredDate,
            intervalDays: interval
        )
    }
}
