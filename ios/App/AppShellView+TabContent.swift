import PlanteriorDesignSystem
import SwiftUI

extension AppShellView {
    @ViewBuilder
    var tabContent: some View {
        switch navigation.selectedTab {
        case .home: tabStack(tab: .home, path: $navigation.homePath)
        case .collection: tabStack(tab: .collection, path: $navigation.collectionPath)
        case .storage: tabStack(tab: .storage, path: $navigation.storagePath)
        case .settings: tabStack(tab: .settings, path: $navigation.settingsPath)
        }
    }

    func tabStack(tab: AppTab, path: Binding<[AppRoute]>) -> some View {
        NavigationStack(path: path) {
            AppTabRootView(
                tab: tab,
                selectTab: requestTab,
                openDetail: { navigation.push(.tabDetail(tab)) },
                openCamera: requestCamera,
                openMiniHome: { navigation.push(.miniHome) },
                returnFromSettingsRoot: { navigation.returnFromSettingsRoot() },
                authorizeAccountAction: authorizeAccountAction
            )
            .navigationDestination(for: AppRoute.self) { route in
                AppRouteDestination(route: route)
            }
        }
        .persistentAppTabBar(
            selectedTab: navigation.selectedTab,
            selectTab: requestTab,
            presentCamera: requestCamera
        )
    }
}

extension View {
    func persistentAppTabBar(
        selectedTab: AppTab,
        selectTab: @escaping (AppTab) -> Void,
        presentCamera: @escaping () -> Void
    ) -> some View {
        VStack(spacing: PlanteriorSpacing.none) {
            self
            AppTabBar(
                selectedTab: selectedTab,
                selectTab: selectTab,
                presentCamera: presentCamera
            )
            .background(PlanteriorPalette.canvas.color)
        }
    }
}
