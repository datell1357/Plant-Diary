import Foundation
import PlanteriorData
import PlanteriorDomain

struct LocalPlantIdentificationService: PlantIdentificationService {
    func identify(
        requestID: IdentificationRequestID,
        idempotencyKey: OperationID,
        image: Data
    ) -> AsyncStream<IdentificationState> {
        AsyncStream(IdentificationState.self) { continuation in
            continuation.yield(.pending)
            let ids = (1 ... 3).compactMap {
                try? PlantContentID.parse("local-candidate-\($0)")
            }
            let candidates = ids.enumerated().map {
                IdentificationCandidate(
                    plantID: $0.element,
                    confidence: [0.95, 0.72, 0.45][$0.offset]
                )
            }
            continuation.yield(
                candidates.isEmpty
                    ? .noCandidates
                    : .candidates(IdentificationCandidates(candidates))
            )
            continuation.finish()
        }
    }
}
