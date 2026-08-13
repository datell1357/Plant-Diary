import PlanteriorDesignSystem
import SwiftUI

struct AppShellView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var navigation = AppNavigationState()

    var body: some View {
        VStack(spacing: 0) {
            tabContent
            AppTabBar(
                selectedTab: navigation.selectedTab,
                selectTab: { navigation.select($0) },
                presentCamera: { navigation.presentCamera() }
            )
        }
        .background(PlanteriorPalette.canvas.color)
        .transaction {
            $0.animation = effectiveReduceMotion
                ? nil
                : .easeInOut(duration: PlanteriorMotion.duration(reduceMotion: false))
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(effectiveReduceMotion ? "app.shell.reduce-motion" : "app.shell")
        .onOpenURL {
            navigation.handle(
                AppURLRoute.parse($0),
                authentication: .signedIn,
                targetAvailability: .available
            )
        }
        .task {
            handleQARouteIfPresent()
        }
        .sheet(
            isPresented: Binding(
                get: { navigation.isCameraPresented },
                set: { $0 ? navigation.presentCamera() : navigation.dismissCamera() }
            )
        ) {
            CameraActionView {
                navigation.dismissCamera()
            }
        }
    }

    private var effectiveReduceMotion: Bool {
        reduceMotion || ProcessInfo.processInfo.environment["QA_REDUCE_MOTION"] == "1"
    }

    private func handleQARouteIfPresent() {
        guard let rawURL = ProcessInfo.processInfo.environment["QA_DEEP_LINK"],
              let url = URL(string: rawURL)
        else {
            return
        }
        let availability: RouteTargetAvailability =
            ProcessInfo.processInfo.environment["QA_TARGET_DELETED"] == "1"
                ? .deleted
                : .available
        navigation.handle(
            AppURLRoute.parse(url),
            authentication: .signedIn,
            targetAvailability: availability
        )
    }

    @ViewBuilder
    private var tabContent: some View {
        switch navigation.selectedTab {
        case .home: tabStack(tab: .home, path: $navigation.homePath)
        case .collection: tabStack(tab: .collection, path: $navigation.collectionPath)
        case .storage: tabStack(tab: .storage, path: $navigation.storagePath)
        case .settings: tabStack(tab: .settings, path: $navigation.settingsPath)
        }
    }

    private func tabStack(tab: AppTab, path: Binding<[AppRoute]>) -> some View {
        NavigationStack(path: path) {
            AppTabRootView(tab: tab) {
                navigation.push(.tabDetail(tab))
            }
            .navigationDestination(for: AppRoute.self) { route in
                AppRouteDestination(route: route)
            }
        }
    }
}

private struct AppTabRootView: View {
    let tab: AppTab
    let openDetail: () -> Void

    var body: some View {
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

private struct AppTabBar: View {
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

private struct CameraActionView: View {
    let dismiss: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "camera.fill")
                .font(.system(size: 52))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityHidden(true)
            Text("식물 사진 촬영")
                .font(PlanteriorTypography.screenTitle)
            PlanteriorPrimaryButton("닫기", action: dismiss)
                .frame(maxWidth: 240)
                .accessibilityLabel("카메라 닫기")
                .accessibilityIdentifier("camera.dismiss")
        }
        .padding(24)
        .presentationDetents([.medium])
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("camera.sheet")
    }
}
