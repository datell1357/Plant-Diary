import Foundation
import PlanteriorDomain

extension MilestoneRepository {
    func seedQAIfNeeded(processInfo: ProcessInfo = .processInfo) {
        #if DEBUG
            guard allowsLocalAuthoritativeService, let accountID else {
                return
            }
            resetQAIfNeeded(processInfo: processInfo)
            guard processInfo.environment["QA_PROGRESS_FIXTURE"] == "1",
                  definitions.isEmpty,
                  let state = try? Self.qaState(
                      accountID: accountID
                  )
            else {
                return
            }
            definitions = state.definitions
            snapshot = state.snapshot
            pendingEvents = state.pendingEvents
            duplicateCount = state.duplicateCount
            deniedCount = state.deniedCount
            persist()
        #endif
    }

    private func resetQAIfNeeded(processInfo: ProcessInfo) {
        guard let accountID,
              let token = processInfo.environment[
                  "QA_PROGRESS_RESET_TOKEN"
              ],
              defaults.string(forKey: "qa.progress.reset-token") != token
        else {
            return
        }
        defaults.removeObject(forKey: persistenceKey)
        defaults.set(token, forKey: "qa.progress.reset-token")
        definitions = []
        snapshot = .empty(accountID: accountID)
        pendingEvents = []
        duplicateCount = 0
        deniedCount = 0
    }

    private static func qaState(
        accountID: AccountID
    ) throws -> MilestonePersistedState {
        let definitions = try qaDefinitions()
        let scenario = ProcessInfo.processInfo.environment[
            "QA_PROGRESS_SCENARIO"
        ] ?? "current"
        let registrationID = try MilestoneID.parse("registration-1")
        let earned = scenario == "earned" || scenario == "claimed"
            ? [registrationID]
            : []
        let claimed = scenario == "claimed" ? [registrationID] : []
        let snapshot = ProgressionSnapshot(
            accountID: accountID,
            totalXP: earned.isEmpty ? 50 : 100,
            receipts: [],
            earnedMilestoneIDs: earned,
            claimedMilestoneIDs: claimed,
            revision: .zero
        )
        let pending = try qaPendingEvents()
        return MilestonePersistedState(
            definitions: definitions,
            snapshot: snapshot,
            pendingEvents: pending,
            duplicateCount: 0,
            deniedCount: 0
        )
    }

    private static func qaPendingEvents() throws -> [PendingProgressionEvent] {
        let shouldQueue = ProcessInfo.processInfo.environment[
            "QA_PROGRESS_OFFLINE_QUEUED"
        ] == "1"
        guard shouldQueue else {
            return []
        }
        let eventID = try OperationID.parse("todo16-offline-1")
        return [
            PendingProgressionEvent(
                id: eventID,
                kind: .watering
            )
        ]
    }
}
