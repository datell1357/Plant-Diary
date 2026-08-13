import PlanteriorDesignSystem
import SwiftUI

struct CameraActionView: View {
    let dismiss: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "camera.fill")
                .font(.system(size: 52))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityHidden(true)
            Text("식물 사진 촬영")
                .font(PlanteriorTypography.screenTitle)
            PlanteriorPrimaryButton("닫기", action: dismiss)
                .frame(maxWidth: 240)
                .accessibilityLabel("카메라 닫기")
                .accessibilityIdentifier("camera.dismiss")
        }
        .padding(24)
        .presentationDetents([.medium])
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("camera.sheet")
    }
}
