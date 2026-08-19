import PlanteriorData
import PlanteriorDomain

struct ProgressionFixture {
    let accountID: AccountID
    let foreignAccountID: AccountID
    let snapshot: ProgressionSnapshot
    let now: Instant

    init() throws {
        accountID = try AccountID.parse("account-a")
        foreignAccountID = try AccountID.parse("account-b")
        snapshot = ProgressionSnapshot.empty(accountID: accountID)
        now = try Instant.parse("2026-08-11T00:00:00Z")
    }

    func event(
        id: String,
        kind: ProgressionEventKind,
        points: Int
    ) throws -> ApprovedProgressionEvent {
        let eventID = try OperationID.parse(id)
        return try ApprovedProgressionEvent(
            id: eventID,
            ownerID: accountID,
            kind: kind,
            experiencePoints: points,
            approvedAt: now
        )
    }

    func definitions() throws -> [MilestoneDefinition] {
        try [
            definition(
                id: "milestone-registration",
                rewardID: "reward-registration",
                threshold: 100,
                publicationState: .public
            ),
            definition(
                id: "milestone-watering",
                rewardID: "reward-watering",
                threshold: 200,
                publicationState: .public
            ),
            definition(
                id: "milestone-hidden",
                rewardID: "reward-hidden",
                threshold: 50,
                publicationState: .draft
            )
        ]
    }

    func appliedSnapshot(
        id: String,
        kind: ProgressionEventKind,
        points: Int
    ) throws -> ProgressionSnapshot {
        let approvedEvent = try event(id: id, kind: kind, points: points)
        let milestoneDefinitions = try definitions()
        return ProgressionCoordinator.apply(
            event: approvedEvent,
            definitions: milestoneDefinitions,
            to: snapshot
        ).snapshot
    }

    private func definition(
        id: String,
        rewardID: String,
        threshold: Int,
        publicationState: PublicationState
    ) throws -> MilestoneDefinition {
        let milestoneID = try MilestoneID.parse(id)
        let parsedRewardID = try RewardID.parse(rewardID)
        return try MilestoneDefinition(
            id: milestoneID,
            rewardID: parsedRewardID,
            thresholdXP: threshold,
            publicationState: publicationState
        )
    }
}
