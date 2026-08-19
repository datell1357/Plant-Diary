import PlanteriorDomain

extension MilestoneRepository {
    static func qaDefinitions() throws -> [MilestoneDefinition] {
        try [
            definition(
                id: "registration-1",
                rewardID: "reward-registration",
                threshold: 100,
                publicationState: .public
            ),
            definition(
                id: "watering-1",
                rewardID: "reward-watering",
                threshold: 200,
                publicationState: .public
            ),
            definition(
                id: "minihome-1",
                rewardID: "reward-minihome",
                threshold: 300,
                publicationState: .public
            ),
            definition(
                id: "sharing-1",
                rewardID: "reward-sharing",
                threshold: 500,
                publicationState: .public
            ),
            definition(
                id: "hidden-1",
                rewardID: "reward-hidden",
                threshold: 75,
                publicationState: .draft
            )
        ]
    }

    private static func definition(
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
