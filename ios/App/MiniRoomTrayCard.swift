import PlanteriorDesignSystem
import SwiftUI

/// One tray card: asset tile plus caption, with the Figma selected treatment.
struct MiniRoomTrayCard: View {
    let entry: MiniRoomTrayEntry
    let index: Int
    let selected: Bool
    let select: (MiniRoomTrayEntry) -> Void
    @Environment(\.sizeCategory) private var sizeCategory

    static let referenceHorizontalInset = MiniRoomReferenceMetrics
        .trayHorizontalInset
    static let referenceSpacing = MiniRoomReferenceMetrics.traySpacing

    var body: some View {
        Button {
            select(entry)
        } label: {
            VStack(spacing: PlanteriorSpacing.small) {
                tile
                caption
            }
            .frame(
                maxWidth: sizeCategory.isAccessibilityCategory
                    ? .infinity
                    : MiniRoomReferenceMetrics.trayTileSide
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(entry.name)
        .accessibilityValue(selected ? "선택됨" : "선택 안 됨")
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("minihome.editor.tray.\(index)")
    }

    /// The source PNG remains an independent RGBA layer. A Surface shape owns
    /// the rounded backing, while stroke and badge are siblings rather than
    /// overlays on the resizable image, so none can substitute opaque pixels
    /// into the asset's transparent regions.
    private var tile: some View {
        ZStack {
            RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
                .fill(PlanteriorPalette.surface.color)
            Image(entry.asset)
                .resizable()
                .scaledToFit()
                .padding(
                    sizeCategory.isAccessibilityCategory && selected
                        ? PlanteriorSpacing.extraSmall
                        : PlanteriorSpacing.none
                )
                .accessibilityIdentifier(
                    "minihome.editor.tray.image.\(index)"
                )
            RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
                .strokeBorder(
                    selected
                        ? PlanteriorPalette.accent.color
                        : PlanteriorPalette.border.color,
                    lineWidth: selected
                        ? MiniRoomReferenceMetrics.traySelectedBorder
                        : PlanteriorControl.hairline
                )
                .accessibilityHidden(true)
            if selected {
                checkBadge
                    .frame(
                        maxWidth: .infinity,
                        maxHeight: .infinity,
                        alignment: .topTrailing
                    )
            }
        }
        .frame(width: tileSide, height: tileSide)
        .clipShape(
            RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
        )
    }

    private var tileSide: CGFloat {
        sizeCategory.isAccessibilityCategory
            ? MiniRoomReferenceMetrics.trayAccessibilityTileSide
            : MiniRoomReferenceMetrics.trayTileSide
    }

    /// Korean captions must never be clamped to the tile column, so the
    /// caption keeps its own width. In the wrapped accessibility grid the
    /// column is already bounded by the screen, so the caption grows
    /// vertically inside it instead of forcing the row past the frame.
    ///
    /// At the accessibility sizes the column is narrow enough that a name like
    /// `스킨답서스` broke mid-word and stranded its final syllable on its own
    /// line, so the caption paints one optically shrunk line instead.
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
            .lineLimit(sizeCategory.isAccessibilityCategory ? 1 : nil)
            .minimumScaleFactor(
                sizeCategory.isAccessibilityCategory
                    ? MiniRoomReferenceMetrics.trayCaptionMinimumScale
                    : MiniRoomReferenceMetrics.noTextShrink
            )
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .center)
    }

    private var checkBadge: some View {
        Image(systemName: "checkmark")
            .resizable()
            .scaledToFit()
            .foregroundStyle(PlanteriorPalette.textOnAccent.color)
            .frame(
                width: MiniRoomReferenceMetrics.trayBadgeGlyphSide,
                height: MiniRoomReferenceMetrics.trayBadgeGlyphSide
            )
            .frame(
                width: MiniRoomReferenceMetrics.trayBadgeSide,
                height: MiniRoomReferenceMetrics.trayBadgeSide
            )
            .background(PlanteriorPalette.accent.color)
            .clipShape(Circle())
            .offset(
                x: MiniRoomReferenceMetrics.trayBadgeOffset.width,
                y: MiniRoomReferenceMetrics.trayBadgeOffset.height
            )
            .accessibilityHidden(true)
    }
}
