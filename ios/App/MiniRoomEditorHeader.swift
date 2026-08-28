import PlanteriorDesignSystem
import SwiftUI

/// Figma `editor-header`: canvas-tinted bar with a leading close control, a
/// centered screen title, a trailing accent save action, and a hairline base.
struct MiniRoomEditorHeader: View {
    let close: () -> Void
    let showRoomSettings: () -> Void
    let save: () -> Void

    private static let height: CGFloat = 52
    private static let edgeActionInset: CGFloat = 10
    private static let saveOpticalInset: CGFloat = -8
    private static let contentVerticalOffset: CGFloat = -2

    var body: some View {
        HStack(spacing: PlanteriorSpacing.small) {
            Button(action: close) {
                Image(systemName: "xmark")
                    .font(PlanteriorTypography.body)
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .frame(
                        width: PlanteriorControl.minimumTarget,
                        height: PlanteriorControl.minimumTarget
                    )
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("편집 닫기")
            .accessibilityIdentifier("minihome.close")

            Text("마이룸 편집")
                .font(PlanteriorTypography.screenTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .lineLimit(1)
                .minimumScaleFactor(
                    MiniRoomReferenceMetrics.headerTitleMinimumScale
                )
                .allowsTightening(true)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .layoutPriority(1)
                .contentShape(Rectangle())
                .onTapGesture(perform: showRoomSettings)
                .accessibilityElement()
                .accessibilityLabel("마이룸 편집")
                .accessibilityHint("방 이름과 편집 상태 보기")
                .accessibilityAddTraits(.isHeader)
                .accessibilityAction { showRoomSettings() }
                .accessibilityIdentifier("minihome.editor.title")

            Button(action: save) {
                Text("저장")
                    .font(PlanteriorTypography.supporting.weight(.semibold))
                    .foregroundStyle(PlanteriorPalette.accent.color)
                    .lineLimit(1)
                    .minimumScaleFactor(
                        MiniRoomReferenceMetrics.headerSaveMinimumScale
                    )
                    .frame(
                        width: PlanteriorControl.minimumTarget,
                        height: PlanteriorControl.minimumTarget,
                        alignment: .trailing
                    )
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("저장")
            .accessibilityIdentifier("minihome.save")
            .offset(x: Self.saveOpticalInset)
        }
        // The reference centers its leading 44pt action at x=32; the save
        // label is optically inset from the trailing edge by another 10pt.
        .padding(.horizontal, Self.edgeActionInset)
        .offset(y: Self.contentVerticalOffset)
        .frame(height: Self.height)
        .background(PlanteriorPalette.canvas.color)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(PlanteriorPalette.border.color)
                .frame(height: PlanteriorControl.hairline)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("minihome.editor.header")
    }
}
