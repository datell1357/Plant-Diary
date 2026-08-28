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
                    if let selected = selectedCandidate ?? candidates.items.first {
                        compositeResultCard(selected)
                    }
                    alternatesSection(candidates)
                }
                .padding(.horizontal, PlanteriorSpacing.extraLarge)
                .padding(.top, CaptureLayoutMetrics.resultTopSpacing)
                .padding(.bottom, PlanteriorSpacing.large)
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                resultActions(candidates)
            }
        }
        .padding(.top, CaptureLayoutMetrics.referenceStatusBarHeight)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PlanteriorPalette.canvas.color.ignoresSafeArea())
        .ignoresSafeArea(edges: .top)
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
                CaptureReferenceBackButton(
                    identifier: "capture.result.back",
                    action: returnToReviewedPhoto
                )
                Spacer()
            }
        }
        .padding(.horizontal, PlanteriorSpacing.large)
        .frame(height: PlanteriorControl.navigationBarHeight)
        .background(PlanteriorPalette.canvas.color)
    }

    private func compositeResultCard(_ candidate: IdentificationCandidate) -> some View {
        VStack(spacing: 0) {
            resultHero
            resultSummary(candidate)
        }
        .frame(maxWidth: .infinity)
        .background(PlanteriorPalette.surface.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
        .overlay {
            RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge)
                .stroke(
                    PlanteriorPalette.border.color,
                    lineWidth: PlanteriorControl.hairline
                )
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("capture.result.card")
    }

    @ViewBuilder
    private var resultHero: some View {
        let height = sizeCategory.isAccessibilityCategory
            ? CaptureLayoutMetrics.resultCompactHeroHeight
            : CaptureLayoutMetrics.resultHeroSize.height
        if usesFigmaPhotoFixture, !sizeCategory.isAccessibilityCategory {
            Image(.capturePreview)
                .resizable()
                .frame(maxWidth: .infinity, minHeight: height, maxHeight: height)
                .accessibilityIdentifier("capture.result.hero")
                .accessibilityLabel("식별한 식물 사진")
        } else if let data = submittedPhoto, let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity, minHeight: height, maxHeight: height)
                .clipped()
                .accessibilityIdentifier("capture.result.hero")
                .accessibilityLabel("식별한 식물 사진")
        } else {
            Image(.capturePreview)
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity, minHeight: height, maxHeight: height)
                .clipped()
                .accessibilityIdentifier("capture.result.hero")
                .accessibilityLabel("식별한 식물 사진")
        }
    }

    private func resultSummary(_ candidate: IdentificationCandidate) -> some View {
        VStack(alignment: .leading, spacing: 0) {
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
            Text(KoreanTypography.atomic(candidate.species.koreanName))
                .font(PlanteriorTypography.pageTitle)
                .accessibilityLabel(candidate.species.koreanName)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .padding(.top, PlanteriorSpacing.small)
                .accessibilityIdentifier("capture.result.species")
            Text(candidate.species.binomial)
                .font(PlanteriorTypography.supporting.italic())
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("capture.result.binomial")
            Text(candidate.species.summary)
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, PlanteriorSpacing.medium)
                .accessibilityIdentifier("capture.result.summary")
        }
        .padding(.horizontal, PlanteriorSpacing.extraLarge)
        .padding(.top, PlanteriorSpacing.extraLarge)
        .padding(.bottom, CaptureLayoutMetrics.resultSummaryBottomInset)
        .frame(
            maxWidth: .infinity,
            minHeight: sizeCategory.isAccessibilityCategory ? nil : 166,
            alignment: .topLeading
        )
        .background(PlanteriorPalette.surface.color)
    }

    private func confidencePill(_ candidate: IdentificationCandidate) -> some View {
        HStack(spacing: PlanteriorSpacing.extraSmall) {
            Image(systemName: "checkmark")
                .font(PlanteriorTypography.caption.weight(.bold))
                .accessibilityHidden(true)
            Text("신뢰도 \(candidate.confidencePercentage)%")
                .font(PlanteriorTypography.microLabel)
                .lineLimit(1)
        }
        .fixedSize(horizontal: true, vertical: false)
        .foregroundStyle(PlanteriorPalette.accent.color)
        .padding(.horizontal, PlanteriorSpacing.medium)
        .padding(.vertical, PlanteriorSpacing.extraSmall)
        .background(PlanteriorPalette.successSurface.color)
        .clipShape(Capsule())
        .accessibilityElement()
        .accessibilityLabel("신뢰도 \(candidate.confidencePercentage)%")
        .accessibilityIdentifier("capture.result.confidence")
    }
}
