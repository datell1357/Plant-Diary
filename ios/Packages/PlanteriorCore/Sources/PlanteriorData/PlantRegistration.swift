import PlanteriorDomain

public enum DuplicatePlantDecision: Sendable {
    case openExisting
    case addAnother
    case cancel
}

public enum PlantRegistrationOutcome: Equatable, Sendable {
    case duplicate
    case openedExisting
    case registered
    case cancelled
}

public enum PlantRegistrationError: Error, Equatable, Sendable {
    case invalidName
    case futureWateringDate
    case saveFailed
}

public protocol PlantRegistrationStore: Sendable {
    func save(_ draft: PlantRegistrationDraft) async throws
}

public actor PlantRegistrationCoordinator {
    public private(set) var personalPlants: [PlantRegistrationDraft] = []
    private let existingPlantIDs: Set<PlantContentID>
    private var pendingDuplicate: PlantRegistrationDraft?
    public private(set) var failedDraft: PlantRegistrationDraft?
    private let store: (any PlantRegistrationStore)?

    public init(existingPlantIDs: Set<PlantContentID>) {
        self.existingPlantIDs = existingPlantIDs
        store = nil
    }

    public var personalPlantCount: Int {
        personalPlants.count
    }

    public init(existingPlantIDs: [String]) throws {
        var parsed: Set<PlantContentID> = []
        for rawValue in existingPlantIDs {
            try parsed.insert(PlantContentID.parse(rawValue))
        }
        self.existingPlantIDs = parsed
        store = nil
    }

    public init(
        existingPlantIDs: Set<PlantContentID>,
        store: any PlantRegistrationStore
    ) {
        self.existingPlantIDs = existingPlantIDs
        self.store = store
    }

    public func save(
        _ draft: PlantRegistrationDraft,
        today: CalendarDate
    ) async throws -> PlantRegistrationOutcome {
        let trimmed = draft.displayName.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        guard (1 ... 100).contains(trimmed.count) else {
            throw PlantRegistrationError.invalidName
        }
        let hasFutureWateringDate =
            draft.lastWateredOn?.rawValue ?? "" > today.rawValue
        if hasFutureWateringDate {
            throw PlantRegistrationError.futureWateringDate
        }
        let outcome = register(draft)
        guard outcome == .registered else {
            return outcome
        }
        do {
            try await store?.save(draft)
            failedDraft = nil
            return .registered
        } catch {
            personalPlants.removeLast()
            failedDraft = draft
            throw PlantRegistrationError.saveFailed
        }
    }

    public func register(
        _ draft: PlantRegistrationDraft
    ) -> PlantRegistrationOutcome {
        if let plantID = draft.plantID, existingPlantIDs.contains(plantID) {
            pendingDuplicate = draft
            return .duplicate
        }
        personalPlants.append(draft)
        return .registered
    }

    public func register(
        plantID: String?,
        displayName: String
    ) throws -> PlantRegistrationOutcome {
        let parsedPlantID: PlantContentID? = if let plantID {
            try PlantContentID.parse(plantID)
        } else {
            nil
        }
        return register(
            PlantRegistrationDraft(
                plantID: parsedPlantID,
                displayName: displayName,
                representativePhoto: nil,
                lastWateredOn: nil,
                registrationMethod: plantID == nil ? .manual : .identified
            )
        )
    }

    public func resolveDuplicate(
        _ decision: DuplicatePlantDecision
    ) -> PlantRegistrationOutcome {
        guard let pendingDuplicate else {
            return .cancelled
        }
        switch decision {
        case .openExisting:
            self.pendingDuplicate = nil
            return .openedExisting
        case .addAnother:
            personalPlants.append(pendingDuplicate)
            self.pendingDuplicate = nil
            return .registered
        case .cancel:
            self.pendingDuplicate = nil
            return .cancelled
        }
    }

    public func openExistingDuplicate() {
        _ = resolveDuplicate(.openExisting)
    }

    public func addDuplicate() {
        _ = resolveDuplicate(.addAnother)
    }
}
