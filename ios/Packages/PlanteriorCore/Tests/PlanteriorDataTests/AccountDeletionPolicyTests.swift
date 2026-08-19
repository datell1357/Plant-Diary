import PlanteriorData
import PlanteriorDomain
import Testing

struct AccountDeletionPolicyTests {
    @Test
    func requestRequiresOwnerRecentReauthAndConfirmation() throws {
        let fixture = try DeletionFixture()
        #expect(
            AccountDeletionPolicy.request(
                fixture.requestInput(ownerID: fixture.ownerID),
                existing: nil
            ) == .accepted(fixture.workflow)
        )
        #expect(
            AccountDeletionPolicy.request(
                fixture.requestInput(ownerID: fixture.foreignID),
                existing: nil
            ) == .denied
        )
    }

    @Test
    func graceCancellationIdempotencyAndExecutionAreExact() throws {
        let fixture = try DeletionFixture()
        #expect(
            AccountDeletionPolicy.beginProcessing(
                fixture.workflow,
                now: fixture.workflow.scheduledAt - 1
            ) == .tooEarly
        )
        #expect(
            AccountDeletionPolicy.cancel(
                fixture.workflow,
                ownerID: fixture.ownerID,
                now: fixture.workflow.scheduledAt - 1
            ).workflow?.status == .cancelled
        )
        #expect(
            AccountDeletionPolicy.request(
                fixture.requestInput(ownerID: fixture.ownerID),
                existing: fixture.workflow
            ) == .duplicate(fixture.workflow)
        )
    }

    @Test
    func cleanupIsCompletionOnlyAndPartialFailureStaysDistinct() throws {
        let fixture = try DeletionFixture()
        let processingDecision = AccountDeletionPolicy.beginProcessing(
            fixture.workflow,
            now: fixture.workflow.scheduledAt
        )
        let processing = try #require(processingDecision.workflow)
        let partial = AccountDeletionPolicy.execute(
            processing,
            now: processing.scheduledAt,
            succeeded: ["firestore"],
            failed: ["storage"]
        ).workflow
        let completed = AccountDeletionPolicy.execute(
            processing,
            now: processing.scheduledAt,
            succeeded: fixture.scope.categories,
            failed: []
        ).workflow
        let partialWorkflow = try #require(partial)
        let completedWorkflow = try #require(completed)

        #expect(partial?.status == .partiallyFailed)
        #expect(!AccountDeletionPolicy.allowsLocalCleanup(partialWorkflow))
        #expect(completed?.status == .completed)
        #expect(AccountDeletionPolicy.allowsLocalCleanup(completedWorkflow))
    }
}

private extension AccountDeletionDecision {
    var workflow: AccountDeletionWorkflow? {
        switch self {
        case let .accepted(value),
             let .processing(value),
             let .cancelled(value),
             let .completed(value),
             let .partiallyFailed(value),
             let .failed(value),
             let .duplicate(value):
            value
        default:
            nil
        }
    }
}

private struct DeletionFixture {
    let ownerID: AccountID
    let foreignID: AccountID
    let requestID: DeletionRequestID
    let scope: AccountDeletionScope
    let workflow: AccountDeletionWorkflow

    init() throws {
        ownerID = try AccountID.parse("delete-owner")
        foreignID = try AccountID.parse("delete-foreign")
        requestID = try DeletionRequestID.parse("delete-request")
        scope = AccountDeletionScope(
            ownerID: ownerID,
            categories: ["auth", "firestore", "storage"],
            scopeHash: "scope-hash"
        )
        workflow = AccountDeletionWorkflow(
            requestID: requestID,
            ownerID: ownerID,
            scope: scope,
            requestedAt: 1000,
            scheduledAt: 1000 + AccountDeletionPolicy.graceSeconds,
            status: .received
        )
    }

    func requestInput(
        ownerID: AccountID
    ) -> AccountDeletionRequestInput {
        AccountDeletionRequestInput(
            requestID: requestID,
            ownerID: ownerID,
            scope: scope,
            now: 1000,
            reauthenticatedAt: 1000,
            confirmed: true
        )
    }
}
