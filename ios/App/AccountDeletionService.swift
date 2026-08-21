import PlanteriorData
import PlanteriorDomain

struct AccountDeletionServiceSnapshot: Sendable {
    let scope: AccountDeletionScope
    let workflow: AccountDeletionWorkflow?
}

protocol AccountDeletionServicing: Sendable {
    func preview(ownerID: AccountID) async throws -> AccountDeletionServiceSnapshot
    func request(
        ownerID: AccountID,
        scope: AccountDeletionScope
    ) async throws -> AccountDeletionWorkflow
    func cancel(
        ownerID: AccountID,
        workflow: AccountDeletionWorkflow
    ) async throws -> AccountDeletionWorkflow
    func recover(
        ownerID: AccountID,
        requestID: DeletionRequestID
    ) async throws -> AccountDeletionWorkflow
}

enum AccountDeletionServiceError: Error {
    case recoveryUnavailable
}

struct QAAccountDeletionService: AccountDeletionServicing {
    let now: Int64

    func preview(ownerID: AccountID) -> AccountDeletionServiceSnapshot {
        AccountDeletionServiceSnapshot(
            scope: AccountDeletionScope(
                ownerID: ownerID,
                categories: [
                    "인증 계정", "식물과 기록", "미니홈과 창고",
                    "공유 링크", "알림", "저장 파일"
                ],
                scopeHash: "trusted-scope-v1"
            ),
            workflow: nil
        )
    }

    func request(
        ownerID: AccountID,
        scope: AccountDeletionScope
    ) throws -> AccountDeletionWorkflow {
        try AccountDeletionWorkflow(
            requestID: DeletionRequestID.parse("delete-request-1"),
            ownerID: ownerID,
            scope: scope,
            requestedAt: now,
            scheduledAt: now + AccountDeletionPolicy.graceSeconds,
            status: .received
        )
    }

    func cancel(
        ownerID: AccountID,
        workflow: AccountDeletionWorkflow
    ) -> AccountDeletionWorkflow {
        AccountDeletionWorkflow(
            requestID: workflow.requestID,
            ownerID: ownerID,
            scope: workflow.scope,
            requestedAt: workflow.requestedAt,
            scheduledAt: workflow.scheduledAt,
            status: .cancelled
        )
    }

    func recover(
        ownerID: AccountID,
        requestID: DeletionRequestID
    ) throws -> AccountDeletionWorkflow {
        _ = ownerID
        _ = requestID
        throw AccountDeletionServiceError.recoveryUnavailable
    }
}
