import PlanteriorData
import PlanteriorDesignSystem
import SwiftUI
import UIKit

extension IdentificationFlowView {
    func resultSurface(_ candidates: IdentificationCandidates) -> some View {
        VStack(spacing: 0) {
            resultNavigationBar
            ScrollView {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.large) {
                    resultHero
                    if let selected = selectedCandidate ?? candidates.items.first {
                        resultSummaryCard(selected)
                    }
                    alternatesSection(candidates)
                }
                .padding(.horizontal, PlanteriorSpacing.large)
                .padding(.vertical, PlanteriorSpacing.large)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            resultActions(candidates)
        }
        .background(PlanteriorPalette.canvas.color)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("capture.identification-result")
    }

    private var resultNavigationBar: some View {
        ZStack {
            Text("식별 결과")
                .font(PlanteriorTypography.screenTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("capture.result.title")
            HStack {
                Button {
                    returnToReviewedPhoto()
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(PlanteriorPalette.textPrimary.color)
                        .frame(
                            width: PlanteriorControl.minimumTarget,
                            height: PlanteriorControl.minimumTarget
                        )
                }
                .accessibilityLabel("뒤로")
                .accessibilityIdentifier("capture.result.back")
                Spacer()
            }
        }
        .padding(.horizontal, PlanteriorSpacing.small)
        .frame(height: PlanteriorControl.navigationBarHeight)
        .background(PlanteriorPalette.surface.color)
    }

    @ViewBuilder
    private var resultHero: some View {
        if let data = submittedPhoto, let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity, minHeight: 220, maxHeight: 220)
                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
                .accessibilityIdentifier("capture.result.hero")
                .accessibilityLabel("식별한 식물 사진")
        } else {
            Image(.capturePreview)
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity, minHeight: 220, maxHeight: 220)
                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
                .accessibilityIdentifier("capture.result.hero")
                .accessibilityLabel("식별한 식물 사진")
        }
    }

    private func resultSummaryCard(_ candidate: IdentificationCandidate) -> some View {
        PlanteriorCard {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                ViewThatFits(in: .horizontal) {
                    HStack {
                        confidencePill(candidate)
                        Spacer()
                        analysisCompleteLabel
                    }
                    VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                        confidencePill(candidate)
                        analysisCompleteLabel
                    }
                }
                Text(candidate.species.koreanName)
                    .font(PlanteriorTypography.pageTitle)
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .accessibilityIdentifier("capture.result.species")
                Text(candidate.species.binomial)
                    .font(PlanteriorTypography.supporting.italic())
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .accessibilityIdentifier("capture.result.binomial")
                Text(candidate.species.summary)
                    .font(PlanteriorTypography.supporting)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("capture.result.summary")
            }
        }
    }

    private func confidencePill(_ candidate: IdentificationCandidate) -> some View {
        HStack(spacing: PlanteriorSpacing.extraSmall) {
            Image(systemName: "checkmark.circle.fill")
                .font(PlanteriorTypography.caption)
                .accessibilityHidden(true)
            Text("신뢰도 \(candidate.confidencePercentage)%")
                .font(PlanteriorTypography.microLabel)
                .lineLimit(1)
        }
        .fixedSize(horizontal: true, vertical: false)
        .foregroundStyle(PlanteriorPalette.accent.color)
        .padding(.horizontal, PlanteriorSpacing.medium)
        .padding(.vertical, PlanteriorSpacing.extraSmall)
        .background(PlanteriorPalette.accentSurface.color)
        .clipShape(Capsule())
        .accessibilityElement()
        .accessibilityLabel("신뢰도 \(candidate.confidencePercentage)%")
        .accessibilityIdentifier("capture.result.confidence")
    }

    @ViewBuilder
    private func alternatesSection(_ candidates: IdentificationCandidates) -> some View {
        let alternates = Array(candidates.items.enumerated()).dropFirst()
        if !alternates.isEmpty {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
                Text("다른 후보")
                    .font(PlanteriorTypography.sectionTitle)
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("capture.result.alternates.header")
                ForEach(alternates, id: \.offset) { index, candidate in
                    alternateRow(index: index, candidate: candidate)
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
                    .frame(width: 48, height: 48)
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
