import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain

actor CleanupOwnerRecorder {
    private(set) var ownerIDs: [AccountID] = []

    func record(_ ownerID: AccountID) {
        ownerIDs.append(ownerID)
    }
}

actor RecoveryDeletionService: AccountDeletionServicing {
    let now: Int64
    private var workflows: [AccountID: AccountDeletionWorkflow] = [:]

    init(now: Int64) {
        self.now = now
    }

    func preview(ownerID: AccountID) -> AccountDeletionServiceSnapshot {
        AccountDeletionServiceSnapshot(
            scope: scope(ownerID),
            workflow: workflows[ownerID]
        )
    }

    func request(
        ownerID: AccountID,
        scope: AccountDeletionScope
    ) throws -> AccountDeletionWorkflow {
        let workflow = try AccountDeletionWorkflow(
            requestID: DeletionRequestID.parse("recovery-request-1"),
            ownerID: ownerID,
            scope: scope,
            requestedAt: now,
            scheduledAt: now + AccountDeletionPolicy.graceSeconds,
            status: .received
        )
        workflows[ownerID] = workflow
        return workflow
    }

    func cancel(
        ownerID: AccountID,
        workflow: AccountDeletionWorkflow
    ) -> AccountDeletionWorkflow {
        let cancelled = AccountDeletionWorkflow(
            requestID: workflow.requestID,
            ownerID: ownerID,
            scope: workflow.scope,
            requestedAt: workflow.requestedAt,
            scheduledAt: workflow.scheduledAt,
            status: .cancelled
        )
        workflows[ownerID] = cancelled
        return cancelled
    }

    func recover(
        ownerID: AccountID,
        requestID: DeletionRequestID
    ) throws -> AccountDeletionWorkflow {
        guard let workflow = workflows[ownerID], workflow.requestID == requestID else {
            throw CocoaError(.fileReadNoSuchFile)
        }
        return workflow
    }

    func complete(ownerID: AccountID) {
        guard let workflow = workflows[ownerID] else { return }
        workflows[ownerID] = AccountDeletionWorkflow(
            requestID: workflow.requestID,
            ownerID: ownerID,
            scope: workflow.scope,
            requestedAt: workflow.requestedAt,
            scheduledAt: workflow.scheduledAt,
            status: .completed,
            succeededCategories: workflow.scope.categories
        )
    }

    private func scope(_ ownerID: AccountID) -> AccountDeletionScope {
        AccountDeletionScope(
            ownerID: ownerID,
            categories: ["인증 계정", "저장 파일"],
            scopeHash: "scope-\(ownerID.rawValue)"
        )
    }
}
