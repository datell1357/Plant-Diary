import PlanteriorDesignSystem
import SwiftUI

extension HomeDashboardView {
    /// Figma `mini-room-card` §6.3: the isometric room fills a radius-xl card
    /// with two floating 36pt circular actions. Signed-out renders the same
    /// geometry with the empty room.
    var miniHomeSection: some View {
        Image(.homeRoom)
            .resizable()
            .scaledToFill()
            .frame(maxWidth: .infinity)
            .frame(height: 326)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
            .accessibilityIdentifier("home.room.hero")
            .accessibilityLabel("\(roomTitle) 미리보기")
            .overlay(alignment: .topLeading) {
                roomAction(
                    systemImage: "leaf",
                    label: "미니홈 꾸미기",
                    identifier: "home.room.decorate",
                    action: requestMiniHomeOpen
                )
            }
            .overlay(alignment: .topTrailing) {
                roomAction(
                    systemImage: "square.and.arrow.up",
                    label: "미니홈 공유",
                    identifier: "home.room.share",
                    action: requestMiniHomeOpen
                )
            }
            .accessibilityElement(children: .contain)
    }

    private func roomAction(
        systemImage: String,
        label: String,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .frame(
                    width: PlanteriorControl.minimumTarget,
                    height: PlanteriorControl.minimumTarget
                )
                .background(PlanteriorPalette.surface.color)
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
        .padding(PlanteriorSpacing.medium)
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
