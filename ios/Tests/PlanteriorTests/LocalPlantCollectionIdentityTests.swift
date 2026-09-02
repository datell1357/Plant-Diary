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
    func wateringAndHomeIdentityStayWithSurvivorAfterReorderAndDeletion() throws {
        // Given
        let (_, store) = try makeStore()
        store.save(draft(
            named: "첫 번째",
            lastWateredOn: "2026-08-01",
            intervalDays: 10
        ))
        store.save(draft(
            named: "두 번째",
            lastWateredOn: "2026-08-01",
            intervalDays: 10
        ))
        let firstID = try #require(store.weatherPlantID(at: 0))
        let secondID = try #require(store.weatherPlantID(at: 1))
        let today = try CalendarDate.parse("2026-08-11")

        // When
        _ = try store.recordWateredToday(at: 1, today: today, intervalDays: 10)
        store.movePlant(from: 1, to: 0)
        store.remove(at: 1)
        let home = HomeDashboardStore()
        home.updatePlantIDs(store.weatherPlantIDs)
        home.updateCompletedPlantIDs(store.completedPlantIDs)
        home.reload(
            plants: store.plants,
            today: today,
            weather: .unavailable,
            miniHome: nil,
            notificationState: .initial
        )

        // Then
        #expect(try store.personalPlantID(at: 0) == secondID)
        #expect(store.completedPlantIDs == [secondID])
        #expect(!store.completedPlantIDs.contains(firstID))
        #expect(home.snapshot.careItems.map(\.plantID) == [secondID])
    }

    @Test
    func stablePlantIdentityRemainsAccountScopedAcrossRemounts() throws {
        // Given
        let (_, store) = try makeStore()
        store.mount(accountID: "account-a")
        store.save(draft(named: "A의 식물"))
        let accountAID = try #require(store.weatherPlantID(at: 0))

        // When
        store.mount(accountID: "account-b")
        store.save(draft(named: "B의 식물"))
        let accountBID = try #require(store.weatherPlantID(at: 0))
        store.mount(accountID: "account-a")

        // Then
        #expect(accountAID != accountBID)
        #expect(store.weatherPlantID(at: 0) == accountAID)
        #expect(store.plants.map(\.displayName) == ["A의 식물"])
    }

    @Test
    func healthNotesSurviveFreshStoreAndAccountAReturnWithoutLeakingToAccountB() throws {
        let (defaults, store) = try makeStore()
        store.mount(accountID: "account-a")
        store.save(draft(named: "A의 몬스테라"))
        store.addHealthNote("A의 건강 기록", at: 0)

        let remounted = LocalPlantCollectionStore(
            defaults: defaults,
            notificationSchedules: LocalNotificationScheduleStore(defaults: defaults)
        )
        remounted.mount(accountID: "account-a")
        #expect(remounted.healthNotes(at: 0) == ["A의 건강 기록"])

        remounted.mount(accountID: "account-b")
        #expect(remounted.plants.isEmpty)
        #expect(remounted.healthNotesByPlantID.isEmpty)

        remounted.mount(accountID: "account-a")
        #expect(remounted.healthNotes(at: 0) == ["A의 건강 기록"])

        let freshStore = LocalPlantCollectionStore(
            defaults: defaults,
            notificationSchedules: LocalNotificationScheduleStore(defaults: defaults)
        )
        freshStore.mount(accountID: "account-a")
        #expect(freshStore.healthNotes(at: 0) == ["A의 건강 기록"])

        freshStore.remove(at: 0)
        #expect(freshStore.plants.isEmpty)
        #expect(freshStore.healthNotesByPlantID.isEmpty)

        let afterDeletion = LocalPlantCollectionStore(
            defaults: defaults,
            notificationSchedules: LocalNotificationScheduleStore(defaults: defaults)
        )
        afterDeletion.mount(accountID: "account-a")
        #expect(afterDeletion.healthNotesByPlantID.isEmpty)
    }

    func makeStore() throws -> (UserDefaults, LocalPlantCollectionStore) {
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

    func draft(
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
