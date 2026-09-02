import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct SettingsView: View {
    let returnFromRoot: (() -> Void)?
    @Environment(\.dismiss) var dismiss
    @Environment(\.openURL) var openURL
    @Environment(\.sizeCategory) var sizeCategory
    @EnvironmentObject var auth: AuthRuntime
    @StateObject var weather = WeatherRuntime()
    @State var notificationStatus = "확인 중"
    @State var regionName = "서울특별시"
    @State var quietHoursSummary = "없음"
    @State var showsPrivacy = false
    @State var showsDeletion = false
    @State var showsQuietHours = false
    @State var showsRegionSettings = false
    @State var wateringEnabled = true
    @State var notificationAuthorizationRequest: NotificationAuthorizationRequestContext?

    init(
        returnFromRoot: (() -> Void)? = nil
    ) {
        self.returnFromRoot = returnFromRoot
    }

    var body: some View {
        VStack(spacing: 0) {
            PlanteriorTopBar("설정", leading: {
                SettingsBackButton(identifier: "settings.back") {
                    if let returnFromRoot {
                        returnFromRoot()
                    } else {
                        dismiss()
                    }
                }
            })
            .settingsReferenceTopBar()
            ScrollView {
                VStack(
                    alignment: .leading,
                    spacing: SettingsReferenceMetrics.rootGroupSpacing
                ) {
                    profileCard
                    alertGroup
                        .padding(
                            .top,
                            sizeCategory.isAccessibilityCategory
                                ? SettingsReferenceMetrics.rootAccessibilityAlertOffset
                                : PlanteriorSpacing.none
                        )
                    environmentGroup
                    accountGroup
                    operationalGroup
                }
                .padding(.horizontal, PlanteriorSpacing.large)
                .padding(.top, PlanteriorSpacing.small)
                .padding(.bottom, PlanteriorSpacing.large)
            }
            .accessibilityIdentifier("settings.screen")
            .settingsReferenceBody()
        }
        .settingsReferenceChrome()
        .accessibilityElement(children: .contain)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(isPresented: $showsQuietHours) {
            QuietHoursSettingsView(onSaved: reloadPresentedValues)
        }
        .task {
            AnalyticsRecorder.shared.record(.screenViewed(.settings))
            mountPresentedAccount()
            weather.reloadAlertPreferences()
            reloadPresentedValues()
            await reloadNotificationAuthorization()
        }
        .onChange(of: wateringEnabled) { _, enabled in
            let time = LocalNotificationPreferenceStore.shared.global?.time
                ?? (try? LocalTime.parse("09:00"))
            if let time {
                LocalNotificationPreferenceStore.shared.setGlobal(
                    enabled: enabled,
                    time: time
                )
                if enabled {
                    LocalNotificationScheduleStore.shared
                        .refreshDeliveryForCurrentAccount()
                } else {
                    LocalNotificationScheduleStore.shared
                        .suspendDeliveryForCurrentAccount()
                }
            }
        }
        .onChange(of: auth.accountID?.rawValue) {
            notificationAuthorizationRequest = nil
            mountPresentedAccount()
            reloadPresentedValues()
            Task { await reloadNotificationAuthorization() }
        }
        .onReceive(
            NotificationCenter.default.publisher(
                for: .localNotificationAuthorizationDidChange
            )
        ) { _ in
            Task { await reloadNotificationAuthorization() }
        }
        .fullScreenCover(isPresented: $showsRegionSettings) {
            NavigationStack {
                RegionSettingsView(
                    weather: weather,
                    showsCloseButton: true,
                    onSaved: reloadPresentedValues
                )
            }
        }
        .sheet(isPresented: $showsPrivacy) {
            NavigationStack { PrivacyPolicyView() }
        }
        .sheet(isPresented: $showsDeletion) {
            let ownerID = accountScopeID.flatMap { try? AccountID.parse($0) }
            NavigationStack {
                AccountDeletionView(ownerID: ownerID) { completedOwnerID in
                    await performDeletionCleanup(ownerID: completedOwnerID)
                }
            }
        }
    }
}
