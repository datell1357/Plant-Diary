import Foundation
import PlanteriorDomain

public enum IntegrationStatus: String, Codable, Sendable {
    case unavailable
}

public enum BackendContractError: Error, Equatable {
    case integrationUnavailable
    case invalidFixture
    case invalidMutation
}

public struct PinnedContractFile: Codable, Equatable, Sendable {
    public let path: String
    public let sha256: String

    public init(path: String, sha256: String) {
        self.path = path
        self.sha256 = sha256
    }
}

public struct UnavailableContract: Codable, Equatable, Sendable {
    public let id: String
    public let value: String?
    public let integrationStatus: IntegrationStatus

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        value = try container.decodeIfPresent(String.self, forKey: .value)
        integrationStatus = try container.decode(IntegrationStatus.self, forKey: .integrationStatus)
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case value
        case integrationStatus
    }
}

public struct ForbiddenFixture: Codable, Equatable, Sendable {
    public let reason: String
    public let value: String

    public func validateRejected() throws {
        let rejected: Bool = switch reason {
        case "foreign-owner":
            value.hasPrefix("users/other/")
        case "server-only-write":
            value.contains("/weatherSnapshots/")
        case "unsafe-storage-name":
            value.first?.isLetter != true && value.first?.isNumber != true
        case "placement-target-xor":
            value == "plantId+itemId"
        case "unknown-enum":
            !["IDENTIFIED", "IDENTIFICATION_EDITED", "MANUAL"].contains(
                value.replacingOccurrences(of: "registrationMethod=", with: "")
            )
        default:
            false
        }
        guard rejected else {
            throw BackendContractError.invalidFixture
        }
    }
}

public struct OwnerMutationFixture: Codable, Equatable, Sendable {
    public let collection: String
    public let documentID: String
    public let expectedRevision: Int
    public let idempotencyKey: String
    public let payload: [String: String]
}

public struct BackendContractManifest: Codable, Equatable, Sendable {
    public let contractVersion: String
    public let sourceCommit: String
    public let pinnedFiles: [PinnedContractFile]
    public let validOwnerMutations: [OwnerMutationFixture]
    public let forbiddenFixtures: [ForbiddenFixture]
    public let unavailableIntegrations: [UnavailableContract]
    public let unavailablePolicies: [UnavailableContract]
    public let compatibilityNotes: [String]

    public func requireLiveIntegration(_ id: String) throws {
        throw BackendContractError.integrationUnavailable
    }

    public func requireEnforcedPolicy(_ id: String) throws {
        throw BackendContractError.integrationUnavailable
    }

    public func validateForbiddenFixtures() throws {
        for fixture in forbiddenFixtures {
            try fixture.validateRejected()
        }
    }
}

public struct OwnerMutationRequest: Equatable, Sendable {
    public let ownerUID: String
    public let collection: String
    public let documentID: String
    public let expectedRevision: Int
    public let idempotencyKey: String

    public init(
        ownerUID: String,
        collection: String,
        documentID: String,
        expectedRevision: Int,
        idempotencyKey: String
    ) throws {
        guard (try? AccountID.parse(ownerUID)) != nil,
              collection == "personalPlants",
              (try? PersonalPlantID.parse(documentID)) != nil,
              (0 ... 9_007_199_254_740_991).contains(expectedRevision),
              (try? OperationID.parse(idempotencyKey)) != nil
        else {
            throw BackendContractError.invalidMutation
        }
        self.ownerUID = ownerUID
        self.collection = collection
        self.documentID = documentID
        self.expectedRevision = expectedRevision
        self.idempotencyKey = idempotencyKey
    }
}

public enum OwnerMutationResult: Equatable, Sendable {
    case applied(revision: Int)
    case duplicate(revision: Int)
    case conflict(actualRevision: Int)
}

public struct ProvisionalOwnerMutationFake: Sendable {
    private var revisions: [String: Int] = [:]
    private var receipts: [String: (path: String, revision: Int)] = [:]

    public init() {}

    public mutating func apply(_ request: OwnerMutationRequest) throws -> OwnerMutationResult {
        let path = "users/\(request.ownerUID)/\(request.collection)/\(request.documentID)"
        let receiptKey = "\(request.ownerUID)/\(request.idempotencyKey)"
        if let receipt = receipts[receiptKey] {
            guard receipt.path == path else {
                throw BackendContractError.invalidMutation
            }
            return .duplicate(revision: receipt.revision)
        }

        let actualRevision = revisions[path] ?? 0
        guard request.expectedRevision == actualRevision else {
            return .conflict(actualRevision: actualRevision)
        }
        let nextRevision = actualRevision + 1
        revisions[path] = nextRevision
        receipts[receiptKey] = (path, nextRevision)
        return .applied(revision: nextRevision)
    }

    public func revision(collection: String, documentID: String) -> Int? {
        revisions.first(where: { $0.key.hasSuffix("/\(collection)/\(documentID)") })?.value
    }
}
