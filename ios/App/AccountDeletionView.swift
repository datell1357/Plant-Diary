import AuthenticationServices
import Foundation
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct AccountDeletionView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.sizeCategory) private var sizeCategory
    @EnvironmentObject private var auth: AuthRuntime
    @StateObject var coordinator: AccountDeletionCoordinator
    #if DEBUG
        @State var recoveryArtifactStatus: String?
    #endif

    init(
        ownerID: AccountID?,
        onCompleted: @escaping (AccountID) async -> [String] = { _ in [] }
    ) {
        let allowsFake: Bool
        #if DEBUG
            allowsFake = ProcessInfo.processInfo.environment[
                "QA_DELETION_FIXTURE"
            ] == "1"
        #else
            allowsFake = false
        #endif
        let now = Int64(Date().timeIntervalSince1970)
        let service: any AccountDeletionServicing = allowsFake
            ? QAAccountDeletionService(now: 1000)
            : FirebaseAccountDeletionService()
        _coordinator = StateObject(
            wrappedValue: AccountDeletionCoordinator(
                allowsTrustedFake: allowsFake,
                ownerID: ownerID,
                now: allowsFake ? 1000 : now,
                service: service,
                onCompleted: onCompleted
            )
        )
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(coordinator.message)
                    .accessibilityIdentifier("account-deletion.status")
                scopeCard
                actionButtons
                #if DEBUG
                    qaButtons
                    if let recoveryArtifactStatus {
                        Text(recoveryArtifactStatus)
                            .accessibilityIdentifier(
                                "account-deletion.artifact-\(recoveryArtifactStatus)"
                            )
                    }
                #endif
                Text("로컬 정리 \(coordinator.cleanupCount)회")
                    .accessibilityIdentifier(
                        "account-deletion.cleanup-count"
                    )
                Text("정리 영수증 \(coordinator.cleanupReceipts.count)개")
                    .accessibilityIdentifier(
                        "account-deletion.cleanup-receipts"
                    )
                if !failedCategories.isEmpty {
                    Text(
                        "실패 범위 · \(failedCategories.joined(separator: ", "))"
                    )
                    .accessibilityIdentifier(
                        "account-deletion.failed-scope"
                    )
                }
            }
            .padding(20)
        }
        .background(PlanteriorPalette.canvas.color)
        .environment(\.sizeCategory, effectiveSizeCategory)
        .navigationTitle("계정 삭제")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("닫기") { dismiss() }
            }
        }
        .accessibilityIdentifier("account-deletion.screen")
        .task { await coordinator.preview() }
        #if DEBUG
            .onChange(of: coordinator.cleanupCount) { _, cleanupCount in
                guard cleanupCount == 1 else { return }
                Task { await writeRecoveryArtifact() }
            }
        #endif
    }

    private var failedCategories: [String] {
        coordinator.workflow?.failedCategories ?? []
    }

    private var scopeCard: some View {
        PlanteriorCard {
            VStack(alignment: .leading, spacing: 8) {
                Text("서버 계산 삭제 범위")
                    .font(PlanteriorTypography.sectionTitle)
                ForEach(coordinator.scope?.categories ?? [], id: \.self) {
                    Text("· \($0)")
                }
            }
        }
        .accessibilityIdentifier("account-deletion.scope")
    }

    private var actionButtons: some View {
        VStack(spacing: 12) {
            reauthenticationControl
            PlanteriorPrimaryButton("최종 삭제 확인") {
                Task { await coordinator.request() }
            }
            .accessibilityIdentifier("account-deletion.confirm")
            if coordinator.workflow?.status == .received {
                PlanteriorPrimaryButton("삭제 요청 취소") {
                    Task { await coordinator.cancel() }
                }
                .accessibilityIdentifier("account-deletion.cancel-request")
            }
        }
    }

    @ViewBuilder
    private var reauthenticationControl: some View {
        if coordinator.allowsTrustedFake {
            PlanteriorPrimaryButton("최근 인증") {
                coordinator.reauthenticate()
            }
            .accessibilityIdentifier("account-deletion.reauthenticate")
        } else if auth.sessionProvider == .apple {
            SignInWithAppleButton(.continue) { request in
                auth.beginApple(request)
            } onCompletion: { result in
                Task {
                    let succeeded = await auth.reauthenticateApple(result)
                    updateReauthentication(succeeded)
                }
            }
            .signInWithAppleButtonStyle(.black)
            .frame(minHeight: PlanteriorControl.primaryButtonHeight)
            .clipShape(Capsule())
            .accessibilityIdentifier("account-deletion.reauthenticate")
        } else {
            PlanteriorPrimaryButton("Google로 최근 인증") {
                guard let controller = UIApplication.shared
                    .planteriorTopViewController else { return }
                Task {
                    let succeeded = await auth.reauthenticateGoogle(
                        presenting: controller
                    )
                    updateReauthentication(succeeded)
                }
            }
            .accessibilityIdentifier("account-deletion.reauthenticate")
        }
    }

    private func updateReauthentication(_ succeeded: Bool) {
        if succeeded {
            coordinator.acceptReauthentication()
        } else {
            coordinator.reportReauthenticationFailure()
        }
    }

    private var effectiveSizeCategory: ContentSizeCategory {
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_SETTINGS_SIZE_CATEGORY"
            ] == "AX5" {
                return .accessibilityExtraExtraExtraLarge
            }
        #endif
        return sizeCategory
    }
}
