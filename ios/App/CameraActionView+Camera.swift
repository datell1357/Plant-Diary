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

    /// §6.11: the viewfinder is a ~320pt inset card.
    static var viewportWidth: CGFloat {
        320
    }

    /// §6.11: 72pt shutter inside an 80pt ring.
    static var shutterDiameter: CGFloat {
        72
    }

    static var shutterRingDiameter: CGFloat {
        80
    }

    var cameraSurface: some View {
        VStack(spacing: 0) {
            cameraTopBar
            Spacer()
                .frame(height: 161)
            cameraViewport
            Spacer(minLength: 0)
            cameraErrorRegion
            cameraControlRow
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
            Text("식물을 초점에 맞춰주세요")
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
    private var cameraViewport: some View {
        ZStack {
            Image(.captureCameraSimulation)
                .resizable()
                .scaledToFill()
                .frame(width: Self.viewportWidth, height: Self.viewportWidth)
                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
                .accessibilityIdentifier("capture.viewport")
                .accessibilityLabel("카메라 미리보기")
            focusReticle
        }
        .frame(width: Self.viewportWidth, height: Self.viewportWidth)
    }

    private var focusReticle: some View {
        ZStack {
            CameraCornerReticle()
                .stroke(
                    PlanteriorPalette.accent.color,
                    style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
                )
            Circle()
                .stroke(
                    PlanteriorPalette.textOnAccent.color.opacity(0.9),
                    lineWidth: PlanteriorControl.hairline * 2
                )
                .frame(width: 160, height: 160)
        }
        .frame(width: 240, height: 240)
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
        .frame(height: Self.shutterRingDiameter)
        .padding(.horizontal, PlanteriorSpacing.extraLarge)
        .padding(.bottom, PlanteriorSpacing.large)
    }

    private var shutterControl: some View {
        Button {
            requestCamera()
        } label: {
            ZStack {
                Circle()
                    .stroke(
                        PlanteriorPalette.textOnAccent.color,
                        lineWidth: PlanteriorControl.hairline * 2
                    )
                    .frame(width: Self.shutterRingDiameter, height: Self.shutterRingDiameter)
                Circle()
                    .fill(PlanteriorPalette.textOnAccent.color)
                    .frame(width: Self.shutterDiameter, height: Self.shutterDiameter)
            }
        }
        .accessibilityLabel("촬영")
        .accessibilityIdentifier("capture.shutter")
    }

    private var flashControl: some View {
        Button {
            requestCamera()
        } label: {
            CameraControlLabel(
                systemImage: "bolt.fill",
                title: "플래시",
                labelID: "capture.flash.label"
            )
        }
        .accessibilityLabel("플래시")
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
        .frame(minHeight: PlanteriorControl.minimumTarget)
    }
}

private struct CameraCornerReticle: Shape {
    func path(in rect: CGRect) -> Path {
        let arm: CGFloat = 44
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
