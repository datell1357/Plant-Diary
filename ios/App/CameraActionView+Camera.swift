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
            Spacer(minLength: PlanteriorSpacing.large)
            cameraViewport
            Spacer(minLength: PlanteriorSpacing.large)
            cameraErrorRegion
            cameraControlRow
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Self.cameraBackdrop.ignoresSafeArea())
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
        .padding(.top, PlanteriorSpacing.small)
    }

    /// The Simulator has no camera device, so DEBUG renders the deterministic
    /// `FigmaCaptureCameraSimulation` fixture in the viewfinder. Production
    /// keeps the live `AVCaptureVideoPreviewLayer` path through
    /// `SystemCameraPicker`, which the shutter presents.
    private var cameraViewport: some View {
        ZStack {
            Image(.captureCameraSimulation)
                .resizable()
                .scaledToFill()
                .frame(width: Self.viewportWidth, height: Self.viewportWidth * 4 / 3)
                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
                .accessibilityIdentifier("capture.viewport")
                .accessibilityLabel("카메라 미리보기")
            focusReticle
        }
        .overlay {
            RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge)
                .stroke(
                    PlanteriorPalette.textOnAccent.color.opacity(0.9),
                    lineWidth: PlanteriorControl.hairline * 2
                )
                .frame(width: Self.viewportWidth, height: Self.viewportWidth * 4 / 3)
        }
    }

    private var focusReticle: some View {
        Circle()
            .stroke(
                PlanteriorPalette.textOnAccent.color.opacity(0.85),
                lineWidth: PlanteriorControl.hairline * 2
            )
            .frame(width: 96, height: 96)
            .accessibilityHidden(true)
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
            switchControl
        }
        .padding(.horizontal, PlanteriorSpacing.section)
        .padding(.bottom, PlanteriorSpacing.section)
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

    private var switchControl: some View {
        Button {
            requestCamera()
        } label: {
            captureControlLabel(systemImage: "arrow.triangle.2.circlepath", title: "카메라 전환")
        }
        .accessibilityLabel("카메라 전환")
        .accessibilityIdentifier("capture.switch")
    }

    func captureControlLabel(systemImage: String, title: String) -> some View {
        VStack(spacing: PlanteriorSpacing.extraSmall) {
            Image(systemName: systemImage)
                .font(.system(size: 22))
            Text(title)
                .font(PlanteriorTypography.microLabel)
        }
        .foregroundStyle(PlanteriorPalette.textOnAccent.color)
        .frame(
            minWidth: PlanteriorControl.minimumTarget,
            minHeight: PlanteriorControl.minimumTarget
        )
    }
}
