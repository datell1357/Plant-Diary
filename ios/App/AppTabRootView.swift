import PlanteriorDesignSystem
import SwiftUI

struct AppTabRootView: View {
    let tab: AppTab
    let openDetail: () -> Void
    let openCamera: () -> Void
    let openMiniHome: () -> Void
    @ObservedObject private var collection = LocalPlantCollectionStore.shared

    var body: some View {
        if tab == .home {
            HomeDashboardView(
                openCamera: openCamera,
                openMiniHome: openMiniHome
            )
        } else if tab == .collection {
            PlantCollectionView(
                openLegacyDetail: openDetail,
                openCamera: openCamera
            )
        } else if tab == .storage {
            InventoryView()
        } else if tab == .settings {
            SettingsView(openMilestones: openDetail)
        } else {
            placeholder
        }
    }

    private var placeholder: some View {
        VStack(spacing: 16) {
            Image(systemName: tab.systemImage + ".fill")
                .font(.system(size: 52))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityHidden(true)
            Text(tab.title)
                .font(PlanteriorTypography.screenTitle)
            PlanteriorPrimaryButton("상세 보기", action: openDetail)
                .frame(maxWidth: 240)
                .accessibilityLabel("\(tab.title) 상세 보기")
                .accessibilityIdentifier("\(tab.rawValue).open-detail")
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PlanteriorPalette.canvas.color)
        .navigationTitle(tab.title)
    }
}
