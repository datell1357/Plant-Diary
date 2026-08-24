import PlanteriorDesignSystem
import SwiftUI
import UIKit

/// Figma `Screen-AI-Identifying`: the fixture contains the board's softened
/// photo and lower scrim, so it stays visible rather than receiving another
/// full-screen wash. Reduce Motion keeps the same static progress semantics.
extension IdentificationFlowView {
    var identifyingSurface: some View {
        GeometryReader { geometry in
            let scale = CaptureLayoutMetrics.fittingScale(for: geometry.size)
            ZStack(alignment: .topLeading) {
                PlanteriorPalette.canvas.color
                Image(.captureBlurredBackground)
                    .resizable()
                    .interpolation(.none)
                    .frame(
                        width: CaptureLayoutMetrics.identifyingBackdropSize.width * scale,
                        height: CaptureLayoutMetrics.identifyingBackdropSize.height * scale
                    )
                    .accessibilityIdentifier("capture.identifying.backdrop")
                    .accessibilityLabel("분석 중인 식물 사진")
                identifyingContent(scale: scale)
                    .frame(maxWidth: .infinity)
                    .padding(
                        .top,
                        CaptureLayoutMetrics.identifyingProgressTop * scale
                    )
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PlanteriorPalette.canvas.color.ignoresSafeArea())
        .ignoresSafeArea()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("capture.identifying")
    }

    private func identifyingContent(scale: CGFloat) -> some View {
        VStack(spacing: 0) {
            progressCore(scale: scale)
            Text("AI가 식물을 분석하고 있어요...")
                .font(.system(size: identifyingHeadlineFontSize, weight: .bold))
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .multilineTextAlignment(.center)
                .padding(
                    .top,
                    CaptureLayoutMetrics.identifyingHeadlineTopSpacing * scale
                )
                .accessibilityIdentifier("identification.pending")
            Text("잠시만 기다려주세요")
                .font(.system(size: identifyingHintFontSize))
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .multilineTextAlignment(.center)
                .padding(
                    .top,
                    CaptureLayoutMetrics.identifyingHintTopSpacing * scale
                )
                .accessibilityIdentifier("capture.identifying.hint")
            progressDots(scale: scale)
                .padding(
                    .top,
                    CaptureLayoutMetrics.identifyingDotTopSpacing * scale
                )
        }
        .padding(.horizontal, PlanteriorSpacing.huge)
    }

    private func progressCore(scale: CGFloat) -> some View {
        ZStack {
            Circle()
                .fill(PlanteriorPalette.subtle.color)
                .frame(
                    width: CaptureLayoutMetrics.identifyingProgressLength * scale,
                    height: CaptureLayoutMetrics.identifyingProgressLength * scale
                )
            Circle()
                .fill(PlanteriorPalette.surface.color)
                .frame(
                    width: CaptureLayoutMetrics.identifyingCoreLength * scale,
                    height: CaptureLayoutMetrics.identifyingCoreLength * scale
                )
            dashedRing(scale: scale)
            Image(uiImage: identifyingLeafImage)
                .resizable()
                .interpolation(.none)
                .frame(
                    width: CaptureLayoutMetrics.identifyingGlyphSize * scale,
                    height: CaptureLayoutMetrics.identifyingGlyphSize * scale
                )
                .accessibilityHidden(true)
        }
        .frame(
            width: CaptureLayoutMetrics.identifyingProgressLength * scale,
            height: CaptureLayoutMetrics.identifyingProgressLength * scale
        )
        .accessibilityElement()
        .accessibilityLabel("분석 진행")
        .accessibilityValue("분석 중")
        .accessibilityIdentifier("capture.identifying.progress")
        .overlay {
            Color.clear
                .accessibilityHidden(false)
                .accessibilityIdentifier(
                    usesStaticCapturePhase
                        ? "capture.identifying.progress.static"
                        : "capture.identifying.progress.animated"
                )
        }
    }

    @ViewBuilder
    private func dashedRing(scale: CGFloat) -> some View {
        let ring = Circle()
            .strokeBorder(
                PlanteriorPalette.accent.color,
                style: StrokeStyle(
                    lineWidth: 2,
                    dash: [12, 8],
                    dashPhase: CaptureLayoutMetrics.identifyingRingDashPhase
                )
            )
            .frame(
                width: CaptureLayoutMetrics.identifyingProgressLength * scale,
                height: CaptureLayoutMetrics.identifyingProgressLength * scale
            )
        if usesStaticCapturePhase {
            ring
        } else {
            TimelineView(.animation) { context in
                let seconds = context.date.timeIntervalSinceReferenceDate
                let progress = seconds.truncatingRemainder(dividingBy: 1.6) / 1.6
                ring.rotationEffect(.degrees(progress * 360))
            }
        }
    }

    @ViewBuilder
    private func progressDots(scale: CGFloat) -> some View {
        if usesStaticCapturePhase {
            dotRow(active: 0, scale: scale)
        } else {
            TimelineView(.periodic(from: .now, by: 0.4)) { context in
                let step = Int(context.date.timeIntervalSinceReferenceDate / 0.4) % 3
                dotRow(active: step, scale: scale)
            }
        }
    }

    private func dotRow(active: Int, scale: CGFloat) -> some View {
        HStack(spacing: PlanteriorSpacing.small * scale) {
            ForEach(0 ..< 3, id: \.self) { index in
                Circle()
                    .fill(dotColor(index: index, active: active))
                    .frame(
                        width: CaptureLayoutMetrics.identifyingDotSize * scale,
                        height: CaptureLayoutMetrics.identifyingDotSize * scale
                    )
            }
        }
        .accessibilityHidden(true)
    }

    private var identifyingLeafImage: UIImage {
        guard let image = UIImage(named: "FigmaCaptureIdentifyingLeaf") else {
            assertionFailure("Missing FigmaCaptureIdentifyingLeaf resource")
            return UIImage()
        }
        return image
    }

    private func dotColor(index: Int, active: Int) -> Color {
        guard index != active else {
            return PlanteriorPalette.accent.color
        }
        return PlanteriorPalette.accent.color.opacity(index == 1 ? 0.5 : 0.24)
    }
}
