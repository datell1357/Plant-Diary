import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension PlantCareDetailView {
    var weatherSection: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
            Text("날씨 알림")
                .font(PlanteriorTypography.sectionTitle)
            PlanteriorCard {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                    weatherAlertToggle
                    Text("관리 지역의 기온과 건조 위험을 식물별로 알려드려요.")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                }
            }
        }
    }

    var weatherAlertToggle: some View {
        Toggle("식물별 날씨 위험 알림", isOn: $weatherAlertsEnabled)
            .frame(minHeight: PlanteriorControl.minimumTarget)
            .onChange(of: weatherAlertsEnabled) { _, enabled in
                guard let plantID = collection.weatherPlantID(at: index) else { return }
                LocalWeatherAlertStore.shared.setPlantEnabled(enabled, plantID: plantID)
                NotificationCenter.default.post(
                    name: .weatherAlertPreferencesDidChange,
                    object: nil
                )
            }
            .accessibilityIdentifier("weather.plant-alerts-enabled")
    }
}

struct PlantSymptomRemedyView: View {
    let displayName: String
    let hasWateringBaseline: Bool
    @State private var expandedIndex: Int? = 0

    private let guidance = [
        SymptomGuidance(
            icon: "🍂",
            title: "잎 끝이 갈색으로 변해요",
            cause: "건조한 공기나 불규칙한 물 주기가 원인일 가능성이 있어요.",
            action: "흙의 마름을 먼저 확인하고, 주변 습도와 통풍 상태를 함께 살펴보세요."
        ),
        SymptomGuidance(
            icon: "🌿",
            title: "잎이 축 처져요",
            cause: "수분이 부족하거나 뿌리가 오래 젖어 있을 때 나타날 수 있어요.",
            action: "흙 속 수분을 확인한 뒤 상태에 맞춰 물 주기 일정을 조정하세요."
        ),
        SymptomGuidance(
            icon: "☀️",
            title: "잎 색이 옅어졌어요",
            cause: "빛의 양이 갑자기 달라졌을 가능성이 있어요.",
            action: "밝은 간접광이 드는 곳으로 천천히 옮기고 며칠간 관찰하세요."
        )
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
                Text("반려식물: \(displayName) 🌿")
                    .font(PlanteriorTypography.caption.weight(.semibold))
                    .foregroundStyle(PlanteriorPalette.accent.color)
                    .padding(.horizontal, PlanteriorSpacing.large)
                    .frame(minHeight: PlanteriorControl.minimumTarget)
                    .background(PlanteriorPalette.subtle.color)
                    .clipShape(Capsule())
                    .accessibilityIdentifier("remedy.context")
                Text(wateringContext)
                    .font(PlanteriorTypography.supporting)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                ForEach(guidance.indices, id: \.self) { symptomCard($0) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, PlanteriorSpacing.large)
            .padding(.vertical, PlanteriorSpacing.medium)
        }
        .background(PlanteriorPalette.canvas.color)
        .navigationTitle("증상 대처법")
        .navigationBarTitleDisplayMode(.inline)
        .planteriorInlineNavigationChrome()
        .toolbar(.visible, for: .navigationBar)
        .accessibilityIdentifier("remedy.screen")
    }

    private func symptomCard(_ index: Int) -> some View {
        let item = guidance[index]
        return PlanteriorCard {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
                Button {
                    expandedIndex = expandedIndex == index ? nil : index
                } label: {
                    HStack(spacing: PlanteriorSpacing.medium) {
                        Text(item.icon).accessibilityHidden(true)
                        Text(item.title)
                            .font(PlanteriorTypography.cardTitle)
                            .multilineTextAlignment(.leading)
                        Spacer(minLength: PlanteriorSpacing.small)
                        Image(
                            systemName: expandedIndex == index
                                ? "chevron.up"
                                : "chevron.down"
                        )
                        .foregroundStyle(PlanteriorPalette.textTertiary.color)
                        .accessibilityHidden(true)
                    }
                    .frame(minHeight: PlanteriorControl.minimumTarget)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("remedy.symptom.\(index)")
                .accessibilityValue(expandedIndex == index ? "펼쳐짐" : "접힘")
                if expandedIndex == index {
                    guidanceBody(item, index: index)
                }
            }
        }
    }

    private func guidanceBody(_ item: SymptomGuidance, index: Int) -> some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
            Text("⚠ 원인 가능성")
                .font(PlanteriorTypography.caption.weight(.semibold))
                .foregroundStyle(PlanteriorPalette.warning.color)
            Text(item.cause)
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("remedy.cause.\(index)")
            Text("🌿 확인과 대처 방법")
                .font(PlanteriorTypography.caption.weight(.semibold))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .padding(.top, PlanteriorSpacing.extraSmall)
            Text(item.action)
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("remedy.action.\(index)")
        }
    }

    private var wateringContext: String {
        hasWateringBaseline
            ? "현재 물 주기 기록을 참고해 흙과 잎 상태를 직접 확인해 주세요."
            : "물 주기 기준일이 없어요. 흙을 확인한 뒤 상세 화면에서 기준일을 설정하세요."
    }
}

private struct SymptomGuidance {
    let icon: String
    let title: String
    let cause: String
    let action: String
}

extension Notification.Name {
    static let weatherAlertPreferencesDidChange = Notification.Name(
        "weatherAlertPreferencesDidChange"
    )
}
