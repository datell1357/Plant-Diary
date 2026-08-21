import Foundation
import PlanteriorDomain

struct PendingAccountDeletion: Codable, Equatable {
    let ownerID: AccountID
    let requestID: DeletionRequestID
}

@MainActor
final class PendingAccountDeletionStore {
    static let shared = PendingAccountDeletionStore()

    private let defaults: UserDefaults
    private let key = "account-deletion.pending-recoveries"
    private let legacyKey = "account-deletion.pending-recovery"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> PendingAccountDeletion? {
        loadAll().first
    }

    func loadAll() -> [PendingAccountDeletion] {
        let pending = defaults.data(forKey: key).flatMap {
            try? JSONDecoder().decode([PendingAccountDeletion].self, from: $0)
        }
        if let pending {
            return sorted(pending)
        }
        guard let legacy = loadLegacy() else {
            return []
        }
        persist([legacy])
        defaults.removeObject(forKey: legacyKey)
        return [legacy]
    }

    func save(_ workflow: AccountDeletionWorkflow) {
        let pending = PendingAccountDeletion(
            ownerID: workflow.ownerID,
            requestID: workflow.requestID
        )
        var all = loadAll()
        guard !all.contains(pending) else {
            return
        }
        all.append(pending)
        persist(all)
    }

    func clear(matching workflow: AccountDeletionWorkflow) {
        let matching = PendingAccountDeletion(
            ownerID: workflow.ownerID,
            requestID: workflow.requestID
        )
        persist(loadAll().filter { $0 != matching })
    }

    private func loadLegacy() -> PendingAccountDeletion? {
        guard let values = defaults.dictionary(forKey: legacyKey),
              let rawOwnerID = values["ownerID"] as? String,
              let rawRequestID = values["requestID"] as? String,
              let ownerID = try? AccountID.parse(rawOwnerID),
              let requestID = try? DeletionRequestID.parse(rawRequestID)
        else {
            return nil
        }
        return PendingAccountDeletion(ownerID: ownerID, requestID: requestID)
    }

    private func persist(_ pending: [PendingAccountDeletion]) {
        guard let data = try? JSONEncoder().encode(sorted(pending)) else {
            return
        }
        defaults.set(data, forKey: key)
    }

    private func sorted(
        _ pending: [PendingAccountDeletion]
    ) -> [PendingAccountDeletion] {
        pending.sorted {
            ($0.ownerID.rawValue, $0.requestID.rawValue)
                < ($1.ownerID.rawValue, $1.requestID.rawValue)
        }
    }
}
