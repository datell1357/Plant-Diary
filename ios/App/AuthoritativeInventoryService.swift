@preconcurrency import FirebaseFunctions
import Foundation
import PlanteriorDomain

struct AuthoritativeInventorySnapshot: Sendable {
    let catalog: [ShopItem]
    let ownedItems: [OwnedItem]
    let inventoryGeneration: UInt64
    let snapshotHash: String
}

struct InventoryOwnershipReceipt: Sendable {
    enum Kind: Equatable, Sendable {
        case acquired
        case alreadyOwned
    }

    let kind: Kind
    let ownerID: String
    let itemID: ItemID
    let catalogRevision: Revision
    let ownershipRevision: Revision
    let acquiredAt: Instant
}

enum InventoryProviderError: Error, Equatable, Sendable {
    case unavailable
    case unauthenticated
    case forbidden
    case itemUnavailable
    case catalogChanged
    case idempotencyMismatch
    case malformedResponse
}

@MainActor
protocol AuthoritativeInventoryService {
    func load(accountID: String) async throws -> AuthoritativeInventorySnapshot
    func acquire(
        accountID: String,
        itemID: ItemID,
        expectedCatalogRevision: Revision,
        operationID: OperationID
    ) async throws -> InventoryOwnershipReceipt
}

@MainActor
struct UnavailableAuthoritativeInventoryService: AuthoritativeInventoryService {
    func load(accountID: String) async throws -> AuthoritativeInventorySnapshot {
        throw InventoryProviderError.unavailable
    }

    func acquire(
        accountID: String,
        itemID: ItemID,
        expectedCatalogRevision: Revision,
        operationID: OperationID
    ) async throws -> InventoryOwnershipReceipt {
        throw InventoryProviderError.unavailable
    }
}

@MainActor
struct FirebaseAuthoritativeInventoryService: AuthoritativeInventoryService {
    func load(accountID: String) async throws -> AuthoritativeInventorySnapshot {
        let data = try await call(
            function: "loadInventory",
            payload: ["expectedOwnerUid": accountID]
        )
        return try AuthoritativeInventoryResponseDecoder.snapshot(
            data: data,
            expectedAccountID: accountID
        )
    }

    func acquire(
        accountID: String,
        itemID: ItemID,
        expectedCatalogRevision: Revision,
        operationID: OperationID
    ) async throws -> InventoryOwnershipReceipt {
        let data = try await call(
            function: "acquireInventoryItem",
            payload: [
                "expectedOwnerUid": accountID,
                "itemId": itemID.rawValue,
                "expectedCatalogRevision": expectedCatalogRevision.rawValue,
                "operationId": operationID.rawValue
            ]
        )
        return try AuthoritativeInventoryResponseDecoder.receipt(
            data: data,
            expectedAccountID: accountID,
            expectedItemID: itemID
        )
    }

    private func call(
        function: String,
        payload: sending [String: Any]
    ) async throws -> Data {
        let callablePayload = InventoryUnsafeTransfer(value: payload)
        let callable = Functions.functions().httpsCallable(function)
        return try await withCheckedThrowingContinuation { continuation in
            callable.call(callablePayload.value) { result, error in
                if let error {
                    continuation.resume(throwing: Self.map(error))
                    return
                }
                guard let value = result?.data,
                      JSONSerialization.isValidJSONObject(value),
                      let data = try? JSONSerialization.data(
                          withJSONObject: value
                      )
                else {
                    continuation.resume(
                        throwing: InventoryProviderError.malformedResponse
                    )
                    return
                }
                continuation.resume(returning: data)
            }
        }
    }

    private static func map(_ error: Error) -> InventoryProviderError {
        guard let code = FunctionsErrorCode(rawValue: (error as NSError).code) else {
            return .unavailable
        }
        switch code {
        case .unauthenticated: return .unauthenticated
        case .permissionDenied: return .forbidden
        case .notFound: return .itemUnavailable
        case .failedPrecondition: return .catalogChanged
        case .invalidArgument, .dataLoss: return .malformedResponse
        default: return .unavailable
        }
    }
}

private final class InventoryUnsafeTransfer<Value>: @unchecked Sendable {
    nonisolated(unsafe) let value: Value

    init(value: sending Value) {
        self.value = value
    }
}

@MainActor
enum AuthoritativeInventoryServiceFactory {
    static func current() -> any AuthoritativeInventoryService {
        make(firebaseConfigured: FirebaseConfiguration.isAvailable)
    }

    static func make(
        firebaseConfigured: Bool
    ) -> any AuthoritativeInventoryService {
        guard firebaseConfigured else {
            return UnavailableAuthoritativeInventoryService()
        }
        return FirebaseAuthoritativeInventoryService()
    }
}
