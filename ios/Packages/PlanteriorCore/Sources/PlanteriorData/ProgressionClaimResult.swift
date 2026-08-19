import PlanteriorDomain

public enum ProgressionClaimResult: Equatable, Sendable {
    case claimed(snapshot: ProgressionSnapshot)
    case alreadyClaimed(snapshot: ProgressionSnapshot)
    case rejected(
        snapshot: ProgressionSnapshot,
        reason: ProgressionRejection
    )

    public var snapshot: ProgressionSnapshot {
        switch self {
        case let .claimed(snapshot): snapshot
        case let .alreadyClaimed(snapshot): snapshot
        case let .rejected(snapshot, _): snapshot
        }
    }

    public var isClaimed: Bool {
        if case .claimed = self {
            true
        } else {
            false
        }
    }

    public var isAlreadyClaimed: Bool {
        if case .alreadyClaimed = self {
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
