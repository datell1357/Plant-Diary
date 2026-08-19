import Foundation
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct AccountDeletionView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.sizeCategory) private var sizeCategory
    @StateObject private var coordinator: AccountDeletionCoordinator

    init(onCompleted: @escaping () async -> [String] = { [] }) {
        let allowsFake: Bool
        #if DEBUG
            allowsFake = ProcessInfo.processInfo.environment[
                "QA_DELETION_FIXTURE"
            ] == "1"
        #else
            allowsFake = false
        #endif
        _coordinator = StateObject(
            wrappedValue: AccountDeletionCoordinator(
                allowsTrustedFake: allowsFake,
                ownerID: try? AccountID.parse("qa-delete-owner"),
                now: 1000,
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
        .task { coordinator.preview() }
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
            PlanteriorPrimaryButton("최근 인증") {
                coordinator.reauthenticate()
            }
            .accessibilityIdentifier("account-deletion.reauthenticate")
            PlanteriorPrimaryButton("최종 삭제 확인") {
                coordinator.request()
            }
            .accessibilityIdentifier("account-deletion.confirm")
            if coordinator.workflow?.status == .received {
                PlanteriorPrimaryButton("삭제 요청 취소") {
                    coordinator.cancel()
                }
                .accessibilityIdentifier("account-deletion.cancel-request")
            }
        }
    }

    private var qaButtons: some View {
        HStack {
            Button("부분 실패") {
                coordinator.simulatePartialFailure()
            }
            .frame(minHeight: PlanteriorControl.minimumTarget)
            .accessibilityIdentifier("account-deletion.qa.partial")
            Button("완료") {
                Task { await coordinator.simulateCompletion() }
            }
            .frame(minHeight: PlanteriorControl.minimumTarget)
            .accessibilityIdentifier("account-deletion.qa.complete")
        }
        .frame(minHeight: PlanteriorControl.minimumTarget)
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
