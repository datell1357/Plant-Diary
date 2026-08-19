import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing

@MainActor
struct LocalPlantCollectionStoreTests {
    @Test
    func weatherPlantIdentitySurvivesEarlierPlantDeletion() throws {
        let suiteName = "LocalPlantCollectionStoreTests-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let store = LocalPlantCollectionStore(
            defaults: defaults,
            notificationSchedules: LocalNotificationScheduleStore(
                defaults: defaults
            )
        )
        store.save(draft(named: "첫 번째"))
        store.save(draft(named: "두 번째"))
        let secondID = try #require(store.weatherPlantID(at: 1))

        store.remove(at: 0)

        #expect(store.weatherPlantID(at: 0) == secondID)
        let restored = LocalPlantCollectionStore(
            defaults: defaults,
            notificationSchedules: LocalNotificationScheduleStore(
                defaults: defaults
            )
        )
        #expect(restored.weatherPlantID(at: 0) == secondID)
    }

    private func draft(named name: String) -> PlantRegistrationDraft {
        PlantRegistrationDraft(
            plantID: nil,
            displayName: name,
            representativePhoto: nil,
            lastWateredOn: nil,
            registrationMethod: .manual
        )
    }
}
