import Foundation
import PlanteriorData
import PlanteriorDomain

enum PlantIdentificationProxyResponse {
    static func decode(_ data: Data) throws -> IdentificationState {
        let raw = try JSONSerialization.jsonObject(with: data)
        guard let object = raw as? [String: Any],
              let kind = object["kind"] as? String
        else {
            throw invalid()
        }
        switch kind {
        case "pending": return try stateWithoutPayload(.pending, object: object)
        case "no_candidates": return try stateWithoutPayload(.noCandidates, object: object)
        case "failed": return try failedState(object)
        case "candidates": return try candidatesState(object)
        default: throw invalid()
        }
    }

    private static func stateWithoutPayload(
        _ state: IdentificationState,
        object: [String: Any]
    ) throws -> IdentificationState {
        guard Set(object.keys) == ["kind"] else { throw invalid() }
        return state
    }

    private static func failedState(
        _ object: [String: Any]
    ) throws -> IdentificationState {
        guard Set(object.keys) == ["kind", "reason"],
              let reason = object["reason"] as? String
        else {
            throw invalid()
        }
        return try .failed(failure(reason))
    }

    private static func candidatesState(
        _ object: [String: Any]
    ) throws -> IdentificationState {
        guard Set(object.keys) == ["kind", "candidates"],
              let values = object["candidates"] as? [Any],
              !values.isEmpty,
              values.count <= 3
        else {
            throw invalid()
        }
        let candidates = try values.map(candidate)
        guard Set(candidates.map(\.plantID)).count == candidates.count else {
            throw invalid()
        }
        return .candidates(IdentificationCandidates(candidates))
    }

    private static func candidate(_ raw: Any) throws -> IdentificationCandidate {
        guard let value = raw as? [String: Any],
              Set(value.keys) == candidateKeys,
              let identifier = value["publicContentId"] as? String,
              let koreanName = value["koreanName"] as? String,
              let commonName = value["commonName"] as? String,
              let scientificName = value["scientificName"] as? String,
              let thumbnailValue = value["thumbnailUrl"] as? String,
              let thumbnailURL = URL(string: thumbnailValue),
              let confidence = value["confidence"] as? NSNumber,
              !["c", "B"].contains(String(cString: confidence.objCType))
        else {
            throw invalid()
        }
        do {
            return try IdentificationCandidate(
                plantID: PlantContentID.parse(identifier),
                koreanName: koreanName,
                commonName: commonName,
                scientificName: scientificName,
                thumbnailURL: thumbnailURL,
                confidence: confidence.doubleValue
            )
        } catch {
            throw invalid()
        }
    }

    private static func failure(_ reason: String) throws -> IdentificationFailure {
        switch reason {
        case "timeout": return .timeout
        case "rate_limited": return .rateLimited
        case "provider_unavailable": return .providerUnavailable
        case "malformed_response": return .invalidResponse
        default: throw invalid()
        }
    }

    private static func invalid() -> PlantIdentificationProxyError {
        .invalidResponse
    }

    private static let candidateKeys: Set<String> = [
        "publicContentId", "koreanName", "commonName",
        "scientificName", "confidence", "thumbnailUrl"
    ]
}
