import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct HomeDashboardView: View {
    let openCamera: () -> Void
    let openMiniHome: () -> Void
    let authorizeAccountAction: () -> Bool
    @Environment(\.sizeCategory) var sizeCategory
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @EnvironmentObject var auth: AuthRuntime
    @EnvironmentObject var miniHomeStore: MiniHomeStore
    @ObservedObject var collection = LocalPlantCollectionStore.shared
    @StateObject var store = HomeDashboardStore()
    @StateObject var weatherRuntime = WeatherRuntime()
    @State var notificationState = NotificationRuntimeState.initial
    @State var showsRegionSettings = false
    @State var showsQuietHoursSettings = false
    @State var isInitialLoadComplete = false
    @State var pendingMiniHomeOpen = false
    @State var isRenamePresented = false
    @State var renameDraft = ""
    @State var renameAllowance = HomeRenameAllowance(
        hasUsedFreeRename: false,
        balance: 0
    )
    @FocusState var isRenameFieldFocused: Bool
    let calendar = PlantCareCalendar()

    /// `home.screen` is the single vertical scroll owner for the whole Home
    /// surface; nothing below it introduces a second scroll view.
    var body: some View {
        homeSurface
            .fullScreenCover(isPresented: $isRenamePresented) {
                renameDialog
                    .presentationBackground(.clear)
            }
    }

    private var homeSurface: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                homeHeader
                signingInIndicator
                miniHomeSection
                    .padding(.top, PlanteriorSpacing.small)
                weatherWarningBanner
                    .padding(.top, PlanteriorSpacing.large)
                careSection
                    .padding(.top, PlanteriorSpacing.small)
                if authenticationState == .authenticated {
                    weatherSection
                        .id("home.weather.section")
                        .padding(.top, PlanteriorSpacing.huge)
                    notificationSection
                        .padding(.top, PlanteriorSpacing.huge)
                    syncSection
                        .padding(.top, PlanteriorSpacing.huge)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.bottom, PlanteriorSpacing.large)
        }
        .contentMargins(
            .horizontal,
            PlanteriorLayout.contentGutter,
            for: .scrollContent
        )
        .contentMargins(.top, 0, for: .scrollContent)
        .accessibilityIdentifier("home.screen")
        .accessibilityValue(store.qaFixtureMountReceipt)
        .background(PlanteriorPalette.canvas.color)
        .environment(\.sizeCategory, effectiveSizeCategory)
        .toolbar(.hidden, for: .navigationBar)
        .task {
            remountAccount(accountScopeID)
            collection.loadQAFixtureIfNeeded()
            resetRenameStateForQAIfNeeded()
            renameAllowance = allowanceStore.load()
            notificationState = await NotificationRuntimeState.current()
            await weatherRuntime.refresh(plants: collection.weatherPlantIDs)
            reload()
            isInitialLoadComplete = true
            if pendingMiniHomeOpen {
                pendingMiniHomeOpen = false
                openMiniHome()
            }
        }
        .onAppear {
            reload()
        }
        .onChange(of: miniHomeStore.committed) {
            reload()
        }
        .onChange(of: collection.plants) {
            Task { await refreshWeather() }
        }
        .onChange(of: collection.completedPlantIDs) {
            reload()
        }
        .onChange(of: auth.accountID?.rawValue) {
            remountAccount(accountScopeID)
            Task { await refreshWeather() }
        }
        .onChange(of: weatherRuntime.homeState) {
            reload()
        }
        .onChange(of: weatherRuntime.authorization) {
            Task { await refreshWeather() }
        }
        .onChange(of: weatherRuntime.locationRegionCode) {
            Task { await refreshWeather() }
        }
        .onReceive(
            NotificationCenter.default.publisher(
                for: .weatherAlertPreferencesDidChange
            )
        ) { _ in
            weatherRuntime.reconcileAlerts(
                plants: collection.weatherPlantIDs
            )
        }
        .fullScreenCover(isPresented: $showsRegionSettings) {
            NavigationStack {
                RegionSettingsView(
                    weather: weatherRuntime,
                    showsCloseButton: true
                ) {
                    showsRegionSettings = false
                    Task { await refreshWeather() }
                }
            }
        }
        .fullScreenCover(isPresented: $showsQuietHoursSettings) {
            NavigationStack {
                QuietHoursSettingsView(showsCloseButton: true)
            }
        }
    }
}
