import PlanteriorDesignSystem
import SwiftUI

extension HomeDashboardView {
    /// Figma `alert-banner` §6.4: amber tint, 20pt leading glyph, 12 gap, and
    /// 13 Semibold amber copy. Geometry is identical in both auth states; only
    /// the copy differs (§8.3).
    var weatherWarningBanner: some View {
        HStack(
            alignment: .center,
            spacing: HomeReferenceMetrics.weatherRowSpacing
        ) {
            Image(systemName: "exclamationmark.shield")
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.warningText.color)
                .frame(
                    width: HomeReferenceMetrics.weatherGlyphFrame,
                    height: HomeReferenceMetrics.weatherGlyphFrame
                )
                .accessibilityHidden(true)
            Text(weatherWarningText)
                .font(PlanteriorTypography.caption.weight(.semibold))
                .foregroundStyle(PlanteriorPalette.warningText.color)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("home.weather.warning")
        }
        .padding(.horizontal, PlanteriorSpacing.large)
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(height: HomeReferenceMetrics.weatherRowHeight)
        .background(PlanteriorPalette.homeWeatherWarningSurface.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        .overlay {
            RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                .stroke(PlanteriorPalette.homeWeatherWarningBorder.color)
        }
    }

    var weatherSection: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
            Text("날씨 기반 안내")
                .font(PlanteriorTypography.sectionTitle)
            PlanteriorCard {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                    Text("날씨는 식물 위험 안내에만 사용해요.")
                        .accessibilityIdentifier("weather.purpose")
                    if let regionName = weatherRuntime.effectiveRegionName {
                        Text("지역 · \(regionName)")
                            .accessibilityIdentifier("weather.region")
                    }
                    weatherStatus
                    WeatherRiskView(
                        risks: weatherRuntime.risks,
                        isStale: weatherRuntime.isStale,
                        plannedAlertCount:
                        weatherRuntime.plannedAlertCount
                    )
                    #if DEBUG
                        Text(
                            "위치 요청 \(weatherRuntime.locationRequestCount)회"
                        )
                        .accessibilityIdentifier("weather.location.calls")
                    #endif
                    Button("날씨 지역 설정") {
                        showsRegionSettings = true
                    }
                    .frame(minHeight: PlanteriorControl.minimumTarget)
                    .foregroundStyle(PlanteriorPalette.accent.color)
                    .accessibilityIdentifier("weather.open-region")
                }
            }
        }
    }

    @ViewBuilder
    private var weatherStatus: some View {
        switch store.snapshot.weather {
        case let .content(summary):
            Text(summary)
                .accessibilityIdentifier("home.weather.content")
        case .loading:
            ProgressView("날씨를 불러오는 중")
                .accessibilityIdentifier("home.weather.loading")
        case .failed:
            VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                Text("날씨 정보를 불러오지 못했어요.")
                    .accessibilityIdentifier("home.weather.failed")
                Text("돌봄 일정은 계속 사용할 수 있어요.")
            }
        case .unavailable:
            Text("지역을 설정하면 날씨 안내가 표시돼요.")
                .accessibilityIdentifier("home.weather.unavailable")
        }
    }
}
