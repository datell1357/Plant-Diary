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

    var guideSourceLink: some View {
        Group {
            if let careProfile {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    Link(destination: careProfile.sourceURL) {
                        Label(
                            "출처: \(careProfile.sourceName) · 공공데이터 \(careProfile.datasetID)",
                            systemImage: "arrow.up.right.square"
                        )
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    }
                    .accessibilityIdentifier("plant.detail.guide-source")
                    .accessibilityHint("공공데이터 상세 페이지 열기")
                    .accessibilityValue(careProfile.sourceURL.absoluteString)

                    Text(guideProvenanceAccessibilityValue)
                        .font(PlanteriorTypography.microLabel)
                        .foregroundStyle(PlanteriorPalette.textAccessibleCaption.color)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("관리 정보 출처 세부사항")
                        .accessibilityValue(guideProvenanceAccessibilityValue)
                        .accessibilityIdentifier("plant.detail.guide-provenance")
                }
            }
        }
    }

    var careProfile: DomesticPlantCareProfile? {
        plant.flatMap(PlantCarePresentation.careProfile(for:))
    }

    private var guideSourceAccessibilityValue: String {
        guard let careProfile else { return "국내 공공데이터 미지원" }
        return "출처: \(careProfile.sourceName), 공공데이터 \(careProfile.datasetID)"
    }

    private var guideProvenanceAccessibilityValue: String {
        guard let provenance = careProfile?.provenance else { return "" }
        var fields = [
            "제공기관: \(provenance.providerName)",
            "데이터셋: \(provenance.datasetName) (\(provenance.datasetID))",
            "원문: \(provenance.sourceURL.absoluteString)",
            "변환 안내: \(provenance.transformationNotice)"
        ]
        if let retrievedDate = provenance.retrievedDate {
            fields.insert(
                "조회일: \(retrievedDate.formatted(date: .numeric, time: .omitted))",
                at: 3
            )
        }
        if let sourceModifiedDate = provenance.sourceModifiedDate {
            fields.insert(
                "원문 수정일: \(sourceModifiedDate.formatted(date: .numeric, time: .omitted))",
                at: fields.count - 1
            )
        }
        if let originalSourceFieldNames = provenance.originalSourceFieldNames {
            if !originalSourceFieldNames.isEmpty {
                fields.insert(
                    "원본 필드명: \(originalSourceFieldNames.joined(separator: ", "))",
                    at: fields.count - 1
                )
            }
        }
        return fields.joined(separator: "\n")
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
