import Foundation
import PlanteriorDomain

extension MilestoneRepository {
    var persistenceKey: String {
        "progression.\(accountID?.rawValue ?? "signed-out").snapshot"
    }

    @discardableResult
    func persist() -> Bool {
        guard let snapshot,
              let data = try? JSONEncoder().encode(
                  MilestonePersistedState(
                      definitions: definitions,
                      snapshot: snapshot,
                      pendingEvents: pendingEvents,
                      duplicateCount: duplicateCount,
                      deniedCount: deniedCount
                  )
              )
        else {
            return false
        }
        defaults.set(data, forKey: persistenceKey)
        return true
    }
}

struct MilestonePersistedState: Codable {
    let definitions: [MilestoneDefinition]
    let snapshot: ProgressionSnapshot
    let pendingEvents: [PendingProgressionEvent]
    let duplicateCount: Int
    let deniedCount: Int
}
