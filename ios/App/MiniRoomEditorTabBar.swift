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

    private static let plantIcon = UIImage(
        named: "FigmaRoomCategoryPlant",
        in: .main,
        compatibleWith: nil
    )

    /// Source-ordered wrapped rows for the accessibility sizes. A horizontal
    /// scroller here made the strip an assistive container that VoiceOver
    /// traversed after the plain footer; wrapping keeps every tab a direct
    /// child in reading order and each caption on one line.
    private var accessibilityRows: [MiniRoomWrappedRow<MiniRoomCategory>] {
        MiniRoomWrappedRow.rows(
            of: MiniRoomCategory.allCases,
            columns: MiniRoomReferenceMetrics
                .accessibilityCategoryColumnCount
        )
    }

    var body: some View {
        Group {
            if sizeCategory.isAccessibilityCategory {
                VStack(spacing: PlanteriorSpacing.small) {
                    ForEach(accessibilityRows) { row in
                        HStack(spacing: PlanteriorSpacing.small) {
                            ForEach(row.elements) { category in
                                tab(category)
                                    .frame(maxWidth: .infinity)
                            }
                            ForEach(0 ..< row.trailingGaps, id: \.self) { _ in
                                Color.clear
                                    .frame(maxWidth: .infinity)
                                    .accessibilityHidden(true)
                            }
                        }
                    }
                }
                .padding(.horizontal, PlanteriorSpacing.small)
                .padding(.vertical, PlanteriorSpacing.small)
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
                    .lineLimit(
                        sizeCategory.isAccessibilityCategory ? 1 : 2
                    )
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
            .frame(
                minHeight: PlanteriorControl.minimumTarget,
                maxHeight: sizeCategory.isAccessibilityCategory
                    ? nil
                    : .infinity
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
        if category == .plant, let image = Self.plantIcon {
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
