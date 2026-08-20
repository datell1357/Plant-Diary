import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct HomeDashboardView: View {
    let openCamera: () -> Void
    let openMiniHome: () -> Void
    @Environment(\.sizeCategory) var sizeCategory
    @EnvironmentObject var auth: AuthRuntime
    @ObservedObject var collection = LocalPlantCollectionStore.shared
    @StateObject var store = HomeDashboardStore()
    @StateObject var weatherRuntime = WeatherRuntime()
    @State var notificationState = NotificationRuntimeState.initial
    @State var showsRegionSettings = false
    @State var isInitialLoadComplete = false
    @State var pendingMiniHomeOpen = false
    let calendar = PlantCareCalendar()

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                authenticationContent
                if authenticationState == .authenticated {
                    miniHomeSection
                    weatherSection
                        .id("home.weather.section")
                    careSection
                    notificationSection
                    syncSection
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
        }
        .background(PlanteriorPalette.canvas.color)
        .environment(\.sizeCategory, effectiveSizeCategory)
        .navigationTitle("홈")
        .task {
            remountAccount(accountScopeID)
            collection.loadQAFixtureIfNeeded()
            miniHomeRepository.seedQAIfNeeded()
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
        .onReceive(
            NotificationCenter.default.publisher(
                for: .miniHomeCommittedDidChange
            )
        ) { _ in
            reload()
        }
        .sheet(isPresented: $showsRegionSettings) {
            RegionSettingsView(
                weather: weatherRuntime,
                dismiss: {
                    showsRegionSettings = false
                    Task { await refreshWeather() }
                }
            )
        }
    }

    private var careSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("오늘의 돌봄")
                .font(PlanteriorTypography.sectionTitle)
            if store.snapshot.careItems.isEmpty {
                PlanteriorCard {
                    Text("예정된 돌봄이 없어요.")
                        .foregroundStyle(
                            PlanteriorPalette.textSecondary.color
                        )
                }
            } else {
                ForEach(
                    Array(store.snapshot.careItems.enumerated()),
                    id: \.element.plantID
                ) { index, item in
                    PlanteriorCard {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(item.displayName)
                                .font(PlanteriorTypography.sectionTitle)
                                .accessibilityIdentifier("home.care.row.\(index)")
                            Text(statusText(item.status))
                                .foregroundStyle(statusColor(item.status))
                        }
                    }
                }
            }
        }
    }

    private var notificationSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("알림")
                .font(PlanteriorTypography.sectionTitle)
            PlanteriorCard {
                VStack(alignment: .leading, spacing: 8) {
                    notificationAuthorizationText
                    VStack(alignment: .leading, spacing: 2) {
                        Text("기본 알림")
                        Text(store.globalNotificationTime)
                    }
                    notificationEndpointText
                    if notificationState.endpoint == .registered {
                        Text("예정 알림 \(store.plannedNotificationCount)건")
                            .accessibilityIdentifier(
                                "home.notification.scheduled"
                            )
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var notificationAuthorizationText: some View {
        switch notificationState.authorization {
        case .notDetermined:
            Text("알림 권한 미선택")
                .accessibilityIdentifier("home.notification.status")
        case .denied:
            VStack(alignment: .leading, spacing: 2) {
                Text("알림 꺼짐")
                Text("돌봄 기능 유지")
            }
            .accessibilityIdentifier("home.notification.denied")
        case .authorized:
            Text("알림 켜짐")
                .accessibilityIdentifier("home.notification.status")
        }
    }

    private var notificationEndpointText: some View {
        VStack(alignment: .leading, spacing: 2) {
            if notificationState.endpoint == .registered {
                Text("알림 기기")
                Text("등록 완료")
            } else {
                Text("서버 알림")
                Text("준비 중")
            }
        }
        .foregroundStyle(PlanteriorPalette.textSecondary.color)
    }

    private var syncSection: some View {
        PlanteriorCard {
            Text(syncText)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("home.sync.status")
        }
    }
}
