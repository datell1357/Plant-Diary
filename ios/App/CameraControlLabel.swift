import PlanteriorDesignSystem
import SwiftUI

struct CameraControlLabel: View {
    @Environment(\.sizeCategory) private var sizeCategory
    let systemImage: String
    let title: String
    let labelID: String

    var body: some View {
        VStack(spacing: PlanteriorSpacing.extraSmall) {
            Image(systemName: systemImage)
                .font(CaptureLayoutMetrics.cameraControlGlyphFont)
            Text(title)
                .font(PlanteriorTypography.microLabel)
                .lineLimit(sizeCategory.isAccessibilityCategory ? nil : 2)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(width: labelWidth)
                .accessibilityIdentifier(labelID)
        }
        .foregroundStyle(PlanteriorPalette.textOnAccent.color)
        .frame(width: controlWidth)
        .frame(minHeight: CaptureLayoutMetrics.cameraControlMinimumTarget)
    }

    private var labelWidth: CGFloat {
        sizeCategory.isAccessibilityCategory
            ? CaptureLayoutMetrics.cameraControlAccessibilityLabelWidth
            : CaptureLayoutMetrics.cameraControlLabelWidth
    }

    private var controlWidth: CGFloat {
        sizeCategory.isAccessibilityCategory
            ? CaptureLayoutMetrics.cameraControlAccessibilityWidth
            : CaptureLayoutMetrics.cameraControlWidth
    }
}

struct CameraCornerReticle: Shape {
    let armLength: CGFloat

    func path(in rect: CGRect) -> Path {
        let arm = armLength
        let radius = CaptureLayoutMetrics.cameraReticleCornerRadius
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.minY + arm))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + radius))
        path.addQuadCurve(
            to: CGPoint(x: rect.minX + radius, y: rect.minY),
            control: CGPoint(x: rect.minX, y: rect.minY)
        )
        path.addLine(to: CGPoint(x: rect.minX + arm, y: rect.minY))

        path.move(to: CGPoint(x: rect.maxX - arm, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - radius, y: rect.minY))
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX, y: rect.minY + radius),
            control: CGPoint(x: rect.maxX, y: rect.minY)
        )
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + arm))

        path.move(to: CGPoint(x: rect.maxX, y: rect.maxY - arm))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - radius))
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX - radius, y: rect.maxY),
            control: CGPoint(x: rect.maxX, y: rect.maxY)
        )
        path.addLine(to: CGPoint(x: rect.maxX - arm, y: rect.maxY))

        path.move(to: CGPoint(x: rect.minX + arm, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX + radius, y: rect.maxY))
        path.addQuadCurve(
            to: CGPoint(x: rect.minX, y: rect.maxY - radius),
            control: CGPoint(x: rect.minX, y: rect.maxY)
        )
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - arm))

        for offset in CaptureLayoutMetrics.cameraReticleMiddleSegmentOffsets {
            path.move(to: CGPoint(x: rect.minX + offset, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.minX + offset + arm, y: rect.minY))
            path.move(to: CGPoint(x: rect.minX + offset, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.minX + offset + arm, y: rect.maxY))
            path.move(to: CGPoint(x: rect.minX, y: rect.minY + offset))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + offset + arm))
            path.move(to: CGPoint(x: rect.maxX, y: rect.minY + offset))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + offset + arm))
        }
        return path
    }
}
