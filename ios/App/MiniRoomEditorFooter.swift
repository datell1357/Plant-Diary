import PlanteriorDesignSystem
import SwiftUI

/// Figma `action-footer`: Surface strip with a hairline top, an undo action at
/// the leading edge, and a reset action at the trailing edge. Both are draft-only
/// operations and never touch the committed room.
struct MiniRoomEditorFooter: View {
    let canUndo: Bool
    let canReset: Bool
    let undo: () -> Void
    let reset: () -> Void
    @Environment(\.sizeCategory) private var sizeCategory

    var body: some View {
        content
            .padding(.horizontal, PlanteriorSpacing.large)
            .padding(.vertical, PlanteriorSpacing.small)
            .background(PlanteriorPalette.surface.color)
            .overlay(alignment: .top) {
                Rectangle()
                    .fill(PlanteriorPalette.border.color)
                    .frame(height: PlanteriorControl.hairline)
            }
    }

    /// Korean action labels must not be split mid-word. Once Dynamic Type
    /// stops both fitting side by side, they stack instead of wrapping.
    @ViewBuilder
    private var content: some View {
        if sizeCategory.isAccessibilityCategory {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                actions
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            HStack(spacing: PlanteriorSpacing.small) {
                actions
            }
        }
    }

    @ViewBuilder
    private var actions: some View {
        action(
            title: "되돌리기",
            systemImage: "arrow.uturn.backward",
            enabled: canUndo,
            identifier: "minihome.editor.undo",
            action: undo
        )
        if !sizeCategory.isAccessibilityCategory {
            Spacer(minLength: PlanteriorSpacing.small)
        }
        action(
            title: "초기화",
            systemImage: "arrow.triangle.2.circlepath",
            enabled: canReset,
            identifier: "minihome.editor.reset",
            action: reset
        )
    }

    private func action(
        title: String,
        systemImage: String,
        enabled: Bool,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: PlanteriorSpacing.extraSmall) {
                Image(systemName: systemImage)
                    .font(PlanteriorTypography.caption)
                Text(title)
                    .font(PlanteriorTypography.supporting)
                    .fixedSize(horizontal: true, vertical: true)
            }
            .foregroundStyle(
                enabled
                    ? PlanteriorPalette.textSecondary.color
                    : PlanteriorPalette.textTertiary.color
            )
            .frame(minHeight: PlanteriorControl.minimumTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityIdentifier(identifier)
    }
}
