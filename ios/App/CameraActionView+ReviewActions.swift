import PlanteriorDesignSystem
import SwiftUI

extension CameraActionView {
    var reviewActions: some View {
        VStack(spacing: CaptureLayoutMetrics.reviewActionSpacing) {
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
