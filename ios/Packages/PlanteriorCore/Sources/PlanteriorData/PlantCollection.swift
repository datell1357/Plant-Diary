import Foundation
import PlanteriorDomain

public struct PlantCollection: Sendable {
    private let plants: [PersonalPlant]

    public init(plants: [PersonalPlant]) {
        self.plants = plants
    }

    public func filtered(search: String) -> [PersonalPlant] {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        return plants
            .filter {
                query.isEmpty
                    || $0.displayName.localizedCaseInsensitiveContains(query)
            }
            .sorted { lhs, rhs in
                lhs.displayName.localizedStandardCompare(
                    rhs.displayName
                ) == .orderedAscending
            }
    }
}

public struct PlantHealthNote: Equatable, Sendable {
    public let text: String

    public init(text: String) {
        self.text = text
    }
}

public enum PlantDeletionState: Equatable, Sendable {
    case idle
    case confirmationRequired
    case deleted
}

public enum PlantCollectionSnapshot: Equatable, Sendable {
    case loading
    case content([PersonalPlant])
    case empty
    case error
    case partial([PersonalPlant])
    case stale([PersonalPlant])
}

public enum PlantContentAccess: Equatable, Sendable {
    case published
    case unpublished
    case deleted
    case forbidden
    case notFound
}

public struct PlantCollectionRepository: Sendable {
    public init() {}

    public func snapshot(
        plants: [PersonalPlant],
        access: [PlantContentID: PlantContentAccess],
        cachedPublishedIDs: Set<PlantContentID>
    ) -> PlantCollectionSnapshot {
        let visible = plants.filter { plant in
            guard let contentID = plant.contentID else {
                return true
            }
            switch access[contentID] {
            case .published:
                return true
            case .unpublished:
                return cachedPublishedIDs.contains(contentID)
            case .deleted, .forbidden, .notFound, .none:
                return false
            }
        }
        guard !visible.isEmpty else {
            return .empty
        }
        return visible.count == plants.count
            ? .content(visible)
            : .partial(visible)
    }
}

public enum PlantCareValidationError: Error, Equatable, Sendable {
    case invalidLocation
    case invalidMemo
}

public final class PlantCareDetailCoordinator {
    public let plant: PersonalPlant
    public private(set) var draftNickname: String
    public private(set) var healthNotes: [PlantHealthNote] = []
    public private(set) var deletionState = PlantDeletionState.idle

    public init(plant: PersonalPlant) {
        self.plant = plant
        draftNickname = plant.displayName
    }

    public func updateNickname(_ nickname: String) {
        draftNickname = nickname.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
    }

    public func addHealthNote(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return
        }
        healthNotes.append(PlantHealthNote(text: trimmed))
    }

    public func validateEdits(
        location: String,
        privateMemo: String
    ) throws {
        guard location.count <= 50 else {
            throw PlantCareValidationError.invalidLocation
        }
        guard privateMemo.count <= 1000 else {
            throw PlantCareValidationError.invalidMemo
        }
    }

    public func requestDeletion() {
        deletionState = .confirmationRequired
    }

    public func cancelDeletion() {
        deletionState = .idle
    }

    public func confirmDeletion() {
        deletionState = .deleted
    }
}
