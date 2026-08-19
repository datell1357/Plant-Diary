import PlanteriorDomain

public enum ProgressionCoordinator {
    public static func apply(
        event: ApprovedProgressionEvent,
        definitions: [MilestoneDefinition],
        to snapshot: ProgressionSnapshot
    ) -> ProgressionMutationResult {
        guard event.ownerID == snapshot.accountID else {
            return .rejected(snapshot: snapshot, reason: .foreignOwner)
        }
        if let receipt = snapshot.receipts.first(
            where: { $0.eventID == event.id }
        ) {
            return receipt.fingerprint == event.fingerprint
                ? .duplicate(snapshot: snapshot)
                : .rejected(snapshot: snapshot, reason: .receiptMismatch)
        }
        guard let revision = try? snapshot.revision.next() else {
            return .rejected(snapshot: snapshot, reason: .revisionOverflow)
        }
        guard event.experiencePoints <= Int.max - snapshot.totalXP else {
            return .rejected(snapshot: snapshot, reason: .xpOverflow)
        }
        let nextXP = snapshot.totalXP + event.experiencePoints
        let publicDefinitions = definitions.filter {
            $0.publicationState == .public
        }
        let newlyEarned = publicDefinitions
            .filter {
                $0.thresholdXP <= nextXP &&
                    !snapshot.earnedMilestoneIDs.contains($0.id)
            }
            .sorted { $0.thresholdXP < $1.thresholdXP }
            .map(\.id)
        let earned = stableUnique(
            snapshot.earnedMilestoneIDs + newlyEarned
        )
        let updated = ProgressionSnapshot(
            accountID: snapshot.accountID,
            totalXP: nextXP,
            receipts: snapshot.receipts + [
                ProgressionReceipt(
                    eventID: event.id,
                    fingerprint: event.fingerprint
                )
            ],
            earnedMilestoneIDs: earned,
            claimedMilestoneIDs: snapshot.claimedMilestoneIDs,
            revision: revision
        )
        return .applied(snapshot: updated, newlyEarned: newlyEarned)
    }

    static func stableUnique<T: Hashable>(_ values: [T]) -> [T] {
        var seen: Set<T> = []
        var result: [T] = []
        for value in values where seen.insert(value).inserted {
            result.append(value)
        }
        return result
    }
}
