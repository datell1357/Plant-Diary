import PlanteriorDesignSystem
import SwiftUI

/// Figma `Screen-AI-Identifying` (figma-analysis §6.11): the blurred plant photo
/// backdrop keeps the photo context, with a leaf progress core over a light
/// scrim. §10: under Reduce Motion the rotating ring is *removed*, not swapped
/// for another animation — the semantic state text carries the meaning.
extension IdentificationFlowView {
    var identifyingSurface: some View {
        ZStack {
            Image(.captureBlurredBackground)
                .resizable()
                .scaledToFill()
                .accessibilityIdentifier("capture.identifying.backdrop")
                .accessibilityLabel("분석 중인 식물 사진")
                .ignoresSafeArea()
            PlanteriorPalette.canvas.color
                .opacity(0.72)
                .ignoresSafeArea()
            identifyingContent
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("capture.identifying")
    }

    private var identifyingContent: some View {
        VStack(spacing: PlanteriorSpacing.large) {
            progressCore
            Text("AI가 식물을 분석하고 있어요...")
                .font(PlanteriorTypography.screenTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .multilineTextAlignment(.center)
                .accessibilityIdentifier("identification.pending")
            Text("잠시만 기다려주세요")
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .multilineTextAlignment(.center)
                .accessibilityIdentifier("capture.identifying.hint")
            progressDots
        }
        .padding(.horizontal, PlanteriorSpacing.huge)
    }

    /// The 96pt white circle with a 40pt leaf glyph inside a dashed accent ring.
    ///
    /// The ring is driven by `TimelineView` rather than a `repeatForever`
    /// animation: an indefinitely repeating animation keeps the app from ever
    /// reaching accessibility idle, which stalls assistive technology and any
    /// UI automation query issued after this screen appears.
    private var progressCore: some View {
        ZStack {
            Circle()
                .fill(PlanteriorPalette.surface.color)
                .frame(width: 96, height: 96)
            dashedRing
            Image(systemName: "leaf.fill")
                .font(.system(size: 40))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityHidden(true)
        }
        .accessibilityElement()
        .accessibilityLabel("분석 진행")
        .accessibilityValue("분석 중")
        .accessibilityIdentifier("capture.identifying.progress")
        .overlay {
            // A separate identified marker per motion mode, so the Reduce Motion
            // contract is observable rather than inferred.
            Color.clear
                .accessibilityHidden(false)
                .accessibilityIdentifier(
                    effectiveReduceMotion
                        ? "capture.identifying.progress.static"
                        : "capture.identifying.progress.animated"
                )
        }
    }

    /// §10: under Reduce Motion the rotation is removed outright, leaving the
    /// same dashed ring static — never a substitute animation.
    @ViewBuilder
    private var dashedRing: some View {
        let ring = Circle()
            .stroke(
                PlanteriorPalette.accent.color,
                style: StrokeStyle(lineWidth: 3, dash: [6, 6])
            )
            .frame(width: 96, height: 96)
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

    /// §6.11 3-dot progress indicator. The active dot advances with the same
    /// timeline, and holds still under Reduce Motion.
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
