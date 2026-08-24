import PlanteriorDesignSystem
import SwiftUI

/// Figma `Screen-Camera-Capture` (figma-analysis §6.11): full black chrome with
/// a leading close, a centered focus hint, an inset viewfinder card, and a
/// bottom control row of library / shutter / camera-switch.
extension CameraActionView {
    /// §5 `cameraBackdrop` `#000000`. The shared palette is a light-appearance
    /// contract; this is the one Figma-specified surface that is pure black, so
    /// it is named here rather than diluting the semantic palette.
    static var cameraBackdrop: Color {
        .black
    }

    var cameraSurface: some View {
        GeometryReader { geometry in
            let scale = CaptureLayoutMetrics.horizontalScale(
                for: geometry.size.width
            )
            VStack(spacing: 0) {
                cameraTopBar
                Spacer()
                    .frame(height: CaptureLayoutMetrics.cameraViewportTopSpacing)
                cameraViewport(scale: scale)
                Spacer(minLength: 0)
                cameraErrorRegion
                cameraControlRow
            }
        }
        .padding(.top, 48)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Self.cameraBackdrop.ignoresSafeArea())
        .ignoresSafeArea(edges: .top)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("capture.camera")
    }

    private var cameraTopBar: some View {
        ZStack {
            Text("식물을 프레임 안에 맞춰주세요")
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textOnAccent.color)
                .accessibilityIdentifier("capture.hint")
            HStack {
                Button(action: dismiss) {
                    Image(systemName: "xmark")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(PlanteriorPalette.textOnAccent.color)
                        .frame(
                            width: PlanteriorControl.minimumTarget,
                            height: PlanteriorControl.minimumTarget
                        )
                }
                .accessibilityLabel("촬영 닫기")
                .accessibilityIdentifier("capture.close")
                Spacer()
            }
        }
        .padding(.horizontal, PlanteriorSpacing.large)
        .frame(height: PlanteriorControl.navigationBarHeight)
    }

    /// The Simulator uses a deterministic viewfinder fixture. The shutter and
    /// flash affordance still enter the native capture pathway in production.
    private func cameraViewport(scale: CGFloat) -> some View {
        let length = CaptureLayoutMetrics.cameraViewportLength * scale
        return ZStack {
            Image(.captureCameraSimulation)
                .resizable()
                .scaledToFill()
                .frame(width: length, height: length)
                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
                .accessibilityIdentifier("capture.viewport")
                .accessibilityLabel("카메라 미리보기")
            focusReticle(scale: scale)
        }
        .frame(width: length, height: length)
    }

    private func focusReticle(scale: CGFloat) -> some View {
        ZStack {
            CameraCornerReticle(
                armLength: CaptureLayoutMetrics.cameraReticleArmLength * scale
            )
            .stroke(
                PlanteriorPalette.accent.color,
                style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
            )
            Circle()
                .stroke(
                    PlanteriorPalette.textOnAccent.color.opacity(0.9),
                    lineWidth: PlanteriorControl.hairline * 2
                )
                .frame(
                    width: CaptureLayoutMetrics.cameraFocusCircleLength * scale,
                    height: CaptureLayoutMetrics.cameraFocusCircleLength * scale
                )
        }
        .frame(
            width: CaptureLayoutMetrics.cameraReticleLength * scale,
            height: CaptureLayoutMetrics.cameraReticleLength * scale
        )
        .accessibilityElement()
        .accessibilityLabel("촬영 안내 프레임")
        .accessibilityIdentifier("capture.reticle")
    }

    @ViewBuilder
    private var cameraErrorRegion: some View {
        if let errorMessage {
            VStack(spacing: PlanteriorSpacing.small) {
                Text(errorMessage)
                    .font(PlanteriorTypography.caption)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(PlanteriorPalette.textOnAccent.color)
                    .accessibilityIdentifier("capture.error")
                if cameraDenied {
                    Button("설정 열기") {
                        openSettings()
                    }
                    .font(PlanteriorTypography.caption.weight(.semibold))
                    .foregroundStyle(PlanteriorPalette.textOnAccent.color)
                    .frame(minHeight: PlanteriorControl.minimumTarget)
                    .accessibilityIdentifier("capture.settings")
                }
            }
            .padding(.horizontal, PlanteriorSpacing.huge)
            .padding(.bottom, PlanteriorSpacing.medium)
        }
    }

    private var cameraControlRow: some View {
        HStack {
            libraryControl
            Spacer()
            shutterControl
            Spacer()
            flashControl
        }
        .frame(height: CaptureLayoutMetrics.cameraControlRowHeight)
        .padding(.horizontal, PlanteriorSpacing.extraLarge)
        .padding(.bottom, PlanteriorSpacing.large)
    }

    private var shutterControl: some View {
        Button {
            requestCamera()
        } label: {
            ZStack {
                Circle()
                    .strokeBorder(
                        PlanteriorPalette.textOnAccent.color,
                        lineWidth: CaptureLayoutMetrics.shutterStrokeWidth
                    )
                    .frame(
                        width: CaptureLayoutMetrics.shutterRingDiameter,
                        height: CaptureLayoutMetrics.shutterRingDiameter
                    )
                Circle()
                    .fill(PlanteriorPalette.textOnAccent.color)
                    .frame(
                        width: CaptureLayoutMetrics.shutterDiameter,
                        height: CaptureLayoutMetrics.shutterDiameter
                    )
            }
        }
        .accessibilityLabel("촬영")
        .accessibilityIdentifier("capture.shutter")
    }

    private var flashControl: some View {
        Button {
            isFlashEnabled.toggle()
        } label: {
            CameraControlLabel(
                systemImage: isFlashEnabled ? "bolt.fill" : "bolt",
                title: "플래시",
                labelID: "capture.flash.label"
            )
        }
        .accessibilityLabel("플래시")
        .accessibilityValue(isFlashEnabled ? "켜짐" : "꺼짐")
        .accessibilityAddTraits(isFlashEnabled ? .isSelected : [])
        .accessibilityIdentifier("capture.flash")
    }
}

struct CameraControlLabel: View {
    let systemImage: String
    let title: String
    let labelID: String

    var body: some View {
        VStack(spacing: PlanteriorSpacing.extraSmall) {
            Image(systemName: systemImage)
                .font(.system(size: 22))
            Text(title)
                .font(PlanteriorTypography.microLabel)
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(width: 80)
                .accessibilityIdentifier(labelID)
        }
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        .foregroundStyle(PlanteriorPalette.textOnAccent.color)
        .frame(width: 88)
        .frame(minHeight: CaptureLayoutMetrics.cameraControlMinimumTarget)
    }
}

private struct CameraCornerReticle: Shape {
    let armLength: CGFloat

    func path(in rect: CGRect) -> Path {
        let arm = armLength
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.minY + arm))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.minX + arm, y: rect.minY))
        path.move(to: CGPoint(x: rect.maxX - arm, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + arm))
        path.move(to: CGPoint(x: rect.maxX, y: rect.maxY - arm))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX - arm, y: rect.maxY))
        path.move(to: CGPoint(x: rect.minX + arm, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - arm))
        return path
    }
}
