import PlanteriorData
import PlanteriorDesignSystem
import SwiftUI

extension PlantCareDetailView {
    var plant: PlantRegistrationDraft? {
        guard collection.plants.indices.contains(index) else { return nil }
        return collection.plants[index]
    }

    var guideSection: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.large) {
            Text("식물 가이드 및 관리 기준")
                .font(PlanteriorTypography.sectionTitle)
            if let careProfile {
                LazyVGrid(columns: guideColumns, spacing: PlanteriorSpacing.small) {
                    ForEach(careProfile.metrics) { metric in
                        guideCard(metric)
                    }
                }
            } else {
                Text("국내 공공데이터에서 이 식물의 관리 정보를 준비 중이에요.")
                    .font(PlanteriorTypography.caption)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .accessibilityIdentifier("plant.detail.guide-unavailable")
            }
        }
        .frame(
            minHeight: dynamicTypeSize.isAccessibilitySize
                ? nil
                : PlantCareReferenceMetrics.guideMinimumHeight,
            alignment: .top
        )
        .accessibilityElement(children: .contain)
        .accessibilityValue(guideSourceAccessibilityValue)
        .accessibilityIdentifier("plant.detail.guide")
    }

    private func guideCard(_ metric: PlantGuideMetric) -> some View {
        PlanteriorCard {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                HStack(spacing: PlanteriorSpacing.extraSmall) {
                    Text(metric.icon)
                        .font(PlantCareReferenceMetrics.guideGlyphFont)
                        .accessibilityHidden(true)
                    Text(metric.title)
                        .font(PlanteriorTypography.caption.weight(.semibold))
                        .foregroundStyle(PlanteriorPalette.textPrimary.color)
                }
                Text(metric.value)
                    .font(PlanteriorTypography.cardTitle)
                Text(metric.hint)
                    .font(PlanteriorTypography.microLabel)
                    .foregroundStyle(PlanteriorPalette.textAccessibleCaption.color)
            }
            .padding(.vertical, -PlanteriorSpacing.extraSmall)
        }
    }

    private var careProfile: DomesticPlantCareProfile? {
        plant.flatMap(PlantCarePresentation.careProfile(for:))
    }

    private var guideSourceAccessibilityValue: String {
        guard let careProfile else { return "국내 공공데이터 미지원" }
        return "출처: \(careProfile.sourceName), 공공데이터 \(careProfile.datasetID)"
    }

    private var guideColumns: [GridItem] {
        let count = dynamicTypeSize.isAccessibilitySize ? 1 : 2
        return Array(
            repeating: GridItem(
                .flexible(),
                spacing: PlantCareReferenceMetrics.guideGridSpacing
            ),
            count: count
        )
    }
}
