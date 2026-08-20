import FirebaseFirestore
import FirebaseFunctions
import Foundation
import PlanteriorData
import PlanteriorDomain

@MainActor
final class AppSyncRuntime: ObservableObject {
    @Published private(set) var snapshot = AccountSyncSnapshot()
    private let engine: AccountSyncEngine
    private let repository: AccountSyncRepository

    init() {
        let root = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        )[0].appending(path: "Sync")
        let persistence = SwiftDataAccountCache(rootDirectory: root)
        engine = AccountSyncEngine(persistence: persistence)
        repository = AccountSyncRepository(
            engine: engine,
            transport: FirestoreSyncTransport()
        )
    }

    func mount(accountID: String) async throws {
        try await repository.mount(AccountID.parse(accountID))
        await refresh()
    }

    func reconnect() async {
        await repository.reconnect()
        await refresh()
    }

    func enqueue(_ mutation: SyncMutation) async {
        await repository.enqueue(mutation)
        await refresh()
    }

    func reapplyConflict(_ id: OperationID) async {
        await repository.reapplyConflict(id)
        await refresh()
    }

    func logout(action: LogoutPendingAction) async -> LogoutOutcome {
        let outcome = await repository.logout(action: action)
        await refresh()
        return outcome
    }

    private func refresh() async {
        snapshot = await engine.snapshot()
    }
}

private struct FirestoreSyncTransport: SyncTransport {
    func send(
        _ mutation: SyncMutation,
        accountID: AccountID
    ) async -> SyncTransportResult {
        guard let command = try? JSONSerialization.jsonObject(
            with: mutation.payload
        ) as? [String: Any],
            let collection = command["collection"] as? String,
            let documentID = command["documentId"] as? String,
            let payload = command["payload"] as? [String: Any]
        else {
            return .permanentFailure
        }
        do {
            let response = try await Functions.functions()
                .httpsCallable("applyRevisionedOwnerWrite")
                .call([
                    "collection": collection,
                    "documentId": documentID,
                    "expectedRevision": mutation.revision.rawValue,
                    "idempotencyKey": mutation.id.rawValue,
                    "payload": payload
                ])
            guard let result = response.data as? [String: Any],
                  let kind = result["kind"] as? String
            else {
                return .permanentFailure
            }
            switch kind {
            case "applied", "duplicate":
                return .confirmed
            case "conflict":
                return await conflictResult(
                    result: result,
                    accountID: accountID,
                    collection: collection,
                    documentID: documentID
                )
            default:
                return .permanentFailure
            }
        } catch {
            let code = FunctionsErrorCode(rawValue: (error as NSError).code)
            return code == .unavailable || code == .deadlineExceeded
                ? .transientFailure
                : .permanentFailure
        }
    }

    private func conflictResult(
        result: [String: Any],
        accountID: AccountID,
        collection: String,
        documentID: String
    ) async -> SyncTransportResult {
        guard let revision = result["actualRevision"] as? UInt64,
              let parsed = try? Revision.parse(revision),
              let snapshot = try? await Firestore.firestore()
              .collection("users")
              .document(accountID.rawValue)
              .collection(collection)
              .document(documentID)
              .getDocument(),
              let committed = snapshot.data(),
              let payload = try? JSONSerialization.data(
                  withJSONObject: FirestorePayloadJSON.normalize(committed)
              )
        else {
            return .permanentFailure
        }
        return .conflict(
            committedRevision: parsed,
            committedPayload: payload
        )
    }

    func events(
        accountID: AccountID,
        domain: SyncDomain
    ) -> AsyncStream<SyncRemoteEvent> {
        AsyncStream { continuation in
            let listener = Firestore.firestore()
                .collection("users")
                .document(accountID.rawValue)
                .collection(domain.collectionName)
                .addSnapshotListener { snapshot, error in
                    guard error == nil,
                          snapshot?.metadata.isFromCache == false
                    else {
                        return
                    }
                    continuation.yield(
                        SyncRemoteEvent(
                            domain: domain,
                            receivedAt: Date()
                        )
                    )
                }
            let listenerBox = ListenerRegistrationBox(listener)
            continuation.onTermination = { _ in listenerBox.remove() }
        }
    }
}

private extension SyncDomain {
    var collectionName: String {
        switch self {
        case .plants: "personalPlants"
        case .care: "wateringRecords"
        case .inventory: "ownedItems"
        }
    }
}

extension AppSyncRuntime {
    func destroyLocalStore(for accountID: AccountID) async -> Bool {
        let removed = await engine.discardAndUnmount(accountID)
        await refresh()
        return removed
    }
}

private final class ListenerRegistrationBox: @unchecked Sendable {
    private let listener: any ListenerRegistration

    init(_ listener: any ListenerRegistration) {
        self.listener = listener
    }

    func remove() {
        listener.remove()
    }
}
