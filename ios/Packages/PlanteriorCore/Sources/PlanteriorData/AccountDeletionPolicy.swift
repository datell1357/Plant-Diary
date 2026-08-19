import PlanteriorDomain

public struct AccountDeletionRequestInput: Sendable {
    public let requestID: DeletionRequestID
    public let ownerID: AccountID
    public let scope: AccountDeletionScope
    public let now: Int64
    public let reauthenticatedAt: Int64
    public let confirmed: Bool

    public init(
        requestID: DeletionRequestID,
        ownerID: AccountID,
        scope: AccountDeletionScope,
        now: Int64,
        reauthenticatedAt: Int64,
        confirmed: Bool
    ) {
        self.requestID = requestID
        self.ownerID = ownerID
        self.scope = scope
        self.now = now
        self.reauthenticatedAt = reauthenticatedAt
        self.confirmed = confirmed
    }
}

public enum AccountDeletionPolicy {
    public static let graceSeconds: Int64 = 7 * 24 * 60 * 60
    public static let recentAuthenticationSeconds: Int64 = 5 * 60

    public static func request(
        _ input: AccountDeletionRequestInput,
        existing: AccountDeletionWorkflow?
    ) -> AccountDeletionDecision {
        guard input.confirmed,
              input.scope.ownerID == input.ownerID,
              input.reauthenticatedAt <= input.now,
              input.now - input.reauthenticatedAt <=
              recentAuthenticationSeconds
        else {
            return .denied
        }
        if let existing {
            guard existing.ownerID == input.ownerID else {
                return .denied
            }
            return .duplicate(existing)
        }
        return .accepted(
            AccountDeletionWorkflow(
                requestID: input.requestID,
                ownerID: input.ownerID,
                scope: input.scope,
                requestedAt: input.now,
                scheduledAt: input.now + graceSeconds,
                status: .received
            )
        )
    }

    public static func cancel(
        _ workflow: AccountDeletionWorkflow,
        ownerID: AccountID,
        now: Int64
    ) -> AccountDeletionDecision {
        guard workflow.ownerID == ownerID,
              workflow.status == .received,
              now < workflow.scheduledAt
        else {
            return .denied
        }
        return .cancelled(
            replacing(workflow, status: .cancelled)
        )
    }

    public static func execute(
        _ workflow: AccountDeletionWorkflow,
        now: Int64,
        succeeded: [String],
        failed: [String]
    ) -> AccountDeletionDecision {
        guard workflow.status == .processing else { return .denied }
        let status: DeletionStatus = if failed.isEmpty {
            .completed
        } else if succeeded.isEmpty {
            .failed
        } else {
            .partiallyFailed
        }
        let updated = AccountDeletionWorkflow(
            requestID: workflow.requestID,
            ownerID: workflow.ownerID,
            scope: workflow.scope,
            requestedAt: workflow.requestedAt,
            scheduledAt: workflow.scheduledAt,
            status: status,
            succeededCategories: succeeded,
            failedCategories: failed
        )
        switch status {
        case .completed: return .completed(updated)
        case .partiallyFailed: return .partiallyFailed(updated)
        default: return .failed(updated)
        }
    }

    public static func beginProcessing(
        _ workflow: AccountDeletionWorkflow,
        now: Int64
    ) -> AccountDeletionDecision {
        guard workflow.status == .received else { return .denied }
        guard now >= workflow.scheduledAt else { return .tooEarly }
        return .processing(replacing(workflow, status: .processing))
    }

    public static func allowsLocalCleanup(
        _ workflow: AccountDeletionWorkflow
    ) -> Bool {
        workflow.status == .completed &&
            workflow.failedCategories.isEmpty
    }

    private static func replacing(
        _ workflow: AccountDeletionWorkflow,
        status: DeletionStatus
    ) -> AccountDeletionWorkflow {
        AccountDeletionWorkflow(
            requestID: workflow.requestID,
            ownerID: workflow.ownerID,
            scope: workflow.scope,
            requestedAt: workflow.requestedAt,
            scheduledAt: workflow.scheduledAt,
            status: status
        )
    }
}
