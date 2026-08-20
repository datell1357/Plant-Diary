import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing

@MainActor
struct LocalPlantCollectionStoreTests {
    @Test
    func resolvesRouteTargetByExactIdentityAcrossReorderAndDeletion() throws {
        let suiteName = "LocalPlantCollectionStoreRoutesTests-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let store = LocalPlantCollectionStore(
            defaults: defaults,
            notificationSchedules: LocalNotificationScheduleStore(
                defaults: defaults
            )
        )
        let first = try identifiedDraft(named: "첫 번째", id: "local-0")
        let second = try identifiedDraft(named: "두 번째", id: "local-1")

        store.plants = [second, first]

        #expect(store.index(forRouteTarget: "local-0") == 1)

        store.plants.remove(at: 1)

        #expect(store.index(forRouteTarget: "local-0") == nil)
        #expect(store.index(forRouteTarget: "local-1") == 0)
    }

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

    private func identifiedDraft(
        named name: String,
        id: String
    ) throws -> PlantRegistrationDraft {
        try PlantRegistrationDraft(
            plantID: PlantContentID.parse(id),
            displayName: name,
            representativePhoto: nil,
            lastWateredOn: nil,
            registrationMethod: .manual
        )
    }
}
