import PlanteriorDesignSystem
import SwiftUI

extension MiniHomeEditorView {
    var roomName: Binding<String> {
        Binding(
            get: { store.draft?.name ?? "" },
            set: { store.renameDraft($0) }
        )
    }

    var stateLabel: String {
        switch store.state {
        case .idle: "편집 중"
        case .saved: "저장 완료"
        case .failed: "저장 실패"
        case let .conflicted(serverRevision):
            "충돌 · 서버 \(serverRevision)판"
        }
    }

    @ViewBuilder
    var stateMessage: some View {
        switch store.state {
        case .saved:
            EmptyView()
        case .failed:
            Text("저장하지 못했어요. 초안은 그대로 남아 있어요.")
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("minihome.save-error")
        case let .conflicted(serverRevision):
            Button("충돌 해결 · 서버 \(serverRevision)판") {
                showsConflictPrompt = true
            }
            .frame(minHeight: PlanteriorControl.minimumTarget)
            .accessibilityIdentifier("minihome.conflict")
        case .idle:
            EmptyView()
        }
    }
}
