import FirebaseFunctions
import Foundation
import PlanteriorDomain

struct FirebaseAccountDeletionService: AccountDeletionServicing {
    func preview(
        ownerID: AccountID
    ) async throws -> AccountDeletionServiceSnapshot {
        let payload = try await call(
            "previewAccountDeletion",
            values: ["ownerID": ownerID.rawValue]
        )
        let scope: AccountDeletionScope = try decode(
            payload["scope"] ?? payload
        )
        let workflow: AccountDeletionWorkflow? = try payload["workflow"]
            .flatMap { $0 is NSNull ? nil : try decode($0) }
        return AccountDeletionServiceSnapshot(
            scope: scope,
            workflow: workflow
        )
    }

    func request(
        ownerID: AccountID,
        scope: AccountDeletionScope
    ) async throws -> AccountDeletionWorkflow {
        let payload = try await call(
            "requestAccountDeletion",
            values: [
                "ownerID": ownerID.rawValue,
                "scopeHash": scope.scopeHash
            ]
        )
        return try decode(payload["workflow"] ?? payload)
    }

    func cancel(
        ownerID: AccountID,
        workflow: AccountDeletionWorkflow
    ) async throws -> AccountDeletionWorkflow {
        let payload = try await call(
            "cancelAccountDeletion",
            values: [
                "ownerID": ownerID.rawValue,
                "requestID": workflow.requestID.rawValue
            ]
        )
        return try decode(payload["workflow"] ?? payload)
    }

    func recover(
        ownerID: AccountID,
        requestID: DeletionRequestID
    ) async throws -> AccountDeletionWorkflow {
        let payload = try await call(
            "recoverAccountDeletion",
            values: [
                "ownerID": ownerID.rawValue,
                "requestID": requestID.rawValue
            ]
        )
        return try decode(payload["workflow"] ?? payload)
    }

    private func call(
        _ name: String,
        values: [String: Any]
    ) async throws -> [String: Any] {
        let response = try await Functions.functions()
            .httpsCallable(name)
            .call(values)
        guard let payload = response.data as? [String: Any] else {
            throw CocoaError(.coderInvalidValue)
        }
        return payload
    }

    private func decode<T: Decodable>(_ value: Any) throws -> T {
        let data = try JSONSerialization.data(withJSONObject: value)
        return try JSONDecoder().decode(T.self, from: data)
    }
}
