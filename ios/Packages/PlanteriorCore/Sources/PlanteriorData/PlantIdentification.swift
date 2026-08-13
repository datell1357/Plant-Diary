import Foundation
import PlanteriorDomain

public struct IdentificationCandidate: Equatable, Sendable {
    public let plantID: PlantContentID
    public let score: Double

    public init(plantID: PlantContentID, confidence: Double) {
        self.plantID = plantID
        score = confidence
    }
}

public struct IdentificationCandidates: Equatable, Sendable {
    public let items: [IdentificationCandidate]

    public init(_ items: [IdentificationCandidate]) {
        self.items = Array(
            items.sorted { $0.score > $1.score }.prefix(3)
        )
    }
}

public enum IdentificationFailure: Equatable, Sendable {
    case providerUnavailable
    case invalidResponse
    case timeout
    case rateLimited
    case serverFailure
}

public enum IdentificationState: Equatable, Sendable {
    case awaitingPhoto
    case pending
    case candidates(IdentificationCandidates)
    case noCandidates
    case failed(IdentificationFailure)
}

public protocol PlantIdentificationService: Sendable {
    func identify(
        requestID: IdentificationRequestID,
        idempotencyKey: OperationID,
        image: Data
    ) -> AsyncStream<IdentificationState>
}

public struct PlantRegistrationDraft: Equatable, Sendable {
    public let plantID: PlantContentID?
    public let displayName: String
    public let representativePhoto: Data?
    public let lastWateredOn: CalendarDate?
    public let registrationMethod: RegistrationMethod

    public init(
        plantID: PlantContentID?,
        displayName: String,
        representativePhoto: Data?,
        lastWateredOn: CalendarDate?,
        registrationMethod: RegistrationMethod
    ) {
        self.plantID = plantID
        self.displayName = displayName
        self.representativePhoto = representativePhoto
        self.lastWateredOn = lastWateredOn
        self.registrationMethod = registrationMethod
    }
}

public actor PlantIdentificationCoordinator {
    public private(set) var state = IdentificationState.awaitingPhoto
    public private(set) var draft: PlantRegistrationDraft?
    public private(set) var personalPlant: PlantRegistrationDraft?
    public private(set) var candidateCount = 0
    private let service: any PlantIdentificationService
    private var image: Data?
    private var selectedCandidate: IdentificationCandidate?
    private var requestID: IdentificationRequestID?
    private var idempotencyKey: OperationID?

    public init(service: any PlantIdentificationService) {
        self.service = service
    }

    public var hasPersonalPlant: Bool {
        personalPlant != nil
    }

    public var hasDraft: Bool {
        draft != nil
    }

    public func submit(_ image: Data) async {
        self.image = image
        guard let identity = requestIdentity()
        else {
            state = .failed(.invalidResponse)
            return
        }
        await consume(
            service.identify(
                requestID: identity.requestID,
                idempotencyKey: identity.idempotencyKey,
                image: image
            )
        )
    }

    public func retry() async {
        guard let image else {
            return
        }
        await submit(image)
    }

    public func replacePhoto() {
        selectedCandidate = nil
        draft = nil
        state = .awaitingPhoto
        requestID = nil
        idempotencyKey = nil
    }

    private func requestIdentity() -> (
        requestID: IdentificationRequestID,
        idempotencyKey: OperationID
    )? {
        if let requestID, let idempotencyKey {
            return (requestID, idempotencyKey)
        }
        guard let requestID = try? IdentificationRequestID.parse(UUID().uuidString),
              let idempotencyKey = try? OperationID.parse(UUID().uuidString)
        else {
            return nil
        }
        self.requestID = requestID
        self.idempotencyKey = idempotencyKey
        return (requestID, idempotencyKey)
    }

    public func selectCandidate(
        plantID: String,
        confidence: Double
    ) throws {
        let candidate = try IdentificationCandidate(
            plantID: PlantContentID.parse(plantID),
            confidence: confidence
        )
        guard case let .candidates(candidates) = state,
              candidates.items.contains(candidate)
        else {
            return
        }
        selectedCandidate = candidate
    }

    public func confirmSelection() {
        guard let selectedCandidate else {
            return
        }
        draft = PlantRegistrationDraft(
            plantID: selectedCandidate.plantID,
            displayName: "",
            representativePhoto: image,
            lastWateredOn: nil,
            registrationMethod: .identified
        )
    }

    public func beginManualEntry(name: String) {
        draft = PlantRegistrationDraft(
            plantID: nil,
            displayName: name,
            representativePhoto: image,
            lastWateredOn: nil,
            registrationMethod: .manual
        )
    }

    private func consume(_ stream: AsyncStream<IdentificationState>) async {
        for await nextState in stream {
            switch nextState {
            case let .candidates(candidates):
                candidateCount = candidates.items.count
                state = .candidates(candidates)
            default:
                state = nextState
            }
        }
    }
}
