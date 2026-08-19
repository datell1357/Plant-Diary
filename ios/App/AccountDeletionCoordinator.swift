import Combine
import PlanteriorData
import PlanteriorDomain

@MainActor
final class AccountDeletionCoordinator: ObservableObject {
    @Published private(set) var scope: AccountDeletionScope?
    @Published private(set) var workflow: AccountDeletionWorkflow?
    @Published private(set) var message = "삭제 범위를 확인하세요"
    @Published private(set) var reauthenticated = false
    @Published private(set) var requestCount = 0
    @Published private(set) var cleanupCount = 0
    @Published private(set) var cleanupReceipts: [String] = []

    let allowsTrustedFake: Bool
    let ownerID: AccountID?
    let now: Int64
    let onCompleted: () async -> [String]

    init(
        allowsTrustedFake: Bool,
        ownerID: AccountID?,
        now: Int64,
        onCompleted: @escaping () async -> [String] = { [] }
    ) {
        self.allowsTrustedFake = allowsTrustedFake
        self.ownerID = ownerID
        self.now = now
        self.onCompleted = onCompleted
    }

    func preview() {
        #if DEBUG
            guard allowsTrustedFake, let ownerID else {
                message = "삭제 서버 연동 준비 중"
                return
            }
            scope = AccountDeletionScope(
                ownerID: ownerID,
                categories: [
                    "인증 계정", "식물과 기록", "미니홈과 창고",
                    "공유 링크", "알림", "저장 파일"
                ],
                scopeHash: "trusted-scope-v1"
            )
            message = "서버 계산 삭제 범위 확인됨"
        #else
            message = "삭제 서버 연동 준비 중"
        #endif
    }

    func reauthenticate() {
        guard allowsTrustedFake else {
            message = "최근 인증을 확인할 수 없음"
            return
        }
        reauthenticated = true
        message = "최근 인증 완료"
    }

    func request() {
        guard let ownerID, let scope else { return }
        let requestID = try? DeletionRequestID.parse("delete-request-1")
        guard let requestID else { return }
        let decision = AccountDeletionPolicy.request(
            AccountDeletionRequestInput(
                requestID: requestID,
                ownerID: ownerID,
                scope: scope,
                now: now,
                reauthenticatedAt: reauthenticated ? now : 0,
                confirmed: true
            ),
            existing: workflow
        )
        apply(decision)
    }

    func cancel() {
        guard let workflow, let ownerID else { return }
        apply(
            AccountDeletionPolicy.cancel(
                workflow,
                ownerID: ownerID,
                now: now + 60
            )
        )
    }

    func simulatePartialFailure() {
        guard let workflow else { return }
        guard case let .processing(processing) =
            AccountDeletionPolicy.beginProcessing(
                workflow,
                now: workflow.scheduledAt
            )
        else {
            return
        }
        apply(
            AccountDeletionPolicy.execute(
                processing,
                now: processing.scheduledAt,
                succeeded: ["firestore"],
                failed: ["저장 파일"]
            )
        )
    }

    func simulateCompletion() async {
        guard let workflow, let scope else { return }
        guard case let .processing(processing) =
            AccountDeletionPolicy.beginProcessing(
                workflow,
                now: workflow.scheduledAt
            )
        else {
            return
        }
        let decision = AccountDeletionPolicy.execute(
            processing,
            now: processing.scheduledAt,
            succeeded: scope.categories,
            failed: []
        )
        guard case let .completed(value) = decision else { return }
        self.workflow = value
        cleanupReceipts = await onCompleted()
        cleanupCount = cleanupReceipts.count == 7 ? 1 : 0
        message = cleanupCount == 1
            ? "삭제 완료 · 로컬 정리 승인됨"
            : "삭제 완료 · 로컬 정리 실패"
    }

    private func apply(_ decision: AccountDeletionDecision) {
        switch decision {
        case let .accepted(value):
            workflow = value
            requestCount += 1
            message = "삭제 요청 접수됨 · 7일 유예"
        case let .duplicate(value):
            workflow = value
            message = "기존 삭제 요청 유지됨"
        case let .cancelled(value):
            workflow = value
            message = "삭제 요청 취소됨"
        case let .processing(value):
            workflow = value
            message = "삭제 처리 중"
        case let .partiallyFailed(value):
            workflow = value
            message = "일부 삭제 실패 · 계정 유지"
        case .completed:
            message = "삭제 완료 영수증 처리 중"
        case let .failed(value):
            workflow = value
            message = "삭제 실패 · 계정 유지"
        default:
            message = "삭제 요청을 진행할 수 없음"
        }
    }
}
