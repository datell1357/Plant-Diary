import AVFoundation
import CoreLocation
import PlanteriorDesignSystem
import SwiftUI
import UIKit

extension SettingsView {
    var alertGroup: some View {
        settingsGroup("알림 관리") {
            toggleRow(
                "물 주기 알림",
                icon: .system("drop"),
                isOn: Binding(
                    get: { wateringEnabled },
                    set: { setWateringNotificationsEnabled($0) }
                ),
                id: "settings.alerts.watering-enabled"
            )
            .disabled(notificationAuthorizationRequestInFlight)
            rowDivider
            toggleRow(
                "날씨 알림",
                icon: .system("cloud.sun"),
                isOn: Binding(
                    get: { weather.globalAlertsEnabled },
                    set: { setWeatherNotificationsEnabled($0) }
                ),
                id: "settings.alerts.weather-enabled"
            )
            .disabled(notificationAuthorizationRequestInFlight)
            rowDivider
            Button {
                showsQuietHours = true
            } label: {
                rowLabel(
                    "알림 금지 시간 설정",
                    icon: .system("clock"),
                    value: quietHoursSummary
                )
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("settings.quiet-hours.open")
            .padding(
                .top,
                sizeCategory.isAccessibilityCategory
                    ? PlanteriorSpacing.medium
                    : PlanteriorSpacing.none
            )
        }
    }

    var environmentGroup: some View {
        settingsGroup("지역 및 환경") {
            Button {
                showsRegionSettings = true
            } label: {
                rowLabel("관리 지역 설정", icon: .location, value: regionName)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("settings.region.open")
            rowDivider
            Button {
                guard let url = URL(string: UIApplication.openSettingsURLString) else {
                    return
                }
                openURL(url)
            } label: {
                rowLabel(
                    "위치 권한 관리",
                    icon: .system("shield"),
                    value: Self.locationText
                )
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("settings.permission.location")
        }
    }

    var accountGroup: some View {
        settingsGroup("계정") {
            actionRow(
                "개인정보 처리방침",
                icon: .system("list.clipboard"),
                id: "settings.privacy"
            ) { showsPrivacy = true }
            rowDivider
            rowLabel(
                "앱 버전",
                icon: .system("info.circle"),
                value: appVersion,
                disclosure: false
            )
            .accessibilityIdentifier("settings.app-version")
            rowDivider
            actionRow(
                "로그아웃",
                icon: .system("rectangle.portrait.and.arrow.right"),
                id: "auth.logout",
                disclosure: false
            ) { auth.pendingLogout = true }
        }
    }

    var operationalGroup: some View {
        settingsGroup("기타 설정") {
            permissionRow(
                "카메라",
                value: Self.cameraText,
                id: "settings.permission.camera"
            )
            rowDivider
            permissionRow(
                "알림",
                value: notificationStatus,
                id: "settings.permission.notifications"
            )
            rowDivider
            actionRow(
                "계정 삭제",
                icon: .system("person.crop.circle.badge.minus"),
                id: "settings.delete-account"
            ) { showsDeletion = true }
        }
    }

    var appVersion: String {
        let version = Bundle.main.infoDictionary?[
            "CFBundleShortVersionString"
        ] as? String ?? "1.0"
        let normalizedVersion = version.split(separator: ".").count == 2
            ? "\(version).0"
            : version
        return "v\(normalizedVersion)"
    }
}

extension SettingsView {
    static var locationText: String {
        #if DEBUG
            switch ProcessInfo.processInfo.environment["QA_WEATHER_AUTHORIZATION"] {
            case "full", "reduced": return "허용됨"
            case "denied", "revoked": return "허용 안 됨"
            default: break
            }
        #endif
        return switch CLLocationManager().authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse: "허용됨"
        case .denied, .restricted: "허용 안 됨"
        case .notDetermined: "확인 필요"
        @unknown default: "확인 필요"
        }
    }

    static var cameraText: String {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: "허용됨"
        case .denied, .restricted: "허용 안 됨"
        case .notDetermined: "확인 필요"
        @unknown default: "확인 필요"
        }
    }

    static func fullRegionName(_ code: String?) -> String {
        switch code {
        case "manual-seoul": "서울특별시"
        case "manual-busan": "경기도 성남시"
        case "manual-haeundae": "부산광역시"
        case "manual-incheon": "인천광역시"
        case "manual-daegu": "대구광역시"
        case "manual-daejeon": "대전광역시"
        case "manual-gwangju": "광주광역시"
        case "manual-jeju": "제주특별자치도"
        case let code?: WeatherRuntime.regionName(for: code)
        case nil: "현재 위치"
        }
    }
}
