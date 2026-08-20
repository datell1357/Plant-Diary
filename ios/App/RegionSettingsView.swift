import PlanteriorDesignSystem
import SwiftUI

struct RegionSettingsView: View {
    @Environment(\.dismiss) var dismiss
    @ObservedObject var weather: WeatherRuntime
    @State var regionQuery = ""
    @State var selectedCode: String?
    @State var usesCurrentLocation: Bool
    @State var recentCodes = [
        "manual-seoul", "manual-busan", "manual-haeundae"
    ]
    let showsCloseButton: Bool
    let onSaved: () -> Void

    let regions = [
        ("manual-seoul", "서울특별시 강남구"),
        ("manual-busan", "경기도 성남시 분당구"),
        ("manual-haeundae", "부산광역시 해운대구"),
        ("manual-incheon", "인천광역시"),
        ("manual-daegu", "대구광역시"),
        ("manual-daejeon", "대전광역시"),
        ("manual-gwangju", "광주광역시"),
        ("manual-jeju", "제주특별자치도")
    ]

    init(
        weather: WeatherRuntime,
        showsCloseButton: Bool = false,
        onSaved: @escaping () -> Void = {}
    ) {
        self.weather = weather
        self.showsCloseButton = showsCloseButton
        self.onSaved = onSaved
        let manualCode = weather.manualRegionCode
        _selectedCode = State(initialValue: manualCode)
        _usesCurrentLocation = State(initialValue: manualCode == nil)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.large) {
                searchField
                currentLocationCard
                Text("최근 검색 지역")
                    .font(PlanteriorTypography.caption.weight(.semibold))
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .padding(.leading, PlanteriorSpacing.large)
                    .accessibilityIdentifier("weather.recent-regions")
                regionCard
                #if DEBUG
                    revokeLocationButton
                #endif
            }
            .padding(PlanteriorSpacing.large)
        }
        .accessibilityIdentifier("region-settings.screen")
        .background(PlanteriorPalette.canvas.color)
        .navigationTitle("관리 지역 설정")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if showsCloseButton {
                ToolbarItem(placement: .topBarLeading) {
                    Button { dismiss() } label: {
                        Image(systemName: "chevron.left")
                    }
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .accessibilityLabel("뒤로")
                    .accessibilityIdentifier("weather.region.back")
                }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("저장", action: save)
                    .accessibilityIdentifier("weather.region.save")
            }
        }
    }

    #if DEBUG
        @ViewBuilder private var revokeLocationButton: some View {
            if ProcessInfo.processInfo.environment["QA_WEATHER_SHOW_REVOKE"] == "1" {
                Button("QA 위치 철회") {
                    weather.revokeLocationForQA()
                    onSaved()
                    dismiss()
                }
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .accessibilityIdentifier("weather.qa-revoke")
            }
        }
    #endif

    var filteredRegions: [(code: String, name: String)] {
        let query = regionQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        let visible = query.isEmpty ? recentCodes : regions.map(\.0)
        return regions.compactMap { code, name in
            guard visible.contains(code),
                  query.isEmpty || name.localizedCaseInsensitiveContains(query)
            else {
                return nil
            }
            return (code: code, name: name)
        }
    }

    var currentLocationText: String {
        if let code = weather.locationRegionCode {
            return WeatherRuntime.regionName(for: code)
        }
        switch weather.authorization {
        case .denied: return "위치 권한을 확인해 주세요"
        case .reduced: return "대략적인 위치 사용 중"
        case .full: return "정확한 위치 사용 중"
        case .notDetermined: return "위치를 확인해 현재 지역을 설정합니다"
        }
    }

    func save() {
        weather.setManualRegion(usesCurrentLocation ? nil : selectedCode)
        onSaved()
        dismiss()
    }
}
