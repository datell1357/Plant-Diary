import PlanteriorData
import PlanteriorDomain
import Testing

struct ProgressionCoordinatorTests {
    @Test
    func exactBoundaryCanCrossMultiplePublicMilestones() throws {
        let fixture = try ProgressionFixture()
        let event = try fixture.event(
            id: "event-registration-1",
            kind: .registration,
            points: 200
        )
        let definitions = try fixture.definitions()

        let result = ProgressionCoordinator.apply(
            event: event,
            definitions: definitions,
            to: fixture.snapshot
        )

        guard case let .applied(snapshot, newlyEarned) = result else {
            Issue.record("Expected applied progression")
            return
        }
        #expect(snapshot.totalXP == 200)
        #expect(newlyEarned.map(\.rawValue) == [
            "milestone-registration",
            "milestone-watering"
        ])
    }

    @Test
    func duplicateAndOutOfOrderEventsAreOrderIndependent() throws {
        let fixture = try ProgressionFixture()
        let first = try fixture.event(
            id: "event-watering-2",
            kind: .watering,
            points: 60
        )
        let second = try fixture.event(
            id: "event-watering-1",
            kind: .watering,
            points: 40
        )
        let definitions = try fixture.definitions()
        let laterFirst = ProgressionCoordinator.apply(
            event: first,
            definitions: definitions,
            to: fixture.snapshot
        ).snapshot
        let complete = ProgressionCoordinator.apply(
            event: second,
            definitions: definitions,
            to: laterFirst
        ).snapshot
        let duplicate = ProgressionCoordinator.apply(
            event: first,
            definitions: definitions,
            to: complete
        )

        #expect(complete.totalXP == 100)
        #expect(duplicate.snapshot.totalXP == 100)
        #expect(duplicate.isDuplicate)
    }
}
