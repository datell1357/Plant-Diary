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

    private static let referenceHeight: CGFloat = 40

    var body: some View {
        content
            .padding(.horizontal, PlanteriorSpacing.extraLarge)
            .padding(
                .vertical,
                sizeCategory.isAccessibilityCategory
                    ? PlanteriorSpacing.small
                    : 0
            )
            // Reference raster: footer separator y=800. The 44pt actions
            // align to its top and extend safely into the bottom inset.
            .frame(
                height: sizeCategory.isAccessibilityCategory
                    ? nil
                    : Self.referenceHeight,
                alignment: .top
            )
            .background(PlanteriorPalette.surface.color)
            .overlay(alignment: .top) {
                Rectangle()
                    .fill(PlanteriorPalette.border.color)
                    .frame(height: PlanteriorControl.hairline)
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("minihome.editor.footer")
    }

    /// Korean action labels stay atomic and scale within the fixed footer
    /// rather than adding a second row that would collapse the room viewport.
    private var content: some View {
        HStack(spacing: PlanteriorSpacing.small) {
            actions
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
        .frame(maxWidth: .infinity, alignment: .leading)
        action(
            title: "초기화",
            systemImage: "arrow.triangle.2.circlepath",
            enabled: canReset,
            identifier: "minihome.editor.reset",
            action: reset
        )
        .frame(maxWidth: .infinity, alignment: .trailing)
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
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .foregroundStyle(PlanteriorPalette.textSecondary.color)
            .frame(minHeight: PlanteriorControl.minimumTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(MiniRoomFooterButtonStyle())
        .disabled(!enabled)
        .accessibilityIdentifier(identifier)
    }
}

private struct MiniRoomFooterButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}
