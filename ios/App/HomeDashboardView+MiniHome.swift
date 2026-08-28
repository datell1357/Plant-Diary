import PlanteriorDesignSystem
import SwiftUI

enum HomeMiniRoomActionStyle {
    static let decorateSymbol = "chair.lounge.fill"
    static let exportSymbol = "arrow.up.to.line"
}

extension HomeDashboardView {
    /// Figma `mini-room-card` §6.3: the isometric room fills a radius-xl card
    /// with two floating 36pt circular actions. A committed layout is rendered
    /// from its live placements; signed-out and unsaved states remain empty.
    var miniHomeSection: some View {
        MiniHomeRoomComposition(
            room: store.miniHome,
            background: .homeRoom,
            roomIdentifier: "home.room.hero",
            roomLabel: "\(roomTitle) 미리보기"
        )
        .frame(maxWidth: .infinity)
        .frame(height: HomeReferenceMetrics.miniRoomHeight)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
        .overlay(alignment: .topLeading) {
            roomAction(
                systemImage: HomeMiniRoomActionStyle.decorateSymbol,
                tint: PlanteriorPalette.accent.color,
                label: "미니홈 꾸미기",
                identifier: "home.room.decorate",
                action: requestMiniHomeOpen
            )
        }
        .overlay(alignment: .topTrailing) {
            roomAction(
                systemImage: HomeMiniRoomActionStyle.exportSymbol,
                tint: PlanteriorPalette.textPrimary.color,
                label: "미니홈 공유",
                identifier: "home.room.share",
                action: requestMiniHomeOpen
            )
        }
        .accessibilityElement(children: .contain)
    }

    private func roomAction(
        systemImage: String,
        tint: Color,
        label: String,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(PlanteriorTypography.caption)
                .foregroundStyle(tint)
                .frame(
                    width: HomeReferenceMetrics.roomActionVisualSide,
                    height: HomeReferenceMetrics.roomActionVisualSide
                )
                .background(PlanteriorPalette.surface.color)
                .clipShape(Circle())
                .frame(
                    width: PlanteriorControl.minimumTarget,
                    height: PlanteriorControl.minimumTarget
                )
        }
        .buttonStyle(.plain)
        .frame(
            width: PlanteriorControl.minimumTarget,
            height: PlanteriorControl.minimumTarget
        )
        .contentShape(Rectangle())
        .padding(HomeReferenceMetrics.roomActionInset)
        .accessibilityLabel(label)
        .accessibilityIdentifier(identifier)
    }

    func requestMiniHomeOpen() {
        guard authorizeAccountAction() else {
            return
        }
        if isInitialLoadComplete {
            openMiniHome()
        } else {
            pendingMiniHomeOpen = true
        }
    }
}
