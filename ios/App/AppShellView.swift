import PlanteriorData
import PlanteriorDesignSystem
import SwiftUI

struct AppShellView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.sizeCategory) var sizeCategory
    @EnvironmentObject var auth: AuthRuntime
    @State var navigation = AppNavigationState()
    @State var showsLogin = false
    @State private var showsOnboarding = OnboardingState.shouldPresent
    @State private var captureDestination: AppRoute?
    @State private var restoresReviewedPhoto = false
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
                selectTab: requestTab,
                presentCamera: requestCamera
            )
        }
        .background(PlanteriorPalette.canvas.color)
        .environment(\.sizeCategory, effectiveShellSizeCategory)
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
            await mountAccountStores()
            handleQARouteIfPresent()
        }
        .fullScreenCover(
            isPresented: Binding(
                get: { navigation.isCameraPresented },
                set: { $0 ? navigation.presentCamera() : navigation.dismissCamera() }
            ),
            onDismiss: {
                captureDestination = nil
                restoresReviewedPhoto = false
                Task { try? await IdentificationDraftStore.shared.clear() }
            },
            content: {
                NavigationStack {
                    switch captureDestination {
                    case .identificationDraft: IdentificationFlowView(
                            revisePhoto: {
                                restoresReviewedPhoto = true
                                captureDestination = nil
                            },
                            completeRegistration: { navigation.dismissCamera() }
                        )
                    case .manualRegistration: PlantRegistrationView(
                            onRegistered: { navigation.dismissCamera() }
                        )
                    default:
                        CameraActionView(
                            dismiss: { navigation.dismissCamera() },
                            complete: { captureDestination = .identificationDraft },
                            manualRegistration: { captureDestination = .manualRegistration },
                            restoresReviewedPhoto: restoresReviewedPhoto
                        )
                    }
                }
            }
        )
        .sheet(isPresented: $showsLogin) {
            LoginSheet(auth: auth)
        }
        .fullScreenCover(isPresented: $showsOnboarding) {
            OnboardingView {
                OnboardingState.complete()
                showsOnboarding = false
            }
        }
        .onChange(of: auth.isRestoring) { _, isRestoring in
            guard !isRestoring else {
                return
            }
            Task { await mountAccountStores() }
        }
        .onChange(of: auth.accountID?.rawValue) {
            Task { await mountAccountStores() }
        }
        .onChange(of: auth.isSignedIn) { _, isSignedIn in
            guard isSignedIn else {
                navigation = AppNavigationState()
                return
            }
            navigation.completeAuthentication(
                targetAvailability: pendingTargetAvailability()
            )
        }
    }

    private func requestTab(_ tab: AppTab) {
        guard navigation.requestTab(tab, authentication: authenticationState) == .proceed
        else {
            showsLogin = true
            return
        }
    }

    private func requestCamera() {
        guard navigation.requestCamera(authentication: authenticationState) == .proceed
        else {
            showsLogin = true
            return
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
                openCamera: requestCamera,
                openMiniHome: { navigation.push(.miniHome) },
                authorizeAccountAction: authorizeAccountAction
            )
            .navigationDestination(for: AppRoute.self) { route in
                AppRouteDestination(route: route)
            }
        }
    }
}
