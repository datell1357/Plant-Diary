import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing

@MainActor
struct AccountDeletionRecoveryTests {
    @Test
    func relaunchWithoutAuthRecoversCompletionAndCleansOnlyPendingOwner() async throws {
        let defaults = try #require(UserDefaults(
            suiteName: "AccountDeletionRecoveryTests-\(UUID().uuidString)"
        ))
        let pendingStore = PendingAccountDeletionStore(defaults: defaults)
        let service = RecoveryDeletionService(now: 1000)
        let ownerA = try AccountID.parse("account-a")
        let ownerB = try AccountID.parse("account-b")
        let recorder = CleanupOwnerRecorder()
        let requester = coordinator(
            ownerID: ownerA,
            service: service,
            pendingStore: pendingStore,
            recorder: recorder
        )

        await requester.preview()
        requester.acceptReauthentication()
        await requester.request()

        let pending = try #require(pendingStore.load())
        #expect(pending.ownerID == ownerA)
        #expect(!AccountDeletionRecoveryRuntime.shouldRefresh(
            pending: pending,
            isSignedIn: true,
            accountID: ownerB
        ))
        let accountB = coordinator(
            ownerID: ownerB,
            service: service,
            pendingStore: pendingStore,
            recorder: recorder
        )
        await accountB.preview()
        #expect(accountB.cleanupCount == 0)
        #expect(pendingStore.load()?.ownerID == ownerA)

        await service.complete(ownerID: ownerA)
        let relaunched = coordinator(
            ownerID: nil,
            service: service,
            pendingStore: pendingStore,
            recorder: recorder
        )
        await relaunched.preview()

        #expect(relaunched.workflow?.status == .completed)
        #expect(relaunched.cleanupCount == 1)
        #expect(await recorder.ownerIDs == [ownerA])
        #expect(pendingStore.load() == nil)
    }

    private func coordinator(
        ownerID: AccountID?,
        service: RecoveryDeletionService,
        pendingStore: PendingAccountDeletionStore,
        recorder: CleanupOwnerRecorder
    ) -> AccountDeletionCoordinator {
        AccountDeletionCoordinator(
            allowsTrustedFake: false,
            ownerID: ownerID,
            now: 1000,
            service: service,
            pendingStore: pendingStore,
            onCompleted: { ownerID in
                await recorder.record(ownerID)
                return Array(AccountDeletionCoordinator.requiredCleanupReceipts)
            }
        )
    }
}

private actor CleanupOwnerRecorder {
    private(set) var ownerIDs: [AccountID] = []

    func record(_ ownerID: AccountID) {
        ownerIDs.append(ownerID)
    }
}

private actor RecoveryDeletionService: AccountDeletionServicing {
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
