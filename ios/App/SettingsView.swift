import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI
import UserNotifications

struct SettingsView: View {
    let openMilestones: () -> Void
    @Environment(\.openURL) var openURL
    @EnvironmentObject var auth: AuthRuntime
    @StateObject var weather = WeatherRuntime()
    @State var notificationStatus = "확인 중"
    @State var regionName = "서울특별시"
    @State var quietHoursSummary = "없음"
    @State var showsPrivacy = false
    @State var showsDeletion = false
    @State var showsQuietHours = false
    @State var showsRegionSettings = false
    @AppStorage("settings.watering.enabled") var wateringEnabled = true

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.large) {
                profileCard
                alertGroup
                environmentGroup
                accountGroup
            }
            .padding(PlanteriorSpacing.large)
            .padding(.bottom, PlanteriorSpacing.large)
        }
        .accessibilityIdentifier("settings.screen")
        .background(PlanteriorPalette.canvas.color)
        .navigationTitle("설정")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            AnalyticsRecorder.shared.record(.screenViewed(.settings))
            weather.reloadAlertPreferences()
            reloadPresentedValues()
            let settings = await UNUserNotificationCenter.current()
                .notificationSettings()
            notificationStatus = Self.notificationText(
                settings.authorizationStatus
            )
        }
        .onChange(of: wateringEnabled) { _, enabled in
            let time = LocalNotificationPreferenceStore.shared.global?.time
                ?? (try? LocalTime.parse("09:00"))
            if let time {
                LocalNotificationPreferenceStore.shared.setGlobal(
                    enabled: enabled,
                    time: time
                )
            }
        }
        .fullScreenCover(isPresented: $showsQuietHours) {
            NavigationStack {
                QuietHoursSettingsView(
                    showsCloseButton: true,
                    onSaved: reloadPresentedValues
                )
            }
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
            NavigationStack {
                AccountDeletionView(onCompleted: performDeletionCleanup)
            }
        }
    }

    func reloadPresentedValues() {
        quietHoursSummary = QuietHoursPresentation.summary(
            LocalNotificationPreferenceStore.shared.quietHours
        )
        regionName = Self.fullRegionName(weather.manualRegionCode)
    }

    func performDeletionCleanup() async -> [String] {
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_DELETION_FIXTURE"] == "1" {
                return Self.deletionReceipt
            }
        #endif
        UNUserNotificationCenter.current()
            .removeAllPendingNotificationRequests()
        UNUserNotificationCenter.current()
            .removeAllDeliveredNotifications()
        if let bundleID = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(forName: bundleID)
        }
        await auth.completeSignOut(action: .discard)
        return Self.deletionReceipt
    }

    static let deletionReceipt = [
        "auth", "keychain", "swiftdata", "sync",
        "userdefaults", "notifications", "routes"
    ]

    static func notificationText(_ status: UNAuthorizationStatus) -> String {
        switch status {
        case .authorized, .provisional, .ephemeral: "허용됨"
        case .denied: "허용 안 됨"
        case .notDetermined: "확인 필요"
        @unknown default: "확인 필요"
        }
    }
}
