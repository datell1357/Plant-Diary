import PlanteriorDesignSystem
import SwiftUI

struct RegionSettingsView: View {
    @ObservedObject var weather: WeatherRuntime
    let dismiss: () -> Void
    @State private var regionQuery = ""
    private let regions = [
        ("manual-seoul", "서울"),
        ("manual-busan", "부산"),
        ("manual-incheon", "인천"),
        ("manual-daegu", "대구"),
        ("manual-daejeon", "대전"),
        ("manual-gwangju", "광주"),
        ("manual-jeju", "제주")
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section("날씨 기능") {
                    Text("날씨는 식물의 온도·습도 위험 안내에만 사용해요.")
                        .accessibilityIdentifier("weather.purpose")
                    Toggle(
                        "날씨 위험 알림",
                        isOn: Binding(
                            get: { weather.globalAlertsEnabled },
                            set: { weather.setGlobalAlertsEnabled($0) }
                        )
                    )
                    .accessibilityIdentifier("weather.alerts-enabled")
                    Text(
                        weather.globalAlertsEnabled
                            ? "위험 알림 켜짐"
                            : "위험 알림 꺼짐"
                    )
                    .accessibilityIdentifier(
                        weather.globalAlertsEnabled
                            ? "weather.alerts.enabled"
                            : "weather.alerts.disabled"
                    )
                }
                Section("현재 위치") {
                    Text(authorizationText)
                    Button("위치 권한 요청") {
                        weather.requestLocationPermission()
                    }
                    .accessibilityIdentifier("weather.request-location")
                    #if DEBUG
                        if ProcessInfo.processInfo.environment[
                            "QA_WEATHER_SHOW_REVOKE"
                        ] == "1" {
                            Button("QA 위치 철회") {
                                weather.revokeLocationForQA()
                                dismiss()
                            }
                            .accessibilityIdentifier("weather.qa-revoke")
                        }
                    #endif
                }
                Section("수동 지역") {
                    TextField("지역 검색", text: $regionQuery)
                        .accessibilityIdentifier("weather.manual-region")
                    ForEach(filteredRegions, id: \.code) { region in
                        Button(region.name) {
                            weather.setManualRegion(region.code)
                            dismiss()
                        }
                        .accessibilityIdentifier(
                            "weather.region-result.\(region.code)"
                        )
                    }
                    Button("수동 지역 해제") {
                        weather.setManualRegion(nil)
                        dismiss()
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(PlanteriorPalette.canvas.color)
            .navigationTitle("날씨 지역")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("닫기", action: dismiss)
                }
            }
        }
    }

    private var authorizationText: String {
        switch weather.authorization {
        case .notDetermined: "위치 권한 미선택"
        case .denied: "위치 권한 꺼짐"
        case .reduced: "대략적인 위치 사용 중"
        case .full: "정확한 위치 사용 중"
        }
    }

    private var filteredRegions: [(code: String, name: String)] {
        let query = regionQuery.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        return regions.compactMap { code, name in
            guard query.isEmpty || name.localizedCaseInsensitiveContains(query)
            else {
                return nil
            }
            return (code: code, name: name)
        }
    }
}
