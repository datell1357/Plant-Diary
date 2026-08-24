import PlanteriorDesignSystem
import SwiftUI

struct RegionSettingsView: View {
    @Environment(\.dismiss) var dismiss
    @Environment(\.sizeCategory) var sizeCategory
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
        VStack(spacing: 0) {
            topBar
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    searchField
                    Spacer().frame(height: PlanteriorSpacing.extraLarge)
                    currentLocationCard
                    Spacer().frame(height: PlanteriorSpacing.extraLarge)
                    Text("최근 검색 지역")
                        .font(PlanteriorTypography.caption.weight(.semibold))
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                        .accessibilityIdentifier("weather.recent-regions")
                    Spacer().frame(
                        height: SettingsReferenceMetrics.regionRecentGap
                    )
                    regionCard
                    #if DEBUG
                        revokeLocationButton
                    #endif
                }
                .padding(.horizontal, PlanteriorSpacing.extraLarge)
                .padding(.top, PlanteriorSpacing.large)
                .padding(.bottom, PlanteriorSpacing.large)
            }
            .accessibilityIdentifier("region-settings.screen")
            .settingsReferenceBody()
        }
        .settingsReferenceChrome()
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var topBar: some View {
        PlanteriorTopBar("관리 지역 설정", leading: {
            SettingsBackButton(identifier: "weather.region.back") {
                dismiss()
            }
        }, trailing: {
            EmptyView()
        })
        .background {
            SettingsTopBarFrame(identifier: "region-settings.top-bar")
        }
        .settingsReferenceTopBar()
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
        #if DEBUG
            if let fixture = ProcessInfo.processInfo.environment[
                "QA_SETTINGS_LOCATION_TEXT"
            ] {
                return fixture
            }
        #endif
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

}
