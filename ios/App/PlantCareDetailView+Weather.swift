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
    let scientificName: String?
    let hasWateringBaseline: Bool
    @Environment(\.dismiss) var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var expandedIndex: Int? = 0

    private var education: PlantSymptomEducation {
        PlantSymptomEducationCatalog.education(
            scientificName: scientificName,
            hasWateringBaseline: hasWateringBaseline
        )
    }

    var body: some View {
        VStack(spacing: PlanteriorSpacing.none) {
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
                    Text(PlantSymptomEducationCatalog.disclaimer)
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                        .accessibilityIdentifier("remedy.disclaimer")
                    VStack(spacing: PlanteriorSpacing.medium) {
                        ForEach(education.items.indices, id: \.self) { index in
                            symptomCard(education.items[index], index: index)
                        }
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

    private func symptomCard(_ item: PlantSymptomGuidance, index: Int) -> some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.none) {
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
                    .padding(.bottom, PlantCareReferenceMetrics.remedyExpandedBottomInset)
            }
        }
        .remedyReferenceMinimumHeight(isExpanded: expandedIndex == index)
        .background(PlanteriorPalette.surface.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        .overlay {
            RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                .stroke(
                    PlanteriorPalette.border.color,
                    lineWidth: PlanteriorControl.hairline
                )
        }
        .remedyCardAccessibility(index: index)
    }

    private func guidanceBody(_ item: PlantSymptomGuidance, index: Int) -> some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
            Text("가능성")
                .font(PlanteriorTypography.caption.weight(.semibold))
                .foregroundStyle(PlanteriorPalette.warningText.color)
                .accessibilityIdentifier("remedy.cause-heading.\(index)")
            Text(item.possibleCause)
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("remedy.cause.\(index)")
            Text("확인 순서")
                .font(PlanteriorTypography.caption.weight(.semibold))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .padding(.top, PlanteriorSpacing.extraSmall)
                .accessibilityIdentifier("remedy.check-heading.\(index)")
            Text(KoreanTypography.binding(
                item.initialResponse,
                phrases: PlantCareKoreanPhrases.remedy
            ))
            .font(PlanteriorTypography.supporting)
            .foregroundStyle(PlanteriorPalette.textSecondary.color)
            .padding(.trailing, PlanteriorSpacing.extraLarge)
            .accessibilityLabel(item.initialResponse)
            .accessibilityIdentifier("remedy.action.\(index)")
        }
    }
}

extension Notification.Name {
    static let weatherAlertPreferencesDidChange =
        Notification.Name("weatherAlertPreferencesDidChange")
}
