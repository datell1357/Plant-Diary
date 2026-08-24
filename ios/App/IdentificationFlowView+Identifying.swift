import PlanteriorDesignSystem
import SwiftUI

/// Figma `Screen-AI-Identifying`: the fixture contains the board's softened
/// photo and lower scrim, so it stays visible rather than receiving another
/// full-screen wash. Reduce Motion keeps the same static progress semantics.
extension IdentificationFlowView {
    var identifyingSurface: some View {
        ZStack(alignment: .topLeading) {
            PlanteriorPalette.canvas.color
            Image(.captureBlurredBackground)
                .resizable()
                .frame(width: 390, height: 844)
                .accessibilityIdentifier("capture.identifying.backdrop")
                .accessibilityLabel("분석 중인 식물 사진")
            identifyingContent
                .frame(maxWidth: .infinity)
                .padding(.top, 200)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PlanteriorPalette.canvas.color.ignoresSafeArea())
        .ignoresSafeArea()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("capture.identifying")
    }

    private var identifyingContent: some View {
        VStack(spacing: 0) {
            progressCore
            Text("AI가 식물을 분석하고 있어요...")
                .font(PlanteriorTypography.screenTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .multilineTextAlignment(.center)
                .padding(.top, PlanteriorSpacing.section)
                .accessibilityIdentifier("identification.pending")
            Text("잠시만 기다려주세요")
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .multilineTextAlignment(.center)
                .padding(.top, PlanteriorSpacing.extraSmall)
                .accessibilityIdentifier("capture.identifying.hint")
            progressDots
                .padding(.top, 28)
        }
        .padding(.horizontal, PlanteriorSpacing.huge)
    }

    private var progressCore: some View {
        ZStack {
            Circle()
                .fill(PlanteriorPalette.surface.color)
                .frame(width: 80, height: 80)
            dashedRing
            Image(systemName: "leaf")
                .font(.system(size: 40, weight: .regular))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityHidden(true)
        }
        .frame(width: 120, height: 120)
        .accessibilityElement()
        .accessibilityLabel("분석 진행")
        .accessibilityValue("분석 중")
        .accessibilityIdentifier("capture.identifying.progress")
        .overlay {
            Color.clear
                .accessibilityHidden(false)
                .accessibilityIdentifier(
                    effectiveReduceMotion
                        ? "capture.identifying.progress.static"
                        : "capture.identifying.progress.animated"
                )
        }
    }

    @ViewBuilder
    private var dashedRing: some View {
        let ring = Circle()
            .strokeBorder(
                PlanteriorPalette.accent.color,
                style: StrokeStyle(lineWidth: 2, dash: [12, 8])
            )
            .frame(width: 120, height: 120)
        if effectiveReduceMotion {
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
    private var progressDots: some View {
        if effectiveReduceMotion {
            dotRow(active: 0)
        } else {
            TimelineView(.periodic(from: .now, by: 0.4)) { context in
                let step = Int(context.date.timeIntervalSinceReferenceDate / 0.4) % 3
                dotRow(active: step)
            }
        }
    }

    private func dotRow(active: Int) -> some View {
        HStack(spacing: PlanteriorSpacing.small) {
            ForEach(0 ..< 3, id: \.self) { index in
                Circle()
                    .fill(
                        index == active
                            ? PlanteriorPalette.accent.color
                            : PlanteriorPalette.border.color
                    )
                    .frame(width: 8, height: 8)
            }
        }
        .accessibilityHidden(true)
    }
}
