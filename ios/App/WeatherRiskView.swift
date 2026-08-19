import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct WeatherRiskView: View {
    let risks: [RiskType]
    let isStale: Bool
    let plannedAlertCount: Int

    var body: some View {
        if !risks.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(risks, id: \.self) { risk in
                    Text(label(for: risk))
                        .foregroundStyle(PlanteriorPalette.accent.color)
                        .accessibilityIdentifier(
                            "weather.risk.\(risk.rawValue.lowercased())"
                        )
                }
                if isStale {
                    Text("오래된 정보 · 알림 없음")
                        .foregroundStyle(
                            PlanteriorPalette.textSecondary.color
                        )
                        .accessibilityIdentifier("weather.stale")
                } else {
                    Text("예정 위험 알림 \(plannedAlertCount)건")
                        .accessibilityIdentifier("weather.alert-count")
                }
            }
        }
    }

    private func label(for risk: RiskType) -> String {
        switch risk {
        case .highTemperature: "고온 주의"
        case .lowTemperature: "저온 주의"
        case .dry: "건조 주의"
        case .overwatered: "과습 주의"
        }
    }
}
