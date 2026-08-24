import PlanteriorDesignSystem
import SwiftUI

/// Figma `items-selector-panel`: a horizontally scrolling row of square asset
/// tiles with captions. The active entry carries an Accent outline and a filled
/// Accent check badge; tapping an entry places it in the room.
struct MiniRoomEditorTray: View {
    let entries: [MiniRoomTrayEntry]
    let selectedEntryID: String?
    let emptyMessage: String
    let select: (MiniRoomTrayEntry) -> Void
    @Environment(\.sizeCategory) private var sizeCategory

    private static let referenceHeight: CGFloat = 117
    private static let referenceTopInset: CGFloat = 16

    var body: some View {
        Group {
            if entries.isEmpty {
                Text(emptyMessage)
                    .font(PlanteriorTypography.supporting)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(minHeight: PlanteriorControl.minimumTarget)
                    .padding(.horizontal, PlanteriorSpacing.large)
                    .accessibilityIdentifier("minihome.editor.tray.empty")
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(
                        alignment: .top,
                        spacing: MiniRoomTrayCard.referenceSpacing
                    ) {
                        ForEach(
                            Array(entries.enumerated()),
                            id: \.element.id
                        ) { pair in
                            MiniRoomTrayCard(
                                entry: pair.element,
                                index: pair.offset,
                                selected: pair.element.id == selectedEntryID
                                    || (selectedEntryID == nil && pair.offset == 0),
                                select: select
                            )
                        }
                    }
                    .padding(
                        .horizontal,
                        MiniRoomTrayCard.referenceHorizontalInset
                    )
                }
                .scrollClipDisabled()
            }
        }
        .padding(
            .top,
            sizeCategory.isAccessibilityCategory
                ? PlanteriorSpacing.small
                : Self.referenceTopInset
        )
        .padding(
            .bottom,
            sizeCategory.isAccessibilityCategory
                ? PlanteriorSpacing.small
                : 0
        )
        .frame(maxWidth: .infinity)
        // Reference raster: tray y=683, first tile y=699, footer y=800.
        .frame(
            height: sizeCategory.isAccessibilityCategory
                ? nil
                : Self.referenceHeight,
            alignment: .top
        )
        .background(PlanteriorPalette.surface.color)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("minihome.editor.tray")
    }
}

/// One tray card: asset tile plus caption, with the Figma selected treatment.
struct MiniRoomTrayCard: View {
    let entry: MiniRoomTrayEntry
    let index: Int
    let selected: Bool
    let select: (MiniRoomTrayEntry) -> Void
    @Environment(\.sizeCategory) private var sizeCategory

    static let referenceHorizontalInset: CGFloat = 17
    static let referenceSpacing: CGFloat = 14
    private static let tileSide: CGFloat = 70
    private static let accessibilityTileSide: CGFloat = 56
    private static let badgeSide: CGFloat = 20
    private static let badgeOffset = CGSize(width: -3, height: 3)
    private static let selectedBorder: CGFloat = 2

    var body: some View {
        Button {
            select(entry)
        } label: {
            VStack(spacing: PlanteriorSpacing.small) {
                tile
                caption
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(entry.name)
        .accessibilityValue(selected ? "선택됨" : "선택 안 됨")
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("minihome.editor.tray.\(index)")
    }

    private var tile: some View {
        Image(entry.asset)
            .resizable()
            .scaledToFit()
            .frame(width: tileSide, height: tileSide)
            .clipShape(
                RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
            )
            .overlay {
                RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
                    .strokeBorder(
                        selected
                            ? PlanteriorPalette.accent.color
                            : PlanteriorPalette.border.color,
                        lineWidth: selected
                            ? Self.selectedBorder
                            : PlanteriorControl.hairline
                    )
            }
            .overlay(alignment: .topTrailing) {
                if selected {
                    checkBadge
                }
            }
            .accessibilityIdentifier("minihome.editor.tray.image.\(index)")
    }

    private var tileSide: CGFloat {
        sizeCategory.isAccessibilityCategory
            ? Self.accessibilityTileSide
            : Self.tileSide
    }

    /// Korean captions must never be forced to wrap mid-word, so at the
    /// accessibility sizes the caption sizes to its own content instead of
    /// being clamped to the tile column.
    private var caption: some View {
        Text(entry.name)
            .font(PlanteriorTypography.caption.weight(
                selected ? .semibold : .regular
            ))
            .foregroundStyle(
                selected
                    ? PlanteriorPalette.accent.color
                    : PlanteriorPalette.textSecondary.color
            )
            .multilineTextAlignment(.center)
            .fixedSize(
                horizontal: sizeCategory.isAccessibilityCategory,
                vertical: true
            )
            .frame(
                width: sizeCategory.isAccessibilityCategory
                    ? nil
                    : Self.tileSide
            )
    }

    private var checkBadge: some View {
        Image(systemName: "checkmark")
            .font(PlanteriorTypography.caption.weight(.bold))
            .foregroundStyle(PlanteriorPalette.textOnAccent.color)
            .frame(width: Self.badgeSide, height: Self.badgeSide)
            .background(PlanteriorPalette.accent.color)
            .clipShape(Circle())
            .offset(
                x: Self.badgeOffset.width,
                y: Self.badgeOffset.height
            )
            .accessibilityHidden(true)
    }
}
