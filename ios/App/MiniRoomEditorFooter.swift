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

    private static let referenceHeight = MiniRoomReferenceMetrics.footerHeight

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

    /// Korean action labels stay atomic. At the accessibility sizes the two
    /// painted captions no longer fit one row inside the 402pt frame, so they
    /// stack in source order — undo above reset — each spanning the full strip
    /// width. Asking for their intrinsic widths instead stretched the whole
    /// editor past the window and pushed both actions offscreen.
    @ViewBuilder
    private var content: some View {
        if sizeCategory.isAccessibilityCategory {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                undoAction.frame(maxWidth: .infinity, alignment: .leading)
                resetAction.frame(maxWidth: .infinity, alignment: .leading)
            }
        } else {
            HStack(spacing: PlanteriorSpacing.small) {
                undoAction.frame(maxWidth: .infinity, alignment: .leading)
                resetAction.frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
    }

    private var undoAction: some View {
        action(
            title: "되돌리기",
            systemImage: "arrow.uturn.backward",
            enabled: canUndo,
            identifier: "minihome.editor.undo",
            action: undo
        )
    }

    private var resetAction: some View {
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
                    .lineLimit(1)
                    .minimumScaleFactor(
                        MiniRoomReferenceMetrics.footerTextMinimumScale
                    )
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
            .opacity(
                configuration.isPressed
                    ? PlanteriorOpacity.pressed
                    : MiniRoomReferenceMetrics.fullOpacity
            )
    }
}
