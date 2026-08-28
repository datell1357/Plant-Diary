import PlanteriorDesignSystem
import SwiftUI

struct CaptureReferenceBackButton: View {
    let identifier: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(PlanteriorPalette.surface.color)
                    .frame(
                        width: CaptureLayoutMetrics.navigationBackVisualSide,
                        height: CaptureLayoutMetrics.navigationBackVisualSide
                    )
                Image(systemName: "chevron.left")
                    .font(CaptureLayoutMetrics.navigationBackGlyphFont)
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .accessibilityHidden(true)
            }
            .frame(
                width: PlanteriorControl.minimumTarget,
                height: PlanteriorControl.minimumTarget
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("뒤로")
        .accessibilityIdentifier(identifier)
    }
}

struct CameraShutterMark: View {
    var body: some View {
        ZStack {
            switch CaptureLayoutMetrics.shutterOuterRing {
            case .continuousCircle:
                Circle()
                    .stroke(
                        PlanteriorPalette.textOnAccent.color,
                        lineWidth: CaptureLayoutMetrics.shutterStrokeWidth
                    )
            }
            Circle()
                .fill(PlanteriorPalette.textOnAccent.color)
                .frame(
                    width: CaptureLayoutMetrics.shutterDiameter,
                    height: CaptureLayoutMetrics.shutterDiameter
                )
        }
        .frame(
            width: CaptureLayoutMetrics.shutterRingDiameter,
            height: CaptureLayoutMetrics.shutterRingDiameter
        )
    }
}

struct CaptureSparkleGlyph: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        addSparkle(
            to: &path,
            center: CGPoint(x: rect.width * 0.48, y: rect.height * 0.52),
            horizontal: rect.width * 0.30,
            vertical: rect.height * 0.42
        )
        addSparkle(
            to: &path,
            center: CGPoint(x: rect.width * 0.78, y: rect.height * 0.25),
            horizontal: rect.width * 0.12,
            vertical: rect.height * 0.16
        )
        addSparkle(
            to: &path,
            center: CGPoint(x: rect.width * 0.18, y: rect.height * 0.76),
            horizontal: rect.width * 0.09,
            vertical: rect.height * 0.12
        )
        return path
    }

    private func addSparkle(
        to path: inout Path,
        center: CGPoint,
        horizontal: CGFloat,
        vertical: CGFloat
    ) {
        path.move(to: CGPoint(x: center.x, y: center.y - vertical))
        path.addQuadCurve(
            to: CGPoint(x: center.x + horizontal, y: center.y),
            control: center
        )
        path.addQuadCurve(
            to: CGPoint(x: center.x, y: center.y + vertical),
            control: center
        )
        path.addQuadCurve(
            to: CGPoint(x: center.x - horizontal, y: center.y),
            control: center
        )
        path.addQuadCurve(
            to: CGPoint(x: center.x, y: center.y - vertical),
            control: center
        )
    }
}
