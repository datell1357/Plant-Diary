import PlanteriorDesignSystem
import SwiftUI

/// Save/conflict state and the registered-plant entry point. The Figma frame
/// keeps the room dominant, so this strip stays compact and only grows when a
/// failure or conflict has something to say.
struct MiniHomeEditorStatusStrip: View {
    let stateLabel: String
    let errorMessage: String?
    let conflictState: MiniHomeStoreState
    let addPlant: () -> Void
    let resolveConflict: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
            PlanteriorSecondaryButton("식물 추가", action: addPlant)
                .accessibilityIdentifier("minihome.add-plant")
            if let errorMessage {
                Text(errorMessage)
                    .font(PlanteriorTypography.supporting)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .accessibilityIdentifier("minihome.save-error")
            }
            Text(stateLabel)
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("minihome.state")
            stateMessage
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var stateMessage: some View {
        switch conflictState {
        case .failed:
            Text("저장하지 못했어요. 초안은 그대로 남아 있어요.")
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("minihome.save-error")
        case let .conflicted(serverRevision):
            Button("충돌 해결 · 서버 \(serverRevision)판", action: resolveConflict)
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.accent.color)
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .accessibilityIdentifier("minihome.conflict")
        case .idle, .saved:
            EmptyView()
        }
    }
}
