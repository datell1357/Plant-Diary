import AVFoundation
import CoreLocation
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI
import UserNotifications

struct SettingsView: View {
    let openMilestones: () -> Void
    @EnvironmentObject private var auth: AuthRuntime
    @State private var notificationStatus = "확인 중"
    @State private var showsPrivacy = false
    @State private var showsDeletion = false
    @AppStorage("settings.watering.enabled")
    private var wateringEnabled = true
    @AppStorage("settings.weather.enabled")
    private var weatherEnabled = true
    @AppStorage("settings.disclosure.acknowledged")
    private var disclosureAcknowledged = false
    @AppStorage("settings.region")
    private var region = "서울"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("권한과 알림")
                    .font(PlanteriorTypography.screenTitle)
                statusCard
                Toggle("물주기 알림", isOn: $wateringEnabled)
                    .accessibilityIdentifier(
                        "settings.alerts.watering-enabled"
                    )
                Toggle("날씨 알림", isOn: $weatherEnabled)
                    .accessibilityIdentifier(
                        "settings.alerts.weather-enabled"
                    )
                Menu("날씨 지역 · \(region)") {
                    Button("서울") { region = "서울" }
                    Button("부산") { region = "부산" }
                    Button("제주") { region = "제주" }
                }
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .accessibilityIdentifier("settings.region.open")
                Toggle(
                    "개인정보 안내 확인",
                    isOn: $disclosureAcknowledged
                )
                .accessibilityIdentifier(
                    "settings.disclosure.acknowledged"
                )
                Button("꾸미기 마일스톤") {
                    openMilestones()
                }
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .accessibilityIdentifier("settings.milestones")
                Button("개인정보 처리방침") {
                    showsPrivacy = true
                }
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .accessibilityIdentifier("settings.privacy")
                Button("계정 삭제") {
                    showsDeletion = true
                }
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .accessibilityIdentifier("settings.delete-account")
                Button("로그아웃") {
                    auth.pendingLogout = true
                }
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .accessibilityIdentifier("auth.logout")
            }
            .padding(20)
            .padding(.bottom, 140)
        }
        .background(PlanteriorPalette.canvas.color)
        .navigationTitle("설정")
        .accessibilityIdentifier("settings.screen")
        .task {
            AnalyticsRecorder.shared.record(.screenViewed(.settings))
            let settings = await UNUserNotificationCenter.current()
                .notificationSettings()
            notificationStatus = Self.notificationText(
                settings.authorizationStatus
            )
        }
        .onChange(of: wateringEnabled) { _, enabled in
            let time = LocalNotificationPreferenceStore.shared.global?.time ??
                (try? LocalTime.parse("09:00:00"))
            if let time {
                LocalNotificationPreferenceStore.shared.setGlobal(
                    enabled: enabled,
                    time: time
                )
            }
        }
        .onChange(of: weatherEnabled) { _, enabled in
            LocalWeatherAlertStore.shared.setGlobalEnabled(enabled)
        }
        .onChange(of: region) { _, value in
            let code = value == "부산" ? "manual-busan" :
                value == "제주" ? "manual-jeju" : "manual-seoul"
            UserDefaults.standard.set(code, forKey: "weather.manual-region")
        }
        .sheet(isPresented: $showsPrivacy) {
            NavigationStack {
                PrivacyPolicyView()
            }
        }
        .sheet(isPresented: $showsDeletion) {
            NavigationStack {
                AccountDeletionView(onCompleted: performDeletionCleanup)
            }
        }
    }

    private func performDeletionCleanup() async -> [String] {
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_DELETION_FIXTURE"
            ] == "1" {
                return [
                    "auth", "keychain", "swiftdata", "sync",
                    "userdefaults", "notifications", "routes"
                ]
            }
        #endif
        UNUserNotificationCenter.current()
            .removeAllPendingNotificationRequests()
        UNUserNotificationCenter.current()
            .removeAllDeliveredNotifications()
        if let bundleID = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(
                forName: bundleID
            )
        }
        await auth.completeSignOut(action: .discard)
        return [
            "auth", "keychain", "swiftdata", "sync",
            "userdefaults", "notifications", "routes"
        ]
    }

    private var statusCard: some View {
        PlanteriorCard {
            VStack(alignment: .leading, spacing: 12) {
                Text("카메라 · \(Self.cameraText)")
                    .accessibilityIdentifier("settings.permission.camera")
                Text("알림 · \(notificationStatus)")
                    .accessibilityIdentifier(
                        "settings.permission.notifications"
                    )
                Text("위치 · \(Self.locationText)")
                    .accessibilityIdentifier("settings.permission.location")
                Text("마지막 동기화 · 서버 상태 기준")
                    .accessibilityIdentifier("settings.sync.status")
            }
        }
    }

    private static var locationText: String {
        switch CLLocationManager().authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse: "허용됨"
        case .denied, .restricted: "허용 안 됨"
        case .notDetermined: "확인 필요"
        @unknown default: "확인 필요"
        }
    }

    private static var cameraText: String {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: "허용됨"
        case .denied, .restricted: "허용 안 됨"
        case .notDetermined: "확인 필요"
        @unknown default: "확인 필요"
        }
    }

    private static func notificationText(
        _ status: UNAuthorizationStatus
    ) -> String {
        switch status {
        case .authorized, .provisional, .ephemeral: "허용됨"
        case .denied: "허용 안 됨"
        case .notDetermined: "확인 필요"
        @unknown default: "확인 필요"
        }
    }
}
