import PlanteriorDesignSystem
import SwiftUI
import UIKit

/// Figma `category-tab-bar`: five equal-width tabs on Surface, each an icon over
/// a caption label. The active tab uses Accent for icon, label, and a 2pt
/// underline; inactive tabs stay secondary with no underline.
struct MiniRoomEditorTabBar: View {
    @Binding var selection: MiniRoomCategory
    let reduceMotion: Bool
    @Environment(\.sizeCategory) private var sizeCategory

    private static let referenceHeight: CGFloat = 55
    private static let referenceHorizontalInset: CGFloat = 8
    private static let plantIconSide: CGFloat = 18
    private static let underlineHeight: CGFloat = 2
    /// Minimum readable tab width once Dynamic Type stops the labels fitting
    /// five-across; beyond this the strip scrolls instead of colliding. This is
    /// a FLOOR, not a fixed width: clamping the column to it split every
    /// two-syllable Korean caption one syllable per line at AX5.
    private static let scrollingTabMinimumWidth: CGFloat = 64

    var body: some View {
        Group {
            if sizeCategory.isAccessibilityCategory {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: PlanteriorSpacing.small) {
                        ForEach(MiniRoomCategory.allCases) { category in
                            tab(category)
                                .frame(
                                    minWidth: Self.scrollingTabMinimumWidth
                                )
                                .fixedSize(horizontal: true, vertical: false)
                        }
                    }
                    .padding(.horizontal, PlanteriorSpacing.small)
                }
            } else {
                HStack(spacing: 0) {
                    ForEach(MiniRoomCategory.allCases) { category in
                        tab(category)
                            .frame(maxHeight: .infinity)
                    }
                }
                .padding(.horizontal, Self.referenceHorizontalInset)
            }
        }
        // Reference raster: separator y=628, tray y=683.
        .frame(
            height: sizeCategory.isAccessibilityCategory
                ? nil
                : Self.referenceHeight
        )
        .background(PlanteriorPalette.surface.color)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(PlanteriorPalette.border.color)
                .frame(height: PlanteriorControl.hairline)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("minihome.editor.category-bar")
    }

    private func tab(_ category: MiniRoomCategory) -> some View {
        let selected = selection == category
        return Button {
            withAnimation(PlanteriorMotion.standard(reduceMotion: reduceMotion)) {
                selection = category
            }
        } label: {
            VStack(spacing: PlanteriorSpacing.extraSmall) {
                categoryIcon(category)
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
            .frame(
                maxWidth: .infinity,
                maxHeight: sizeCategory.isAccessibilityCategory
                    ? nil
                    : .infinity
            )
            .frame(minHeight: PlanteriorControl.minimumTarget)
            .padding(
                .vertical,
                sizeCategory.isAccessibilityCategory
                    ? PlanteriorSpacing.small
                    : 0
            )
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

    @ViewBuilder
    private func categoryIcon(_ category: MiniRoomCategory) -> some View {
        if category == .plant,
           let image = UIImage(
               named: "FigmaRoomCategoryPlant",
               in: .main,
               compatibleWith: nil
           ) {
            Image(uiImage: image)
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .frame(
                    width: Self.plantIconSide,
                    height: Self.plantIconSide
                )
        } else {
            Image(systemName: category.systemImage)
                .font(PlanteriorTypography.supporting)
        }
    }
}
