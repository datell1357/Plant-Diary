import Foundation
import PlanteriorDomain
import Testing

struct ProgressionContractTests {
    @Test
    func eventKindsRoundTripAndRejectUnknownValues() throws {
        let encoder = JSONEncoder()
        let decoder = JSONDecoder()

        for kind in ProgressionEventKind.allCases {
            let data = try encoder.encode(kind)
            #expect(
                try decoder.decode(
                    ProgressionEventKind.self,
                    from: data
                ) == kind
            )
        }
        let unknown = Data("\"PURCHASE\"".utf8)
        #expect(throws: DecodingError.self) {
            try decoder.decode(ProgressionEventKind.self, from: unknown)
        }
    }

    @Test
    func invalidXPAndThresholdFailClosed() throws {
        let accountID = try AccountID.parse("progress-account")
        let eventID = try OperationID.parse("progress-event-1")
        let now = try Instant.parse("2026-08-11T00:00:00Z")
        let milestoneID = try MilestoneID.parse("milestone-invalid")
        let rewardID = try RewardID.parse("reward-invalid")

        #expect(throws: ProgressionValidationError.invalidXP) {
            try ApprovedProgressionEvent(
                id: eventID,
                ownerID: accountID,
                kind: .registration,
                experiencePoints: 0,
                approvedAt: now
            )
        }
        #expect(throws: ProgressionValidationError.invalidThreshold) {
            try MilestoneDefinition(
                id: milestoneID,
                rewardID: rewardID,
                thresholdXP: 0,
                publicationState: .public
            )
        }
    }
}
