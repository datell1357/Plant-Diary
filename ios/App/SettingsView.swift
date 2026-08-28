import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI
import UserNotifications

struct SettingsView: View {
    let openMilestones: () -> Void
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

    init(
        openMilestones: @escaping () -> Void,
        returnFromRoot: (() -> Void)? = nil
    ) {
        self.openMilestones = openMilestones
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
        .onChange(of: auth.accountID?.rawValue) {
            mountPresentedAccount()
            reloadPresentedValues()
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

    func reloadPresentedValues() {
        wateringEnabled = LocalNotificationPreferenceStore.shared.global?.enabled
            ?? true
        quietHoursSummary = QuietHoursPresentation.summary(
            LocalNotificationPreferenceStore.shared.quietHours
        )
        regionName = Self.fullRegionName(weather.manualRegionCode)
    }

    func mountPresentedAccount() {
        weather.mount(accountID: accountScopeID)
        LocalNotificationPreferenceStore.shared.mount(
            accountID: accountScopeID
        )
    }

    var accountScopeID: String? {
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_AUTHENTICATED"] == "1" {
                return ProcessInfo.processInfo.environment["QA_ACCOUNT_ID"]
                    ?? "qa-account"
            }
        #endif
        return auth.accountID?.rawValue
    }

    func performDeletionCleanup(ownerID: AccountID) async -> [String] {
        await AccountDeletionLocalCleanup.perform(ownerID: ownerID, auth: auth)
    }

    static func clearAccountDefaults(
        ownerID: AccountID,
        defaults: UserDefaults = .standard
    ) -> Bool {
        let accountSegment = ".\(ownerID.rawValue)."
        let scopedKeys = defaults.dictionaryRepresentation().keys.filter {
            $0.contains(accountSegment)
        }
        scopedKeys.forEach(defaults.removeObject(forKey:))
        return !defaults.dictionaryRepresentation().keys.contains {
            $0.contains(accountSegment)
        }
    }

    static func notificationText(_ status: UNAuthorizationStatus) -> String {
        switch status {
        case .authorized, .provisional, .ephemeral: "허용됨"
        case .denied: "허용 안 됨"
        case .notDetermined: "확인 필요"
        @unknown default: "확인 필요"
        }
    }
}
