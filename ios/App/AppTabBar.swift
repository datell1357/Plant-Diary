import PlanteriorDesignSystem
import SwiftUI

struct AppTabBar: View {
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
        .padding(.horizontal, 8)
        .padding(.top, 6)
        .background(PlanteriorPalette.surface.color)
        .overlay(alignment: .top) { Divider() }
    }

    private func tabButton(_ tab: AppTab) -> some View {
        Button {
            selectTab(tab)
        } label: {
            VStack(spacing: 2) {
                Image(systemName: selectedTab == tab ? tab.systemImage + ".fill" : tab.systemImage)
                    .font(.system(size: 20))
                Text(tab.title).font(.caption2)
            }
            .frame(maxWidth: .infinity, minHeight: PlanteriorControl.minimumTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(selectedTab == tab ? PlanteriorPalette.accent.color : Color.secondary)
        .accessibilityLabel(tab.title)
        .accessibilityAddTraits(selectedTab == tab ? .isSelected : [])
        .accessibilityIdentifier("tab.\(tab.rawValue)")
    }

    private var cameraButton: some View {
        Button(action: presentCamera) {
            Image(systemName: "camera.fill")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(.white)
                .frame(
                    width: PlanteriorControl.cameraDiameter,
                    height: PlanteriorControl.cameraDiameter
                )
                .background(Circle().fill(PlanteriorPalette.accent.color))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity, minHeight: PlanteriorControl.cameraDiameter)
        .accessibilityLabel("식물 사진 촬영")
        .accessibilityIdentifier("tab.camera")
    }
}
