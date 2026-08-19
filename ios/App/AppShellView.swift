import PlanteriorDesignSystem
import SwiftUI

struct AppShellView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject var auth: AuthRuntime
    @State var navigation = AppNavigationState()
    @State var showsLogin = false
    @State private var showsOnboarding = OnboardingState.shouldPresent

    init() {
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_INITIAL_TAB"] == "collection" {
                var state = AppNavigationState()
                state.select(.collection)
                _navigation = State(initialValue: state)
            }
        #endif
    }

    var body: some View {
        VStack(spacing: 0) {
            tabContent
            AppTabBar(
                selectedTab: navigation.selectedTab,
                selectTab: { navigation.select($0) },
                presentCamera: { navigation.presentCamera() }
            )
            if authenticationState == .signedOut {
                Button("로그인하고 동기화하기") {
                    showsLogin = true
                }
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .accessibilityIdentifier("auth.open")
            } else if navigation.selectedTab == .settings {
                syncStatus
                Button("로그아웃") {
                    auth.pendingLogout = true
                }
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .accessibilityIdentifier("auth.logout")
            }
        }
        .background(PlanteriorPalette.canvas.color)
        .confirmationDialog(
            "동기화되지 않은 변경을 어떻게 처리할까요?",
            isPresented: Binding(
                get: { auth.pendingLogout },
                set: { auth.pendingLogout = $0 }
            ),
            titleVisibility: .visible
        ) {
            Button("동기화 후 로그아웃") {
                Task { await auth.completeSignOut(action: .sync) }
            }
            Button("변경 버리고 로그아웃", role: .destructive) {
                Task { await auth.completeSignOut(action: .discard) }
            }
            Button("취소", role: .cancel) {
                auth.pendingLogout = false
            }
        }
        .transaction {
            $0.animation = effectiveReduceMotion
                ? nil
                : .easeInOut(duration: PlanteriorMotion.duration(reduceMotion: false))
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(effectiveReduceMotion ? "app.shell.reduce-motion" : "app.shell")
        .onOpenURL {
            guard $0.scheme == "planterior" else {
                return
            }
            let incomingRoute = AppURLRoute.parse($0)
            let availability: RouteTargetAvailability =
                authenticationState == .signedOut
                    ? .available
                    : targetAvailability(for: incomingRoute)
            navigation.handle(
                incomingRoute,
                authentication: authenticationState,
                targetAvailability: availability
            )
            showsLogin = navigation.pendingAuthenticationRoute != nil
        }
        .task {
            mountAccountStores()
            handleQARouteIfPresent()
        }
        .sheet(
            isPresented: Binding(
                get: { navigation.isCameraPresented },
                set: { $0 ? navigation.presentCamera() : navigation.dismissCamera() }
            )
        ) {
            CameraActionView(
                dismiss: { navigation.dismissCamera() },
                complete: {
                    navigation.dismissCamera()
                    navigation.push(.identificationDraft)
                },
                manualRegistration: {
                    navigation.dismissCamera()
                    navigation.push(.manualRegistration)
                }
            )
        }
        .sheet(isPresented: $showsLogin) {
            LoginSheet(auth: auth)
        }
        .fullScreenCover(isPresented: $showsOnboarding) {
            OnboardingView {
                OnboardingState.complete()
                showsOnboarding = false
            }
        }
        .onChange(of: auth.isSignedIn) { _, isSignedIn in
            LocalPlantCollectionStore.shared.mount(
                accountID: accountScopeID
            )
            LocalNotificationScheduleStore.shared.mount(
                accountID: accountScopeID
            )
            LocalNotificationPreferenceStore.shared.mount(
                accountID: accountScopeID
            )
            LocalWeatherAlertStore.shared.mount(
                accountID: accountScopeID
            )
            guard isSignedIn else {
                navigation = AppNavigationState()
                return
            }
            navigation.completeAuthentication(
                targetAvailability: pendingTargetAvailability()
            )
        }
    }

    private var syncStatus: some View {
        let snapshot = auth.syncSnapshot
        return Text(
            snapshot.conflicts.isEmpty
                ? snapshot.queued.isEmpty
                ? "서버와 동기화됨"
                : "동기화 대기 \(snapshot.queued.count)건"
                : "충돌 \(snapshot.conflicts.count)건"
        )
        .accessibilityIdentifier("sync.status")
    }

    private var effectiveReduceMotion: Bool {
        reduceMotion
            || ProcessInfo.processInfo.environment["QA_REDUCE_MOTION"] == "1"
    }

    var authenticationState: AppAuthenticationState {
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_AUTHENTICATED"] == "1" {
                return .signedIn
            }
        #endif
        return auth.isSignedIn ? .signedIn : .signedOut
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
            AppTabRootView(
                tab: tab,
                openDetail: { navigation.push(.tabDetail(tab)) },
                openCamera: { navigation.presentCamera() },
                openMiniHome: { navigation.push(.miniHome) }
            )
            .navigationDestination(for: AppRoute.self) { route in
                AppRouteDestination(route: route)
            }
        }
    }
}
