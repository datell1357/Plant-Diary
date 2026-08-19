import PlanteriorDomain

public enum ProgressionMutationResult: Equatable, Sendable {
    case applied(
        snapshot: ProgressionSnapshot,
        newlyEarned: [MilestoneID]
    )
    case duplicate(snapshot: ProgressionSnapshot)
    case rejected(
        snapshot: ProgressionSnapshot,
        reason: ProgressionRejection
    )

    public var snapshot: ProgressionSnapshot {
        switch self {
        case let .applied(snapshot, _): snapshot
        case let .duplicate(snapshot): snapshot
        case let .rejected(snapshot, _): snapshot
        }
    }

    public var isDuplicate: Bool {
        if case .duplicate = self {
            true
        } else {
            false
        }
    }

    public var rejection: ProgressionRejection? {
        if case let .rejected(_, reason) = self {
            reason
        } else {
            nil
        }
    }
}
