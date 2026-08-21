import PlanteriorDesignSystem
import SwiftUI
import UIKit

/// Figma `Screen-Photo-Review` (figma-analysis §6.11): back + "사진 확인" nav, a
/// `radius-xl` photo card, a centered caption, and stacked primary identify /
/// secondary retake actions.
extension CameraActionView {
    var photoReviewSurface: some View {
        VStack(spacing: 0) {
            reviewNavigationBar
            ScrollView {
                VStack(spacing: PlanteriorSpacing.large) {
                    reviewPhotoCard
                    Text("이 사진으로 식물을 식별할까요?")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                        .multilineTextAlignment(.center)
                        .accessibilityIdentifier("capture.review.caption")
                    if let errorMessage {
                        Text(errorMessage)
                            .font(PlanteriorTypography.caption)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(PlanteriorPalette.warning.color)
                            .accessibilityIdentifier("photo.error")
                    }
                }
                .padding(.horizontal, PlanteriorSpacing.large)
                .padding(.vertical, PlanteriorSpacing.large)
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                reviewActions
            }
        }
        .background(PlanteriorPalette.canvas.color)
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
                Button {
                    discardDraft()
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
                .accessibilityIdentifier("capture.review.back")
                Spacer()
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, PlanteriorSpacing.small)
        .frame(height: PlanteriorControl.navigationBarHeight)
        .background(PlanteriorPalette.surface.color)
    }

    /// Renders the photo the user actually chose. The DEBUG fixture path feeds
    /// the same `draft`, so QA and production render one code path.
    @ViewBuilder
    private var reviewPhotoCard: some View {
        if let draft, let image = UIImage(data: draft.data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity)
                .frame(height: sizeCategory.isAccessibilityCategory ? 232 : 420)
                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
                .accessibilityIdentifier("photo.review")
                .accessibilityLabel("촬영한 식물 사진")
        } else {
            Image(.capturePhoto)
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity)
                .frame(height: sizeCategory.isAccessibilityCategory ? 232 : 420)
                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
                .accessibilityIdentifier("photo.review")
                .accessibilityLabel("촬영한 식물 사진")
        }
    }

    /// §6.11 stacks the primary identify action above the secondary retake, both
    /// 52pt tall. Direct registration stays reachable as a tertiary text action
    /// so identification is never the only way forward.
    private var reviewActions: some View {
        VStack(spacing: PlanteriorSpacing.medium) {
            PlanteriorPrimaryButton("🌿 이 사진으로 식별하기") {
                showsAcknowledgement = true
            }
            .accessibilityIdentifier("photo.acknowledge")
            PlanteriorSecondaryButton("다시 촬영") {
                discardDraft()
            }
            .accessibilityIdentifier("photo.retake")
            ViewThatFits(in: .horizontal) {
                HStack(spacing: PlanteriorSpacing.large) {
                    replacePhotoButton
                    manualRegistrationButton
                }
                VStack(spacing: PlanteriorSpacing.extraSmall) {
                    replacePhotoButton
                    manualRegistrationButton
                }
            }
            .font(PlanteriorTypography.caption)
            .foregroundStyle(PlanteriorPalette.accent.color)
        }
        .padding(.horizontal, PlanteriorSpacing.large)
        .padding(.bottom, PlanteriorSpacing.large)
        .background(PlanteriorPalette.canvas.color)
    }

    private var replacePhotoButton: some View {
        Button("사진 다시 선택") {
            showsLibrary = true
        }
        .frame(maxWidth: .infinity, minHeight: PlanteriorControl.minimumTarget)
        .contentShape(Rectangle())
        .accessibilityIdentifier("photo.replace")
    }

    private var manualRegistrationButton: some View {
        Button("직접 등록하기") {
            manualRegistration()
        }
        .frame(maxWidth: .infinity, minHeight: PlanteriorControl.minimumTarget)
        .contentShape(Rectangle())
        .accessibilityIdentifier("photo.manual")
    }
}
