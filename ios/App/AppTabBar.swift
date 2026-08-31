import PlanteriorDesignSystem
import SwiftUI

/// Machine-consumed Figma tab-bar geometry (figma-analysis §6.1). Every value is a
/// design-system token or an explicit Figma measurement; none are ad-hoc.
enum AppTabBarMetrics {
    static let iconSize: CGFloat = 24
    static let iconLabelSpacing = PlanteriorSpacing.extraSmall
    static let horizontalPadding = PlanteriorSpacing.small
    static let contentHeight = PlanteriorLayout.tabBarHeight
    static let minimumTarget = PlanteriorControl.minimumTarget
    static let cameraDiameter = PlanteriorControl.cameraDiameter
    static let cameraGlyphSize: CGFloat = 26
    static let hairlineWidth = PlanteriorControl.hairline
    static let surface = PlanteriorPalette.surface
    static let hairline = PlanteriorPalette.border
    static let activeTint = PlanteriorPalette.accent
    static let inactiveTint = PlanteriorPalette.textSecondary
}

struct AppTabBar: View {
    @ScaledMetric(relativeTo: .caption2)
    private var tabLabelSize = 10
    @ScaledMetric(relativeTo: .body)
    private var tabIconSize = AppTabBarMetrics.iconSize
    let selectedTab: AppTab
    let selectTab: (AppTab) -> Void
    let presentCamera: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            tabButton(.home)
            tabButton(.collection)
            cameraButton
            tabButton(.storage)
            tabButton(.settings)
        }
        .padding(.horizontal, AppTabBarMetrics.horizontalPadding)
        .padding(.top, PlanteriorSpacing.small)
        .frame(height: AppTabBarMetrics.contentHeight, alignment: .top)
        .background(AppTabBarMetrics.surface.color)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(AppTabBarMetrics.hairline.color)
                .frame(height: AppTabBarMetrics.hairlineWidth)
        }
    }

    private func tabButton(_ tab: AppTab) -> some View {
        Button {
            selectTab(tab)
        } label: {
            VStack(spacing: AppTabBarMetrics.iconLabelSpacing) {
                Image(systemName: tab.systemImage)
                    .font(.system(size: min(tabIconSize, 28)))
                Text(tab.title)
                    .font(.system(size: min(tabLabelSize, 16), weight: .medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, minHeight: AppTabBarMetrics.minimumTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(
            selectedTab == tab
                ? AppTabBarMetrics.activeTint.color
                : AppTabBarMetrics.inactiveTint.color
        )
        .accessibilityLabel(tab.title)
        .accessibilityAddTraits(selectedTab == tab ? .isSelected : [])
        .accessibilityIdentifier("tab.\(tab.rawValue)")
    }

    /// The camera is a modal action, not a fifth tab: no selected trait, no stack.
    private var cameraButton: some View {
        Button(action: presentCamera) {
            Image(systemName: "camera.fill")
                .font(.system(size: AppTabBarMetrics.cameraGlyphSize, weight: .semibold))
                .foregroundStyle(PlanteriorPalette.textOnAccent.color)
                .frame(
                    width: AppTabBarMetrics.cameraDiameter,
                    height: AppTabBarMetrics.cameraDiameter
                )
                .background(Circle().fill(AppTabBarMetrics.activeTint.color))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .frame(
            maxWidth: .infinity,
            minHeight: AppTabBarMetrics.minimumTarget
        )
        .accessibilityLabel("식물 사진 촬영")
        .accessibilityIdentifier("tab.camera")
    }
}
