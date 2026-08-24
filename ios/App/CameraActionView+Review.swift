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
                .padding(.top, sizeCategory.isAccessibilityCategory ? 16 : 84)
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                reviewActions
            }
        }
        .padding(.top, 48)
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
        .padding(.horizontal, PlanteriorSpacing.large)
        .frame(height: PlanteriorControl.navigationBarHeight)
        .background(PlanteriorPalette.canvas.color)
    }

    private var reviewPhotoRegion: some View {
        let contentHeight: CGFloat = sizeCategory.isAccessibilityCategory ? 232 : 420
        let regionHeight: CGFloat = sizeCategory.isAccessibilityCategory ? 256 : 444
        return ZStack(alignment: .top) {
            reviewPhoto(contentHeight: contentHeight)
            Text("식물의 초점이 맞고 잎이 선명한지 확인해주세요")
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .multilineTextAlignment(.center)
                .padding(.top, contentHeight + 10)
                .accessibilityIdentifier("capture.review.caption")
        }
        .frame(width: 386, height: regionHeight, alignment: .top)
    }

    @ViewBuilder
    private func reviewPhoto(contentHeight: CGFloat) -> some View {
        ZStack(alignment: .top) {
            if usesFigmaPhotoFixture, !sizeCategory.isAccessibilityCategory {
                Image(.capturePhoto)
                    .resizable()
                    .frame(width: 386, height: 444)
                    .accessibilityIdentifier("photo.review")
                    .accessibilityLabel("촬영한 식물 사진")
            } else if let draft, let image = UIImage(data: draft.data) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 362, height: contentHeight)
                    .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
                    .padding(.top, 8)
                    .accessibilityIdentifier("photo.review")
                    .accessibilityLabel("촬영한 식물 사진")
            } else {
                Image(.capturePhoto)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 362, height: contentHeight)
                    .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
                    .padding(.top, 8)
                    .accessibilityIdentifier("photo.review")
                    .accessibilityLabel("촬영한 식물 사진")
            }
            Color.clear
                .frame(width: 362, height: contentHeight)
                .padding(.top, 8)
                .accessibilityElement()
                .accessibilityLabel("사진 표시 영역")
                .accessibilityIdentifier("capture.review.content")
        }
        .frame(width: 386, height: contentHeight + 8, alignment: .top)
    }

    private var reviewActions: some View {
        VStack(spacing: 14) {
            reviewActionButton(
                "이 사진으로 식별하기",
                primary: true,
                identifier: "photo.acknowledge"
            ) {
                showsAcknowledgement = true
            }
            reviewActionButton(
                "다시 촬영",
                primary: false,
                identifier: "photo.retake"
            ) {
                discardDraft()
            }
        }
        .padding(.horizontal, PlanteriorSpacing.huge)
        .padding(.bottom, PlanteriorSpacing.large)
        .background(PlanteriorPalette.canvas.color)
    }

    private func reviewActionButton(
        _ title: String,
        primary: Bool,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: PlanteriorSpacing.small) {
                if primary {
                    Image(systemName: "sparkles")
                        .accessibilityHidden(true)
                }
                Text(title)
            }
            .font(PlanteriorTypography.body.weight(.semibold))
            .frame(maxWidth: .infinity, minHeight: PlanteriorControl.minimumTarget)
        }
        .buttonStyle(.plain)
        .foregroundStyle(
            primary
                ? PlanteriorPalette.textOnAccent.color
                : PlanteriorPalette.accent.color
        )
        .background(
            primary
                ? PlanteriorPalette.accent.color
                : PlanteriorPalette.canvas.color
        )
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        .overlay {
            if !primary {
                RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                    .stroke(
                        PlanteriorPalette.accent.color,
                        lineWidth: PlanteriorControl.hairline
                    )
            }
        }
        .accessibilityIdentifier(identifier)
    }
}
