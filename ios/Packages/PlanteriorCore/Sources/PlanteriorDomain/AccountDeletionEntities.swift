public struct AccountDeletionScope: Codable, Equatable, Sendable {
    public let ownerID: AccountID
    public let categories: [String]
    public let scopeHash: String

    public init(
        ownerID: AccountID,
        categories: [String],
        scopeHash: String
    ) {
        self.ownerID = ownerID
        self.categories = categories
        self.scopeHash = scopeHash
    }
}

public struct AccountDeletionWorkflow: Codable, Equatable, Sendable {
    public let requestID: DeletionRequestID
    public let ownerID: AccountID
    public let scope: AccountDeletionScope
    public let requestedAt: Int64
    public let scheduledAt: Int64
    public let status: DeletionStatus
    public let succeededCategories: [String]
    public let failedCategories: [String]

    public init(
        requestID: DeletionRequestID,
        ownerID: AccountID,
        scope: AccountDeletionScope,
        requestedAt: Int64,
        scheduledAt: Int64,
        status: DeletionStatus,
        succeededCategories: [String] = [],
        failedCategories: [String] = []
    ) {
        self.requestID = requestID
        self.ownerID = ownerID
        self.scope = scope
        self.requestedAt = requestedAt
        self.scheduledAt = scheduledAt
        self.status = status
        self.succeededCategories = succeededCategories
        self.failedCategories = failedCategories
    }
}

public enum AccountDeletionDecision: Equatable, Sendable {
    case accepted(AccountDeletionWorkflow)
    case processing(AccountDeletionWorkflow)
    case cancelled(AccountDeletionWorkflow)
    case completed(AccountDeletionWorkflow)
    case partiallyFailed(AccountDeletionWorkflow)
    case failed(AccountDeletionWorkflow)
    case denied
    case tooEarly
    case duplicate(AccountDeletionWorkflow)
}
