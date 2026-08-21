import Foundation
import PlanteriorDomain

struct PendingAccountDeletion: Equatable {
    let ownerID: AccountID
    let requestID: DeletionRequestID
}

@MainActor
final class PendingAccountDeletionStore {
    static let shared = PendingAccountDeletionStore()

    private let defaults: UserDefaults
    private let key = "account-deletion.pending-recovery"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> PendingAccountDeletion? {
        guard let values = defaults.dictionary(forKey: key),
              let rawOwnerID = values["ownerID"] as? String,
              let rawRequestID = values["requestID"] as? String,
              let ownerID = try? AccountID.parse(rawOwnerID),
              let requestID = try? DeletionRequestID.parse(rawRequestID)
        else {
            return nil
        }
        return PendingAccountDeletion(ownerID: ownerID, requestID: requestID)
    }

    func save(_ workflow: AccountDeletionWorkflow) {
        defaults.set(
            [
                "ownerID": workflow.ownerID.rawValue,
                "requestID": workflow.requestID.rawValue
            ],
            forKey: key
        )
    }

    func clear(matching workflow: AccountDeletionWorkflow) {
        guard load() == PendingAccountDeletion(
            ownerID: workflow.ownerID,
            requestID: workflow.requestID
        ) else {
            return
        }
        defaults.removeObject(forKey: key)
    }
}
