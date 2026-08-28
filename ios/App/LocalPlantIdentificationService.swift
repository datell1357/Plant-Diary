#if DEBUG
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
                let candidates = Self.fixtures.compactMap { fixture -> IdentificationCandidate? in
                    let rawID = fixture.rawID
                    let koreanName = fixture.koreanName
                    let scientificName = fixture.scientificName
                    let confidence = fixture.confidence
                    guard let plantID = try? PlantContentID.parse(rawID),
                          let thumbnailURL = URL(
                              string: "https://images.example.invalid/\(rawID).jpg"
                          )
                    else {
                        return nil
                    }
                    return try? IdentificationCandidate(
                        plantID: plantID,
                        koreanName: koreanName,
                        commonName: scientificName,
                        scientificName: scientificName,
                        thumbnailURL: thumbnailURL,
                        confidence: confidence
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

        private struct LocalCandidateFixture {
            let rawID: String
            let koreanName: String
            let scientificName: String
            let confidence: Double
        }

        private static let fixtures = [
            LocalCandidateFixture(
                rawID: "local-candidate-1",
                koreanName: "몬스테라 델리시오사",
                scientificName: "Monstera deliciosa",
                confidence: 0.95
            ),
            LocalCandidateFixture(
                rawID: "local-candidate-2",
                koreanName: "몬스테라 아단소니",
                scientificName: "Monstera adansonii",
                confidence: 0.72
            ),
            LocalCandidateFixture(
                rawID: "local-candidate-3",
                koreanName: "필로덴드론",
                scientificName: "Philodendron hederaceum",
                confidence: 0.45
            )
        ]
    }
#endif
