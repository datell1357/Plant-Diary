import PlanteriorDomain

public enum ProgressionRejection: Equatable, Sendable {
    case foreignOwner
    case receiptMismatch
    case unpublishedReward
    case notEarned
    case xpOverflow
    case revisionOverflow
}
