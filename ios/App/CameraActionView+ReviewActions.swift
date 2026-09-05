import PlanteriorDesignSystem
import SwiftUI
import UIKit

extension CameraActionView {
    var reviewActions: some View {
        VStack(spacing: CaptureLayoutMetrics.reviewActionSpacing) {
            VStack(spacing: PlanteriorSpacing.extraSmall) {
                Text("더 찍을까요?")
                    .font(PlanteriorTypography.body.weight(.semibold))
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .accessibilityIdentifier("capture.review.guidance.title")
                Text("여러 각도에서 촬영할수록 식별 정확도가 높아집니다.")
                    .font(PlanteriorTypography.caption)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .multilineTextAlignment(.center)
                    .accessibilityIdentifier("capture.review.guidance.detail")
            }
            temporaryPhotoTray
            reviewActionButton(
                "사진 \(photos.count)장 업로드하고 식별하기",
                primary: true,
                identifier: "photo.acknowledge"
            ) {
                showsAcknowledgement = true
            }
            if photos.count < Self.maximumPhotoCount {
                reviewActionButton(
                    "더 찍기",
                    primary: false,
                    identifier: "photo.more"
                ) {
                    captureMore()
                }
            }
        }
        .padding(.horizontal, PlanteriorSpacing.huge)
        .padding(.bottom, PlanteriorSpacing.large)
        .background(PlanteriorPalette.canvas.color)
    }

    private var temporaryPhotoTray: some View {
        HStack(spacing: PlanteriorSpacing.small) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: PlanteriorSpacing.small) {
                    ForEach(Array(photos.enumerated()), id: \.offset) { index, photo in
                        if let image = UIImage(data: photo.data) {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 44, height: 44)
                                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
                                .accessibilityLabel("임시 사진 \(index + 1)")
                                .accessibilityIdentifier("capture.review.thumbnail.\(index)")
                        }
                    }
                }
            }
            Text("\(photos.count)/\(Self.maximumPhotoCount)장")
                .font(PlanteriorTypography.caption.weight(.semibold))
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("capture.review.count")
        }
        .frame(minHeight: PlanteriorControl.minimumTarget)
    }

    func reviewActionButton(
        _ title: String,
        primary: Bool,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: PlanteriorSpacing.small) {
                if primary {
                    CaptureSparkleGlyph()
                        .stroke(
                            PlanteriorPalette.textOnAccent.color,
                            style: StrokeStyle(
                                lineWidth: CaptureLayoutMetrics.reviewSparkleStrokeWidth,
                                lineCap: .round,
                                lineJoin: .round
                            )
                        )
                        .frame(
                            width: CaptureLayoutMetrics.reviewSparkleSize.width,
                            height: CaptureLayoutMetrics.reviewSparkleSize.height
                        )
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
