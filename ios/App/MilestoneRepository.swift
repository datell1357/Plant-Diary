import Combine
import Foundation
import PlanteriorData
import PlanteriorDomain

enum MilestoneRepositoryOutcome: Equatable {
    case applied
    case duplicate
    case claimed
    case alreadyClaimed
    case denied(ProgressionRejection)
    case queued
    case unavailable
}

@MainActor
final class MilestoneRepository: ObservableObject {
    @Published var definitions: [MilestoneDefinition] = []
    @Published var snapshot: ProgressionSnapshot?
    @Published var pendingEvents: [PendingProgressionEvent] = []
    @Published var duplicateCount = 0
    @Published var deniedCount = 0

    let defaults: UserDefaults
    let now: Instant?
    let allowsLocalAuthoritativeService: Bool
    var accountID: AccountID?

    init(
        defaults: UserDefaults = .standard,
        now: Instant?,
        allowsLocalAuthoritativeService: Bool
    ) {
        self.defaults = defaults
        self.now = now
        self.allowsLocalAuthoritativeService =
            allowsLocalAuthoritativeService
    }

    func mount(accountID: AccountID?) {
        self.accountID = accountID
        guard allowsLocalAuthoritativeService, let accountID else {
            clear()
            return
        }
        restore(accountID: accountID)
    }

    private func restore(accountID: AccountID) {
        guard let data = defaults.data(forKey: persistenceKey),
              let state = try? JSONDecoder().decode(
                  MilestonePersistedState.self,
                  from: data
              )
        else {
            snapshot = .empty(accountID: accountID)
            definitions = []
            pendingEvents = []
            duplicateCount = 0
            deniedCount = 0
            return
        }
        definitions = state.definitions
        snapshot = state.snapshot
        pendingEvents = state.pendingEvents
        duplicateCount = state.duplicateCount
        deniedCount = state.deniedCount
    }

    func submit(
        eventID: OperationID,
        kind: ProgressionEventKind,
        ownerID: AccountID? = nil
    ) -> MilestoneRepositoryOutcome {
        guard allowsLocalAuthoritativeService,
              let accountID,
              let now,
              let current = snapshot,
              let event = try? ApprovedProgressionEvent(
                  id: eventID,
                  ownerID: ownerID ?? accountID,
                  kind: kind,
                  experiencePoints: trustedXP(for: kind),
                  approvedAt: now
              )
        else {
            return .unavailable
        }
        let result = ProgressionCoordinator.apply(
            event: event,
            definitions: definitions,
            to: current
        )
        return handle(result)
    }

    private func handle(
        _ result: ProgressionMutationResult
    ) -> MilestoneRepositoryOutcome {
        snapshot = result.snapshot
        switch result {
        case .applied:
            persist()
            return .applied
        case .duplicate:
            duplicateCount += 1
            persist()
            return .duplicate
        case let .rejected(_, reason):
            deniedCount += 1
            persist()
            return .denied(reason)
        }
    }

    private func trustedXP(for kind: ProgressionEventKind) -> Int {
        switch kind {
        case .registration: 50
        case .watering: 100
        case .miniHomeSave: 150
        case .sharing: 200
        }
    }

    private func clear() {
        definitions = []
        snapshot = nil
        pendingEvents = []
        duplicateCount = 0
        deniedCount = 0
    }
}
