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
                    HStack(alignment: .top, spacing: PlanteriorSpacing.medium) {
                        ForEach(
                            Array(entries.enumerated()),
                            id: \.element.id
                        ) { pair in
                            MiniRoomTrayCard(
                                entry: pair.element,
                                index: pair.offset,
                                selected: pair.element.id == selectedEntryID,
                                select: select
                            )
                        }
                    }
                    .padding(.horizontal, PlanteriorSpacing.large)
                }
                .scrollClipDisabled()
            }
        }
        .padding(
            .vertical,
            sizeCategory.isAccessibilityCategory
                ? PlanteriorSpacing.small
                : PlanteriorSpacing.medium
        )
        .frame(maxWidth: .infinity)
        .background(PlanteriorPalette.surface.color)
        .accessibilityElement(children: .contain)
    }
}

/// One tray card: asset tile plus caption, with the Figma selected treatment.
struct MiniRoomTrayCard: View {
    let entry: MiniRoomTrayEntry
    let index: Int
    let selected: Bool
    let select: (MiniRoomTrayEntry) -> Void
    @Environment(\.sizeCategory) private var sizeCategory

    private static let tileSide: CGFloat = 72
    private static let accessibilityTileSide: CGFloat = 56
    private static let badgeSide: CGFloat = 22
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
            .padding(PlanteriorSpacing.small)
            .frame(width: tileSide, height: tileSide)
            .background(
                selected
                    ? PlanteriorPalette.accentSurface.color
                    : PlanteriorPalette.subtle.color
            )
            .clipShape(
                RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
            )
            .overlay {
                RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
                    .stroke(
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
            .offset(x: PlanteriorSpacing.small, y: -PlanteriorSpacing.small)
            .accessibilityHidden(true)
    }
}
