import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing

@MainActor
struct LocalPlantCollectionIdentityTests {
    @Test
    func healthNotesStayWithSurvivorAfterEarlierPlantDeletionAndRestore() throws {
        let (defaults, store) = try makeStore()
        store.save(draft(named: "첫 번째"))
        store.save(draft(named: "두 번째"))
        store.addHealthNote("첫 기록", at: 0)
        store.addHealthNote("둘째 기록", at: 1)

        store.remove(at: 0)

        #expect(store.healthNotes(at: 0) == ["둘째 기록"])
        let restored = LocalPlantCollectionStore(
            defaults: defaults,
            notificationSchedules: LocalNotificationScheduleStore(defaults: defaults)
        )
        #expect(restored.healthNotes(at: 0) == ["둘째 기록"])
    }

    @Test
    func healthNotesFollowStablePlantIdentityAcrossReorder() throws {
        let (_, store) = try makeStore()
        store.save(draft(named: "첫 번째"))
        store.save(draft(named: "두 번째"))
        store.addHealthNote("첫 기록", at: 0)
        store.addHealthNote("둘째 기록", at: 1)

        store.movePlant(from: 1, to: 0)

        #expect(store.plants.map(\.displayName) == ["두 번째", "첫 번째"])
        #expect(store.healthNotes(at: 0) == ["둘째 기록"])
        #expect(store.healthNotes(at: 1) == ["첫 기록"])
    }

    @Test
    func restoresLegacyIndexNotesIntoStablePlantIdentityKeys() throws {
        let (defaults, _) = try makeStore()
        let keyPrefix = "collection.signed-out"
        try defaults.set(
            JSONEncoder().encode([draft(named: "첫 번째"), draft(named: "두 번째")]),
            forKey: "\(keyPrefix).plants"
        )
        defaults.set(
            ["local_identity_a", "local_identity_b"],
            forKey: "\(keyPrefix).weather-plant-ids"
        )
        try defaults.set(
            JSONEncoder().encode([0: ["첫 기록"], 1: ["둘째 기록"]]),
            forKey: "\(keyPrefix).health-notes"
        )

        let restored = LocalPlantCollectionStore(
            defaults: defaults,
            notificationSchedules: LocalNotificationScheduleStore(defaults: defaults)
        )

        #expect(restored.healthNotes(at: 0) == ["첫 기록"])
        #expect(restored.healthNotes(at: 1) == ["둘째 기록"])
        let persisted = try #require(defaults.data(forKey: "\(keyPrefix).health-notes"))
        let stableNotes = try JSONDecoder().decode([String: [String]].self, from: persisted)
        #expect(stableNotes["local_identity_a"] == ["첫 기록"])
        #expect(stableNotes["local_identity_b"] == ["둘째 기록"])
        #expect(stableNotes["0"] == nil)
    }

    @Test
    func collectionSummaryIsDerivedFromWateringModels() throws {
        let (_, store) = try makeStore()
        store.plants = [
            draft(named: "지연", lastWateredOn: "2026-07-01", intervalDays: 10),
            draft(named: "오늘", lastWateredOn: "2026-08-01", intervalDays: 10),
            draft(named: "예정", lastWateredOn: "2026-08-10", intervalDays: 5),
            draft(named: "미설정")
        ]

        let summary = try store.careSummary(today: CalendarDate.parse("2026-08-11"))

        #expect(summary.total == 4)
        #expect(summary.overdue == 1)
        #expect(summary.dueToday == 1)
        #expect(summary.upcoming == 1)
        #expect(summary.unconfigured == 1)
    }

    private func makeStore() throws -> (UserDefaults, LocalPlantCollectionStore) {
        let suiteName = "LocalPlantCollectionIdentityTests-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        return (
            defaults,
            LocalPlantCollectionStore(
                defaults: defaults,
                notificationSchedules: LocalNotificationScheduleStore(defaults: defaults)
            )
        )
    }

    private func draft(
        named name: String,
        lastWateredOn: String? = nil,
        intervalDays: Int = 10
    ) -> PlantRegistrationDraft {
        PlantRegistrationDraft(
            plantID: nil,
            displayName: name,
            representativePhoto: nil,
            lastWateredOn: lastWateredOn.flatMap { try? CalendarDate.parse($0) },
            wateringIntervalDays: intervalDays,
            registrationMethod: .manual
        )
    }
}
