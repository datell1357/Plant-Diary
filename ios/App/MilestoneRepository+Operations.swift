import PlanteriorData
import PlanteriorDomain

extension MilestoneRepository {
    func queue(
        eventID: OperationID,
        kind: ProgressionEventKind
    ) -> MilestoneRepositoryOutcome {
        guard allowsLocalAuthoritativeService else {
            return .unavailable
        }
        if !pendingEvents.contains(where: { $0.id == eventID }) {
            pendingEvents.append(
                PendingProgressionEvent(id: eventID, kind: kind)
            )
        }
        persist()
        return .queued
    }

    func reconnect() {
        let queued = pendingEvents
        for pending in queued {
            _ = submit(eventID: pending.id, kind: pending.kind)
        }
        guard let snapshot else { return }
        pendingEvents = ProgressionCoordinator.reconcile(
            authoritative: snapshot,
            pendingEvents: pendingEvents
        ).pendingEvents
        persist()
    }

    func claim(_ milestoneID: MilestoneID) -> MilestoneRepositoryOutcome {
        guard allowsLocalAuthoritativeService,
              let accountID,
              let current = snapshot
        else {
            return .unavailable
        }
        let result = ProgressionCoordinator.claim(
            milestoneID: milestoneID,
            ownerID: accountID,
            definitions: definitions,
            snapshot: current
        )
        snapshot = result.snapshot
        switch result {
        case .claimed:
            persist()
            return .claimed
        case .alreadyClaimed:
            duplicateCount += 1
            persist()
            return .alreadyClaimed
        case let .rejected(_, reason):
            deniedCount += 1
            persist()
            return .denied(reason)
        }
    }

    func reconcile(authoritative: ProgressionSnapshot) {
        guard authoritative.accountID == accountID else {
            deniedCount += 1
            persist()
            return
        }
        guard let current = snapshot,
              authoritative.revision.rawValue >= current.revision.rawValue
        else {
            return
        }
        snapshot = authoritative
        pendingEvents = ProgressionCoordinator.reconcile(
            authoritative: authoritative,
            pendingEvents: pendingEvents
        ).pendingEvents
        persist()
    }
}
