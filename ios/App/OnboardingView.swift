import PlanteriorDesignSystem
import SwiftUI

struct OnboardingView: View {
    let complete: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "leaf.circle.fill")
                .font(.system(size: 72))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityHidden(true)
            Text("식물 돌봄을 한곳에서")
                .font(PlanteriorTypography.screenTitle)
            Text("식별부터 물 주기 기록까지\n내 식물의 하루를 함께 관리해요.")
                .font(PlanteriorTypography.body)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .multilineTextAlignment(.center)
            Spacer()
            PlanteriorPrimaryButton("시작하기", action: complete)
                .accessibilityIdentifier("onboarding.complete")
        }
        .padding(24)
        .background(PlanteriorPalette.canvas.color)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("onboarding.screen")
    }
}

enum OnboardingState {
    private static let key = "didCompleteOnboarding"

    static var shouldPresent: Bool {
        if ProcessInfo.processInfo.environment["QA_SKIP_ONBOARDING"] == "1" {
            return false
        }
        if ProcessInfo.processInfo.environment["QA_RESET_ONBOARDING"] == "1" {
            return true
        }
        return !UserDefaults.standard.bool(forKey: key)
    }

    static func complete() {
        UserDefaults.standard.set(true, forKey: key)
    }
}
