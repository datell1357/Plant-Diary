import PlanteriorDomain

extension LocalPlantCollectionStore {
    func weatherPlantID(at index: Int) -> PersonalPlantID? {
        guard weatherPlantIDs.indices.contains(index) else {
            return nil
        }
        return weatherPlantIDs[index]
    }

    func personalPlant(at index: Int) throws -> PersonalPlant {
        let draft = plants[index]
        return try PersonalPlant(
            id: personalPlantID(at: index),
            displayName: draft.displayName,
            contentID: draft.plantID,
            registrationMethod: draft.registrationMethod,
            representativePhotoPath: nil,
            location: draft.location,
            note: draft.privateMemo,
            lastWateredDate: draft.lastWateredOn,
            revision: Revision.parse(0),
            updatedAt: Instant.parse("2026-08-14T00:00:00Z")
        )
    }
}

struct CollectionCareSummary: Equatable {
    let total: Int
    let overdue: Int
    let dueToday: Int
    let upcoming: Int
    let unconfigured: Int
}

enum CollectionViewState: String {
    case loading
    case content
    case error
    case partial
    case stale
}

enum CollectionSaveError: Error {
    case failed
}
