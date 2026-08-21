import Combine
import Foundation
import PlanteriorData
import PlanteriorDomain

@MainActor
final class AccountDeletionCoordinator: ObservableObject {
    static let requiredCleanupReceipts = Set([
        "auth", "keychain", "swiftdata", "sync", "userdefaults",
        "notifications", "media", "routes"
    ])

    @Published private(set) var scope: AccountDeletionScope?
    @Published private(set) var workflow: AccountDeletionWorkflow?
    @Published private(set) var message = "삭제 범위를 확인하세요"
    @Published private(set) var reauthenticated = false
    @Published private(set) var requestCount = 0
    @Published private(set) var cleanupCount = 0
    @Published private(set) var cleanupReceipts: [String] = []

    let allowsTrustedFake: Bool
    private let ownerID: AccountID?
    private let service: any AccountDeletionServicing
    private let pendingStore: PendingAccountDeletionStore
    private let onCompleted: (AccountID) async -> [String]

    init(
        allowsTrustedFake: Bool,
        ownerID: AccountID?,
        now: Int64,
        service: (any AccountDeletionServicing)? = nil,
        pendingStore: PendingAccountDeletionStore = .shared,
        onCompleted: @escaping (AccountID) async -> [String] = { _ in [] }
    ) {
        self.allowsTrustedFake = allowsTrustedFake
        self.ownerID = ownerID
        self.service = service ?? (allowsTrustedFake
            ? QAAccountDeletionService(now: now)
            : FirebaseAccountDeletionService())
        self.pendingStore = pendingStore
        self.onCompleted = onCompleted
    }

    func preview() async {
        guard let ownerID else {
            await recoverPendingWorkflow()
            return
        }
        do {
            let snapshot = try await service.preview(ownerID: ownerID)
            guard snapshot.scope.ownerID == ownerID else {
                message = "삭제 범위 소유자가 일치하지 않음"
                return
            }
            scope = snapshot.scope
            workflow = snapshot.workflow
            if let workflow = snapshot.workflow {
                rememberPending(workflow)
            }
            message = "서버 계산 삭제 범위 확인됨"
            await finalizeIfCompleted()
        } catch {
            message = "삭제 범위를 불러오지 못함"
        }
    }

    func reauthenticate() {
        guard allowsTrustedFake else {
            message = "소셜 계정으로 다시 인증하세요"
            return
        }
        acceptReauthentication()
    }

    func acceptReauthentication() {
        reauthenticated = true
        message = "최근 인증 완료"
    }

    func reportReauthenticationFailure() {
        reauthenticated = false
        message = "최근 인증을 확인할 수 없음"
    }

    func request() async {
        guard reauthenticated, let ownerID, let scope else {
            message = "최근 인증 후 삭제를 확인하세요"
            return
        }
        do {
            workflow = try await service.request(ownerID: ownerID, scope: scope)
            if let workflow {
                rememberPending(workflow)
            }
            requestCount += 1
            message = "삭제 요청 접수됨 · 7일 유예"
            await finalizeIfCompleted()
        } catch {
            message = "삭제 요청을 접수하지 못함"
        }
    }

    func cancel() async {
        guard let ownerID, let workflow else { return }
        do {
            self.workflow = try await service.cancel(
                ownerID: ownerID,
                workflow: workflow
            )
            if let cancelled = self.workflow {
                pendingStore.clear(matching: cancelled)
            }
            message = "삭제 요청 취소됨"
        } catch {
            message = "삭제 요청을 취소하지 못함"
        }
    }

    func simulatePartialFailure() {
        guard allowsTrustedFake, let workflow else { return }
        self.workflow = AccountDeletionWorkflow(
            requestID: workflow.requestID,
            ownerID: workflow.ownerID,
            scope: workflow.scope,
            requestedAt: workflow.requestedAt,
            scheduledAt: workflow.scheduledAt,
            status: .partiallyFailed,
            succeededCategories: ["firestore"],
            failedCategories: ["저장 파일"]
        )
        message = "일부 삭제 실패 · 계정 유지"
    }

    func simulateCompletion() async {
        guard allowsTrustedFake, let workflow else { return }
        self.workflow = AccountDeletionWorkflow(
            requestID: workflow.requestID,
            ownerID: workflow.ownerID,
            scope: workflow.scope,
            requestedAt: workflow.requestedAt,
            scheduledAt: workflow.scheduledAt,
            status: .completed,
            succeededCategories: workflow.scope.categories
        )
        await finalizeIfCompleted()
    }

    private func recoverPendingWorkflow() async {
        guard let pending = pendingStore.load() else {
            message = "인증된 계정을 확인할 수 없음"
            return
        }
        do {
            let recovered = try await service.recover(
                ownerID: pending.ownerID,
                requestID: pending.requestID
            )
            guard recovered.ownerID == pending.ownerID,
                  recovered.requestID == pending.requestID
            else {
                message = "삭제 요청 소유자가 일치하지 않음"
                return
            }
            scope = recovered.scope
            workflow = recovered
            await finalizeIfCompleted()
        } catch {
            message = "삭제 상태를 복구하지 못함"
        }
    }

    private func rememberPending(_ workflow: AccountDeletionWorkflow) {
        guard !allowsTrustedFake else { return }
        if workflow.status == .cancelled {
            pendingStore.clear(matching: workflow)
        } else {
            pendingStore.save(workflow)
        }
    }

    private func finalizeIfCompleted() async {
        guard let workflow,
              AccountDeletionPolicy.allowsLocalCleanup(workflow),
              cleanupCount == 0 else { return }
        cleanupReceipts = await onCompleted(workflow.ownerID)
        cleanupCount = Set(cleanupReceipts) == Self.requiredCleanupReceipts ? 1 : 0
        if cleanupCount == 1 {
            pendingStore.clear(matching: workflow)
        }
        message = cleanupCount == 1
            ? "삭제 완료 · 로컬 정리 승인됨"
            : "삭제 완료 · 로컬 정리 실패"
    }
}
