import PlanteriorDesignSystem
import SwiftUI
import UIKit

/// Figma `Screen-Photo-Review`: the selected photo keeps the reference crop and
/// the state exposes only the identify and retake decisions shown on the board.
extension CameraActionView {
    var photoReviewSurface: some View {
        VStack(spacing: 0) {
            reviewNavigationBar
            ScrollView {
                VStack(spacing: 0) {
                    reviewPhotoRegion
                    if let errorMessage {
                        Text(errorMessage)
                            .font(PlanteriorTypography.caption)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(PlanteriorPalette.warning.color)
                            .padding(.top, PlanteriorSpacing.small)
                            .accessibilityIdentifier("photo.error")
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(
                    .top,
                    sizeCategory.isAccessibilityCategory
                        ? PlanteriorSpacing.extraLarge
                        : CaptureLayoutMetrics.reviewTopSpacing
                )
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                reviewActions
            }
        }
        .padding(.top, CaptureLayoutMetrics.referenceStatusBarHeight)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PlanteriorPalette.canvas.color.ignoresSafeArea())
        .ignoresSafeArea(edges: .top)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("capture.photo-review")
    }

    private var reviewNavigationBar: some View {
        ZStack {
            Text("사진 확인")
                .font(PlanteriorTypography.screenTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("capture.review.title")
            HStack {
                CaptureReferenceBackButton(
                    identifier: "capture.review.back",
                    action: discardDraft
                )
                Spacer()
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, PlanteriorSpacing.large)
        .frame(height: PlanteriorControl.navigationBarHeight)
        .background(PlanteriorPalette.canvas.color)
    }

    @ViewBuilder
    private var reviewPhotoRegion: some View {
        if sizeCategory.isAccessibilityCategory {
            VStack(spacing: CaptureLayoutMetrics.reviewCaptionTopGap) {
                reviewPhoto(
                    contentHeight: CaptureLayoutMetrics.reviewAccessibilityPhotoHeight
                )
                reviewCaption
                    .frame(width: CaptureLayoutMetrics.reviewContentSize.width)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(width: CaptureLayoutMetrics.reviewAssetSize.width)
        } else {
            ZStack(alignment: .top) {
                reviewPhoto(contentHeight: CaptureLayoutMetrics.reviewContentSize.height)
                reviewCaption
                    .padding(
                        .top,
                        CaptureLayoutMetrics.reviewContentSize.height
                            + CaptureLayoutMetrics.reviewCaptionTopGap
                    )
            }
            .frame(
                width: CaptureLayoutMetrics.reviewAssetSize.width,
                height: CaptureLayoutMetrics.reviewAssetSize.height,
                alignment: .top
            )
        }
    }

    private var reviewCaption: some View {
        Text("식물의 초점이 맞고 잎이 선명한지 확인해주세요")
            .font(PlanteriorTypography.caption)
            .foregroundStyle(PlanteriorPalette.textSecondary.color)
            .multilineTextAlignment(.center)
            .accessibilityIdentifier("capture.review.caption")
    }

    private func reviewPhoto(contentHeight: CGFloat) -> some View {
        ZStack(alignment: .top) {
            if usesFigmaPhotoFixture, !sizeCategory.isAccessibilityCategory {
                Image(.capturePhoto)
                    .resizable()
                    .frame(
                        width: CaptureLayoutMetrics.reviewAssetSize.width,
                        height: CaptureLayoutMetrics.reviewAssetSize.height
                    )
                    .accessibilityIdentifier("photo.review")
                    .accessibilityLabel("촬영한 식물 사진")
            } else if let draft, let image = UIImage(data: draft.data) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(
                        width: CaptureLayoutMetrics.reviewContentSize.width,
                        height: contentHeight
                    )
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
                    .padding(.top, CaptureLayoutMetrics.reviewAssetTopInset)
                    .accessibilityIdentifier("photo.review")
                    .accessibilityLabel("촬영한 식물 사진")
            } else {
                Image(.capturePhoto)
                    .resizable()
                    .scaledToFill()
                    .frame(
                        width: CaptureLayoutMetrics.reviewContentSize.width,
                        height: contentHeight
                    )
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
                    .padding(.top, CaptureLayoutMetrics.reviewAssetTopInset)
                    .accessibilityIdentifier("photo.review")
                    .accessibilityLabel("촬영한 식물 사진")
            }
        }
        .frame(
            width: CaptureLayoutMetrics.reviewAssetSize.width,
            height: contentHeight + CaptureLayoutMetrics.reviewAssetTopInset,
            alignment: .top
        )
    }
}
