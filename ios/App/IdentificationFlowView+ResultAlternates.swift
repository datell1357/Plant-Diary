import PlanteriorData
import PlanteriorDesignSystem
import SwiftUI

extension IdentificationFlowView {
    @ViewBuilder
    func alternatesSection(_ candidates: IdentificationCandidates) -> some View {
        let alternates = Array(candidates.items.enumerated()).dropFirst()
        if !alternates.isEmpty {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                Text("다른 후보")
                    .font(PlanteriorTypography.sectionTitle)
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("capture.result.alternates.header")
                VStack(spacing: CaptureLayoutMetrics.resultAlternateSpacing) {
                    ForEach(alternates, id: \.offset) { index, candidate in
                        alternateRow(index: index, candidate: candidate)
                    }
                }
            }
        }
    }

    private func alternateRow(index: Int, candidate: IdentificationCandidate) -> some View {
        Button {
            selectedCandidate = candidate
        } label: {
            HStack(spacing: PlanteriorSpacing.medium) {
                Image(index == 1 ? .captureCandidateMonstera : .captureCandidateAlternate)
                    .resizable()
                    .scaledToFill()
                    .frame(
                        width: PlanteriorLayout.mediaThumbnailSize,
                        height: PlanteriorLayout.mediaThumbnailSize
                    )
                    .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    Text(candidate.species.koreanName)
                        .font(PlanteriorTypography.cardTitle)
                        .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    Text("신뢰도 \(candidate.confidencePercentage)%")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                }
                Spacer()
                if selectedCandidate == candidate {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(PlanteriorPalette.accent.color)
                        .accessibilityHidden(true)
                }
            }
            .padding(PlanteriorSpacing.medium)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(PlanteriorPalette.surface.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
            .overlay {
                RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                    .stroke(
                        selectedCandidate == candidate
                            ? PlanteriorPalette.accent.color
                            : PlanteriorPalette.border.color,
                        lineWidth: PlanteriorControl.hairline
                    )
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(
            "\(candidate.species.koreanName), 신뢰도 \(candidate.confidencePercentage)%"
        )
        .accessibilityValue(selectedCandidate == candidate ? "선택됨" : "선택 안 됨")
        .accessibilityAddTraits(selectedCandidate == candidate ? [.isSelected] : [])
        .accessibilityIdentifier("identification.candidate.\(index)")
    }
}
