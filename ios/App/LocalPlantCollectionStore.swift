import Foundation
import PlanteriorData

@MainActor
final class LocalPlantCollectionStore: ObservableObject {
    static let shared = LocalPlantCollectionStore()
    @Published private(set) var plants: [PlantRegistrationDraft] = []

    private init() {}

    func save(_ draft: PlantRegistrationDraft) {
        plants.append(draft)
    }

    func contains(_ plantID: String) -> Bool {
        plants.contains { $0.plantID?.rawValue == plantID }
    }

    func existingName(for plantID: String) -> String? {
        plants.first { $0.plantID?.rawValue == plantID }?.displayName
    }
}
