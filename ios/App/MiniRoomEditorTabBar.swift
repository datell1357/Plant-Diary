import PlanteriorDesignSystem
import SwiftUI

/// Figma `category-tab-bar`: five equal-width tabs on Surface, each an icon over
/// a caption label. The active tab uses Accent for icon, label, and a 2pt
/// underline; inactive tabs stay secondary with no underline.
struct MiniRoomEditorTabBar: View {
    @Binding var selection: MiniRoomCategory
    let reduceMotion: Bool
    @Environment(\.sizeCategory) private var sizeCategory

    private static let underlineHeight: CGFloat = 2
    /// Minimum readable tab width once Dynamic Type stops the labels fitting
    /// five-across; beyond this the strip scrolls instead of colliding.
    private static let scrollingTabWidth: CGFloat = 64

    var body: some View {
        Group {
            if sizeCategory.isAccessibilityCategory {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: PlanteriorSpacing.small) {
                        ForEach(MiniRoomCategory.allCases) { category in
                            tab(category)
                                .frame(width: Self.scrollingTabWidth)
                        }
                    }
                    .padding(.horizontal, PlanteriorSpacing.small)
                }
            } else {
                HStack(spacing: 0) {
                    ForEach(MiniRoomCategory.allCases) { category in
                        tab(category)
                    }
                }
            }
        }
        .background(PlanteriorPalette.surface.color)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(PlanteriorPalette.border.color)
                .frame(height: PlanteriorControl.hairline)
        }
        .accessibilityElement(children: .contain)
    }

    private func tab(_ category: MiniRoomCategory) -> some View {
        let selected = selection == category
        return Button {
            withAnimation(PlanteriorMotion.standard(reduceMotion: reduceMotion)) {
                selection = category
            }
        } label: {
            VStack(spacing: PlanteriorSpacing.extraSmall) {
                Image(systemName: category.systemImage)
                    .font(PlanteriorTypography.supporting)
                Text(category.title)
                    .font(PlanteriorTypography.caption.weight(
                        selected ? .semibold : .regular
                    ))
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .minimumScaleFactor(0.8)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, PlanteriorSpacing.extraSmall)
            .foregroundStyle(
                selected
                    ? PlanteriorPalette.accent.color
                    : PlanteriorPalette.textSecondary.color
            )
            .frame(maxWidth: .infinity)
            .frame(minHeight: PlanteriorControl.minimumTarget)
            .padding(.vertical, PlanteriorSpacing.small)
            .contentShape(Rectangle())
            .overlay(alignment: .bottom) {
                Rectangle()
                    .fill(
                        selected
                            ? PlanteriorPalette.accent.color
                            : Color.clear
                    )
                    .frame(height: Self.underlineHeight)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(category.title)
        .accessibilityValue(selected ? "선택됨" : "선택 안 됨")
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(
            "minihome.editor.category.\(category.rawValue)"
        )
    }
}
