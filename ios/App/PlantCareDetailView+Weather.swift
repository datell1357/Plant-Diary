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
    @Environment(\.dismiss) var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var expandedIndex: Int? = 0

    private let guidance = [
        SymptomGuidance(
            icon: "🍂",
            title: "잎이 노랗게 변해요",
            cause: "과습 또는 영양 부족",
            action: "물 주기 간격을 대폭 늘려 화분의 속흙까지 완전히 건조시키고 배수 상태를 확인하세요. 필요시 영양제를 보충합니다."
        ),
        SymptomGuidance(
            icon: "🥀",
            title: "잎이 힘없이 축 처져요",
            cause: "수분이 부족하거나 뿌리가 오래 젖어 있을 때 나타날\u{00A0}수\u{00A0}있어요.",
            action: "겉흙과 속흙의 수분을 확인하고 식물 상태에 맞춰 물 주기 일정을 조정하세요."
        ),
        SymptomGuidance(
            icon: "🟤",
            title: "잎에 갈색 반점이 생겨요",
            cause: "강한 빛, 통풍 부족 또는 잎에 오래 남은 물방울이 원인일 수 있어요.",
            action: "밝은 간접광과 통풍을 확보하고 손상 부위가 번지는지 며칠간 관찰하세요."
        ),
        SymptomGuidance(
            icon: "🐛",
            title: "벌레가 기어다녀요",
            cause: "잎 뒷면이나 줄기 주변에 해충이 머물고 있을 수 있어요.",
            action: "다른 식물과 잠시 분리하고 잎 양면을 확인한 뒤 식물용 방제 제품의 사용법을 따르세요."
        )
    ]

    var body: some View {
        VStack(spacing: 0) {
            remedyTopBar
            ScrollView {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    Text("반려식물: \(displayName) 🌱")
                        .font(PlanteriorTypography.caption.weight(.semibold))
                        .foregroundStyle(PlanteriorPalette.accent.color)
                        .padding(.horizontal, PlanteriorSpacing.medium)
                        .frame(minHeight: PlanteriorControl.minimumTarget)
                        .background {
                            RoundedRectangle(cornerRadius: PlanteriorRadius.small)
                                .fill(PlanteriorPalette.subtle.color)
                                .frame(
                                    height: PlanteriorControl.minimumTarget
                                        - PlanteriorSpacing.large
                                )
                        }
                        .accessibilityIdentifier("remedy.context")
                        .padding(.leading, PlanteriorSpacing.small)
                    VStack(spacing: PlanteriorSpacing.medium) {
                        ForEach(guidance.indices, id: \.self) { symptomCard($0) }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, PlanteriorSpacing.large)
                .padding(
                    .top,
                    dynamicTypeSize.isAccessibilitySize ? PlanteriorSpacing.large : 0
                )
                .padding(.bottom, PlanteriorSpacing.large)
            }
            .plantCareReferenceBody()
            .accessibilityIdentifier("remedy.screen")
        }
        .background(PlanteriorPalette.canvas.color)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private func symptomCard(_ index: Int) -> some View {
        let item = guidance[index]
        return VStack(alignment: .leading, spacing: 0) {
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
                    .font(PlanteriorTypography.caption.weight(.semibold))
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .accessibilityHidden(true)
                }
                .padding(.horizontal, PlanteriorSpacing.large)
                .frame(
                    maxWidth: .infinity,
                    minHeight: PlanteriorLayout.mediaThumbnailSize
                )
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("remedy.symptom.\(index)")
            .accessibilityValue(expandedIndex == index ? "펼쳐짐" : "접힘")
            if expandedIndex == index {
                Divider()
                    .padding(.horizontal, PlanteriorSpacing.large)
                guidanceBody(item, index: index)
                    .padding(.horizontal, PlanteriorSpacing.large)
                    .padding(.top, PlanteriorSpacing.medium)
                    .padding(.bottom, PlanteriorSpacing.huge)
            }
        }
        .background(PlanteriorPalette.surface.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        .overlay {
            RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                .stroke(
                    PlanteriorPalette.border.color,
                    lineWidth: PlanteriorControl.hairline
                )
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("remedy.card.\(index)")
    }

    private func guidanceBody(_ item: SymptomGuidance, index: Int) -> some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
            Text("⚠️ 원인")
                .font(PlanteriorTypography.caption.weight(.semibold))
                .foregroundStyle(PlanteriorPalette.warning.color)
            Text(item.cause)
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("remedy.cause.\(index)")
            Text("✨ 대처 방법")
                .font(PlanteriorTypography.caption.weight(.semibold))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .padding(.top, PlanteriorSpacing.extraSmall)
            Text(item.action)
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .padding(.trailing, PlanteriorSpacing.extraLarge)
                .accessibilityIdentifier("remedy.action.\(index)")
        }
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
