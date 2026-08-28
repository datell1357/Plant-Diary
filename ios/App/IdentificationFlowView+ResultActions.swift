import PlanteriorData
import PlanteriorDesignSystem
import SwiftUI

extension IdentificationFlowView {
    func resultActions(_ candidates: IdentificationCandidates) -> some View {
        VStack(spacing: 0) {
            Button {
                selectedCandidate = selectedCandidate ?? candidates.items.first
                showsRegistration = selectedCandidate != nil
            } label: {
                Text("이 식물로 등록하기")
                    .font(PlanteriorTypography.body.weight(.semibold))
                    .frame(maxWidth: .infinity, minHeight: PlanteriorControl.minimumTarget)
            }
            .buttonStyle(.plain)
            .foregroundStyle(PlanteriorPalette.textOnAccent.color)
            .background(PlanteriorPalette.accent.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
            .accessibilityIdentifier("capture.result.register")
            Button("직접 수정하기") {
                selectedCandidate = selectedCandidate ?? candidates.items.first
                showsManualRegistration = selectedCandidate != nil
            }
            .font(PlanteriorTypography.caption)
            .foregroundStyle(PlanteriorPalette.textSecondary.color)
            .frame(minHeight: PlanteriorControl.minimumTarget)
            .accessibilityIdentifier("identification.manual")
            HStack(spacing: PlanteriorSpacing.extraSmall) {
                Text("원하는 결과가 없나요?")
                    .foregroundStyle(PlanteriorPalette.textAccessibleCaption.color)
                    .accessibilityIdentifier("capture.result.guidance")
                Button("직접 등록하기") {
                    showsBlankManualRegistration = true
                }
                .foregroundStyle(PlanteriorPalette.accent.color)
                .underline()
                .accessibilityIdentifier("identification.manual-registration")
            }
            .font(PlanteriorTypography.caption)
            .frame(height: 28)
        }
        .padding(.horizontal, PlanteriorSpacing.huge)
        .padding(.bottom, PlanteriorSpacing.small)
        .background(PlanteriorPalette.canvas.color)
        .offset(y: CaptureLayoutMetrics.resultActionVerticalOffset)
    }
}
