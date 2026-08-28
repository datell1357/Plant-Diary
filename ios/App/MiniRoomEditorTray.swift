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

    /// The wrapped accessibility grid paints what the 874pt frame can hold
    /// above the footer actions; the horizontal scroller it replaces was the
    /// assistive container that pushed the tray behind the plain footer.
    private var accessibilityRows: [MiniRoomWrappedRow<MiniRoomTraySlot>] {
        MiniRoomWrappedRow.rows(
            of: entries
                .prefix(
                    MiniRoomReferenceMetrics.accessibilityTrayVisibleCount
                )
                .enumerated()
                .map { MiniRoomTraySlot(index: $0.offset, entry: $0.element) }
        )
    }

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
            } else if sizeCategory.isAccessibilityCategory {
                VStack(
                    alignment: .leading,
                    spacing: MiniRoomTrayCard.referenceSpacing
                ) {
                    ForEach(accessibilityRows) { row in
                        HStack(
                            alignment: .top,
                            spacing: MiniRoomTrayCard.referenceSpacing
                        ) {
                            ForEach(row.elements) { slot in
                                card(slot.entry, index: slot.index)
                                    .frame(maxWidth: .infinity)
                                    .clipped()
                            }
                            ForEach(0 ..< row.trailingGaps, id: \.self) { _ in
                                Color.clear
                                    .frame(maxWidth: .infinity)
                                    .accessibilityHidden(true)
                            }
                        }
                    }
                }
                .padding(
                    .horizontal,
                    MiniRoomTrayCard.referenceHorizontalInset
                )
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
                            card(pair.element, index: pair.offset)
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

    private func card(
        _ entry: MiniRoomTrayEntry,
        index: Int
    ) -> some View {
        MiniRoomTrayCard(
            entry: entry,
            index: index,
            selected: entry.id == selectedEntryID
                || (selectedEntryID == nil && index == 0),
            select: select
        )
    }
}

/// One wrapped-grid slot: the entry plus the stable index its identifier and
/// selection default are derived from.
struct MiniRoomTraySlot: Identifiable {
    let index: Int
    let entry: MiniRoomTrayEntry

    var id: String {
        entry.id
    }
}
