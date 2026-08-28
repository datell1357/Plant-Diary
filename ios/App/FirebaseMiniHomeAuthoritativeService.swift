@preconcurrency import FirebaseFunctions
import Foundation
import PlanteriorDomain

@MainActor
struct FirebaseMiniHomeAuthoritativeService: MiniHomeAuthoritativeService {
    private let client: any MiniHomeCallableClient

    init(client: any MiniHomeCallableClient = FirebaseMiniHomeCallableClient()) {
        self.client = client
    }

    func load(accountID: String) async throws -> MiniHomeAuthoritativeLoadResult {
        try Self.requireID(accountID)
        let data = try await call(
            name: "loadMiniHome",
            payload: ["expectedOwnerUid": accountID]
        )
        return try MiniHomeResponseDecoder.load(
            data: data,
            expectedAccountID: accountID
        )
    }

    func save(
        _ request: MiniHomeAuthoritativeSaveRequest
    ) async throws -> MiniHomeAuthoritativeSaveResult {
        let payload = try Self.payload(request)
        let data = try await call(name: "saveMiniHome", payload: payload)
        return try MiniHomeResponseDecoder.save(
            data: data,
            expectedAccountID: request.accountID
        )
    }

    private func call(
        name: String,
        payload: sending [String: Any]
    ) async throws -> Data {
        do {
            return try await client.call(name: name, payload: payload)
        } catch let error as MiniHomeAuthoritativeError {
            throw error
        } catch {
            throw MiniHomeAuthoritativeError.transport
        }
    }

    private static func payload(
        _ request: MiniHomeAuthoritativeSaveRequest
    ) throws -> [String: Any] {
        try requireID(request.accountID)
        let home = request.draft
        try requireName(home.name)
        guard home.placements.count <= 20,
              Set(home.placements.map(\.id)).count == home.placements.count
        else {
            throw MiniHomeAuthoritativeError.invalidRequest
        }
        let placements = try MiniHomeCanonicalEncoding
            .sortedPlacements(home.placements)
            .map { try placementPayload($0) }
        return [
            "expectedOwnerUid": request.accountID,
            "expectedRevision": request.expectedRevision.rawValue,
            "operationId": request.operationID.rawValue,
            "roomId": home.id.rawValue,
            "name": home.name,
            "placements": placements
        ]
    }

    private static func placementPayload(
        _ placement: MiniHomePlacement
    ) throws -> [String: Any] {
        guard placement.normalizedX.isFinite,
              placement.normalizedY.isFinite,
              (0 ... 1).contains(placement.normalizedX),
              (0 ... 1).contains(placement.normalizedY),
              (0 ... 19).contains(placement.zIndex)
        else {
            throw MiniHomeAuthoritativeError.invalidRequest
        }
        var payload: [String: Any] = [
            "placementId": placement.id.rawValue,
            "normalizedX": placement.normalizedX,
            "normalizedY": placement.normalizedY,
            "zIndex": placement.zIndex
        ]
        switch (placement.plantID, placement.itemID) {
        case let (.some(plantID), .none):
            payload["plantId"] = plantID.rawValue
        case let (.none, .some(itemID)):
            payload["itemId"] = itemID.rawValue
        case (.some, .some), (.none, .none):
            throw MiniHomeAuthoritativeError.invalidRequest
        }
        return payload
    }

    private static func requireID(_ value: String) throws {
        guard value.range(
            of: "^[A-Za-z0-9_-]{1,128}$",
            options: .regularExpression
        ) != nil else {
            throw MiniHomeAuthoritativeError.invalidRequest
        }
    }

    private static func requireName(_ value: String) throws {
        guard !value.isEmpty,
              value.utf16.count <= 100,
              value == value.trimmingCharacters(in: .whitespacesAndNewlines)
        else {
            throw MiniHomeAuthoritativeError.invalidRequest
        }
    }
}

@MainActor
struct FirebaseMiniHomeCallableClient: MiniHomeCallableClient {
    func call(
        name: String,
        payload: sending [String: Any]
    ) async throws -> Data {
        let transfer = MiniHomeUnsafeTransfer(value: payload)
        let callable = Functions.functions().httpsCallable(name)
        return try await withCheckedThrowingContinuation { continuation in
            callable.call(transfer.value) { result, error in
                if let error {
                    continuation.resume(throwing: Self.map(error))
                    return
                }
                guard let value = result?.data,
                      JSONSerialization.isValidJSONObject(value),
                      let data = try? JSONSerialization.data(withJSONObject: value)
                else {
                    continuation.resume(
                        throwing: MiniHomeAuthoritativeError.malformedResponse
                    )
                    return
                }
                continuation.resume(returning: data)
            }
        }
    }

    private static func map(_ error: Error) -> MiniHomeAuthoritativeError {
        guard let code = FunctionsErrorCode(rawValue: (error as NSError).code) else {
            return .transport
        }
        switch code {
        case .unauthenticated: return .unauthenticated
        case .permissionDenied: return .forbidden
        case .invalidArgument: return .invalidRequest
        case .failedPrecondition: return .failedPrecondition
        case .dataLoss: return .dataLoss
        default: return .transport
        }
    }
}

private final class MiniHomeUnsafeTransfer<Value>: @unchecked Sendable {
    nonisolated(unsafe) let value: Value

    init(value: sending Value) {
        self.value = value
    }
}
