import PlanteriorDesignSystem
import SwiftUI

extension CameraActionView {
    @ViewBuilder
    var cameraTopBar: some View {
        if sizeCategory.isAccessibilityCategory {
            HStack(alignment: .top, spacing: PlanteriorSpacing.small) {
                cameraCloseControl
                cameraHint
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity)
                Color.clear
                    .frame(
                        width: PlanteriorControl.minimumTarget,
                        height: PlanteriorControl.minimumTarget
                    )
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, PlanteriorSpacing.large)
            .frame(minHeight: PlanteriorControl.navigationBarHeight)
        } else {
            ZStack {
                cameraHint
                HStack {
                    cameraCloseControl
                    Spacer()
                }
            }
            .padding(.horizontal, PlanteriorSpacing.large)
            .frame(height: PlanteriorControl.navigationBarHeight)
        }
    }

    private var cameraHint: some View {
        Text("식물을 프레임 안에 맞춰주세요")
            .font(PlanteriorTypography.supporting)
            .foregroundStyle(PlanteriorPalette.textOnAccent.color)
            .accessibilityIdentifier("capture.hint")
    }

    private var cameraCloseControl: some View {
        Button(action: dismiss) {
            Image(systemName: "xmark")
                .font(CaptureLayoutMetrics.cameraCloseGlyphFont)
                .foregroundStyle(PlanteriorPalette.textOnAccent.color)
                .frame(
                    width: PlanteriorControl.minimumTarget,
                    height: PlanteriorControl.minimumTarget
                )
        }
        .accessibilityLabel("촬영 닫기")
        .accessibilityIdentifier("capture.close")
    }
}
