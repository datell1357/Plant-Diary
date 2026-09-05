import Foundation
@testable import PlanteriorData
import PlanteriorDomain

final class IdentificationServiceFake: PlantIdentificationService, @unchecked Sendable {
    private let lock = NSLock()
    private var states: [IdentificationState]
    private var receivedIdentities: [String] = []
    private var receivedImageBatches: [[Data]] = []

    init(states: [IdentificationState]) {
        self.states = states
    }

    func identify(
        requestID: IdentificationRequestID,
        idempotencyKey: OperationID,
        images: [Data]
    ) -> AsyncStream<IdentificationState> {
        lock.lock()
        receivedIdentities.append(
            "\(requestID.rawValue):\(idempotencyKey.rawValue)"
        )
        receivedImageBatches.append(images)
        let current = states
        lock.unlock()
        return AsyncStream(IdentificationState.self) { continuation in
            for state in current {
                continuation.yield(state)
            }
            continuation.finish()
        }
    }

    func identities() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return receivedIdentities
    }

    func imageBatches() -> [[Data]] {
        lock.lock()
        defer { lock.unlock() }
        return receivedImageBatches
    }

    func replaceStates(_ states: [IdentificationState]) {
        lock.lock()
        self.states = states
        lock.unlock()
    }
}

struct RegistrationStoreFake: PlantRegistrationStore {
    let shouldFail: Bool

    func save(_ draft: PlantRegistrationDraft) async throws {
        if shouldFail {
            throw PlantRegistrationError.saveFailed
        }
    }
}
