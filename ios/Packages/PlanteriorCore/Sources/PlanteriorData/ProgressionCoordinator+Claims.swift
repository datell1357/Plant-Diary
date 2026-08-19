import PlanteriorDomain

public extension ProgressionCoordinator {
    static func claim(
        milestoneID: MilestoneID,
        ownerID: AccountID,
        definitions: [MilestoneDefinition],
        snapshot: ProgressionSnapshot
    ) -> ProgressionClaimResult {
        guard ownerID == snapshot.accountID else {
            return .rejected(snapshot: snapshot, reason: .foreignOwner)
        }
        guard let definition = definitions.first(
            where: { $0.id == milestoneID }
        ), definition.publicationState == .public else {
            return .rejected(
                snapshot: snapshot,
                reason: .unpublishedReward
            )
        }
        guard snapshot.earnedMilestoneIDs.contains(milestoneID) else {
            return .rejected(snapshot: snapshot, reason: .notEarned)
        }
        if snapshot.claimedMilestoneIDs.contains(milestoneID) {
            return .alreadyClaimed(snapshot: snapshot)
        }
        guard let revision = try? snapshot.revision.next() else {
            return .rejected(snapshot: snapshot, reason: .revisionOverflow)
        }
        let updated = ProgressionSnapshot(
            accountID: snapshot.accountID,
            totalXP: snapshot.totalXP,
            receipts: snapshot.receipts,
            earnedMilestoneIDs: snapshot.earnedMilestoneIDs,
            claimedMilestoneIDs: stableUnique(
                snapshot.claimedMilestoneIDs + [milestoneID]
            ),
            revision: revision
        )
        return .claimed(snapshot: updated)
    }

    static func state(
        milestoneID: MilestoneID,
        snapshot: ProgressionSnapshot
    ) -> MilestoneState {
        if snapshot.claimedMilestoneIDs.contains(milestoneID) {
            return .claimed
        }
        if snapshot.earnedMilestoneIDs.contains(milestoneID) {
            return .earned
        }
        return .current
    }
}
