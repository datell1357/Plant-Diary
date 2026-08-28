import Foundation
import PlanteriorDomain

public enum IdentificationCandidateError: Error, Equatable, Sendable {
    case invalidName
    case invalidScientificName
    case invalidThumbnailURL
    case invalidConfidence
}

public struct IdentificationCandidate: Equatable, Sendable {
    public let plantID: PlantContentID
    public let koreanName: String
    public let commonName: String
    public let scientificName: String
    public let thumbnailURL: URL
    public let score: Double

    public init(
        plantID: PlantContentID,
        koreanName: String,
        commonName: String,
        scientificName: String,
        thumbnailURL: URL,
        confidence: Double
    ) throws {
        guard confidence.isFinite, (0 ... 1).contains(confidence) else {
            throw IdentificationCandidateError.invalidConfidence
        }
        self.plantID = plantID
        self.koreanName = try Self.normalizedName(koreanName)
        self.commonName = try Self.normalizedName(commonName)
        self.scientificName = try Self.normalizedScientificName(scientificName)
        guard thumbnailURL.scheme?.lowercased() == "https",
              thumbnailURL.host != nil,
              thumbnailURL.user == nil,
              thumbnailURL.password == nil,
              thumbnailURL.fragment == nil,
              thumbnailURL.absoluteString.count <= 2048
        else {
            throw IdentificationCandidateError.invalidThumbnailURL
        }
        self.thumbnailURL = thumbnailURL
        score = confidence
    }

    private static func normalizedName(_ value: String) throws -> String {
        let normalized = value
            .precomposedStringWithCanonicalMapping
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty,
              normalized.count <= 200,
              normalized.unicodeScalars.allSatisfy({
                  !CharacterSet.controlCharacters.contains($0)
              })
        else {
            throw IdentificationCandidateError.invalidName
        }
        return normalized
    }

    private static func normalizedScientificName(_ value: String) throws -> String {
        let normalized = try normalizedName(value)
        guard normalized.unicodeScalars.allSatisfy({ scalar in
            let value = scalar.value
            return value == 32
                || (65 ... 90).contains(value)
                || (97 ... 122).contains(value)
                || (48 ... 57).contains(value)
                || "'.-".unicodeScalars.contains(scalar)
        }) else {
            throw IdentificationCandidateError.invalidScientificName
        }
        return normalized
    }
}
