import PlanteriorDesignSystem
import SwiftUI

extension CameraActionView {
    var cameraSurface: some View {
        GeometryReader { geometry in
            let scale = CaptureLayoutMetrics.horizontalScale(
                for: geometry.size.width
            )
            VStack(spacing: 0) {
                cameraTopBar
                Spacer()
                    .frame(
                        height: sizeCategory.isAccessibilityCategory
                            ? PlanteriorSpacing.large
                            : CaptureLayoutMetrics.cameraViewportTopSpacing
                    )
                cameraViewport(scale: scale)
                Spacer(minLength: 0)
                cameraErrorRegion
                cameraControlRow
            }
        }
        .padding(.top, CaptureLayoutMetrics.referenceStatusBarHeight)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Self.cameraBackdrop.ignoresSafeArea())
        .ignoresSafeArea(edges: .top)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("capture.camera")
        .onAppear {
            prepareCameraPreview()
        }
        .onDisappear {
            liveCamera.stop()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                prepareCameraPreview()
            } else {
                liveCamera.stop()
            }
        }
    }

    /// Production devices render the same live session used by the shutter.
    private func cameraViewport(scale: CGFloat) -> some View {
        let length = CaptureLayoutMetrics.cameraViewportLength * scale
        return ZStack {
            if cameraPresentationMode == .deterministicFixture {
                Image(.captureCameraSimulation)
                    .resizable()
                    .scaledToFill()
                    .frame(width: length, height: length)
                    .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
                    .accessibilityIdentifier("capture.viewport")
                    .accessibilityLabel("카메라 미리보기")
            } else {
                LiveCameraPreview(session: liveCamera.session)
                    .frame(width: length, height: length)
                    .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
                    .accessibilityElement()
                    .accessibilityIdentifier("capture.viewport.live")
                    .accessibilityLabel("실시간 카메라 미리보기")
            }
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
                style: StrokeStyle(
                    lineWidth: CaptureLayoutMetrics.cameraReticleStrokeWidth,
                    lineCap: .round,
                    lineJoin: .round
                )
            )
            Circle()
                .stroke(
                    PlanteriorPalette.textOnAccent.color.opacity(PlanteriorOpacity.strong),
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

    @ViewBuilder
    private var cameraControlRow: some View {
        if sizeCategory.isAccessibilityCategory {
            cameraControls
                .frame(minHeight: CaptureLayoutMetrics.cameraControlMinimumTarget)
                .padding(.horizontal, PlanteriorSpacing.medium)
                .padding(.bottom, PlanteriorSpacing.large)
        } else {
            cameraControls
                .frame(height: CaptureLayoutMetrics.cameraControlRowHeight)
                .padding(.horizontal, PlanteriorSpacing.extraLarge)
                .padding(.bottom, PlanteriorSpacing.large)
        }
    }

    private var cameraControls: some View {
        HStack {
            libraryControl
            Spacer(minLength: 0)
            shutterControl
            Spacer(minLength: 0)
            flashControl
        }
    }

    private var shutterControl: some View {
        Button {
            requestCamera()
        } label: {
            CameraShutterMark()
                .offset(
                    x: CaptureLayoutMetrics.shutterOffset.width,
                    y: CaptureLayoutMetrics.shutterOffset.height
                )
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
