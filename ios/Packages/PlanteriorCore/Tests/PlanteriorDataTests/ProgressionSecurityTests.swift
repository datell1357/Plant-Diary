import PlanteriorData
import PlanteriorDomain
import Testing

struct ProgressionSecurityTests {
    @Test
    func mismatchedReceiptIsDenied() throws {
        let fixture = try ProgressionFixture()
        let definitions = try fixture.definitions()
        let original = try fixture.event(
            id: "event-minihome-1",
            kind: .miniHomeSave,
            points: 200
        )
        let applied = ProgressionCoordinator.apply(
            event: original,
            definitions: definitions,
            to: fixture.snapshot
        ).snapshot
        let changed = try fixture.event(
            id: "event-minihome-1",
            kind: .miniHomeSave,
            points: 999
        )
        let mismatch = ProgressionCoordinator.apply(
            event: changed,
            definitions: definitions,
            to: applied
        )
        #expect(mismatch.rejection == .receiptMismatch)
        #expect(applied.totalXP == 200)
    }

    @Test
    func overflowingXPIsDeniedWithoutMutation() throws {
        let fixture = try ProgressionFixture()
        let snapshot = ProgressionSnapshot(
            accountID: fixture.accountID,
            totalXP: Int.max,
            receipts: [],
            earnedMilestoneIDs: [],
            claimedMilestoneIDs: [],
            revision: .zero
        )
        let event = try fixture.event(
            id: "event-overflow-1",
            kind: .watering,
            points: 1
        )
        let definitions = try fixture.definitions()
        let result = ProgressionCoordinator.apply(
            event: event,
            definitions: definitions,
            to: snapshot
        )

        #expect(result.rejection == .xpOverflow)
        #expect(result.snapshot.totalXP == Int.max)
    }

    @Test
    func foreignOwnerAndUnpublishedClaimAreDenied() throws {
        let fixture = try ProgressionFixture()
        let definitions = try fixture.definitions()
        let applied = try fixture.appliedSnapshot(
            id: "event-minihome-2",
            kind: .miniHomeSave,
            points: 200
        )
        let milestoneID = try MilestoneID.parse("milestone-registration")
        let hiddenID = try MilestoneID.parse("milestone-hidden")
        let foreign = ProgressionCoordinator.claim(
            milestoneID: milestoneID,
            ownerID: fixture.foreignAccountID,
            definitions: definitions,
            snapshot: applied
        )
        let unpublished = ProgressionCoordinator.claim(
            milestoneID: hiddenID,
            ownerID: fixture.accountID,
            definitions: definitions,
            snapshot: applied
        )
        #expect(foreign.rejection == .foreignOwner)
        #expect(unpublished.rejection == .unpublishedReward)
        #expect(applied.claimedMilestoneIDs.isEmpty)
    }

    @Test
    func claimIsIdempotentAndOfflineProjectionIsPendingOnly() throws {
        let fixture = try ProgressionFixture()
        let definitions = try fixture.definitions()
        let applied = try fixture.appliedSnapshot(
            id: "event-share-1",
            kind: .sharing,
            points: 100
        )
        let milestoneID = try MilestoneID.parse(
            "milestone-registration"
        )
        let claimed = ProgressionCoordinator.claim(
            milestoneID: milestoneID,
            ownerID: fixture.accountID,
            definitions: definitions,
            snapshot: applied
        )
        let repeated = ProgressionCoordinator.claim(
            milestoneID: milestoneID,
            ownerID: fixture.accountID,
            definitions: definitions,
            snapshot: claimed.snapshot
        )
        let pendingID = try OperationID.parse("event-offline-1")
        let projection = ProgressionProjection(
            authoritative: repeated.snapshot,
            pendingEvents: [
                PendingProgressionEvent(
                    id: pendingID,
                    kind: .watering
                )
            ]
        )

        #expect(claimed.isClaimed)
        #expect(repeated.isAlreadyClaimed)
        #expect(projection.serverXP == 100)
        #expect(projection.pendingCount == 1)
    }
}
