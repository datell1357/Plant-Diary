public struct MilestoneDefinition: Codable, Equatable, Sendable {
    public let id: MilestoneID
    public let rewardID: RewardID
    public let thresholdXP: Int
    public let publicationState: PublicationState

    public init(
        id: MilestoneID,
        rewardID: RewardID,
        thresholdXP: Int,
        publicationState: PublicationState
    ) throws {
        guard thresholdXP > 0 else {
            throw ProgressionValidationError.invalidThreshold
        }
        self.id = id
        self.rewardID = rewardID
        self.thresholdXP = thresholdXP
        self.publicationState = publicationState
    }
}

public struct ProgressionSnapshot: Codable, Equatable, Sendable {
    public let accountID: AccountID
    public let totalXP: Int
    public let receipts: [ProgressionReceipt]
    public let earnedMilestoneIDs: [MilestoneID]
    public let claimedMilestoneIDs: [MilestoneID]
    public let revision: Revision

    public init(
        accountID: AccountID,
        totalXP: Int,
        receipts: [ProgressionReceipt],
        earnedMilestoneIDs: [MilestoneID],
        claimedMilestoneIDs: [MilestoneID],
        revision: Revision
    ) {
        self.accountID = accountID
        self.totalXP = totalXP
        self.receipts = receipts
        self.earnedMilestoneIDs = earnedMilestoneIDs
        self.claimedMilestoneIDs = claimedMilestoneIDs
        self.revision = revision
    }

    public static func empty(accountID: AccountID) -> Self {
        Self(
            accountID: accountID,
            totalXP: 0,
            receipts: [],
            earnedMilestoneIDs: [],
            claimedMilestoneIDs: [],
            revision: .zero
        )
    }
}

public enum MilestoneState: String, Codable, Sendable {
    case current = "CURRENT"
    case earned = "EARNED"
    case claimed = "CLAIMED"
}

public struct ProgressionProjection: Equatable, Sendable {
    public let authoritative: ProgressionSnapshot
    public let pendingEvents: [PendingProgressionEvent]

    public init(
        authoritative: ProgressionSnapshot,
        pendingEvents: [PendingProgressionEvent]
    ) {
        self.authoritative = authoritative
        self.pendingEvents = pendingEvents
    }

    public var serverXP: Int {
        authoritative.totalXP
    }

    public var pendingCount: Int {
        pendingEvents.count
    }
}
